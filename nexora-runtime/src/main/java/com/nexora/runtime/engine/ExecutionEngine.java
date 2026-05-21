package com.nexora.runtime.engine;

import com.nexora.core.context.ExecutionContext;
import com.nexora.core.context.TraceContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.intent.Intent;
import com.nexora.core.plan.Plan;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.PlanCompletedEvent;
import com.nexora.event.PlanFailedEvent;
import com.nexora.event.PlanStartedEvent;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.event.StepStartedEvent;
import com.nexora.executor.DagStepScheduler;
import com.nexora.persistence.ExecutionRecord;
import com.nexora.saga.SagaOrchestrator;
import com.nexora.persistence.ExecutionState;
import com.nexora.persistence.ExecutionStore;
import com.nexora.persistence.StepRecord;
import com.nexora.persistence.StepState;
import com.nexora.planner.engine.DefaultPlanningContext;
import com.nexora.spi.Planner;
import com.nexora.spi.PlanningContext;
import com.nexora.spi.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final Planner planner;
    private final CapabilityRegistry capabilityRegistry;
    private final DagStepScheduler scheduler;
    private final ExecutionEventBus eventBus;
    private final ExecutionStore store;           // null = persistence disabled
    private final SagaOrchestrator sagaOrchestrator; // null = saga disabled

    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus) {
        this(planner, capabilityRegistry, scheduler, eventBus, null, null);
    }

    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus,
            ExecutionStore store) {
        this(planner, capabilityRegistry, scheduler, eventBus, store, null);
    }

    public ExecutionEngine(
            Planner planner,
            CapabilityRegistry capabilityRegistry,
            DagStepScheduler scheduler,
            ExecutionEventBus eventBus,
            ExecutionStore store,
            SagaOrchestrator sagaOrchestrator) {
        this.planner = Objects.requireNonNull(planner);
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.store = store;
        this.sagaOrchestrator = sagaOrchestrator;
        if (store != null) {
            wireStoreSubscriptions();
        }
    }

    private void wireStoreSubscriptions() {
        eventBus.subscribe(StepStartedEvent.class, e -> {
            try {
                store.upsertStep(e.executionId(),
                        StepRecord.started(e.stepId(), e.capabilityId(), e.idempotencyKey(), e.occurredAt()));
            } catch (Exception ex) {
                log.warn("Persistence: failed to record step start stepId={}", e.stepId(), ex);
            }
        });
        eventBus.subscribe(StepCompletedEvent.class, e -> {
            try {
                StepRecord running = StepRecord.started(e.stepId(), e.capabilityId(), null,
                        e.occurredAt().minus(e.elapsed()));
                store.upsertStep(e.executionId(), running.completed(e.occurredAt()));
            } catch (Exception ex) {
                log.warn("Persistence: failed to record step completion stepId={}", e.stepId(), ex);
            }
        });
        eventBus.subscribe(StepFailedEvent.class, e -> {
            try {
                StepRecord running = StepRecord.started(e.stepId(), e.capabilityId(), null,
                        e.occurredAt().minus(e.elapsed()));
                store.upsertStep(e.executionId(),
                        running.failed(e.failureCode(), e.failureMessage(), e.occurredAt()));
            } catch (Exception ex) {
                log.warn("Persistence: failed to record step failure stepId={}", e.stepId(), ex);
            }
        });
    }

    private void persistExecutionState(String executionId, ExecutionState state, Instant completedAt) {
        if (store == null) return;
        try {
            store.updateExecution(executionId, state, completedAt);
        } catch (Exception e) {
            log.warn("Persistence: failed to update execution state executionId={}", executionId, e);
        }
    }

    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        TraceContext traceContext = TraceContext.root();
        ExecutionContext ctx = new ExecutionContext(intent, traceContext);

        PlanningContext planningContext = new DefaultPlanningContext(capabilityRegistry, Map.of());
        Plan plan = planner.plan(intent, planningContext);
        Instant planStart = Instant.now();

        log.info("Starting execution executionId={} traceId={} goal={}",
                ctx.getExecutionId(), traceContext.traceId(), intent.getGoal());

        if (store != null) {
            try {
                store.createExecution(ExecutionRecord.started(
                        ctx.getExecutionId(), traceContext.traceId(),
                        intent.getGoal(), intent.getContext(), planStart));
            } catch (Exception e) {
                log.warn("Persistence: failed to create execution record executionId={}", ctx.getExecutionId(), e);
            }
        }

        eventBus.publish(new PlanStartedEvent(ctx.getExecutionId(), traceContext.traceId(), planStart));

        return scheduler.schedule(plan, ctx)
                .whenComplete((result, ex) -> {
                    Instant now = Instant.now();
                    Duration elapsed = Duration.between(planStart, now);
                    if (ex != null) {
                        log.error("Execution threw unexpectedly executionId={}", ctx.getExecutionId(), ex);
                        persistExecutionState(ctx.getExecutionId(), ExecutionState.FAILED, now);
                        eventBus.publish(new PlanFailedEvent(
                                ctx.getExecutionId(), traceContext.traceId(),
                                null, "UNEXPECTED_ERROR", elapsed, now
                        ));
                    } else if (result.status().name().equals("FAILED")) {
                        String failedStep = result.stepResults().stream()
                                .filter(sr -> !sr.succeeded())
                                .map(sr -> sr.stepId())
                                .findFirst().orElse(null);
                        persistExecutionState(ctx.getExecutionId(), ExecutionState.FAILED, now);
                        eventBus.publish(new PlanFailedEvent(
                                ctx.getExecutionId(), traceContext.traceId(),
                                failedStep, "STEP_FAILED", elapsed, now
                        ));
                        log.warn("Execution failed executionId={} failedStep={}", ctx.getExecutionId(), failedStep);
                        if (sagaOrchestrator != null) {
                            persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPENSATING, now);
                            sagaOrchestrator.compensate(plan, result, ctx)
                                    .whenComplete((v, err) -> {
                                        if (err != null) {
                                            log.error("Saga compensation threw executionId={}", ctx.getExecutionId(), err);
                                        }
                                        persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPENSATED, Instant.now());
                                    });
                        }
                    } else {
                        persistExecutionState(ctx.getExecutionId(), ExecutionState.COMPLETED, now);
                        eventBus.publish(new PlanCompletedEvent(
                                ctx.getExecutionId(), traceContext.traceId(), elapsed, now
                        ));
                        log.info("Execution completed executionId={} elapsed={}ms",
                                ctx.getExecutionId(), elapsed.toMillis());
                    }
                });
    }
}

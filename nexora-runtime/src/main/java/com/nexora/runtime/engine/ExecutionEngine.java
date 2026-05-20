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
import com.nexora.executor.DagStepScheduler;
import com.nexora.planner.engine.PlannerEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final PlannerEngine plannerEngine;
    private final DagStepScheduler scheduler;
    private final ExecutionEventBus eventBus;

    public ExecutionEngine(PlannerEngine plannerEngine, DagStepScheduler scheduler, ExecutionEventBus eventBus) {
        this.plannerEngine = Objects.requireNonNull(plannerEngine);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.eventBus = Objects.requireNonNull(eventBus);
    }

    public CompletableFuture<ExecutionResult> execute(Intent intent) {
        TraceContext traceContext = TraceContext.root();
        ExecutionContext ctx = new ExecutionContext(intent, traceContext);

        Plan plan = plannerEngine.createPlan(intent);
        Instant planStart = Instant.now();

        log.info("Starting execution executionId={} traceId={} goal={}",
                ctx.getExecutionId(), traceContext.traceId(), intent.getGoal());

        eventBus.publish(new PlanStartedEvent(ctx.getExecutionId(), traceContext.traceId(), planStart));

        return scheduler.schedule(plan, ctx)
                .whenComplete((result, ex) -> {
                    Duration elapsed = Duration.between(planStart, Instant.now());
                    if (ex != null) {
                        log.error("Execution threw unexpectedly executionId={}", ctx.getExecutionId(), ex);
                        eventBus.publish(new PlanFailedEvent(
                                ctx.getExecutionId(), traceContext.traceId(),
                                null, "UNEXPECTED_ERROR", elapsed, Instant.now()
                        ));
                    } else if (result.status().name().equals("FAILED")) {
                        String failedStep = result.stepResults().stream()
                                .filter(sr -> !sr.succeeded())
                                .map(sr -> sr.stepId())
                                .findFirst().orElse(null);
                        eventBus.publish(new PlanFailedEvent(
                                ctx.getExecutionId(), traceContext.traceId(),
                                failedStep, "STEP_FAILED", elapsed, Instant.now()
                        ));
                        log.warn("Execution failed executionId={} failedStep={}", ctx.getExecutionId(), failedStep);
                    } else {
                        eventBus.publish(new PlanCompletedEvent(
                                ctx.getExecutionId(), traceContext.traceId(), elapsed, Instant.now()
                        ));
                        log.info("Execution completed executionId={} elapsed={}ms",
                                ctx.getExecutionId(), elapsed.toMillis());
                    }
                });
    }
}

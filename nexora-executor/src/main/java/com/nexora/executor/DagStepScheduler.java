package com.nexora.executor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.core.context.ExecutionContext;
import com.nexora.core.execution.ExecutionResult;
import com.nexora.core.execution.ExecutionStatus;
import com.nexora.core.execution.StepResult;
import com.nexora.core.plan.InputBinding;
import com.nexora.core.plan.Plan;
import com.nexora.core.plan.Step;
import com.nexora.event.ExecutionEventBus;
import com.nexora.event.StepCompletedEvent;
import com.nexora.event.StepFailedEvent;
import com.nexora.event.StepStartedEvent;
import com.nexora.retry.RetryPolicyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Schedules plan steps as a DAG, executing independent steps in parallel.
 *
 * Dependency resolution: each Step.dependsOn() set declares which step IDs
 * must complete before this step may start. The scheduler builds a
 * CompletableFuture per step and chains them according to declared dependencies.
 * Steps with no dependencies start immediately and run concurrently.
 */
public final class DagStepScheduler {

    private static final Logger log = LoggerFactory.getLogger(DagStepScheduler.class);

    private final InterceptorPipeline pipeline;
    private final RetryPolicyRegistry retryPolicyRegistry;
    private final ExecutionEventBus eventBus;
    private final Executor executor;

    public DagStepScheduler(
            InterceptorPipeline pipeline,
            RetryPolicyRegistry retryPolicyRegistry,
            ExecutionEventBus eventBus,
            Executor executor) {
        this.pipeline = Objects.requireNonNull(pipeline);
        this.retryPolicyRegistry = Objects.requireNonNull(retryPolicyRegistry);
        this.eventBus = Objects.requireNonNull(eventBus);
        this.executor = Objects.requireNonNull(executor);
    }

    public CompletableFuture<ExecutionResult> schedule(Plan plan, ExecutionContext ctx) {
        validateNoCycles(plan);

        Map<String, CompletableFuture<StepResult>> futures = new HashMap<>();
        ConcurrentHashMap<String, StepResult> completedResults = new ConcurrentHashMap<>();

        for (Step step : plan.getSteps()) {
            CompletableFuture<Void> prerequisite = buildPrerequisite(step, futures);

            CompletableFuture<StepResult> stepFuture = prerequisite
                    .thenApplyAsync(ignored -> executeStep(step, ctx), executor)
                    .whenComplete((result, ex) -> {
                        if (result != null) completedResults.put(step.id(), result);
                    });

            futures.put(step.id(), stepFuture);
        }

        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                .handle((ignored, ex) -> collectResults(ctx.getExecutionId(), completedResults, plan));
    }

    private StepResult executeStep(Step step, ExecutionContext ctx) {
        log.debug("Starting step id={} capability={} executionId={}",
                step.id(), step.capabilityId(), ctx.getExecutionId());

        Map<String, Object> resolvedInputs = resolveInputs(step, ctx);

        CapabilityRequest request = new CapabilityRequest(
                step.capabilityId(),
                step.id(),
                resolvedInputs,
                ctx.getTraceContext().childSpan(),
                step.timeout()
        );

        eventBus.publish(new StepStartedEvent(
                ctx.getExecutionId(), step.id(), step.capabilityId(),
                request.traceContext().traceId(), request.traceContext().spanId(),
                Instant.now()
        ));

        Instant start = Instant.now();
        CapabilityResult result = pipeline.execute(request);
        Duration elapsed = Duration.between(start, Instant.now());

        if (result.succeeded()) {
            if (step.outputKey() != null) {
                ctx.put(step.outputKey(), result.output());
            }
            ctx.recordStepOutput(step.id(), result.output());

            eventBus.publish(new StepCompletedEvent(
                    ctx.getExecutionId(), step.id(), step.capabilityId(),
                    request.traceContext().traceId(), elapsed, Instant.now()
            ));
            log.debug("Step completed id={} elapsed={}ms", step.id(), elapsed.toMillis());
        } else {
            eventBus.publish(new StepFailedEvent(
                    ctx.getExecutionId(), step.id(), step.capabilityId(),
                    request.traceContext().traceId(),
                    result.failureCode(), result.failureMessage(),
                    elapsed, Instant.now()
            ));
            log.warn("Step failed id={} code={} message={}",
                    step.id(), result.failureCode(), result.failureMessage());
        }

        return new StepResult(step.id(), result);
    }

    private Map<String, Object> resolveInputs(Step step, ExecutionContext ctx) {
        Map<String, Object> resolved = new HashMap<>();
        for (Map.Entry<String, InputBinding> entry : step.inputs().entrySet()) {
            resolved.put(entry.getKey(), entry.getValue().resolve(ctx));
        }
        return resolved;
    }

    private CompletableFuture<Void> buildPrerequisite(
            Step step,
            Map<String, CompletableFuture<StepResult>> futures) {

        if (step.dependsOn().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<StepResult>> deps = new ArrayList<>();
        for (String depId : step.dependsOn()) {
            CompletableFuture<StepResult> dep = futures.get(depId);
            if (dep == null) {
                throw new IllegalStateException(
                        "Step '" + step.id() + "' declares dependency on '" + depId +
                        "' but that step does not appear earlier in the plan. " +
                        "Steps must be ordered so dependencies come first."
                );
            }
            deps.add(dep);
        }

        return CompletableFuture.allOf(deps.toArray(new CompletableFuture[0]));
    }

    private ExecutionResult collectResults(
            String executionId,
            ConcurrentHashMap<String, StepResult> completedResults,
            Plan plan) {

        List<StepResult> ordered = new ArrayList<>();
        ExecutionStatus overallStatus = ExecutionStatus.COMPLETED;

        for (Step step : plan.getSteps()) {
            StepResult result = completedResults.get(step.id());
            if (result != null) {
                ordered.add(result);
                if (!result.succeeded()) {
                    overallStatus = ExecutionStatus.FAILED;
                }
            }
        }

        return new ExecutionResult(executionId, overallStatus, ordered);
    }

    /**
     * Validates the plan's dependency graph is a DAG (no cycles).
     * Uses iterative DFS with coloring: WHITE=unvisited, GRAY=in-stack, BLACK=done.
     */
    private void validateNoCycles(Plan plan) {
        Map<String, Step> stepById = new HashMap<>();
        for (Step step : plan.getSteps()) {
            stepById.put(step.id(), step);
        }

        Map<String, Integer> color = new HashMap<>();
        for (Step step : plan.getSteps()) {
            if (!color.containsKey(step.id())) {
                dfsCheckCycle(step.id(), stepById, color);
            }
        }
    }

    private void dfsCheckCycle(String stepId, Map<String, Step> stepById, Map<String, Integer> color) {
        color.put(stepId, 1); // GRAY — in current DFS path
        Step step = stepById.get(stepId);
        if (step != null) {
            for (String dep : step.dependsOn()) {
                int depColor = color.getOrDefault(dep, 0);
                if (depColor == 1) {
                    throw new IllegalStateException(
                            "Cycle detected in plan dependency graph involving step: " + dep);
                }
                if (depColor == 0) {
                    dfsCheckCycle(dep, stepById, color);
                }
            }
        }
        color.put(stepId, 2); // BLACK — fully processed
    }
}

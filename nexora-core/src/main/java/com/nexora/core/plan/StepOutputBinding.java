package com.nexora.core.plan;

import com.nexora.core.context.ExecutionContext;
import java.util.Objects;

/**
 * Resolves input from a previously completed step's output.
 * When field is null, the entire output object is returned.
 */
public record StepOutputBinding(String stepId, String field) implements InputBinding {
    public StepOutputBinding {
        Objects.requireNonNull(stepId, "stepId must not be null");
    }

    @Override
    public Object resolve(ExecutionContext ctx) {
        return ctx.getStepOutput(stepId, field);
    }
}

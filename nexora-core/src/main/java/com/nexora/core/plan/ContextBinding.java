package com.nexora.core.plan;

import com.nexora.core.context.ExecutionContext;
import java.util.Objects;

public record ContextBinding(String path) implements InputBinding {
    public ContextBinding {
        Objects.requireNonNull(path, "path must not be null");
    }

    @Override
    public Object resolve(ExecutionContext ctx) {
        return ctx.resolvePath(path);
    }
}

package com.nexora.tracing;

import com.nexora.core.context.TraceContext;

/** Zero-overhead tracer used when no tracing backend is configured. */
public final class NoopTracer implements Tracer {

    public static final NoopTracer INSTANCE = new NoopTracer();

    private NoopTracer() {}

    @Override
    public Span startSpan(String operationName, TraceContext parent) {
        TraceContext childCtx = parent != null ? parent.childSpan() : TraceContext.root();
        return new NoopSpan(childCtx);
    }
}

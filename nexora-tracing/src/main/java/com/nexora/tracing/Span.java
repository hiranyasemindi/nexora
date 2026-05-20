package com.nexora.tracing;

import com.nexora.core.context.TraceContext;

public interface Span {
    TraceContext context();
    void setAttribute(String key, String value);
    void recordException(Throwable t);
    void setStatus(SpanStatus status);
    SpanStatus status();
    void end();
}

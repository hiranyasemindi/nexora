package com.nexora.executor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;

/**
 * Chain-of-responsibility node in the capability execution pipeline.
 * Each interceptor calls chain.proceed() to pass control to the next interceptor.
 * Interceptors must be thread-safe; they are shared across concurrent executions.
 */
@FunctionalInterface
public interface ExecutionInterceptor {
    CapabilityResult intercept(CapabilityRequest request, InterceptorChain chain);
}

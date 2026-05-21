package com.nexora.executor.interceptor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.executor.ExecutionInterceptor;
import com.nexora.executor.InterceptorChain;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class TimeoutInterceptor implements ExecutionInterceptor {

    private final Executor executor;
    private final Duration defaultTimeout;

    public TimeoutInterceptor(Executor executor, Duration defaultTimeout) {
        this.executor = executor;
        this.defaultTimeout = defaultTimeout;
    }

    @Override
    public CapabilityResult intercept(CapabilityRequest request, InterceptorChain chain) {
        Duration timeout = request.timeout() != null ? request.timeout() : defaultTimeout;
        if (timeout == null) {
            return chain.proceed(request);
        }

        CompletableFuture<CapabilityResult> future = CompletableFuture
                .supplyAsync(() -> chain.proceed(request), executor);

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return CapabilityResult.failure(
                    "EXECUTION_TIMEOUT",
                    "Capability " + request.capabilityId() + " exceeded timeout of " + timeout
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CapabilityResult.failure("INTERRUPTED", "Execution was interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException("Capability execution failed", cause);
        }
    }
}

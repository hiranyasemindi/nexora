package com.nexora.executor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;

@FunctionalInterface
public interface InterceptorChain {
    CapabilityResult proceed(CapabilityRequest request);
}

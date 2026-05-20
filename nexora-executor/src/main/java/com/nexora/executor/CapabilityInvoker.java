package com.nexora.executor;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;
import com.nexora.spi.Capability;
import com.nexora.spi.CapabilityRegistry;

/**
 * Terminal node in the pipeline: resolves the capability from the registry and invokes it.
 */
public final class CapabilityInvoker {

    private final CapabilityRegistry registry;

    public CapabilityInvoker(CapabilityRegistry registry) {
        this.registry = registry;
    }

    public CapabilityResult invoke(CapabilityRequest request) {
        Capability capability = registry.find(request.capabilityId())
                .orElse(null);

        if (capability == null) {
            return CapabilityResult.failure(
                    "CAPABILITY_NOT_FOUND",
                    "No capability registered with id: " + request.capabilityId()
            );
        }

        return capability.execute(request);
    }
}

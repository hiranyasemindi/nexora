package com.nexora.spi;

import com.nexora.core.capability.CapabilityRequest;
import com.nexora.core.capability.CapabilityResult;

/**
 * Contract all capability implementations must fulfill.
 * Implementations must be thread-safe; the engine may call execute() concurrently.
 */
public interface Capability {
    CapabilityResult execute(CapabilityRequest request);
}

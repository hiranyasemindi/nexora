package com.nexora.spi;

import java.util.List;
import java.util.Optional;

/**
 * Thread-safe registry for capabilities.
 * This interface lives in the SPI so plugins can register additional capabilities
 * via PluginContext without depending on the registry implementation module.
 */
public interface CapabilityRegistry {

    void register(CapabilityDescriptor descriptor, Capability capability);

    void deregister(String capabilityId);

    Optional<Capability> find(String capabilityId);

    Optional<CapabilityDescriptor> findDescriptor(String capabilityId);

    List<CapabilityDescriptor> listAll();
}

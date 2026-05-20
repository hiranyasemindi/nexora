package com.nexora.spi;

import java.util.List;
import java.util.Objects;

public record CapabilityDescriptor(
        String id,
        String description,
        List<ParameterDescriptor> inputParameters,
        List<ParameterDescriptor> outputParameters,
        boolean idempotent,   // informs default retry policy selection
        boolean async         // hint to executor; true means capability may return before side-effect completes
) {
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        inputParameters = inputParameters == null ? List.of() : List.copyOf(inputParameters);
        outputParameters = outputParameters == null ? List.of() : List.copyOf(outputParameters);
    }
}

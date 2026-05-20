package com.nexora.core.capability;

import java.util.Map;

public record CapabilityResult(
        ResultStatus status,
        Object output,
        String failureCode,
        String failureMessage,
        Map<String, String> metadata
) {
    public CapabilityResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static CapabilityResult success(Object output) {
        return new CapabilityResult(ResultStatus.SUCCESS, output, null, null, Map.of());
    }

    public static CapabilityResult failure(String code, String message) {
        return new CapabilityResult(ResultStatus.FAILURE, null, code, message, Map.of());
    }

    public boolean succeeded() {
        return status == ResultStatus.SUCCESS;
    }
}

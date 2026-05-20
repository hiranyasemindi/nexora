package com.nexora.executor;

import com.nexora.core.capability.CapabilityContract;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityContractMonitorTest {

    @Test
    void remainsHealthyUntilMinimumSamplesAreCollected() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.0)
                .build();

        for (int i = 0; i < 4; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }

        assertTrue(monitor.isHealthy("cap", contract));
    }

    @Test
    void becomesUnhealthyWhenErrorRateBreachIsObserved() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract = CapabilityContract.builder()
                .maxErrorRate(0.20)
                .build();

        for (int i = 0; i < 5; i++) {
            monitor.recordFailure("cap", Duration.ofMillis(10));
        }

        assertFalse(monitor.isHealthy("cap", contract));
    }

    @Test
    void becomesUnhealthyWhenLatencySlaIsBreached() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        CapabilityContract contract = CapabilityContract.builder()
                .p99Latency(Duration.ofMillis(100))
                .build();

        for (int i = 0; i < 5; i++) {
            monitor.recordSuccess("cap", Duration.ofMillis(250));
        }

        assertFalse(monitor.isHealthy("cap", contract));
    }

    @Test
    void snapshotReflectsRecordedWindowStats() {
        CapabilityContractMonitor monitor = new CapabilityContractMonitor();
        monitor.recordSuccess("cap", Duration.ofMillis(10));
        monitor.recordFailure("cap", Duration.ofMillis(20));
        monitor.recordSuccess("cap", Duration.ofMillis(30));

        CapabilityContractMonitor.HealthSnapshot snapshot = monitor.snapshot("cap");

        assertEquals("cap", snapshot.capabilityId());
        assertEquals(3, snapshot.sampleCount());
        assertEquals(1.0 / 3.0, snapshot.errorRate(), 0.0001);
        assertEquals(Duration.ofMillis(30), snapshot.p99Latency());
    }
}

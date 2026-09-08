package com.videoai.common.analysis;

import org.junit.jupiter.api.Test;
import java.io.InterruptedIOException;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionBudgetTest {
    @Test void limitsCallsRejectsExpiredWorkAndRestoresOuterScope() throws Exception {
        try (var outer = ExecutionBudget.bind(Instant.now().plusSeconds(2))) {
            assertTrue(ExecutionBudget.limit(Duration.ofMinutes(10)).compareTo(Duration.ofSeconds(2)) <= 0);
            try (var inner = ExecutionBudget.bind(Instant.now().minusSeconds(1))) {
                assertThrows(InterruptedIOException.class, ExecutionBudget::check);
            }
            assertDoesNotThrow(ExecutionBudget::check);
        }
        assertEquals(Duration.ofMinutes(10), ExecutionBudget.limit(Duration.ofMinutes(10)));
    }
    @Test void cancellationAndInterruptDoNotLeakToNextScope() {
        try (var scope = ExecutionBudget.bind(Instant.now().plusSeconds(5), () -> true)) {
            assertThrows(InterruptedIOException.class, ExecutionBudget::check);
        }
        assertDoesNotThrow(ExecutionBudget::check);
        Thread.currentThread().interrupt();
        try { assertThrows(InterruptedIOException.class, ExecutionBudget::check); }
        finally { Thread.interrupted(); }
    }
}

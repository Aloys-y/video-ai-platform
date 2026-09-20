package com.videoai.infra.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.*;
import com.videoai.infra.mysql.mapper.AiCallLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiCallRecorderTest {
    @Test void writeFailureIsBoundedAndDoesNotThrowModelRetryError() throws Exception {
        var mapper=mock(AiCallLogMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(mapper.lock(anyString())).thenThrow(new TransientDataAccessResourceException("simulated"));
        var recorder=new AiCallRecorder(mapper,new AiCostCalculator(AiCostCalculatorTest.prices(),new ObjectMapper()),new ObjectMapper(),manager);
        assertFalse(recorder.finish(java.util.UUID.randomUUID().toString(),AiCallRecorder.Outcome.SUCCEEDED,
                new AiUsage(100L,10L,null,"{}"),"request",null));
        verify(mapper,times(3)).lock(anyString());verify(manager,times(3)).rollback(any());
    }
    @Test void interruptedWriteBackoffKeepsInterruptFlag() throws Exception {
        var mapper=mock(AiCallLogMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(mapper.lock(anyString())).thenThrow(new TransientDataAccessResourceException("simulated"));
        var recorder=new AiCallRecorder(mapper,new AiCostCalculator(AiCostCalculatorTest.prices(),new ObjectMapper()),new ObjectMapper(),manager);
        Thread.currentThread().interrupt();
        try {
            assertFalse(recorder.finish(java.util.UUID.randomUUID().toString(),AiCallRecorder.Outcome.UNKNOWN,AiUsage.unknown(),null,null));
            assertTrue(Thread.currentThread().isInterrupted());verify(mapper,times(1)).lock(anyString());
        } finally {Thread.interrupted();}
    }
    @Test void beginFailurePropagatesSoCallerCannotProceedToNetwork() throws Exception {
        var mapper=mock(AiCallLogMapper.class);var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call->new SimpleTransactionStatus());
        when(mapper.insert(any())).thenThrow(new TransientDataAccessResourceException("simulated"));
        var recorder=new AiCallRecorder(mapper,new AiCostCalculator(AiCostCalculatorTest.prices(),new ObjectMapper()),new ObjectMapper(),manager);
        assertThrows(TransientDataAccessResourceException.class,()->recorder.begin(java.util.UUID.randomUUID().toString(),
                new AiCallContext("task",0,AiCallContext.Stage.TEXT_SCREEN,0),"qwen3.8-flash"));
        verify(mapper,times(1)).insert(any());
    }

    @Test void tokenReceiptAliasesAreStrictAndDoNotDoubleCountSubtotals() throws Exception {
        var json = new ObjectMapper();
        var usage = AiCallRecorder.tokenUsage(json.readTree("{\"prompt_tokens\":100,\"input_tokens\":100,\"completion_tokens\":20,\"video_tokens\":90}"));
        assertEquals(100L,usage.inputTokens()); assertEquals(20L,usage.outputTokens());
        assertEquals(-1L,AiCallRecorder.tokenUsage(json.readTree("{\"input_tokens\":1.5}")).inputTokens());
        assertEquals(-1L,AiCallRecorder.tokenUsage(json.readTree("{\"input_tokens\":100,\"prompt_tokens\":200}")).inputTokens());
        assertEquals(-1L,AiCallRecorder.tokenUsage(json.readTree("{\"input_tokens\":999999999999999999999}")).inputTokens());
        assertNull(AiCallRecorder.tokenUsage(json.readTree("{\"total_tokens\":120}")).inputTokens());
        assertEquals(AiUsage.unknown(),AiCallRecorder.tokenUsage(null));
    }
}

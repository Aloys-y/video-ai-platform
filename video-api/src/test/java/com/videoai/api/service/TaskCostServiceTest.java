package com.videoai.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.AnalysisTask;
import com.videoai.common.domain.User;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.infra.minio.service.StorageService;
import com.videoai.api.context.UserContext;
import com.videoai.api.controller.TaskController;
import com.videoai.api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskCostServiceTest {
    final AnalysisTaskMapper tasks=mock(AnalysisTaskMapper.class);
    final AiCallLogMapper ledger=mock(AiCallLogMapper.class);
    final ObjectMapper json=new ObjectMapper();
    final TaskSegmentService ownership=new TaskSegmentService(tasks,mock(AnalysisSegmentMapper.class),mock(StorageService.class),json);
    final TaskCostService service=new TaskCostService(ownership,ledger);
    AnalysisTask task;
    @BeforeEach void setup() {
        task=new AnalysisTask();task.setTaskId("task");task.setUserId(7L);task.setAttemptNo(2);task.setStatus("SUCCEEDED");
        when(tasks.selectOne(any())).thenReturn(task);
        when(ledger.coverage("task")).thenReturn(List.of(new AiCallLogMapper.Coverage(0,1),new AiCallLogMapper.Coverage(2,1)));
    }
    @AfterEach void clear(){UserContext.clear();}
    AiCallLogMapper.Aggregate group(int no,String stage,int subtask,String cost,long count,long unknown,long running) {
        return new AiCallLogMapper.Aggregate(no,stage,subtask,count,new BigDecimal(cost),unknown,running,new BigDecimal("1000"),null,null);
    }
    @Test void sumsCurrentAndHistoryOnceAndIncludesFailedAttempts() throws Exception {
        when(ledger.summarize("task")).thenReturn(List.of(group(0,"VIDEO_ANALYSIS",0,"0.2",2,0,0),
                group(2,"ASR",0,"0.01",1,0,0),group(2,"VIDEO_ANALYSIS",1,"0.03",3,1,0)));
        when(ledger.segmentInfo("task",2)).thenReturn(List.of(new AiCallLogMapper.SegmentInfo(0,0,1000,0,0),new AiCallLogMapper.SegmentInfo(1,1000,2000,null,null)));
        var result=service.get("task",7L);
        assertEquals("0.0400000000",result.current().knownCostCny());assertEquals("0.2400000000",result.lifetime().knownCostCny());
        assertEquals(4,result.current().callCount());assertEquals(6,result.lifetime().callCount());assertEquals(1,result.current().incompleteCount());
        assertFalse(result.current().complete());assertTrue(result.current().coverageKnown());
        assertTrue(result.segments().get(0).reused());assertEquals("0.0000000000",result.segments().get(0).current().knownCostCny());
        assertEquals(0,result.segments().get(0).current().callCount());
        assertEquals("0.2000000000",result.segments().get(0).reusedSource().knownCostCny());
        assertEquals(2,result.segments().get(0).reusedSource().callCount());
        assertFalse(json.writeValueAsString(result).contains("usageJson"));
        verify(ledger,times(1)).summarize("task");
    }
    @Test void followsReuseAcrossExecutionsAndDifferentSegmentNumbers() {
        when(ledger.summarize("task")).thenReturn(List.of(group(0,"VIDEO_ANALYSIS",4,"0.12",1,0,0)));
        when(ledger.segmentInfo("task",2)).thenReturn(List.of(new AiCallLogMapper.SegmentInfo(0,0,1000,1,2)));
        when(ledger.segmentInfo("task",1)).thenReturn(List.of(new AiCallLogMapper.SegmentInfo(2,0,1000,0,4)));
        var result=service.get("task",7L);
        assertEquals("0.1200000000",result.segments().get(0).reusedSource().knownCostCny());
        assertEquals("0.0000000000",result.current().knownCostCny());
        assertEquals("0.1200000000",result.lifetime().knownCostCny());
    }

    @Test void emptyLegacyIsUnknownButMarkedZeroAndReuseAreKnown() {
        when(ledger.coverage("task")).thenReturn(List.of(new AiCallLogMapper.Coverage(2,0)));
        assertFalse(service.get("task",7L).current().coverageKnown());assertFalse(service.get("task",7L).current().complete());
        when(ledger.coverage("task")).thenReturn(List.of(new AiCallLogMapper.Coverage(2,1)));
        var zero=service.get("task",7L);assertTrue(zero.current().complete());assertEquals("0.0000000000",zero.current().knownCostCny());
        task.setStatus("RUNNING");assertFalse(service.get("task",7L).current().complete());
    }
    @Test void olderUnmarkedExecutionDoesNotInvalidateCurrentButLifetimeIsIncomplete() {
        when(ledger.coverage("task")).thenReturn(List.of(new AiCallLogMapper.Coverage(0,0),new AiCallLogMapper.Coverage(2,1)));
        var result=service.get("task",7L);assertTrue(result.current().complete());assertFalse(result.lifetime().complete());assertFalse(result.lifetime().coverageKnown());
    }
    @Test void unownedOrAnonymousNeverQueriesLedger() {
        assertThrows(BusinessException.class,()->service.get("task",8L));assertThrows(BusinessException.class,()->service.get("task",null));
        verify(ledger,never()).coverage(anyString());verify(ledger,never()).summarize(anyString());verify(ledger,never()).segmentInfo(anyString(),anyInt());
    }
    @Test void endpointUsesSessionOwnerAndSerializesMoneyAsString() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(new TaskController(mock(TaskService.class),service,ownership))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var user=new User();user.setId(7L);UserContext.setUser(user);
        mvc.perform(get("/task/task/costs").accept("application/json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.executionNo").value(2))
                .andExpect(jsonPath("$.data.current.knownCostCny").isString())
                .andExpect(jsonPath("$.data.current.knownCostCny").value("0.0000000000"));
        clearInvocations(ledger);user.setId(8L);
        mvc.perform(get("/task/task/costs").accept("application/json")).andExpect(status().isBadRequest());verifyNoInteractions(ledger);
    }
}

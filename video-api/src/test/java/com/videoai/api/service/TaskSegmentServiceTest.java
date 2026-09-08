package com.videoai.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.analysis.SegmentReview;
import com.videoai.common.domain.*;
import com.videoai.common.exception.BusinessException;
import com.videoai.infra.mysql.mapper.*;
import com.videoai.infra.minio.service.StorageService;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskSegmentServiceTest {
    final AnalysisTaskMapper tasks = mock(AnalysisTaskMapper.class);
    final AnalysisSegmentMapper rows = mock(AnalysisSegmentMapper.class);
    final StorageService storage = mock(StorageService.class);
    final ObjectMapper json = new ObjectMapper();
    final TaskSegmentService service = new TaskSegmentService(tasks, rows, storage, json);
    AnalysisTask task;
    @BeforeEach void setup() {
        task = new AnalysisTask(); task.setTaskId("task"); task.setUserId(7L); task.setRetryCount(2);
        task.setStatus("FAILED"); task.setCurrentStep("ANALYZING_SEGMENTS");
        when(tasks.selectOne(any())).thenReturn(task);
    }
    AnalysisSegment row(int index, String status) throws Exception {
        var r = new AnalysisSegment(); r.setSegmentNo(index); r.setStartMs(index*1000L); r.setEndMs((index+1)*1000L);
        r.setStatus(status); r.setObjectKey("private/clip-"+index); r.setExecutionNo(2);
        if ("SUCCEEDED".equals(status)) r.setResult(json.writeValueAsString(new SegmentReview(index,index*1000L,(index+1)*1000L,"已观察",List.of(),List.of())));
        return r;
    }
    @Test void failedParentStillExposesSuccessfulSegmentsInTimeOrder() throws Exception {
        var first = row(0,"SUCCEEDED"); first.setReusedExecutionNo(1);
        var failed = row(1,"FAILED"); failed.setErrorMessage("分析失败");
        when(rows.selectExecution("task",2)).thenReturn(List.of(failed,first));
        var result=service.list("task",7L);
        assertEquals("FAILED",result.taskStatus()); assertEquals(2,result.total()); assertEquals(1,result.succeeded());
        assertEquals(0,result.segments().get(0).segmentNo()); assertTrue(result.segments().get(0).reused());
        assertEquals("已观察",result.segments().get(0).review().summary());
        assertNull(result.segments().get(1).review());
        assertFalse(json.writeValueAsString(result).contains("private/")); verifyNoInteractions(storage);
    }
    @Test void otherUserAndUnauthenticatedRequestsCannotReadOrSign() {
        assertThrows(BusinessException.class,()->service.list("task",8L));
        assertThrows(BusinessException.class,()->service.playback("task",0,2,8L));
        assertThrows(BusinessException.class,()->service.list("task",null));
        verifyNoInteractions(rows,storage);
    }
    @Test void playbackUsesOnlyOwnedCurrentGenerationObject() throws Exception {
        when(rows.selectExecution("task",2)).thenReturn(List.of(row(1,"SUCCEEDED")));
        when(storage.getPresignedUrl("private/clip-1",1)).thenReturn("https://signed.example/clip");
        var result=service.playback("task",1,2,7L);
        assertEquals(1000,result.originalStartMs()); assertEquals("https://signed.example/clip",result.url());
        assertThrows(BusinessException.class,()->service.playback("task",1,1,7L));
        assertThrows(BusinessException.class,()->service.playback("task",9,2,7L));
        verify(storage,times(1)).getPresignedUrl(anyString(),anyInt());
    }
    @Test void corruptResultDoesNotHideOtherSegmentsOrExposeRawData() throws Exception {
        var broken=row(0,"SUCCEEDED"); broken.setResult("private-secret-bad-json");
        when(rows.selectExecution("task",2)).thenReturn(List.of(broken,row(1,"SUCCEEDED")));
        var result=service.list("task",7L);
        assertNull(result.segments().get(0).review()); assertNotNull(result.segments().get(0).errorMessage());
        assertNotNull(result.segments().get(1).review()); assertFalse(json.writeValueAsString(result).contains("private-secret"));
    }
}

package com.videoai.common.analysis;

import com.videoai.common.enums.SegmentStatus;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisContractsTest {
    @Test
    void candidateEvidenceCannotBeMutatedAfterConstruction() {
        var ids = new ArrayList<>(List.of("u00001"));
        var candidate = new CandidateRange(1000, 2000, ids, CandidateRange.Type.ENGAGEMENT, "当前交战");
        ids.clear();
        assertEquals(List.of("u00001"), candidate.utteranceIds());
        assertThrows(UnsupportedOperationException.class, () -> candidate.utteranceIds().clear());
    }

    @Test
    void invalidTimelineUrlsAndIncompleteResultsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptUtterance("u1", -1, 2, "文字", 0));
        assertThrows(IllegalArgumentException.class, () -> new PreparedSegment(0, 10, 10, "video.mp4"));
        assertThrows(IllegalArgumentException.class, () -> new PreparedSegment(0, 0, 10, "https://host/video?sig=secret"));
        assertThrows(IllegalArgumentException.class, () -> new SegmentAnalysisResult(0, SegmentStatus.SUCCEEDED, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SegmentAnalysisResult(0, SegmentStatus.PROCESSING, null, null, null));
        var result = new SegmentAnalysisResult(0, SegmentStatus.FAILED, null, "timeout", null);
        assertNull(result.usageJson());
    }
}

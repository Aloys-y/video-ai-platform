package com.videoai.worker.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.videoai.common.analysis.TranscriptUtterance;
import java.io.IOException;
import java.util.List;

public interface CloudAsrClient {
    String submit(String audioUrl) throws IOException;
    Query query(String taskId) throws IOException;
    Transcript downloadResult(String resultUrl, int partNo, long startMs, long endMs) throws IOException;
    record Query(String status, String resultUrl, JsonNode usage) {}
    record Transcript(List<TranscriptUtterance> utterances, JsonNode sanitizedResponse) {
        public Transcript { utterances = List.copyOf(utterances); }
    }
}

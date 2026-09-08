package com.videoai.worker.segment;

import com.videoai.worker.screening.SegmentReviewParser;
import com.videoai.worker.service.provider.AiVideoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class SegmentModelSettings {
    private final AiVideoProvider provider;
    public Map<String, Object> snapshot() {
        return new TreeMap<>(Map.of("version", "p6-segments-v1", "model", provider.segmentSettings(),
                "prompt", SegmentReviewParser.VIDEO_PROMPT));
    }
}

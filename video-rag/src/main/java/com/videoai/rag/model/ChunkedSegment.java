package com.videoai.rag.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChunkedSegment {

    private int chunkNo;

    private String headingPath;

    private String title;

    private String contentText;
}

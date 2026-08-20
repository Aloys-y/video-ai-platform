package com.videoai.common.rag;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RetrievalHit {

    private String vectorId;
    private String cardCode;
    private String title;
    private String category;
    private String headingPath;
    private String contentText;
    private double score;
}

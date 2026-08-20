package com.videoai.common.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeIndexMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String jobId;
    private String baseCode;
    private String jobType;
    private String cardCode;
    private Long timestamp;
}

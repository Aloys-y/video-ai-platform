package com.videoai.api.controller;

import com.videoai.api.context.UserContext;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.domain.TaskRagContext;
import com.videoai.common.dto.request.KnowledgeBaseVersionRequest;
import com.videoai.common.dto.request.KnowledgeCardUpsertRequest;
import com.videoai.common.dto.request.KnowledgeMarkdownDocument;
import com.videoai.common.dto.request.RagRetrieveDebugRequest;
import com.videoai.common.dto.response.ApiResponse;
import com.videoai.common.dto.response.KnowledgeCardPreviewResponse;
import com.videoai.common.dto.response.KnowledgeCardResponse;
import com.videoai.common.dto.response.KnowledgeIndexJobResponse;
import com.videoai.common.dto.response.KnowledgeMarkdownImportResponse;
import com.videoai.common.dto.response.RagRetrieveDebugResponse;
import com.videoai.common.dto.response.TaskRagContextResponse;
import com.videoai.common.enums.ErrorCode;
import com.videoai.common.exception.BusinessException;
import com.videoai.common.rag.PromptEnvelope;
import com.videoai.common.rag.RagContext;
import com.videoai.infra.mysql.mapper.TaskRagContextMapper;
import com.videoai.infra.rag.config.RagProperties;
import com.videoai.rag.service.ApexPromptTemplateService;
import com.videoai.rag.service.KnowledgeBaseService;
import com.videoai.rag.service.KnowledgeCardService;
import com.videoai.rag.service.KnowledgeIndexJobService;
import com.videoai.rag.service.KnowledgeRetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "RAG Knowledge Admin", description = "Knowledge card CRUD, indexing and retrieval debug APIs")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminRagController {

    private final KnowledgeCardService knowledgeCardService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeIndexJobService knowledgeIndexJobService;
    private final KnowledgeRetrievalService knowledgeRetrievalService;
    private final ApexPromptTemplateService apexPromptTemplateService;
    private final TaskRagContextMapper taskRagContextMapper;
    private final RagProperties ragProperties;

    @Operation(summary = "Create knowledge card")
    @PostMapping("/knowledge/cards")
    public ApiResponse<KnowledgeCardResponse> createCard(@Valid @RequestBody KnowledgeCardUpsertRequest request) {
        assertAdmin();
        return ApiResponse.success(knowledgeCardService.create(request, UserContext.getUserBizId()));
    }

    @Operation(summary = "Import markdown files as draft cards",
            description = "Upload multiple .md/.markdown files. The system creates draft cards from file names or first-level headings so metadata can be completed later.")
    @PostMapping(value = "/knowledge/cards/import-markdown", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeMarkdownImportResponse> importMarkdownCards(
            @Parameter(description = "Markdown files") @RequestPart("files") MultipartFile[] files,
            @Parameter(description = "Default category, fallback to MECHANIC") @RequestParam(value = "defaultCategory", required = false) String defaultCategory,
            @Parameter(description = "Whether cards are enabled immediately and queued for indexing") @RequestParam(value = "defaultEnabled", required = false) Boolean defaultEnabled,
            @Parameter(description = "Whether imported cards are timeless") @RequestParam(value = "defaultTimeless", required = false) Boolean defaultTimeless,
            @Parameter(description = "Optional cardCode prefix") @RequestParam(value = "codePrefix", required = false) String codePrefix) {
        assertAdmin();
        if (files == null || files.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "At least one markdown file is required");
        }

        List<KnowledgeMarkdownDocument> documents = List.of(files).stream()
                .map(this::toMarkdownDocument)
                .toList();

        return ApiResponse.success(knowledgeCardService.importMarkdownDocuments(
                documents,
                defaultCategory,
                defaultEnabled,
                defaultTimeless,
                codePrefix,
                UserContext.getUserBizId()));
    }

    @Operation(summary = "Preview parsed knowledge cards",
            description = "Parse uploaded markdown files into card previews without saving to database. Returns editable card fields for user review.")
    @PostMapping(value = "/knowledge/cards/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<KnowledgeCardPreviewResponse>> previewMarkdownCards(
            @Parameter(description = "Markdown files") @RequestPart("files") MultipartFile[] files,
            @Parameter(description = "Default category") @RequestParam(value = "defaultCategory", required = false) String defaultCategory,
            @Parameter(description = "CardCode prefix") @RequestParam(value = "codePrefix", required = false) String codePrefix,
            @Parameter(description = "Default enabled") @RequestParam(value = "defaultEnabled", required = false) Boolean defaultEnabled,
            @Parameter(description = "Default timeless") @RequestParam(value = "defaultTimeless", required = false) Boolean defaultTimeless) {
        assertAdmin();
        if (files == null || files.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "At least one markdown file is required");
        }

        List<KnowledgeMarkdownDocument> documents = List.of(files).stream()
                .map(this::toMarkdownDocument)
                .toList();

        return ApiResponse.success(knowledgeCardService.previewMarkdownDocuments(
                documents,
                defaultCategory,
                codePrefix,
                defaultEnabled,
                defaultTimeless));
    }

    @Operation(summary = "Batch create knowledge cards",
            description = "Save a list of user-confirmed card previews. Each card is created in its own transaction.")
    @PostMapping("/knowledge/cards/batch-create")
    public ApiResponse<KnowledgeMarkdownImportResponse> batchCreateCards(
            @Valid @RequestBody List<KnowledgeCardUpsertRequest> requests) {
        assertAdmin();
        if (requests == null || requests.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "At least one card is required");
        }
        return ApiResponse.success(knowledgeCardService.batchCreateCards(requests, UserContext.getUserBizId()));
    }

    @Operation(summary = "Update knowledge card")
    @PutMapping("/knowledge/cards/{cardCode}")
    public ApiResponse<KnowledgeCardResponse> updateCard(
            @Parameter(description = "Card code") @PathVariable("cardCode") String cardCode,
            @Valid @RequestBody KnowledgeCardUpsertRequest request) {
        assertAdmin();
        return ApiResponse.success(knowledgeCardService.update(cardCode, request, UserContext.getUserBizId()));
    }

    @Operation(summary = "Get knowledge card")
    @GetMapping("/knowledge/cards/{cardCode}")
    public ApiResponse<KnowledgeCardResponse> getCard(
            @Parameter(description = "Card code") @PathVariable("cardCode") String cardCode) {
        assertAdmin();
        return ApiResponse.success(knowledgeCardService.get(cardCode));
    }

    @Operation(summary = "Delete knowledge card")
    @DeleteMapping("/knowledge/cards/{cardCode}")
    public ApiResponse<String> deleteCard(
            @Parameter(description = "Card code") @PathVariable("cardCode") String cardCode) {
        assertAdmin();
        knowledgeCardService.delete(cardCode);
        return ApiResponse.success(cardCode);
    }

    @Operation(summary = "List knowledge cards (paginated)")
    @GetMapping("/knowledge/cards")
    public ApiResponse<com.baomidou.mybatisplus.extension.plugins.pagination.Page<KnowledgeCardResponse>> listCards(
            @Parameter(description = "Keyword") @RequestParam(value = "keyword", required = false) String keyword,
            @Parameter(description = "Category filter") @RequestParam(value = "category", required = false) String category,
            @Parameter(description = "Enabled filter") @RequestParam(value = "enabled", required = false) Boolean enabled,
            @Parameter(description = "Page (1-based)") @RequestParam(value = "page", defaultValue = "1") int page,
            @Parameter(description = "Page size") @RequestParam(value = "size", defaultValue = "10") int size) {
        assertAdmin();
        return ApiResponse.success(knowledgeCardService.list(keyword, category, enabled, page, size));
    }

    @Operation(summary = "Reindex one knowledge card")
    @PostMapping("/knowledge/cards/{cardCode}/reindex")
    public ApiResponse<String> reindexCard(
            @Parameter(description = "Card code") @PathVariable("cardCode") String cardCode) {
        assertAdmin();
        return ApiResponse.success(knowledgeCardService.reindex(cardCode, UserContext.getUserBizId()));
    }

    @Operation(summary = "Cleanup all knowledge data")
    @DeleteMapping("/knowledge/cleanup")
    public ApiResponse<String> cleanupKnowledge() {
        assertAdmin();
        int count = knowledgeCardService.cleanup();
        return ApiResponse.success("Deleted " + count + " cards and all related chunks, jobs, and Milvus vectors");
    }

    @Operation(summary = "Rebuild all knowledge indexes")
    @PostMapping("/knowledge/rebuild")
    public ApiResponse<String> rebuildKnowledge() {
        assertAdmin();
        return ApiResponse.success(knowledgeCardService.rebuild(UserContext.getUserBizId()));
    }

    @Operation(summary = "Get knowledge index job")
    @GetMapping("/knowledge/jobs/{jobId}")
    public ApiResponse<KnowledgeIndexJobResponse> getJob(
            @Parameter(description = "Job ID") @PathVariable("jobId") String jobId) {
        assertAdmin();
        return ApiResponse.success(toResponse(knowledgeIndexJobService.getRequiredJob(jobId)));
    }

    @Operation(summary = "Update current knowledge base version")
    @PutMapping("/knowledge/base/version")
    public ApiResponse<String> updateBaseVersion(@Valid @RequestBody KnowledgeBaseVersionRequest request) {
        assertAdmin();
        return ApiResponse.success(knowledgeBaseService.updateCurrentVersion(request.getVersionTag()).getCurrentVersionTag());
    }

    @Operation(summary = "RAG retrieval eval", description = "Return detailed retrieval results for quality evaluation")
    @PostMapping("/rag/retrieve-test")
    public ApiResponse<RagRetrieveDebugResponse> retrieveDebug(@Valid @RequestBody RagRetrieveDebugRequest request) {
        assertAdmin();
        RagContext context = knowledgeRetrievalService.retrieve(request.getQuery());
        PromptEnvelope envelope = PromptEnvelope.builder()
                .systemPrompt(apexPromptTemplateService.systemPrompt())
                .retrievalContext(apexPromptTemplateService.retrievalBlock(context.getContextText()))
                .userPrompt(apexPromptTemplateService.normalizeUserPrompt(request.getQuery()))
                .build();

        String contextPreview = context.getContextText() != null && context.getContextText().length() > 500
                ? context.getContextText().substring(0, 500) + "..."
                : context.getContextText();

        return ApiResponse.success(RagRetrieveDebugResponse.builder()
                .queryText(request.getQuery())
                .expandedQuery(context.getQueryText())
                .versionTag(context.getVersionTag())
                .topK(ragProperties.getTopK())
                .minScore(ragProperties.getMinScore())
                .hitCount(context.getHits() != null ? context.getHits().size() : 0)
                .latencyMs(context.getLatencyMs())
                .contextPreview(contextPreview)
                .promptPreview(envelope.buildFullPrompt())
                .hits(context.getHits())
                .build());
    }

    @Operation(summary = "Get task RAG context", description = "Inspect the retrieval snapshot associated with one analysis task")
    @GetMapping("/tasks/{taskId}/rag-context")
    public ApiResponse<TaskRagContextResponse> getTaskRagContext(
            @Parameter(description = "Task ID") @PathVariable("taskId") String taskId) {
        assertAdmin();
        TaskRagContext context = taskRagContextMapper.selectLatestByTaskId(taskId);
        if (context == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_RAG_CONTEXT_NOT_FOUND);
        }
        return ApiResponse.success(TaskRagContextResponse.builder()
                .taskId(context.getTaskId())
                .baseCode(context.getBaseCode())
                .versionTag(context.getVersionTag())
                .queryText(context.getQueryText())
                .retrievalMode(context.getRetrievalMode())
                .topK(context.getTopK())
                .hitCount(context.getHitCount())
                .contextChars(context.getContextChars())
                .status(context.getStatus())
                .latencyMs(context.getLatencyMs())
                .snapshotJson(context.getSnapshotJson())
                .createdAt(context.getCreatedAt())
                .updatedAt(context.getUpdatedAt())
                .build());
    }

    private KnowledgeIndexJobResponse toResponse(KnowledgeIndexJob job) {
        return KnowledgeIndexJobResponse.builder()
                .jobId(job.getJobId())
                .baseCode(job.getBaseCode())
                .jobType(job.getJobType())
                .cardCode(job.getCardCode())
                .status(job.getStatus())
                .totalChunks(job.getTotalChunks())
                .successChunks(job.getSuccessChunks())
                .failedChunks(job.getFailedChunks())
                .errorMessage(job.getErrorMessage())
                .createdBy(job.getCreatedBy())
                .queuedAt(job.getQueuedAt())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    private KnowledgeMarkdownDocument toMarkdownDocument(MultipartFile file) {
        try {
            return KnowledgeMarkdownDocument.builder()
                    .fileName(file.getOriginalFilename())
                    .contentMarkdown(new String(file.getBytes(), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "Failed to read uploaded markdown file");
        }
    }

    private void assertAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException(ErrorCode.USER_FORBIDDEN, "Only admin can access this endpoint");
        }
    }
}

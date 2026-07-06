package com.videoai.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoai.common.domain.KnowledgeBase;
import com.videoai.common.domain.KnowledgeCard;
import com.videoai.common.domain.KnowledgeIndexJob;
import com.videoai.common.dto.request.KnowledgeMarkdownDocument;
import com.videoai.common.enums.KnowledgeIndexStatus;
import com.videoai.infra.mysql.mapper.KnowledgeCardMapper;
import com.videoai.infra.mysql.mapper.KnowledgeChunkMapper;
import com.videoai.infra.rag.vector.VectorStoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeCardServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeCardMapper knowledgeCardMapper;

    @Mock
    private KnowledgeIndexJobService knowledgeIndexJobService;

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;

    @Mock
    private VectorStoreClient vectorStoreClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @SuppressWarnings("unchecked")
    @Test
    void shouldImportMarkdownAsDraftWithoutIndexJob() {
        KnowledgeCardService service = new KnowledgeCardService(
                knowledgeBaseService,
                knowledgeCardMapper,
                knowledgeChunkMapper,
                knowledgeIndexJobService,
                vectorStoreClient,
                new ObjectMapper(),
                transactionTemplate);

        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        base.setCurrentVersionTag("v1");
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);
        when(knowledgeCardMapper.selectByCardCode("apex-default", "apex-r301-guide")).thenReturn(null);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));

        var response = service.importMarkdownDocuments(
                List.of(KnowledgeMarkdownDocument.builder()
                        .fileName("apex-r301-guide.md")
                        .contentMarkdown("# R-301 Guide\n\nBody")
                        .build()),
                null,
                false,
                false,
                null,
                "admin");

        ArgumentCaptor<KnowledgeCard> cardCaptor = ArgumentCaptor.forClass(KnowledgeCard.class);
        verify(knowledgeCardMapper).insert(cardCaptor.capture());
        verify(knowledgeIndexJobService, never()).createCardUpsertJob(anyString(), anyString(), anyString());

        KnowledgeCard inserted = cardCaptor.getValue();
        assertEquals("R-301 Guide", inserted.getTitle());
        assertEquals("MECHANIC", inserted.getCategory());
        assertEquals(KnowledgeIndexStatus.DRAFT.getCode(), inserted.getIndexStatus());
        assertEquals(0, inserted.getEnabled());
        assertNull(inserted.getLastJobId());
        assertEquals(1, response.getSuccessCount());
        assertEquals("Imported as draft, metadata can be edited later", response.getItems().get(0).getMessage());
    }

    @SuppressWarnings("unchecked")
    @Test
    void shouldQueueIndexJobWhenImportIsEnabled() {
        KnowledgeCardService service = new KnowledgeCardService(
                knowledgeBaseService,
                knowledgeCardMapper,
                knowledgeChunkMapper,
                knowledgeIndexJobService,
                vectorStoreClient,
                new ObjectMapper(),
                transactionTemplate);

        KnowledgeBase base = new KnowledgeBase();
        base.setBaseCode("apex-default");
        base.setCurrentVersionTag("v1");
        when(knowledgeBaseService.getRequiredBase()).thenReturn(base);

        KnowledgeCard persisted = new KnowledgeCard();
        persisted.setBaseCode("apex-default");
        persisted.setCardCode("apex-r99-notes");
        persisted.setTitle("R99 Notes");
        persisted.setCategory("WEAPON");
        persisted.setEnabled(1);
        persisted.setTimeless(0);
        persisted.setIndexStatus(KnowledgeIndexStatus.PENDING.getCode());
        persisted.setLastJobId("job-1");

        when(knowledgeCardMapper.selectByCardCode("apex-default", "apex-r99-notes"))
                .thenReturn(null)
                .thenReturn(persisted);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));

        KnowledgeIndexJob job = new KnowledgeIndexJob();
        job.setJobId("job-1");
        when(knowledgeIndexJobService.createCardUpsertJob("apex-default", "apex-r99-notes", "admin"))
                .thenReturn(job);

        var response = service.importMarkdownDocuments(
                List.of(KnowledgeMarkdownDocument.builder()
                        .fileName("apex-r99-notes.md")
                        .contentMarkdown("R99 close range notes")
                        .build()),
                "WEAPON",
                true,
                false,
                null,
                "admin");

        verify(knowledgeIndexJobService).createCardUpsertJob("apex-default", "apex-r99-notes", "admin");
        verify(knowledgeCardMapper).updateIndexState("apex-default", "apex-r99-notes",
                KnowledgeIndexStatus.PENDING.getCode(), "job-1", null, "admin");
        assertEquals("job-1", response.getItems().get(0).getLastJobId());
        assertEquals("SUCCESS", response.getItems().get(0).getStatus());
    }
}

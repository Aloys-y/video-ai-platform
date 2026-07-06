package com.videoai.infra.rag.vector;

import com.videoai.infra.rag.config.MilvusProperties;
import com.videoai.infra.rag.config.OpenAiEmbeddingProperties;
import com.videoai.infra.rag.model.VectorRecord;
import com.videoai.infra.rag.model.VectorSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MilvusVectorStoreClient implements VectorStoreClient {

    private static final String FIELD_ID = "id";
    private static final String FIELD_KB_CODE = "kb_code";
    private static final String FIELD_VERSION_TAG = "version_tag";
    private static final String FIELD_CARD_CODE = "card_code";
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_SUBJECT_CODE = "subject_code";
    private static final String FIELD_ENABLED = "enabled";
    private static final String FIELD_TIMELESS = "timeless";
    private static final String FIELD_CHUNK_NO = "chunk_no";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_HEADING_PATH = "heading_path";
    private static final String FIELD_CONTENT_TEXT = "content_text";
    private static final String FIELD_EMBEDDING = "embedding";

    private static final List<String> OUTPUT_FIELDS = List.of(
            FIELD_ID, FIELD_KB_CODE, FIELD_VERSION_TAG, FIELD_CARD_CODE,
            FIELD_CATEGORY, FIELD_HEADING_PATH, FIELD_TITLE, FIELD_CONTENT_TEXT);

    private final MilvusProperties milvusProperties;
    private final int dimension;
    private final MilvusServiceClient client;
    private final AtomicBoolean collectionEnsured = new AtomicBoolean(false);

    public MilvusVectorStoreClient(MilvusProperties milvusProperties,
                                   OpenAiEmbeddingProperties embeddingProperties) {
        this.milvusProperties = milvusProperties;
        this.dimension = embeddingProperties.getDimension();

        String baseUrl = milvusProperties.getBaseUrl();
        String host = extractHost(baseUrl);
        int port = extractPort(baseUrl);

        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .withDatabaseName(milvusProperties.getDatabase());
        if (milvusProperties.getToken() != null && !milvusProperties.getToken().isBlank()) {
            builder.withAuthorization(milvusProperties.getToken());
        }
        this.client = new MilvusServiceClient(builder.build());
    }

    @Override
    public void ensureCollection() {
        if (!collectionEnsured.compareAndSet(false, true)) {
            return;
        }

        try {
            String collectionName = milvusProperties.getCollection();
            String dbName = milvusProperties.getDatabase();

            // Check if collection exists
            R<Boolean> hasColl = client.hasCollection(HasCollectionParam.newBuilder()
                    .withDatabaseName(dbName)
                    .withCollectionName(collectionName)
                    .build());
            if (hasColl.getData() != null && hasColl.getData()) {
                log.info("Milvus collection already exists: {}", collectionName);
                return;
            }

            // Create collection
            FieldType idField = FieldType.newBuilder()
                    .withName(FIELD_ID)
                    .withDataType(DataType.VarChar)
                    .withMaxLength(128)
                    .withPrimaryKey(true)
                    .build();

            List<FieldType> scalarFields = Arrays.asList(
                    idField,
                    FieldType.newBuilder().withName(FIELD_KB_CODE).withDataType(DataType.VarChar).withMaxLength(64).build(),
                    FieldType.newBuilder().withName(FIELD_VERSION_TAG).withDataType(DataType.VarChar).withMaxLength(64).build(),
                    FieldType.newBuilder().withName(FIELD_CARD_CODE).withDataType(DataType.VarChar).withMaxLength(64).build(),
                    FieldType.newBuilder().withName(FIELD_CATEGORY).withDataType(DataType.VarChar).withMaxLength(32).build(),
                    FieldType.newBuilder().withName(FIELD_SUBJECT_CODE).withDataType(DataType.VarChar).withMaxLength(64).build(),
                    FieldType.newBuilder().withName(FIELD_ENABLED).withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName(FIELD_TIMELESS).withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName(FIELD_CHUNK_NO).withDataType(DataType.Int64).build(),
                    FieldType.newBuilder().withName(FIELD_TITLE).withDataType(DataType.VarChar).withMaxLength(255).build(),
                    FieldType.newBuilder().withName(FIELD_HEADING_PATH).withDataType(DataType.VarChar).withMaxLength(512).build(),
                    FieldType.newBuilder().withName(FIELD_CONTENT_TEXT).withDataType(DataType.VarChar).withMaxLength(8192).build()
            );

            FieldType vectorField = FieldType.newBuilder()
                    .withName(FIELD_EMBEDDING)
                    .withDataType(DataType.FloatVector)
                    .withDimension(dimension)
                    .build();

            CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                    .withDatabaseName(dbName)
                    .withCollectionName(collectionName)
                    .withFieldTypes(new ArrayList<FieldType>() {{
                        addAll(scalarFields);
                        add(vectorField);
                    }})
                    .withEnableDynamicField(false)
                    .build();

            R<RpcStatus> createResult = client.createCollection(createParam);
            if (createResult.getStatus() != 0) {
                throw new IllegalStateException("Failed to create Milvus collection: " + createResult.getMessage());
            }
            log.info("Milvus collection created: {}", collectionName);

            // Create index
            MetricType metricType = "IP".equalsIgnoreCase(milvusProperties.getMetricType()) ? MetricType.IP : MetricType.COSINE;
            IndexType indexType = resolveIndexType(milvusProperties.getIndexType());

            CreateIndexParam indexParam = CreateIndexParam.newBuilder()
                    .withDatabaseName(dbName)
                    .withCollectionName(collectionName)
                    .withFieldName(FIELD_EMBEDDING)
                    .withIndexType(indexType)
                    .withMetricType(metricType)
                    .withExtraParam("{\"M\":" + milvusProperties.getHnswM()
                            + ",\"efConstruction\":" + milvusProperties.getHnswEfConstruction() + "}")
                    .build();

            R<RpcStatus> indexResult = client.createIndex(indexParam);
            if (indexResult.getStatus() != 0) {
                throw new IllegalStateException("Failed to create Milvus index: " + indexResult.getMessage());
            }
            log.info("Milvus index created for collection: {}", collectionName);

            // Load collection (sync mode, wait until loaded)
            LoadCollectionParam loadParam = LoadCollectionParam.newBuilder()
                    .withDatabaseName(dbName)
                    .withCollectionName(collectionName)
                    .withSyncLoad(true)
                    .withSyncLoadWaitingInterval(500L)
                    .withSyncLoadWaitingTimeout(60L)
                    .build();

            R<RpcStatus> loadResult = client.loadCollection(loadParam);
            if (loadResult.getStatus() != 0) {
                throw new IllegalStateException("Failed to load Milvus collection: " + loadResult.getMessage());
            }
            log.info("Milvus collection loaded: {}", collectionName);
        } catch (Exception e) {
            collectionEnsured.set(false);
            log.warn("Milvus collection initialization failed, will retry: {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public void upsert(List<VectorRecord> records) {
        ensureCollection();

        List<String> idVals = new ArrayList<>();
        List<String> kbCodeVals = new ArrayList<>();
        List<String> versionTagVals = new ArrayList<>();
        List<String> cardCodeVals = new ArrayList<>();
        List<String> categoryVals = new ArrayList<>();
        List<String> subjectCodeVals = new ArrayList<>();
        List<Long> enabledVals = new ArrayList<>();
        List<Long> timelessVals = new ArrayList<>();
        List<Long> chunkNoVals = new ArrayList<>();
        List<String> titleVals = new ArrayList<>();
        List<String> headingPathVals = new ArrayList<>();
        List<String> contentTextVals = new ArrayList<>();
        List<List<Float>> embeddingVals = new ArrayList<>();

        for (VectorRecord record : records) {
            Map<String, Object> f = record.getFields();
            idVals.add(record.getId());
            kbCodeVals.add(str(f, FIELD_KB_CODE));
            versionTagVals.add(str(f, FIELD_VERSION_TAG));
            cardCodeVals.add(str(f, FIELD_CARD_CODE));
            categoryVals.add(str(f, FIELD_CATEGORY));
            subjectCodeVals.add(str(f, FIELD_SUBJECT_CODE));
            enabledVals.add(num(f, FIELD_ENABLED));
            timelessVals.add(num(f, FIELD_TIMELESS));
            chunkNoVals.add(num(f, FIELD_CHUNK_NO));
            titleVals.add(str(f, FIELD_TITLE));
            headingPathVals.add(str(f, FIELD_HEADING_PATH));
            contentTextVals.add(str(f, FIELD_CONTENT_TEXT));
            embeddingVals.add(record.getVector());
        }

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(FIELD_ID, idVals));
        fields.add(new InsertParam.Field(FIELD_KB_CODE, kbCodeVals));
        fields.add(new InsertParam.Field(FIELD_VERSION_TAG, versionTagVals));
        fields.add(new InsertParam.Field(FIELD_CARD_CODE, cardCodeVals));
        fields.add(new InsertParam.Field(FIELD_CATEGORY, categoryVals));
        fields.add(new InsertParam.Field(FIELD_SUBJECT_CODE, subjectCodeVals));
        fields.add(new InsertParam.Field(FIELD_ENABLED, enabledVals));
        fields.add(new InsertParam.Field(FIELD_TIMELESS, timelessVals));
        fields.add(new InsertParam.Field(FIELD_CHUNK_NO, chunkNoVals));
        fields.add(new InsertParam.Field(FIELD_TITLE, titleVals));
        fields.add(new InsertParam.Field(FIELD_HEADING_PATH, headingPathVals));
        fields.add(new InsertParam.Field(FIELD_CONTENT_TEXT, contentTextVals));
        fields.add(new InsertParam.Field(FIELD_EMBEDDING, embeddingVals));

        InsertParam insertParam = InsertParam.newBuilder()
                .withDatabaseName(milvusProperties.getDatabase())
                .withCollectionName(milvusProperties.getCollection())
                .withFields(fields)
                .build();

        R<MutationResult> result = client.insert(insertParam);
        if (result.getStatus() != 0) {
            throw new IllegalStateException("Milvus upsert failed: " + result.getMessage());
        }
    }

    @Override
    public void deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String expr = ids.stream()
                .map(id -> FIELD_ID + " == \"" + id + "\"")
                .collect(Collectors.joining(" || "));

        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withDatabaseName(milvusProperties.getDatabase())
                .withCollectionName(milvusProperties.getCollection())
                .withExpr(expr)
                .build();

        R<MutationResult> result = client.delete(deleteParam);
        if (result.getStatus() != 0) {
            throw new IllegalStateException("Milvus delete failed: " + result.getMessage());
        }
    }

    @Override
    public List<VectorSearchResult> search(List<Float> vector, int topK, String filterExpression) {
        ensureCollection();

        SearchParam searchParam = SearchParam.newBuilder()
                .withDatabaseName(milvusProperties.getDatabase())
                .withCollectionName(milvusProperties.getCollection())
                .withVectors(List.of(vector))
                .withVectorFieldName(FIELD_EMBEDDING)
                .withTopK(topK)
                .withOutFields(OUTPUT_FIELDS)
                .withExpr(filterExpression)
                .withParams("{\"ef\":" + milvusProperties.getSearchEf() + "}")
                .withMetricType("IP".equalsIgnoreCase(milvusProperties.getMetricType())
                        ? MetricType.IP : MetricType.COSINE)
                .build();

        R<SearchResults> response = client.search(searchParam);
        if (response.getStatus() != 0) {
            throw new IllegalStateException("Milvus search failed: " + response.getMessage());
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

        List<VectorSearchResult> results = new ArrayList<>();
        for (SearchResultsWrapper.IDScore idScore : idScores) {
            Map<String, Object> fields = new LinkedHashMap<>(idScore.getFieldValues());
            results.add(VectorSearchResult.builder()
                    .id(idScore.getStrID())
                    .score(idScore.getScore())
                    .fields(fields)
                    .build());
        }
        return results;
    }

    private IndexType resolveIndexType(String name) {
        if (name == null) return IndexType.AUTOINDEX;
        return switch (name.toUpperCase()) {
            case "FLAT" -> IndexType.FLAT;
            case "IVF_FLAT" -> IndexType.IVF_FLAT;
            case "IVF_SQ8" -> IndexType.IVF_SQ8;
            case "IVF_PQ" -> IndexType.IVF_PQ;
            case "HNSW" -> IndexType.HNSW;
            case "DISKANN" -> IndexType.DISKANN;
            default -> IndexType.AUTOINDEX;
        };
    }

    private String extractHost(String baseUrl) {
        String url = baseUrl.replace("http://", "").replace("https://", "");
        int colonIdx = url.lastIndexOf(':');
        int slashIdx = url.indexOf('/');
        if (colonIdx > 0 && (slashIdx < 0 || colonIdx > slashIdx)) {
            return url.substring(0, colonIdx);
        }
        if (slashIdx > 0) {
            return url.substring(0, slashIdx);
        }
        return url;
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val == null ? "" : String.valueOf(val);
    }

    private long num(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        return 0L;
    }

    private int extractPort(String baseUrl) {
        String url = baseUrl.replace("http://", "").replace("https://", "");
        int colonIdx = url.lastIndexOf(':');
        int slashIdx = url.indexOf('/');
        if (colonIdx > 0) {
            String portStr = slashIdx > colonIdx
                    ? url.substring(colonIdx + 1, slashIdx)
                    : url.substring(colonIdx + 1);
            return Integer.parseInt(portStr);
        }
        return 19530;
    }
}

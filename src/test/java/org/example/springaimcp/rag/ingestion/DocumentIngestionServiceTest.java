package org.example.springaimcp.rag.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.example.springaimcp.rag.RagMetadataKeys;
import org.example.springaimcp.rag.config.RagProperties;
import org.example.springaimcp.rag.indexer.DocumentIndexer;
import org.example.springaimcp.rag.parser.DocumentParser;
import org.example.springaimcp.rag.splitter.DocumentChunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DocumentIngestionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void unchangedFileIsReindexedWhenMissingFromVectorStore() throws IOException {
        Path sourceFile = createSourceFile();
        TestParser parser = new TestParser();
        TestChunker chunker = new TestChunker();
        TestIndexer indexer = new TestIndexer();
        RagProperties ragProperties = ragProperties();
        IngestionStateStore stateStore = newStateStore();

        DocumentIngestionService service = new DocumentIngestionService(
                parser, chunker, indexer, stateStore, ragProperties);

        IngestionResult firstRun = service.ingest(sourceFile.toString());
        assertFalse(firstRun.skippedFile());
        assertEquals(1, firstRun.indexedChunkCount());

        indexer.idsPresent = false;
        IngestionResult secondRun = service.ingest(sourceFile.toString());

        assertFalse(secondRun.skippedFile());
        assertEquals(1, secondRun.indexedChunkCount());
        assertEquals(2, indexer.indexCalls);
    }

    @Test
    void unchangedFileIsSkippedWhenAllChunkIdsArePresent() throws IOException {
        Path sourceFile = createSourceFile();
        TestParser parser = new TestParser();
        TestChunker chunker = new TestChunker();
        TestIndexer indexer = new TestIndexer();
        RagProperties ragProperties = ragProperties();
        IngestionStateStore stateStore = newStateStore();

        DocumentIngestionService service = new DocumentIngestionService(
                parser, chunker, indexer, stateStore, ragProperties);

        service.ingest(sourceFile.toString());
        indexer.idsPresent = true;

        IngestionResult secondRun = service.ingest(sourceFile.toString());

        assertTrue(secondRun.skippedFile());
        assertEquals(0, secondRun.indexedChunkCount());
        assertEquals(1, indexer.indexCalls);
    }

    @Test
    void ingestionEnsuresAllExpectedMetadataPropertiesArePresent() throws IOException {
        Path sourceFile = createSourceFile();
        TestParser parser = new TestParser();
        TestChunker chunker = new TestChunker();
        TestIndexer indexer = new TestIndexer();
        RagProperties ragProperties = ragProperties();
        IngestionStateStore stateStore = newStateStore();

        DocumentIngestionService service = new DocumentIngestionService(
                parser, chunker, indexer, stateStore, ragProperties);

        service.ingest(sourceFile.toString());
        Document indexed = indexer.lastIndexed.get(0);
        Map<String, Object> metadata = indexed.getMetadata();

        assertMetadataPresent(metadata, RagMetadataKeys.SOURCE);
        assertMetadataPresent(metadata, RagMetadataKeys.REPO);
        assertMetadataPresent(metadata, RagMetadataKeys.MODULE);
        assertMetadataPresent(metadata, RagMetadataKeys.BRANCH);
        assertMetadataPresent(metadata, RagMetadataKeys.FILE_PATH);
        assertMetadataPresent(metadata, RagMetadataKeys.FILE_NAME);
        assertMetadataPresent(metadata, RagMetadataKeys.PACKAGE);
        assertMetadataPresent(metadata, RagMetadataKeys.CLASS_NAME);
        assertMetadataPresent(metadata, RagMetadataKeys.MEMBER_NAME);
        assertMetadataPresent(metadata, RagMetadataKeys.MEMBER_TYPE);
        assertMetadataPresent(metadata, RagMetadataKeys.SIGNATURE);
        assertMetadataPresent(metadata, RagMetadataKeys.LANGUAGE);
        assertMetadataPresent(metadata, RagMetadataKeys.CHUNK_INDEX);
        assertMetadataPresent(metadata, RagMetadataKeys.TOTAL_CHUNKS);
        assertMetadataPresent(metadata, RagMetadataKeys.FILE_HASH);
        assertMetadataPresent(metadata, RagMetadataKeys.CHUNK_HASH);
        assertMetadataPresent(metadata, RagMetadataKeys.COMMIT_SHA);
        assertMetadataPresent(metadata, RagMetadataKeys.EMBEDDING_MODEL);
        assertMetadataPresent(metadata, RagMetadataKeys.PARENT_DOCUMENT_ID);
        assertMetadataPresent(metadata, "class");
        assertMetadataPresent(metadata, "member");
    }

    private Path createSourceFile() throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, "class Sample { String id() { return \"1\"; } }");
        return file;
    }

    private IngestionStateStore newStateStore() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        return new IngestionStateStore(dataSource, transactionManager);
    }

    private RagProperties ragProperties() {
        return new RagProperties(
                5,
                0.7,
                1000,
                350,
                tempDir.resolve("documents").toString(),
                "nomic-embed-text",
                false,
                "",
                true,
                false,
                "http://localhost:8081",
                3,
                "EXACT_SYMBOL",
                true,
                2, 3, 4, 2, 3, 2, 2,
                2, 3, 3, 3, 2,
                false
        );
    }

    private static void assertMetadataPresent(Map<String, Object> metadata, String key) {
        assertTrue(metadata.containsKey(key), "Missing metadata key: " + key);
        assertNotNull(metadata.get(key), "Metadata value is null for key: " + key);
    }

    private static final class TestParser implements DocumentParser {
        @Override
        public List<Document> parse(String source) {
            return List.of(Document.builder()
                    .text("public String channelId(final ProductType productType) { return \"x\"; }")
                    .metadata(Map.of(
                            RagMetadataKeys.LANGUAGE, "java",
                            RagMetadataKeys.PACKAGE, "com.axis.order.checkout.common.config",
                            RagMetadataKeys.FILE_NAME, "PosidexDedupeConfig.java",
                            RagMetadataKeys.CLASS_NAME, "PosidexDedupeConfig",
                            RagMetadataKeys.MEMBER_NAME, "channelId",
                            RagMetadataKeys.MEMBER_TYPE, "method",
                            RagMetadataKeys.SIGNATURE, "public String channelId(final ProductType productType)",
                            "class", "PosidexDedupeConfig",
                            "member", "channelId"
                    ))
                    .build());
        }
    }

    private static final class TestChunker implements DocumentChunker {
        @Override
        public List<Document> chunk(List<Document> documents) {
            return documents;
        }
    }

    private static final class TestIndexer implements DocumentIndexer {
        boolean idsPresent = true;
        int indexCalls = 0;
        List<Document> lastIndexed = new ArrayList<>();

        @Override
        public void index(List<Document> documents) {
            indexCalls++;
            lastIndexed = new ArrayList<>(documents);
        }

        @Override
        public void deleteByIds(List<String> documentIds) {
        }

        @Override
        public void deleteByFileIdentity(String repo, String module, String branch, String filePath) {
        }

        @Override
        public boolean areDocumentIdsPresent(List<String> documentIds) {
            return idsPresent;
        }
    }
}

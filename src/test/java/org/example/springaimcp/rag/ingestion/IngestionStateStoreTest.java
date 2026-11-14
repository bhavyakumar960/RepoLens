package org.example.springaimcp.rag.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Regression tests for concurrent ingestion writes. A prior implementation of
 * {@link IngestionStateStore#write} replaced the entire table on every call
 * (DELETE-then-reinsert of the full state), which caused foreign key violations
 * and lost updates when multiple files were ingested in parallel
 * (see {@link org.example.springaimcp.rag.ingestion.HybridIngestionService#ingestRepository}).
 */
class IngestionStateStoreTest {

    private IngestionStateStore newStateStore() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        return new IngestionStateStore(dataSource, transactionManager);
    }

    @Test
    void concurrentUpsertsForDifferentFilesDoNotInterfere() throws InterruptedException {
        IngestionStateStore stateStore = newStateStore();
        int fileCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(fileCount);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < fileCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    String identity = "repo|module|branch|File" + index + ".java";
                    IngestionStateStore.ChunkState chunk = new IngestionStateStore.ChunkState(
                            "chunk-" + index, "hash-" + index, 0, "parent-" + index);
                    IngestionStateStore.FileState fileState = new IngestionStateStore.FileState(
                            "/src/File" + index + ".java",
                            "repo",
                            "module",
                            "branch",
                            "File" + index + ".java",
                            "commit-" + index,
                            "filehash-" + index,
                            "embed-model",
                            Map.of(chunk.chunkId(), chunk));
                    stateStore.upsertFile(identity, fileState);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent upserts did not complete in time");
        executor.shutdown();

        assertEquals(0, failures.get(), "No concurrent upsert should fail");

        IngestionStateStore.IngestionState state = stateStore.read();
        assertEquals(fileCount, state.files().size());
        for (int i = 0; i < fileCount; i++) {
            String identity = "repo|module|branch|File" + i + ".java";
            IngestionStateStore.FileState fileState = state.files().get(identity);
            assertEquals("filehash-" + i, fileState.fileHash());
            assertEquals(1, fileState.chunksById().size());
        }
    }

    @Test
    void removeFilesOnlyDeletesSpecifiedIdentities() {
        IngestionStateStore stateStore = newStateStore();
        stateStore.upsertFile("repo|module|branch|A.java", fileState("A"));
        stateStore.upsertFile("repo|module|branch|B.java", fileState("B"));

        stateStore.removeFiles(List.of("repo|module|branch|A.java"));

        IngestionStateStore.IngestionState state = stateStore.read();
        assertEquals(1, state.files().size());
        assertTrue(state.files().containsKey("repo|module|branch|B.java"));
    }

    private IngestionStateStore.FileState fileState(String name) {
        IngestionStateStore.ChunkState chunk = new IngestionStateStore.ChunkState(
                "chunk-" + name, "hash-" + name, 0, "parent-" + name);
        return new IngestionStateStore.FileState(
                "/src/" + name + ".java",
                "repo",
                "module",
                "branch",
                name + ".java",
                "commit-" + name,
                "filehash-" + name,
                "embed-model",
                Map.of(chunk.chunkId(), chunk));
    }
}

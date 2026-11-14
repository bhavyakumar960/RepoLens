package org.example.springaimcp.rag.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.config.RagProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Auto-ingests Java code on application startup.
 * Ingests into BOTH Neo4j (graph structure) and vector store (semantic content).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RagAutoIngestionRunner implements ApplicationRunner {

    private final RagProperties ragProperties;
    private final HybridIngestionService hybridIngestionService;
    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!ragProperties.autoIngestEnabled()) {
            log.info("RAG auto-ingest is disabled (rag.auto-ingest-enabled=false)");
            return;
        }

        String configuredRoot = ragProperties.autoIngestJavaRoot();
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException(
                    "RAG auto-ingest is enabled, but rag.auto-ingest-java-root is not set");
        }

        Path root = Path.of(configuredRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException(
                    "RAG auto-ingest root does not exist or is not a directory: " + root);
        }

        log.info("RAG auto-ingest starting (HYBRID MODE: Neo4j + Vector Store) for root: {}", root);

        // Use HybridIngestionService for parallel ingestion into both Neo4j and vector store
        HybridIngestionService.HybridBatchStats stats = hybridIngestionService.ingestRepository(root);
        if (ragProperties.autoReingestFailedFilesOnly() && stats.getFailedFiles() > 0) {
            log.warn("Retrying {} failed files from initial auto-ingest run", stats.getFailedFiles());
            HybridIngestionService.HybridBatchStats retryStats =
                    hybridIngestionService.reingestFailedFiles(root, stats.getFailedFilePaths());
            stats = retryStats.getTotalFiles() == 0 ? stats : retryStats;
        }

        // Reconcile deleted files
        HybridIngestionService.ReconciliationResult reconciliationResult =
                hybridIngestionService.reconcileDeletedFiles(root);

        log.info(
                "RAG auto-ingest complete (HYBRID MODE):\n" +
                "  Files: {} total, {} successful, {} failed\n" +
                "  Neo4j: {} classes, {} methods\n" +
                "  Vector: {} chunks\n" +
                "  Reconciliation: {} deleted files, {} deleted chunks\n" +
                "  Root: {}",
                stats.getTotalFiles(),
                stats.getSuccessfulFiles(),
                stats.getFailedFiles(),
                stats.getTotalClasses(),
                stats.getTotalMethods(),
                stats.getTotalChunks(),
                reconciliationResult.vectorDeletedFiles(),
                reconciliationResult.vectorDeletedChunks(),
                root);
    }

}

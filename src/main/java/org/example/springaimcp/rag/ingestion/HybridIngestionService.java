package org.example.springaimcp.rag.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.service.CrossRepoLinkerService;
import org.example.springaimcp.graph.service.Neo4jIngestionService;
import org.example.springaimcp.graph.service.RelationshipReconciliationService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Hybrid ingestion service that orchestrates both Neo4j (graph) and Qdrant/Weaviate (vector) ingestion.
 * 
 * <p>Architecture:
 * <pre>
 *              Repository
 *                   │
 *               JavaParser
 *                   │
 *         ┌─────────┴─────────┐
 *         ▼                   ▼
 *       Neo4j               Qdrant/Weaviate
 *    "relationships"     "semantic content"
 *         │                   │
 *         └─────────┬─────────┘
 *                   ▼
 *                  MCP
 *                   ▼
 *                  LLM
 * </pre>
 * 
 * <p>Design principles from temp2:
 * <ul>
 *   <li>Neo4j stores structure + relationships (what is connected to what)</li>
 *   <li>Qdrant/Weaviate stores semantic content (what the code means)</li>
 *   <li>Every Qdrant chunk links back to Neo4j via stable IDs</li>
 *   <li>No duplication of source code in Neo4j</li>
 *   <li>Incremental, idempotent indexing</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HybridIngestionService {

    private final DocumentIngestionService documentIngestionService;
    private final Neo4jIngestionService neo4jIngestionService;
    private final CrossRepoLinkerService crossRepoLinkerService;
    private final RelationshipReconciliationService relationshipReconciliationService;

    /**
     * Ingest a single Java file into both Neo4j and vector store.
     * Implements compensating transaction: rolls back Neo4j if Qdrant fails.
     * 
     * @param sourcePath Absolute path to Java source file
     * @return Combined ingestion result
     */
    public HybridIngestionResult ingestJavaFile(Path sourcePath) {
        log.info("Starting hybrid ingestion: file={}", sourcePath);

        Neo4jIngestionService.IngestionStats neo4jStats = null;
        IngestionResult vectorStats = null;
        GitMetadata gitMetadata = null;

        try {
            // Extract Git metadata
            gitMetadata = extractGitMetadata(sourcePath);

            // STEP 1: Ingest into Neo4j (graph structure)
            neo4jStats = neo4jIngestionService.ingestJavaFile(
                    sourcePath,
                    gitMetadata.repository(),
                    gitMetadata.module(),
                    gitMetadata.branch(),
                    gitMetadata.commitSha()
            );

            // NOTE: Neo4j and the vector store maintain independent staleness state
            // (Neo4j: JavaFile.fileHash node property; vector: MySQL rag_ingestion_file/
            // rag_ingestion_chunk, which also tracks embedding model changes). Neo4j's
            // skip decision must NOT gate vector ingestion — otherwise an embedding model
            // change, or vector-side state loss/reset, would never trigger re-embedding
            // as long as Neo4j still considers the file content unchanged. Always invoke
            // vector ingestion and let it independently decide (cheaply) whether to skip.
            if (neo4jStats.skipped()) {
                log.debug("Neo4j skipped file (unchanged or parse failure): {}", sourcePath);
            }

            // STEP 2: Ingest into vector store (semantic content)
            try {
                vectorStats = documentIngestionService.ingest(sourcePath.toString());
            } catch (Exception vectorException) {
                // ROLLBACK: only if Neo4j actually wrote new/changed data on this run.
                // If Neo4j itself skipped (file unchanged), its existing nodes predate this
                // run and are unrelated to the vector failure — deleting them would destroy
                // valid data instead of compensating for this run's partial write.
                if (neo4jStats != null && !neo4jStats.skipped()) {
                    log.error("Vector store ingestion failed, rolling back Neo4j: file={}", sourcePath, vectorException);
                    try {
                        neo4jIngestionService.deleteFile(sourcePath, gitMetadata.repository(), gitMetadata.module());
                        log.info("Neo4j rollback successful for: {}", sourcePath);
                    } catch (Exception rollbackException) {
                        log.error("Neo4j rollback failed: {}", sourcePath, rollbackException);
                    }
                } else {
                    log.error("Vector store ingestion failed (Neo4j was skipped/unchanged, no rollback needed): file={}", sourcePath, vectorException);
                }

                throw new RuntimeException("Vector store ingestion failed: " + vectorException.getMessage(), vectorException);
            }

            log.info("Hybrid ingestion complete: file={}, neo4j={}, vector={}",
                    sourcePath, neo4jStats, vectorStats);

            return new HybridIngestionResult(
                    sourcePath.toString(),
                    neo4jStats,
                    vectorStats,
                    true,
                    null
            );

        } catch (Exception e) {
            log.error("Hybrid ingestion failed: file={}", sourcePath, e);
            return new HybridIngestionResult(
                    sourcePath.toString(),
                    neo4jStats,
                    vectorStats,
                    false,
                    e.getMessage()
            );
        }
    }

    /**
     * Ingest an entire repository directory into both Neo4j and vector store.
     * Processes files in parallel for better performance.
     * After ingestion, automatically establishes cross-repo CALLS relationships if configured.
     * 
     * @param rootPath Root directory of the repository
     * @return Batch ingestion statistics
     */
    public HybridBatchStats ingestRepository(Path rootPath) {
        return ingestRepository(rootPath, null);
    }

    public HybridBatchStats ingestRepository(Path rootPath, List<Path> specificFiles) {
        log.info("Starting hybrid repository ingestion: root={}", rootPath);

        HybridBatchStats batchStats = new HybridBatchStats();
        // Cap at 4 threads — Neo4j deadlocks increase sharply beyond this for write-heavy ingestion
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), 4)
        );

        GitMetadata gitMetadata = extractGitMetadata(rootPath);
        
        // Register repository root path for cross-repo linking
        crossRepoLinkerService.registerRepositoryPath(
                gitMetadata.repository(), 
                rootPath.toAbsolutePath().toString()
        );

        try {
            List<CompletableFuture<HybridIngestionResult>> futures = new ArrayList<>();

            // Find all Java files
            List<Path> javaFiles = resolveJavaFiles(rootPath, specificFiles);

            log.info("Found {} Java files to process in: {}", javaFiles.size(), rootPath);

            javaFiles.forEach(javaFile -> {
                CompletableFuture<HybridIngestionResult> future = CompletableFuture
                        .supplyAsync(() -> {
                            try {
                                return ingestJavaFile(javaFile);
                            } catch (Exception e) {
                                log.error("Failed to ingest file: {}", javaFile, e);
                                // Return a failure result instead of propagating exception
                                return new HybridIngestionResult(
                                        javaFile.toString(),
                                        null,
                                        null,
                                        false,
                                        "Parse error: " + e.getMessage()
                                );
                            }
                        }, executor);
                futures.add(future);
            });
            
            log.info("Submitted {} ingestion tasks", futures.size());

            // Wait for all ingestions to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Aggregate results
            for (CompletableFuture<HybridIngestionResult> future : futures) {
                HybridIngestionResult result = future.get();
                batchStats.add(result);
            }

            log.info("Hybrid repository ingestion complete: root={}, stats={}", rootPath, batchStats);

            RelationshipReconciliationService.ReconciliationResult reconciliationResult =
                    relationshipReconciliationService.reconcileRelationships(
                            gitMetadata.repository()
                    );
            log.info("Post-ingestion reconciliation complete: fixed={}, calls={}",
                    reconciliationResult.getTotalFixed(),
                    reconciliationResult.callStats.totalCallsDiscovered);

            // Cross-repo linking depends on stub→real resolution above, so it must run after reconciliation.
            linkCrossRepoRelationshipsAfterIngestion(gitMetadata.repository(), batchStats);

        } catch (Exception e) {
            log.error("Repository ingestion failed: root={}", rootPath, e);
        } finally {
            executor.shutdown();
        }

        return batchStats;
    }

    public HybridBatchStats reingestFailedFiles(Path rootPath, List<String> failedFilePaths) {
        if (failedFilePaths == null || failedFilePaths.isEmpty()) {
            log.info("No failed files provided for re-ingestion under root: {}", rootPath);
            return new HybridBatchStats();
        }

        List<Path> filesToReingest = failedFilePaths.stream()
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(path -> path.startsWith(rootPath))
                .filter(path -> Files.isRegularFile(path))
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !path.toString().contains("/target/"))
                .filter(path -> !path.toString().contains("/build/"))
                .distinct()
                .toList();

        if (filesToReingest.isEmpty()) {
            log.warn("No failed Java files remain available for re-ingestion under root: {}", rootPath);
            return new HybridBatchStats();
        }

        log.info("Re-ingesting {} previously failed files under root: {}", filesToReingest.size(), rootPath);
        return ingestRepository(rootPath, filesToReingest);
    }

    /**
     * Automatically establish cross-repo CALLS/INJECTS relationships after repository
     * ingestion. Re-links every repository already ingested into the graph (not just
     * the one just ingested) against every other discovered repository, so that a
     * previously-ingested repository which depends on the newly-ingested one also gets
     * its cross-repo edges created — see {@link CrossRepoLinkerService#linkAllRepositories()}.
     * Only runs if cross-repo linking is enabled in configuration.
     */
    private void linkCrossRepoRelationshipsAfterIngestion(String repository, HybridBatchStats batchStats) {
        try {
            log.info("Running cross-repo linking for all repositories after ingesting: {}", repository);
            
            Map<String, CrossRepoLinkerService.LinkingStats> allStats = crossRepoLinkerService.linkAllRepositories();
            
            int totalRelationshipsCreated = 0;
            for (Map.Entry<String, CrossRepoLinkerService.LinkingStats> entry : allStats.entrySet()) {
                CrossRepoLinkerService.LinkingStats stats = entry.getValue();
                if (!stats.isSuccess()) {
                    log.debug("Cross-repo linking skipped for {}: {}", entry.getKey(), stats.errorMessage());
                    continue;
                }
                if (stats.injectsCreated() > 0 || stats.callsCreated() > 0) {
                    log.info("Cross-repo linking complete for {}: methods={}, INJECTS={}, CALLS={}",
                            entry.getKey(), stats.methodsScanned(), stats.injectsCreated(), stats.callsCreated());
                }
                totalRelationshipsCreated += stats.injectsCreated() + stats.callsCreated();
            }
            batchStats.setCrossRepoRelationshipsCreated(totalRelationshipsCreated);
        } catch (Exception e) {
            // Don't fail ingestion if linking fails — reconciliation/vector indexing already succeeded.
            log.warn("Cross-repo linking failed after ingesting repository {}: {}", repository, e.getMessage());
        }
    }

    /**
     * Reconcile deleted files - remove from both Neo4j and vector store.
     */
    public ReconciliationResult reconcileDeletedFiles(Path rootPath) {
        log.info("Starting reconciliation: root={}", rootPath);

        GitMetadata gitMetadata = extractGitMetadata(rootPath);

        // Reconcile vector store
        DocumentIngestionService.ReconciliationResult vectorResult =
                documentIngestionService.reconcileDeletedFilesUnderRoot(rootPath);

        // Reconcile Neo4j
        Neo4jIngestionService.ReconciliationStats neo4jResult =
                neo4jIngestionService.reconcileDeletedFiles(
                        rootPath,
                        gitMetadata.repository(),
                        gitMetadata.module()
                );

        log.info("Reconciliation complete: vectorDeletedFiles={}, vectorDeletedChunks={}, neo4jDeletedNodes={}",
                vectorResult.deletedFileCount(), vectorResult.deletedChunkCount(), neo4jResult.deletedNodes());

        return new ReconciliationResult(
                vectorResult.deletedFileCount(),
                vectorResult.deletedChunkCount(),
                neo4jResult.deletedNodes()
        );
    }

    /**
     * Extract Git metadata from a file path.
     */
    private GitMetadata extractGitMetadata(Path filePath) {
        try {
            // Use the path itself if it's a directory, otherwise use parent
            Path parent = Files.isDirectory(filePath) ? filePath : 
                         (filePath.getParent() != null ? filePath.getParent() : filePath);

            String repoRoot = runGitCommand(parent, "rev-parse", "--show-toplevel");
            String remoteUrl = runGitCommand(parent, "config", "--get", "remote.origin.url");
            String repository = remoteUrl != null 
                    ? extractRepoName(remoteUrl) 
                    : (repoRoot != null ? Path.of(repoRoot).getFileName().toString() : null);
            String branch = runGitCommand(parent, "rev-parse", "--abbrev-ref", "HEAD");
            String commitSha = runGitCommand(parent, "rev-parse", "HEAD");
            String relativePath = runGitCommand(parent, "ls-files", "--full-name", filePath.toString());
            String module = extractModule(relativePath != null ? relativePath : filePath.toString());

            return new GitMetadata(
                    repository != null ? repository : "local-repo",
                    module != null ? module : "(root)",
                    branch != null ? branch : "unknown-branch",
                    commitSha != null ? commitSha : "unknown-commit"
            );

        } catch (Exception e) {
            log.warn("Failed to extract Git metadata for: {}", filePath, e);
            return new GitMetadata("local-repo", "(root)", "unknown-branch", "unknown-commit");
        }
    }

    private String runGitCommand(Path cwd, String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(cwd.toString());
            command.addAll(List.of(args));

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                return null;
            }

            String result = new String(output, java.nio.charset.StandardCharsets.UTF_8).trim();
            return result.isBlank() ? null : result;

        } catch (Exception e) {
            log.debug("Git command failed: {}", String.join(" ", args), e);
            return null;
        }
    }

    private String extractRepoName(String remoteUrl) {
        if (remoteUrl == null) {
            return null;
        }
        String trimmed = remoteUrl.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        String lastSegment = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return lastSegment.endsWith(".git")
                ? lastSegment.substring(0, lastSegment.length() - 4)
                : lastSegment;
    }

    private String extractModule(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String normalized = relativePath.replace('\\', '/');
        String[] parts = normalized.split("/");
        return parts.length > 0 ? parts[0] : null;
    }

    private List<Path> resolveJavaFiles(Path rootPath, List<Path> specificFiles) throws java.io.IOException {
        if (specificFiles == null) {
            try (Stream<Path> paths = Files.walk(rootPath)) {
                return paths.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().contains("/target/"))
                        .filter(path -> !path.toString().contains("/build/"))
                        .toList();
            }
        }

        if (specificFiles.isEmpty()) {
            return Collections.emptyList();
        }

        return specificFiles.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    // ── Result classes ──────────────────────────────────────────────────────

    public record HybridIngestionResult(
            String source,
            Neo4jIngestionService.IngestionStats neo4jStats,
            IngestionResult vectorStats,
            boolean success,
            String errorMessage
    ) {
    }

    public static class HybridBatchStats {
        private final AtomicInteger totalFiles = new AtomicInteger(0);
        private final AtomicInteger successfulFiles = new AtomicInteger(0);
        private final AtomicInteger failedFiles = new AtomicInteger(0);
        private final AtomicInteger totalClasses = new AtomicInteger(0);
        private final AtomicInteger totalMethods = new AtomicInteger(0);
        private final AtomicInteger totalChunks = new AtomicInteger(0);
        private final AtomicInteger crossRepoRelationships = new AtomicInteger(0);
        private final List<String> failedFilePaths = Collections.synchronizedList(new ArrayList<>());

        public void add(HybridIngestionResult result) {
            totalFiles.incrementAndGet();
            if (result.success()) {
                successfulFiles.incrementAndGet();
                if (result.neo4jStats() != null) {
                    totalClasses.addAndGet(result.neo4jStats().classesCreated());
                    totalMethods.addAndGet(result.neo4jStats().methodsCreated());
                }
                if (result.vectorStats() != null) {
                    totalChunks.addAndGet(result.vectorStats().chunkCount());
                }
            } else {
                failedFiles.incrementAndGet();
                failedFilePaths.add(result.source());
            }
        }

        public void setCrossRepoRelationshipsCreated(int count) {
            crossRepoRelationships.set(count);
        }

        @Override
        public String toString() {
            String base = String.format(
                    "files=%d, successful=%d, failed=%d, classes=%d, methods=%d, chunks=%d",
                    totalFiles.get(), successfulFiles.get(), failedFiles.get(), 
                    totalClasses.get(), totalMethods.get(), totalChunks.get()
            );
            
            if (crossRepoRelationships.get() > 0) {
                base += String.format(", crossRepoLinks=%d", crossRepoRelationships.get());
            }
            
            return base;
        }

        public int getTotalFiles() {
            return totalFiles.get();
        }

        public int getSuccessfulFiles() {
            return successfulFiles.get();
        }

        public int getFailedFiles() {
            return failedFiles.get();
        }

        public int getTotalClasses() {
            return totalClasses.get();
        }

        public int getTotalMethods() {
            return totalMethods.get();
        }

        public int getTotalChunks() {
            return totalChunks.get();
        }

        public int getCrossRepoRelationships() {
            return crossRepoRelationships.get();
        }

        public List<String> getFailedFilePaths() {
            synchronized (failedFilePaths) {
                return List.copyOf(failedFilePaths);
            }
        }
    }

    public record GitMetadata(
            String repository,
            String module,
            String branch,
            String commitSha
    ) {
    }

    public record ReconciliationResult(
            int vectorDeletedFiles,
            int vectorDeletedChunks,
            int neo4jDeletedNodes
    ) {
    }

    /**
     * List all files indexed in Neo4j for a given repository and module.
     */
    public List<String> listNeo4jFiles(String repository, String module) {
        return neo4jIngestionService.listAllFiles(repository, module);
    }

    /**
     * List all files indexed in Qdrant for a given repository and module.
     */
    public List<String> listQdrantFiles(String repository, String module) {
        return documentIngestionService.listAllFiles(repository, module);
    }
}

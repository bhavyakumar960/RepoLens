package org.example.springaimcp.rag.ingestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.RagMetadataKeys;
import org.example.springaimcp.rag.config.RagProperties;
import org.example.springaimcp.rag.indexer.DocumentIndexer;
import org.example.springaimcp.rag.parser.DocumentParser;
import org.example.springaimcp.rag.splitter.DocumentChunker;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the full ETL pipeline for RAG ingestion:
 * <pre>
 *   parse (Tika) → chunk (TokenTextSplitter) → embed + index (VectorStore)
 * </pre>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentParser documentParser;
    private final DocumentChunker documentChunker;
    private final DocumentIndexer documentIndexer;
    private final IngestionStateStore ingestionStateStore;
    private final RagProperties ragProperties;

    /**
     * Ingests a document from a local file path or HTTP(S) URL.
     *
     * @param source file path or URL of the document to ingest
     * @return summary of parsed documents and chunks indexed
     */
    public IngestionResult ingest(String source) {
        log.info("Ingestion pipeline started: source={}", source);

        if (source == null || source.isBlank() || isHttpUrl(source)) {
            List<Document> parsed = documentParser.parse(source);
            List<Document> chunks = documentChunker.chunk(parsed);
            documentIndexer.index(chunks);
            return new IngestionResult(source, parsed.size(), chunks.size(), chunks.size(), 0, false);
        }

        Path sourcePath = Path.of(source).toAbsolutePath().normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalArgumentException("Source is not a readable file: " + sourcePath);
        }

        SourceDescriptor descriptor = resolveSourceDescriptor(sourcePath);
        String fileHash = computeSha256(sourcePath);
        String identity = fileIdentity(descriptor);

        IngestionStateStore.IngestionState state = ingestionStateStore.read();
        IngestionStateStore.FileState previous = state.files().get(identity);
        boolean sameEmbeddingModel = previous != null && ragProperties.embeddingModel().equals(previous.embeddingModel());
        boolean unchangedFile = previous != null && sameEmbeddingModel && fileHash.equals(previous.fileHash());
        boolean allPreviousChunksPresent = areAllPreviousChunksPresent(previous);

        if (unchangedFile && allPreviousChunksPresent) {
            log.info("Skipping unchanged file: {}", sourcePath);
            return new IngestionResult(source, 0, 0, 0, 0, true);
        }

        List<Document> parsed = documentParser.parse(source);
        log.debug("Parse stage complete: {} document(s)", parsed.size());

        List<Document> chunks = documentChunker.chunk(parsed);
        log.debug("Chunk stage complete: {} chunk(s)", chunks.size());

        PreparedChunks preparedChunks = prepareChunks(chunks, descriptor, fileHash);

        List<Document> chunksToIndex = new ArrayList<>();
        Map<String, IngestionStateStore.ChunkState> currentChunks = new LinkedHashMap<>();
        Map<String, IngestionStateStore.ChunkState> previousChunks = previous != null && previous.chunksById() != null
                ? previous.chunksById()
                : Map.of();

        int skippedChunks = 0;
        for (PreparedChunk preparedChunk : preparedChunks.chunks()) {
            currentChunks.put(preparedChunk.chunkId(), new IngestionStateStore.ChunkState(
                    preparedChunk.chunkId(),
                    preparedChunk.chunkHash(),
                    preparedChunk.chunkIndex(),
                    preparedChunk.parentDocumentId()));

            IngestionStateStore.ChunkState previousChunk = previousChunks.get(preparedChunk.chunkId());
            if (previousChunk != null
                    && sameEmbeddingModel
                    && allPreviousChunksPresent
                    && preparedChunk.chunkHash().equals(previousChunk.chunkHash())) {
                skippedChunks++;
                continue;
            }
            chunksToIndex.add(preparedChunk.document());
        }

        List<String> staleChunkIds = previousChunks.keySet().stream()
                .filter(previousId -> !currentChunks.containsKey(previousId))
                .toList();

        if (!staleChunkIds.isEmpty()) {
            documentIndexer.deleteByIds(staleChunkIds);
        }
        if (!chunksToIndex.isEmpty()) {
            documentIndexer.index(chunksToIndex);
        }

        ingestionStateStore.upsertFile(identity, new IngestionStateStore.FileState(
                sourcePath.toString(),
                descriptor.repo(),
                descriptor.module(),
                descriptor.branch(),
                descriptor.filePath(),
                descriptor.commitSha(),
                fileHash,
                ragProperties.embeddingModel(),
                currentChunks));

        var result = new IngestionResult(
                source,
                parsed.size(),
                chunks.size(),
                chunksToIndex.size(),
                skippedChunks,
                false);
        log.info(
                "Ingestion pipeline complete: source={}, parsedDocs={}, chunks={}, indexed={}, skipped={}, staleDeleted={}",
                result.source(),
                result.parsedDocumentCount(),
                result.chunkCount(),
                result.indexedChunkCount(),
                result.skippedChunkCount(),
                staleChunkIds.size());
        return result;
    }

    public ReconciliationResult reconcileDeletedFilesUnderRoot(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        log.info("Starting Qdrant reconciliation for root: {}", normalizedRoot);
        
        IngestionStateStore.IngestionState state = ingestionStateStore.read();
        
        int deletedFiles = 0;
        int deletedChunks = 0;
        int processedFiles = 0;
        int batchSize = 100; // Process deletions in batches
        List<String> filesToRemove = new ArrayList<>();

        // Filter candidates that match the root path
        List<Map.Entry<String, IngestionStateStore.FileState>> candidates = state.files().entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> entry.getValue().sourcePath() != null)
                .filter(entry -> Path.of(entry.getValue().sourcePath()).startsWith(normalizedRoot))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .toList();
        
        log.info("Total files to check for deletion: {}", candidates.size());

        for (Map.Entry<String, IngestionStateStore.FileState> entry : candidates) {
            processedFiles++;
            Path sourcePath = Path.of(entry.getValue().sourcePath());
            
            if (Files.exists(sourcePath)) {
                continue;
            }
            
            // File no longer exists, prepare for deletion
            List<String> ids = entry.getValue().chunksById() == null
                    ? List.of()
                    : entry.getValue().chunksById().values().stream()
                            .map(IngestionStateStore.ChunkState::chunkId)
                            .filter(id -> id != null && !id.isBlank())
                            .toList();

            if (!ids.isEmpty()) {
                documentIndexer.deleteByIds(ids);
                deletedChunks += ids.size();
            } else {
                documentIndexer.deleteByFileIdentity(
                        entry.getValue().repo(),
                        entry.getValue().module(),
                        entry.getValue().branch(),
                        entry.getValue().filePath());
            }

            filesToRemove.add(entry.getKey());
            deletedFiles++;
            
            // Remove from state in batches to avoid memory buildup. Only removes the
            // specific stale identities, so this is safe to run alongside concurrent
            // per-file upserts for other files.
            if (filesToRemove.size() >= batchSize) {
                ingestionStateStore.removeFiles(filesToRemove);
                log.info("Reconciliation progress: {}/{} files checked, {} deleted", 
                        processedFiles, candidates.size(), deletedFiles);
                filesToRemove.clear();
            }
        }

        // Final batch cleanup
        if (!filesToRemove.isEmpty()) {
            ingestionStateStore.removeFiles(filesToRemove);
        }
        
        log.info("Qdrant reconciliation complete: deletedFiles={}, deletedChunks={}, totalChecked={}", 
                deletedFiles, deletedChunks, processedFiles);
        return new ReconciliationResult(deletedFiles, deletedChunks);
    }

    private PreparedChunks prepareChunks(List<Document> chunks, SourceDescriptor descriptor, String fileHash) {
        List<PreparedChunk> preparedChunks = new ArrayList<>(chunks.size());

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            Document chunk = chunks.get(chunkIndex);

            Map<String, Object> metadata = new HashMap<>();
            if (chunk.getMetadata() != null) {
                metadata.putAll(chunk.getMetadata());
            }
            // Use repository-relative path instead of absolute path
            metadata.put(RagMetadataKeys.SOURCE, descriptor.filePath());
            metadata.put(RagMetadataKeys.REPO, descriptor.repo());
            metadata.put(RagMetadataKeys.MODULE, descriptor.module());
            metadata.put(RagMetadataKeys.SERVICE, descriptor.service());
            metadata.put(RagMetadataKeys.BRANCH, descriptor.branch());
            metadata.put(RagMetadataKeys.FILE_PATH, descriptor.filePath());
            metadata.put(RagMetadataKeys.COMMIT_SHA, descriptor.commitSha());
            metadata.put(RagMetadataKeys.FILE_HASH, fileHash);
            metadata.put(RagMetadataKeys.EMBEDDING_MODEL, ragProperties.embeddingModel());
            metadata.put(RagMetadataKeys.CHUNK_INDEX, chunkIndex);
            metadata.put(RagMetadataKeys.TOTAL_CHUNKS, chunks.size());

            ensureRequiredMetadata(metadata, descriptor);
            String parentDocumentId = computeParentDocumentId(descriptor, metadata);
            metadata.put(RagMetadataKeys.PARENT_DOCUMENT_ID, parentDocumentId);

            String chunkHash = computeSha256(chunk.getText());
            metadata.put(RagMetadataKeys.CHUNK_HASH, chunkHash);

            String chunkId = deterministicChunkId(descriptor, metadata, chunkIndex);
            Document indexedChunk = Document.builder()
                    .id(chunkId)
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build();

            preparedChunks.add(new PreparedChunk(indexedChunk, chunkId, chunkHash, chunkIndex, parentDocumentId));
        }

        return new PreparedChunks(preparedChunks);
    }

    private boolean areAllPreviousChunksPresent(IngestionStateStore.FileState previous) {
        if (previous == null || previous.chunksById() == null || previous.chunksById().isEmpty()) {
            return false;
        }
        List<String> chunkIds = previous.chunksById().values().stream()
                .map(IngestionStateStore.ChunkState::chunkId)
                .filter(Objects::nonNull)
                .filter(id -> !id.isBlank())
                .toList();
        if (chunkIds.isEmpty()) {
            return false;
        }
        return documentIndexer.areDocumentIdsPresent(chunkIds);
    }

    private static void ensureRequiredMetadata(Map<String, Object> metadata, SourceDescriptor descriptor) {
        metadata.putIfAbsent(RagMetadataKeys.LANGUAGE, "unknown");
        metadata.putIfAbsent(RagMetadataKeys.DOCUMENT_TYPE, "unknown");
        metadata.putIfAbsent(RagMetadataKeys.PACKAGE, "");
        metadata.putIfAbsent(RagMetadataKeys.FILE_NAME, Path.of(descriptor.filePath()).getFileName().toString());
        metadata.putIfAbsent(RagMetadataKeys.CLASS_NAME, "");
        metadata.putIfAbsent(RagMetadataKeys.MEMBER_NAME, "");
        metadata.putIfAbsent(RagMetadataKeys.MEMBER_TYPE, "");
        metadata.putIfAbsent(RagMetadataKeys.CHUNK_TYPE, "");
        metadata.putIfAbsent(RagMetadataKeys.SIGNATURE, "");
        metadata.putIfAbsent(RagMetadataKeys.START_LINE, 0);
        metadata.putIfAbsent(RagMetadataKeys.END_LINE, 0);
        metadata.putIfAbsent(RagMetadataKeys.SERVICE, descriptor.service());
    }

    private static String computeParentDocumentId(SourceDescriptor descriptor, Map<String, Object> metadata) {
        String className = stringValue(metadata.get(RagMetadataKeys.CLASS_NAME));
        String memberName = stringValue(metadata.get(RagMetadataKeys.MEMBER_NAME));
        String memberType = stringValue(metadata.get(RagMetadataKeys.MEMBER_TYPE));
        String signature = stringValue(metadata.get(RagMetadataKeys.SIGNATURE));
        return computeSha256Hex(
                descriptor.repo() + "|" + descriptor.module() + "|" + descriptor.branch() + "|"
                        + descriptor.filePath() + "|" + className + "|" + memberName + "|" + memberType + "|"
                        + signature);
    }

    private static String deterministicChunkId(SourceDescriptor descriptor, Map<String, Object> metadata, int chunkIndex) {
        // Create deterministic ID from repo + branch + file_path + member_name + chunk_index
        String memberName = stringValue(metadata.get(RagMetadataKeys.MEMBER_NAME));
        return deterministicUuid(
                descriptor.repo() + "|" + descriptor.branch() + "|" + descriptor.filePath() + "|" 
                        + memberName + "|" + chunkIndex);
    }

    private static String fileIdentity(SourceDescriptor descriptor) {
        return descriptor.repo() + "|" + descriptor.module() + "|" + descriptor.branch() + "|" + descriptor.filePath();
    }

    private SourceDescriptor resolveSourceDescriptor(Path sourcePath) {
        // Use the path itself if it's a directory, otherwise use parent
        Path parent = Files.isDirectory(sourcePath) ? sourcePath : 
                     (sourcePath.getParent() != null ? sourcePath.getParent() : sourcePath);

        Optional<String> repoRootRaw = runGit(parent, "rev-parse", "--show-toplevel");
        Path repoRoot = repoRootRaw.map(Path::of).orElse(sourcePath.getRoot() != null ? sourcePath.getRoot() : sourcePath);
        String repo = runGit(parent, "config", "--get", "remote.origin.url")
                .map(DocumentIngestionService::repoNameFromRemoteUrl)
                .orElseGet(() -> repoRoot.getFileName() != null ? repoRoot.getFileName().toString() : "local-repo");
        String branch = runGit(parent, "rev-parse", "--abbrev-ref", "HEAD").orElse("unknown-branch");
        String commitSha = runGit(parent, "rev-parse", "HEAD").orElse("unknown-commit");
        String filePath = relativize(repoRoot, sourcePath);
        String module = firstPathSegment(filePath);
        
        // Extract service from module (typically same as module for microservices)
        String service = module;

        return new SourceDescriptor(sourcePath, repo, module, service, branch, filePath, commitSha);
    }

    private Optional<String> runGit(Path cwd, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(cwd.toString());
        command.addAll(List.of(arguments));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        try {
            Process process = processBuilder.start();
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return Optional.empty();
            }
            String value = new String(output, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException ex) {
            log.warn("Unable to execute git command {} in {}: {}", command, cwd, ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing git command: " + command, ex);
        }
    }

    private static String relativize(Path repoRoot, Path sourcePath) {
        if (repoRoot == null) {
            return sourcePath.getFileName().toString();
        }
        try {
            Path relative = repoRoot.toAbsolutePath().normalize().relativize(sourcePath.toAbsolutePath().normalize());
            return normalizePath(relative.toString());
        } catch (IllegalArgumentException ex) {
            return normalizePath(sourcePath.toAbsolutePath().normalize().toString());
        }
    }

    private static String normalizePath(String rawPath) {
        return rawPath.replace('\\', '/');
    }

    private static String firstPathSegment(String filePath) {
        String normalized = normalizePath(filePath);
        String[] split = normalized.split("/");
        return split.length == 0 || split[0].isBlank() ? "(root)" : split[0];
    }

    private static String repoNameFromRemoteUrl(String remote) {
        String trimmed = remote.trim();
        int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        String lastSegment = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return lastSegment.endsWith(".git")
                ? lastSegment.substring(0, lastSegment.length() - 4)
                : lastSegment;
    }

    private String computeSha256(Path filePath) {
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            return computeSha256(bytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to hash file: " + filePath, ex);
        }
    }

    private static String computeSha256(String content) {
        return computeSha256(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String computeSha256Hex(String content) {
        return computeSha256(content);
    }

    private static String deterministicUuid(String content) {
        return UUID.nameUUIDFromBytes(content.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String computeSha256(byte[] bytes) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean isHttpUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }

    private record SourceDescriptor(
            Path sourcePath,
            String repo,
            String module,
            String service,
            String branch,
            String filePath,
            String commitSha
    ) {
    }

    private record PreparedChunk(
            Document document,
            String chunkId,
            String chunkHash,
            Integer chunkIndex,
            String parentDocumentId
    ) {
    }

    private record PreparedChunks(List<PreparedChunk> chunks) {
    }

    public record ReconciliationResult(int deletedFileCount, int deletedChunkCount) {
    }

    /**
     * List all files indexed in the vector store for a given repository and module.
     */
    public List<String> listAllFiles(String repository, String module) {
        IngestionStateStore.IngestionState state = ingestionStateStore.read();
        
        return state.files().values().stream()
                .filter(fileState -> repository == null || repository.equals(fileState.repo()))
                .filter(fileState -> module == null || module.equals(fileState.module()))
                .map(IngestionStateStore.FileState::filePath)
                .distinct()
                .sorted()
                .toList();
    }
}

package org.example.springaimcp.rag.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists ingestion state (per-file and per-chunk content hashes) in MySQL so that
 * re-ingestion can detect unchanged files/chunks without re-embedding them.
 *
 * <p>Replaces the previous flat-file ({@code rag-index-state.json}) implementation.
 * The file identity (repo|module|branch|filePath) can be arbitrarily long, so it is
 * hashed (SHA-256) to a fixed-width primary key; the raw identity is retained in a
 * column for lookup/debugging purposes.
 */
@Component
@Slf4j
public class IngestionStateStore {

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public IngestionStateStore(DataSource dataSource,
                                org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        initSchema();
    }

    private void initSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_ingestion_file (
                    identity_hash CHAR(64) PRIMARY KEY,
                    identity TEXT NOT NULL,
                    source_path TEXT,
                    repo VARCHAR(255),
                    module VARCHAR(255),
                    branch VARCHAR(255),
                    file_path TEXT,
                    commit_sha VARCHAR(64),
                    file_hash VARCHAR(64),
                    embedding_model VARCHAR(255)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS rag_ingestion_chunk (
                    identity_hash CHAR(64) NOT NULL,
                    chunk_id VARCHAR(255) NOT NULL,
                    chunk_hash VARCHAR(64),
                    chunk_index INT,
                    parent_document_id VARCHAR(255),
                    PRIMARY KEY (identity_hash, chunk_id),
                    CONSTRAINT fk_rag_ingestion_chunk_file
                        FOREIGN KEY (identity_hash) REFERENCES rag_ingestion_file (identity_hash)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    public IngestionState read() {
        Map<String, FileState> files = new LinkedHashMap<>();
        Map<String, String> hashToIdentity = new LinkedHashMap<>();
        Map<String, Map<String, ChunkState>> chunksByIdentityHash = new LinkedHashMap<>();

        jdbcTemplate.query("SELECT * FROM rag_ingestion_file", rs -> {
            String identityHash = rs.getString("identity_hash");
            String identity = rs.getString("identity");
            hashToIdentity.put(identityHash, identity);
            files.put(identity, new FileState(
                    rs.getString("source_path"),
                    rs.getString("repo"),
                    rs.getString("module"),
                    rs.getString("branch"),
                    rs.getString("file_path"),
                    rs.getString("commit_sha"),
                    rs.getString("file_hash"),
                    rs.getString("embedding_model"),
                    new LinkedHashMap<>()));
        });

        jdbcTemplate.query("SELECT * FROM rag_ingestion_chunk", rs -> {
            String identityHash = rs.getString("identity_hash");
            Map<String, ChunkState> chunks = chunksByIdentityHash.computeIfAbsent(identityHash, k -> new LinkedHashMap<>());
            int chunkIndexValue = rs.getInt("chunk_index");
            chunks.put(rs.getString("chunk_id"), new ChunkState(
                    rs.getString("chunk_id"),
                    rs.getString("chunk_hash"),
                    rs.wasNull() ? null : chunkIndexValue,
                    rs.getString("parent_document_id")));
        });

        for (Map.Entry<String, Map<String, ChunkState>> entry : chunksByIdentityHash.entrySet()) {
            String identity = hashToIdentity.get(entry.getKey());
            if (identity == null) {
                continue;
            }
            FileState fileState = files.get(identity);
            if (fileState == null) {
                continue;
            }
            files.put(identity, new FileState(
                    fileState.sourcePath(),
                    fileState.repo(),
                    fileState.module(),
                    fileState.branch(),
                    fileState.filePath(),
                    fileState.commitSha(),
                    fileState.fileHash(),
                    fileState.embeddingModel(),
                    entry.getValue()));
        }

        return new IngestionState(files);
    }

    public void upsertFile(String identity, FileState fileState) {
        transactionTemplate.executeWithoutResult(status -> {
            String identityHash = sha256(identity);

            jdbcTemplate.update("""
                    INSERT INTO rag_ingestion_file
                        (identity_hash, identity, source_path, repo, module, branch, file_path, commit_sha, file_hash, embedding_model)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        identity = VALUES(identity),
                        source_path = VALUES(source_path),
                        repo = VALUES(repo),
                        module = VALUES(module),
                        branch = VALUES(branch),
                        file_path = VALUES(file_path),
                        commit_sha = VALUES(commit_sha),
                        file_hash = VALUES(file_hash),
                        embedding_model = VALUES(embedding_model)
                    """,
                    identityHash,
                    identity,
                    fileState.sourcePath(),
                    fileState.repo(),
                    fileState.module(),
                    fileState.branch(),
                    fileState.filePath(),
                    fileState.commitSha(),
                    fileState.fileHash(),
                    fileState.embeddingModel());

            // Chunks are replaced only for this file's identity_hash — never touches other files'
            // rows, so this is safe to run concurrently with upserts/deletes for other files.
            jdbcTemplate.update("DELETE FROM rag_ingestion_chunk WHERE identity_hash = ?", identityHash);

            Map<String, ChunkState> chunks = fileState.chunksById();
            if (chunks != null && !chunks.isEmpty()) {
                List<ChunkState> chunkList = List.copyOf(chunks.values());
                jdbcTemplate.batchUpdate(
                        """
                        INSERT INTO rag_ingestion_chunk
                            (identity_hash, chunk_id, chunk_hash, chunk_index, parent_document_id)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        chunkList,
                        chunkList.size(),
                        (ps, chunkState) -> {
                            ps.setString(1, identityHash);
                            ps.setString(2, chunkState.chunkId());
                            ps.setString(3, chunkState.chunkHash());
                            if (chunkState.chunkIndex() != null) {
                                ps.setInt(4, chunkState.chunkIndex());
                            } else {
                                ps.setNull(4, java.sql.Types.INTEGER);
                            }
                            ps.setString(5, chunkState.parentDocumentId());
                        });
            }
        });
    }

    /**
     * Removes tracked state for the given file identities (e.g. files deleted from disk).
     * Associated chunk rows are removed automatically via {@code ON DELETE CASCADE}.
     */
    public void removeFiles(java.util.Collection<String> identities) {
        if (identities == null || identities.isEmpty()) {
            return;
        }
        List<String> hashes = identities.stream().map(IngestionStateStore::sha256).toList();
        transactionTemplate.executeWithoutResult(status ->
                jdbcTemplate.batchUpdate(
                        "DELETE FROM rag_ingestion_file WHERE identity_hash = ?",
                        hashes,
                        hashes.size(),
                        (ps, hash) -> ps.setString(1, hash)));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record IngestionState(Map<String, FileState> files) {
    }

    public record FileState(
            String sourcePath,
            String repo,
            String module,
            String branch,
            String filePath,
            String commitSha,
            String fileHash,
            String embeddingModel,
            Map<String, ChunkState> chunksById
    ) {
    }

    public record ChunkState(
            String chunkId,
            String chunkHash,
            Integer chunkIndex,
            String parentDocumentId
    ) {
    }
}

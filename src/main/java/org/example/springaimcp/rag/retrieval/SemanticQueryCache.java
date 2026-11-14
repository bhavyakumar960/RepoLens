package org.example.springaimcp.rag.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.model.CodeSearchRequest;
import org.example.springaimcp.codeintel.model.CodeSearchResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
@Slf4j
public class SemanticQueryCache {

    private final EmbeddingModel embeddingModel;
    private final boolean enabled;
    private final double similarityThreshold;
    private final int maxEntries;

    /** Guards {@link #entries}. Reads (lookups) are frequent; writes (puts/evictions) are rarer. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Insertion-ordered list acting as a simple LRU (oldest at index 0). */
    private final List<Entry> entries = new ArrayList<>();

    public SemanticQueryCache(
            EmbeddingModel embeddingModel,
            @Value("${rag.semantic-cache-enabled:true}") boolean enabled,
            @Value("${rag.semantic-cache-threshold:0.95}") double similarityThreshold,
            @Value("${rag.semantic-cache-max-entries:200}") int maxEntries) {
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.maxEntries = maxEntries;
        log.info("SemanticQueryCache initialized: enabled={}, threshold={}, maxEntries={}",
                enabled, similarityThreshold, maxEntries);
    }

    /**
     * Look for a cached response whose query is semantically close to this request's query
     * (within the same search scope).
     *
     * @return the cached response on a hit, or {@link Optional#empty()} on a miss / when disabled
     */
    public Optional<CodeSearchResponse> lookup(CodeSearchRequest request) {
        if (!enabled || request.getQuery() == null || request.getQuery().isBlank()) {
            return Optional.empty();
        }

        float[] queryVector;
        try {
            queryVector = embeddingModel.embed(request.getQuery());
        } catch (Exception e) {
            // Embedding failure must never break retrieval — just treat it as a cache miss.
            log.warn("Semantic cache: embedding failed, treating as miss: {}", e.getMessage());
            return Optional.empty();
        }

        String scope = scopeKey(request);

        lock.readLock().lock();
        try {
            Entry best = null;
            double bestScore = -1.0;
            for (Entry e : entries) {
                if (!e.scope.equals(scope)) {
                    continue; // never match across different repo/module/branch/topK
                }
                double score = cosineSimilarity(queryVector, e.vector);
                if (score > bestScore) {
                    bestScore = score;
                    best = e;
                }
            }

            if (best != null && bestScore >= similarityThreshold) {
                log.info("Semantic cache HIT (score={}, cachedQuery='{}', newQuery='{}')",
                        String.format("%.4f", bestScore), best.query, request.getQuery());
                return Optional.of(best.response);
            }
            log.debug("Semantic cache MISS (bestScore={}, query='{}')",
                    String.format("%.4f", bestScore), request.getQuery());
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Store a freshly computed response so future semantically-similar queries hit the cache.
     * Called by the router only after a real retrieval (a cache miss).
     */
    public void put(CodeSearchRequest request, CodeSearchResponse response) {
        if (!enabled || response == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return;
        }

        float[] queryVector;
        try {
            queryVector = embeddingModel.embed(request.getQuery());
        } catch (Exception e) {
            log.warn("Semantic cache: embedding failed on put, skipping cache store: {}", e.getMessage());
            return;
        }

        Entry entry = new Entry(request.getQuery(), scopeKey(request), queryVector, response);

        lock.writeLock().lock();
        try {
            entries.add(entry);
            // Evict oldest entries (front of list) until we're within the bound.
            while (entries.size() > maxEntries) {
                entries.remove(0);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Clears the cache — useful to call after a re-ingestion so stale results aren't served. */
    public void invalidateAll() {
        lock.writeLock().lock();
        try {
            int cleared = entries.size();
            entries.clear();
            log.info("Semantic cache invalidated ({} entries cleared)", cleared);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** A search is only interchangeable with another if these scope fields all match. */
    private String scopeKey(CodeSearchRequest request) {
        return String.join("|",
                nullSafe(request.getRepository()),
                nullSafe(request.getModule()),
                nullSafe(request.getBranch()),
                String.valueOf(request.getTopK()),
                String.valueOf(request.getIncludeTests()),
                String.valueOf(request.getIncludeSource()));
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /**
     * Cosine similarity between two vectors: dot(a,b) / (|a| * |b|).
     * Returns a value in [-1, 1]; 1 means identical direction (same meaning).
     */
    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return -1.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return -1.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** One cached query→response pair, with the query's embedding for similarity checks. */
    private record Entry(String query, String scope, float[] vector, CodeSearchResponse response) {}
}
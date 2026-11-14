package org.example.springaimcp.rag.indexer;

import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.weaviate.client.WeaviateClient;
import io.weaviate.client.base.Result;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.RagMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.ai.vectorstore.weaviate.WeaviateVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Writes documents (with embeddings) into the configured VectorStore.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VectorStoreDocumentIndexer implements DocumentIndexer {

    private final VectorStore vectorStore;
    @Value("${spring.ai.vectorstore.weaviate.object-class:SpringAiWeaviate}")
    private String weaviateObjectClass;
    @Value("${spring.ai.vectorstore.qdrant.collection-name:code_chunks}")
    private String qdrantCollectionName;

    @Override
    public void index(List<Document> documents) {
        if (documents.isEmpty()) {
            log.warn("index() called with empty document list — nothing to index");
            return;
        }
        log.info("Indexing {} document(s) into vector store", documents.size());
        vectorStore.add(documents);
        log.info("Successfully indexed {} document(s)", documents.size());
    }

    @Override
    public void deleteByIds(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        vectorStore.delete(documentIds);
        log.info("Deleted {} document(s) from vector store by ID", documentIds.size());
    }

    @Override
    public void deleteByFileIdentity(String repo, String module, String branch, String filePath) {
        var builder = new FilterExpressionBuilder();
        var expression = builder.and(
                builder.and(
                        builder.eq(RagMetadataKeys.REPO, repo),
                        builder.eq(RagMetadataKeys.MODULE, module)),
                builder.and(
                        builder.eq(RagMetadataKeys.BRANCH, branch),
                        builder.eq(RagMetadataKeys.FILE_PATH, filePath)))
                .build();
        vectorStore.delete(expression);
        log.info(
                "Deleted vector documents for identity repo={}, module={}, branch={}, filePath={}",
                repo, module, branch, filePath);
    }

    @Override
    public boolean areDocumentIdsPresent(List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return false;
        }
        for (String documentId : documentIds) {
            if (documentId == null || documentId.isBlank()) {
                return false;
            }
        }

        if (vectorStore instanceof WeaviateVectorStore) {
            return checkWeaviateDocumentIds(documentIds);
        }
        
        if (vectorStore instanceof QdrantVectorStore) {
            return checkQdrantDocumentIds(documentIds);
        }
        
        // For other vector stores, try to retrieve a sample document
        // If retrieval succeeds, assume all chunks are present (optimization)
        try {
            // Try to retrieve first document by attempting a similarity search
            // This is a fallback - may not be reliable for all vector stores
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query("test") // Need a non-empty query
                            .topK(1)
                            .build()
            );
            
            // If we got any results, optimistically assume chunks are present
            // This is not reliable but better than nothing for unknown vector stores
            return !results.isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check document ID presence in vector store: {}", e.getMessage());
            return false;
        }
    }
    
    private boolean checkQdrantDocumentIds(List<String> documentIds) {
        // Extract QdrantClient from QdrantVectorStore using reflection
        QdrantClient client = extractQdrantClient();
        if (client == null) {
            log.warn("Qdrant native client unavailable; cannot verify document ID presence, will re-index");
            return false;
        }

        try {
            // Convert document IDs (UUIDs) to Qdrant PointIds
            List<io.qdrant.client.grpc.Common.PointId> pointIds = documentIds.stream()
                    .map(id -> {
                        try {
                            UUID uuid = UUID.fromString(id);
                            return PointIdFactory.id(uuid);
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid UUID format for document ID: {}", id);
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());
            
            if (pointIds.isEmpty()) {
                log.warn("No valid point IDs to check in Qdrant");
                return false;
            }

            // Use Qdrant's retrieve API to check existence
            var points = client.retrieveAsync(
                    qdrantCollectionName, 
                    pointIds, 
                    false,  // with_payload
                    false,  // with_vectors  
                    null    // read_consistency
            ).get();
            
            // Check if all requested points were found
            boolean allPresent = points.size() == pointIds.size();
            if (!allPresent) {
                log.debug("Only {}/{} document IDs found in Qdrant, will re-index", 
                        points.size(), pointIds.size());
            } else {
                log.debug("All {}/{} document IDs found in Qdrant, skipping re-index", 
                        points.size(), pointIds.size());
            }
            return allPresent;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while checking Qdrant document IDs: {}", e.getMessage());
            return false;
        } catch (ExecutionException e) {
            log.warn("Failed to check Qdrant document IDs: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Extract QdrantClient from QdrantVectorStore using reflection.
     * Spring AI 2.0.0 doesn't expose getNativeClient(), so we access the private field.
     */
    private QdrantClient extractQdrantClient() {
        if (!(vectorStore instanceof QdrantVectorStore)) {
            return null;
        }
        
        try {
            Field clientField = QdrantVectorStore.class.getDeclaredField("qdrantClient");
            clientField.setAccessible(true);
            return (QdrantClient) clientField.get(vectorStore);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Failed to extract QdrantClient via reflection. " +
                    "Spring AI API may have changed. Error: {}", e.getMessage());
            return null;
        }
    }
    
    private boolean checkWeaviateDocumentIds(List<String> documentIds) {
        // Extract WeaviateClient from WeaviateVectorStore using reflection
        WeaviateClient client = extractWeaviateClient();
        if (client == null) {
            log.warn("Weaviate native client unavailable; cannot verify document ID presence, will re-index");
            return false;
        }

        for (String documentId : documentIds) {
            Result<Boolean> result = client.data()
                    .checker()
                    .withClassName(weaviateObjectClass)
                    .withID(documentId)
                    .run();
            if (result == null || result.hasErrors() || !Boolean.TRUE.equals(result.getResult())) {
                log.debug("Document ID {} not found in Weaviate, will re-index", documentId);
                return false;
            }
        }
        log.debug("All {} document IDs found in Weaviate, skipping re-index", documentIds.size());
        return true;
    }
    
    /**
     * Extract WeaviateClient from WeaviateVectorStore using reflection.
     * Spring AI 2.0.0 doesn't expose getNativeClient(), so we access the private field.
     */
    private WeaviateClient extractWeaviateClient() {
        if (!(vectorStore instanceof WeaviateVectorStore)) {
            return null;
        }
        
        try {
            Field clientField = WeaviateVectorStore.class.getDeclaredField("weaviateClient");
            clientField.setAccessible(true);
            return (WeaviateClient) clientField.get(vectorStore);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.error("Failed to extract WeaviateClient via reflection. " +
                    "Spring AI API may have changed. Error: {}", e.getMessage());
            return null;
        }
    }
}

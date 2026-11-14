package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.rag.config.RagProperties;
import org.example.springaimcp.rag.reranker.DocumentReranker;
import org.example.springaimcp.rag.retrieval.QueryIntent;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for conditional reranking based on query intent.
 * Skips expensive ML-based reranking for intents where it adds no value.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConditionalRerankService {
    
    private final DocumentReranker documentReranker;
    private final RagProperties ragProperties;
    private Set<QueryIntent> skipIntents;
    
    /**
     * Check if reranking should be skipped for the given intent.
     */
    public boolean shouldSkipReranking(QueryIntent intent, int documentCount) {
        if (skipIntents == null) {
            skipIntents = parseSkipIntents();
        }
        
        if (skipIntents.contains(intent)) {
            log.info("Skipping reranking for intent: {} (configured in skip list)", intent);
            return true;
        }
        
        if (documentCount < ragProperties.rerankerMinDocuments()) {
            log.debug("Skipping reranking: {} documents < {} minimum", 
                documentCount, ragProperties.rerankerMinDocuments());
            return true;
        }
        
        return false;
    }
    
    /**
     * Conditionally rerank documents based on query intent.
     */
    public List<Document> conditionalRerank(String query, List<Document> documents, QueryIntent intent) {
        if (shouldSkipReranking(intent, documents.size())) {
            return documents;
        }
        
        log.info("Applying reranking for intent: {} ({} documents)", intent, documents.size());
        return documentReranker.rerank(query, documents);
    }
    
    /**
     * Convert CodeSymbols to Documents for reranking, then convert back.
     */
    public List<CodeSymbol> rerankSymbols(String query, List<CodeSymbol> symbols, QueryIntent intent) {
        if (shouldSkipReranking(intent, symbols.size())) {
            return symbols;
        }
        
        // Convert symbols to documents
        List<Document> documents = symbols.stream()
            .map(symbol -> Document.builder()
                .text(symbol.getSourceCode() != null ? symbol.getSourceCode() : symbol.getName())
                .metadata(buildMetadata(symbol))
                .build())
            .toList();
        
        // Rerank documents
        List<Document> reranked = documentReranker.rerank(query, documents);
        
        // Update symbols with rerank scores
        for (int i = 0; i < reranked.size() && i < symbols.size(); i++) {
            Document doc = reranked.get(i);
            CodeSymbol symbol = symbols.get(i);
            
            Object rerankScore = doc.getMetadata().get("rerank_score");
            if (rerankScore instanceof Number) {
                symbol.setRelevanceScore(((Number) rerankScore).doubleValue());
            }
        }
        
        return symbols;
    }
    
    private java.util.Map<String, Object> buildMetadata(CodeSymbol symbol) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        if (symbol.getRepository() != null) metadata.put("repo", symbol.getRepository());
        if (symbol.getModule() != null) metadata.put("module", symbol.getModule());
        if (symbol.getClassName() != null) metadata.put("className", symbol.getClassName());
        if (symbol.getMethodName() != null) metadata.put("memberName", symbol.getMethodName());
        if (symbol.getRelevanceScore() != null) metadata.put("original_score", symbol.getRelevanceScore());
        return metadata;
    }
    
    private Set<QueryIntent> parseSkipIntents() {
        String skipConfig = ragProperties.rerankerSkipForIntents();
        if (skipConfig == null || skipConfig.isBlank()) {
            return Set.of();
        }
        
        return Arrays.stream(skipConfig.split(","))
            .map(String::trim)
            .map(String::toUpperCase)
            .map(intent -> {
                try {
                    return QueryIntent.valueOf(intent);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid intent in skip list: {}", intent);
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    }
}

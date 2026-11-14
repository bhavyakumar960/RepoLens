package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.exception.InvalidQueryException;
import org.example.springaimcp.codeintel.model.CodeSearchRequest;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.rag.retriever.DocumentRetriever;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.example.springaimcp.rag.RagMetadataKeys.*;

/**
 * Service for semantic code search using Qdrant vector store.
 * Performs similarity search with metadata filtering.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QdrantCodeSearchService {
    
    private final DocumentRetriever documentRetriever;
    private final CodeIntelProperties properties;
    
    /**
     * Perform semantic search for code with metadata filters.
     * 
     * @param request the search request
     * @return list of matching code symbols
     */
    public List<CodeSymbol> search(CodeSearchRequest request) {
        validateRequest(request);
        
        // Module filtering is intentionally not applied to vector search - repositories
        // often span multiple modules and users rarely know the exact module a symbol
        // lives in, so we scope only by query + repository (and optionally branch).
        log.info("Performing semantic code search: query={}, repo={}, topK={}", 
            request.getQuery(), request.getRepository(), request.getTopK());
        
        Map<String, String> filters = buildFilters(request);
        List<Document> documents = documentRetriever.retrieve(
            request.getQuery(), 
            filters, 
            false
        );
        
        log.info("Retrieved {} documents from vector store", documents.size());

        List<CodeSymbol> results = documents.stream()
            .limit(properties.getMaxResults())
            .map(this::documentToCodeSymbol)
            .collect(Collectors.toList());
        
        // Fallback: If no results and a repository filter was applied, retry without it
        // (query-only, cross-repository search).
        if (results.isEmpty() && !filters.isEmpty()) {
            log.info("No results with filters (repo={}). Retrying without repository filter...", 
                request.getRepository());
            
            List<Document> fallbackDocs = documentRetriever.retrieve(
                request.getQuery(), 
                Map.of(),  // Empty filters
                false
            );
            
            results = fallbackDocs.stream()
                .limit(properties.getMaxResults())
                .map(this::documentToCodeSymbol)
                .collect(Collectors.toList());
            
            log.info("Fallback search found {} result(s)", results.size());
        }
        
        return results;
    }
    
    /**
     * Search for code with specific class and member context.
     *
     * <p>Module filtering is intentionally not applied here either, for the same
     * reason as {@link #search(CodeSearchRequest)} - repositories often span
     * multiple modules and callers rarely know the exact module a symbol lives in.
     */
    public List<CodeSymbol> searchWithContext(
            String query, 
            String repository,
            String module,
            String className,
            String memberName,
            int topK) {
        
        Map<String, String> filters = new LinkedHashMap<>();
        putIfPresent(filters, REPO, repository);
        putIfPresent(filters, CLASS_NAME, className);
        putIfPresent(filters, MEMBER_NAME, memberName);
        
        List<Document> documents = documentRetriever.retrieve(query, filters, true);

        return documents.stream()
            .limit(Math.min(topK, properties.getMaxResults()))
            .map(this::documentToCodeSymbol)
            .collect(Collectors.toList());
    }
    
    private void validateRequest(CodeSearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new InvalidQueryException("Query cannot be null or empty");
        }
        
        if (request.getTopK() != null && request.getTopK() > properties.getMaxResults()) {
            throw new InvalidQueryException(
                String.format("topK (%d) exceeds maximum allowed (%d)", 
                    request.getTopK(), properties.getMaxResults()));
        }
    }
    
    private Map<String, String> buildFilters(CodeSearchRequest request) {
        Map<String, String> filters = new LinkedHashMap<>();
        
        // Module filtering removed intentionally - only repository and branch scope the search.
        putIfPresent(filters, REPO, request.getRepository());
        putIfPresent(filters, BRANCH, request.getBranch());
        
        return filters;
    }
    
    private CodeSymbol documentToCodeSymbol(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();
        
        CodeSymbol.CodeSymbolBuilder builder = CodeSymbol.builder()
            .repository(getStringValue(metadata, REPO))
            .module(getStringValue(metadata, MODULE))
            .branch(getStringValue(metadata, BRANCH))
            .filePath(getStringValue(metadata, FILE_PATH))
            .packageName(getStringValue(metadata, PACKAGE))
            .className(getStringValue(metadata, CLASS_NAME))
            .methodName(getStringValue(metadata, MEMBER_NAME))
            .sourceCode(doc.getText());
        
        // Determine symbol type from metadata
        String memberType = getStringValue(metadata, MEMBER_TYPE);
        if (memberType != null) {
            builder.symbolType(mapMemberTypeToSymbolType(memberType));
            builder.name(getStringValue(metadata, MEMBER_NAME));
        } else if (getStringValue(metadata, CLASS_NAME) != null) {
            builder.symbolType(CodeSymbol.SymbolType.CLASS);
            builder.name(getStringValue(metadata, CLASS_NAME));
        }
        
        // Extract relevance score if available
        Object score = metadata.get("rerank_score");
        if (score instanceof Number) {
            builder.relevanceScore(((Number) score).doubleValue());
        }
        
        // Generate symbol ID
        builder.symbolId(generateSymbolId(builder.build()));
        
        return builder.build();
    }
    
    private CodeSymbol.SymbolType mapMemberTypeToSymbolType(String memberType) {
        if (memberType == null) return CodeSymbol.SymbolType.CLASS;
        
        return switch (memberType.toUpperCase()) {
            case "METHOD" -> CodeSymbol.SymbolType.METHOD;
            case "FIELD" -> CodeSymbol.SymbolType.FIELD;
            case "CLASS" -> CodeSymbol.SymbolType.CLASS;
            case "INTERFACE" -> CodeSymbol.SymbolType.INTERFACE;
            case "ENUM" -> CodeSymbol.SymbolType.ENUM;
            default -> CodeSymbol.SymbolType.CLASS;
        };
    }
    
    private String generateSymbolId(CodeSymbol symbol) {
        if (symbol.getSymbolType() == CodeSymbol.SymbolType.METHOD && symbol.getMethodName() != null) {
            return String.format("method:%s:%s.%s",
                symbol.getRepository() != null ? symbol.getRepository() : "unknown",
                symbol.getClassName() != null ? symbol.getClassName() : "Unknown",
                symbol.getMethodName());
        } else if (symbol.getClassName() != null) {
            return String.format("class:%s:%s",
                symbol.getRepository() != null ? symbol.getRepository() : "unknown",
                symbol.getClassName());
        }
        return "unknown";
    }
    
    private String getStringValue(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value != null ? value.toString() : null;
    }
    
    private void putIfPresent(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }
}

package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for ranking and deduplicating code symbols.
 * Implements a multi-factor ranking strategy to prioritize the most relevant results.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RankingService {
    
    /**
     * Rank code symbols by relevance using multiple factors.
     * 
     * Optimization: If symbols already have rerank scores from TEI,
     * preserve those ML-based scores instead of recalculating with heuristics.
     * 
     * Ranking strategy:
     * 1. Exact symbol match
     * 2. Semantic similarity score
     * 3. Same module
     * 4. Same package
     * 5. Symbol type priority (METHOD > CLASS > INTERFACE)
     * 6. Relationship count
     */
    public List<CodeSymbol> rankSymbols(List<CodeSymbol> symbols, String query) {
        if (symbols == null || symbols.isEmpty()) {
            return symbols;
        }
        
        log.debug("Ranking {} symbols", symbols.size());
        
        // Check if symbols have rerank scores (normalized ML scores from TEI)
        boolean hasRerankScores = symbols.stream()
            .filter(s -> s.getRelevanceScore() != null)
            .allMatch(s -> s.getRelevanceScore() >= 0.0 && s.getRelevanceScore() <= 1.0);
        
        if (hasRerankScores && symbols.stream().anyMatch(s -> s.getRelevanceScore() != null)) {
            log.debug("Symbols have rerank scores, preserving ML-based ranking");
            // Sort by rerank score (descending), null scores go last
            return symbols.stream()
                .sorted(Comparator
                    .comparing((CodeSymbol s) -> s.getRelevanceScore() != null ? s.getRelevanceScore() : -1.0)
                    .reversed())
                .collect(Collectors.toList());
        }
        
        // Otherwise use heuristic scoring
        log.debug("Applying heuristic scoring");
        String normalizedQuery = query != null ? query.toLowerCase() : "";
        
        return symbols.stream()
            .sorted(Comparator
                .comparing((CodeSymbol s) -> calculateRankScore(s, normalizedQuery))
                .reversed())
            .collect(Collectors.toList());
    }
    
    /**
     * Deduplicate symbols by their unique ID.
     * Preserves the first occurrence (highest relevance).
     */
    public List<CodeSymbol> deduplicate(List<CodeSymbol> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return symbols;
        }
        
        log.debug("Deduplicating {} symbols", symbols.size());
        
        Map<String, CodeSymbol> uniqueSymbols = new LinkedHashMap<>();
        for (CodeSymbol symbol : symbols) {
            String key = symbol.getSymbolId() != null ? symbol.getSymbolId() : generateKey(symbol);
            uniqueSymbols.putIfAbsent(key, symbol);
        }
        
        int removed = symbols.size() - uniqueSymbols.size();
        if (removed > 0) {
            log.info("Removed {} duplicate symbol(s)", removed);
        }
        
        return new ArrayList<>(uniqueSymbols.values());
    }
    
    /**
     * Combine ranking and deduplication.
     */
    public List<CodeSymbol> rankAndDeduplicate(List<CodeSymbol> symbols, String query) {
        List<CodeSymbol> deduplicated = deduplicate(symbols);
        return rankSymbols(deduplicated, query);
    }
    
    /**
     * Filter symbols to only include top N results.
     */
    public List<CodeSymbol> limitResults(List<CodeSymbol> symbols, int limit) {
        if (symbols == null || symbols.size() <= limit) {
            return symbols;
        }
        
        return symbols.stream()
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate a composite rank score for a symbol.
     */
    private double calculateRankScore(CodeSymbol symbol, String normalizedQuery) {
        double score = 0.0;
        
        // 1. Semantic similarity (if available) - weight: 100
        if (symbol.getRelevanceScore() != null) {
            score += symbol.getRelevanceScore() * 100;
        }
        
        // 2. Exact name match - weight: 50
        if (symbol.getName() != null && normalizedQuery.contains(symbol.getName().toLowerCase())) {
            score += 50;
        }
        
        // 3. Partial name match - weight: 25
        if (symbol.getName() != null && symbol.getName().toLowerCase().contains(normalizedQuery)) {
            score += 25;
        }
        
        // 4. Symbol type priority - weight: 20
        score += getSymbolTypePriority(symbol.getSymbolType());
        
        // 5. Has relationships - weight: 10
        if (symbol.getRelationships() != null && !symbol.getRelationships().isEmpty()) {
            score += 10;
        }
        
        // 6. Fully qualified name match - weight: 15
        if (symbol.getFullyQualifiedName() != null && 
            normalizedQuery.contains(symbol.getFullyQualifiedName().toLowerCase())) {
            score += 15;
        }
        
        return score;
    }
    
    private double getSymbolTypePriority(CodeSymbol.SymbolType type) {
        if (type == null) return 0;
        
        return switch (type) {
            case METHOD, API_ENDPOINT -> 20.0;
            case CLASS -> 15.0;
            case INTERFACE -> 12.0;
            case FIELD -> 8.0;
            case ENUM -> 10.0;
            case TEST_METHOD -> 5.0;
            case ANNOTATION -> 5.0;
        };
    }
    
    private String generateKey(CodeSymbol symbol) {
        StringBuilder key = new StringBuilder();
        
        if (symbol.getRepository() != null) {
            key.append(symbol.getRepository()).append(":");
        }
        if (symbol.getClassName() != null) {
            key.append(symbol.getClassName()).append(":");
        }
        if (symbol.getMethodName() != null) {
            key.append(symbol.getMethodName()).append(":");
        }
        if (symbol.getStartLine() != null) {
            key.append(symbol.getStartLine());
        }
        
        return key.length() > 0 ? key.toString() : UUID.randomUUID().toString();
    }
}

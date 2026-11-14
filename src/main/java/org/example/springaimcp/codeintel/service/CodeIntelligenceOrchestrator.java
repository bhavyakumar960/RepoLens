package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.exception.SymbolNotFoundException;
import org.example.springaimcp.codeintel.model.*;
import org.example.springaimcp.graph.service.CodeGraphService;
import org.example.springaimcp.rag.retrieval.QueryIntent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Main orchestrator for code intelligence retrieval operations.
 * Combines Qdrant semantic search, Neo4j graph traversal, ranking, and source retrieval.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CodeIntelligenceOrchestrator {
    
    private final QdrantCodeSearchService qdrantService;
    private final Neo4jSymbolService neo4jService;
    private final SourceCodeContextService sourceService;
    private final RankingService rankingService;
    private final CodeGraphService codeGraphService;
    private final CodeIntelProperties properties;
    private final ConditionalRerankService conditionalRerankService;
    
    /**
     * Main retrieval method: combines semantic search with graph traversal.
     * 
     * OPTIMIZED Pipeline:
     * 1. Semantic search (Qdrant) - NO RERANKING YET
     * 2. Enrich with Neo4j relationships
     * 3. Batch rerank ALL symbols (Qdrant + Neo4j) - conditional based on intent
     * 4. Rank and deduplicate (preserves rerank scores)
     * 5. Retrieve source code (if requested)
     */
    public CodeSearchResponse retrieveCodeContext(CodeSearchRequest request, QueryIntent intent) {
        long startTime = System.currentTimeMillis();
        
        log.info("Retrieving code context: query={}, intent={}, repo={}, topK={}, depth={}", 
            request.getQuery(), intent, request.getRepository(), request.getTopK(), request.getDependencyDepth());
        
        // Step 1: Semantic search via Qdrant (without reranking)
        List<CodeSymbol> semanticResults = qdrantService.search(request);
        log.info("Semantic search returned {} symbol(s)", semanticResults.size());
        
        // Step 2: Enrich with Neo4j relationships (for METHOD symbols)
        List<CodeSymbol> enrichedSymbols = enrichWithGraphData(semanticResults, request.getDependencyDepth());
        log.info("After Neo4j enrichment: {} symbol(s)", enrichedSymbols.size());
        
        // Step 3: BATCH RERANK ALL SYMBOLS (Qdrant + Neo4j)
        // This ensures Neo4j-discovered symbols also get ML-based scoring
        enrichedSymbols = conditionalRerankService.rerankSymbols(
            request.getQuery(), enrichedSymbols, intent);
        
        // Step 4: Rank and deduplicate (preserves rerank scores if present)
        List<CodeSymbol> rankedSymbols = rankingService.rankAndDeduplicate(enrichedSymbols, request.getQuery());
        
        // Step 5: Limit results
        List<CodeSymbol> limitedSymbols = rankingService.limitResults(rankedSymbols, request.getTopK());
        
        // Step 6: Retrieve source code if requested.
        // To keep responses token-efficient, only the top-ranked N symbols (already
        // sorted by relevance at this point) are enriched with full source code;
        // lower-ranked symbols are returned as metadata only.
        if (request.getIncludeSource()) {
            limitedSymbols = enrichTopSymbolsWithSource(limitedSymbols);
        }
        
        // Step 7: Generate dependency summary
        String dependencySummary = generateDependencySummary(limitedSymbols);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("Retrieval completed in {}ms, returning {} result(s)", duration, limitedSymbols.size());
        
        return CodeSearchResponse.builder()
            .query(request.getQuery())
            .results(limitedSymbols)
            .dependencySummary(dependencySummary)
            .totalResults(limitedSymbols.size())
            .build();
    }
    
    /**
     * Backward compatibility - calls main method with UNKNOWN intent.
     */
    public CodeSearchResponse retrieveCodeContext(CodeSearchRequest request) {
        return retrieveCodeContext(request, QueryIntent.UNKNOWN);
    }

    /**
     * Enrich only the top-ranked {@code properties.getMaxSourceSymbols()} symbols with
     * full source code. Symbols are expected to already be sorted by relevance
     * (descending) when this is called. Remaining symbols are left as metadata-only
     * to keep the response compact and reduce token consumption on large result sets.
     */
    private List<CodeSymbol> enrichTopSymbolsWithSource(List<CodeSymbol> symbols) {
        Integer maxSourceSymbols = properties.getMaxSourceSymbols();
        if (maxSourceSymbols == null || maxSourceSymbols <= 0 || symbols.size() <= maxSourceSymbols) {
            return sourceService.enrichWithSourceCode(symbols, true, properties.getMaxLines());
        }

        List<CodeSymbol> toEnrich = symbols.subList(0, maxSourceSymbols);
        List<CodeSymbol> enriched = sourceService.enrichWithSourceCode(toEnrich, true, properties.getMaxLines());

        List<CodeSymbol> result = new ArrayList<>(enriched);
        result.addAll(symbols.subList(maxSourceSymbols, symbols.size()));
        log.info("Source code enrichment capped: {} of {} symbol(s) enriched (maxSourceSymbols={})",
            maxSourceSymbols, symbols.size(), maxSourceSymbols);
        return result;
    }
    
    /**
     * Pure semantic search without graph enrichment.
     *
     * <p>Module filtering is not applied - the search is scoped by query and
     * repository only. If the initial search (with the caller-supplied repository
     * filter) returns no results, it is broadened by dropping the repository
     * filter entirely (cross-repository search). This mirrors how a user would
     * naturally retry a query manually when unsure which repository a symbol
     * lives in.
     */
    public CodeSearchResponse searchCode(CodeSearchRequest request) {
        log.info("Performing pure semantic search: query={}", request.getQuery());

        CodeSearchResponse response = executeSearch(request);
        if (!response.getResults().isEmpty()) {
            return response;
        }

        if (request.getRepository() != null && !request.getRepository().isBlank()) {
            log.info("No results with repository={}. Retrying without repository filter " +
                    "(cross-repository search)...", request.getRepository());
            CodeSearchRequest withoutRepository = request.toBuilder().repository(null).build();
            response = executeSearch(withoutRepository);
        }

        return response;
    }

    private CodeSearchResponse executeSearch(CodeSearchRequest request) {
        List<CodeSymbol> results = qdrantService.search(request);
        List<CodeSymbol> ranked = rankingService.rankAndDeduplicate(results, request.getQuery());
        List<CodeSymbol> limited = rankingService.limitResults(ranked, request.getTopK());

        return CodeSearchResponse.builder()
            .query(request.getQuery())
            .results(limited)
            .totalResults(limited.size())
            .build();
    }
    
    /**
     * Find a specific symbol by name or ID.
     */
    public CodeSymbol findSymbol(SymbolLookupRequest request) {
        log.info("Finding symbol: name={}, type={}", request.getName(), request.getSymbolType());
        
        List<CodeSymbol> candidates = neo4jService.findSymbolCandidates(request);
        if (candidates.isEmpty()) {
            throw new SymbolNotFoundException(request.getName());
        }
        CodeSymbol symbol = candidates.get(0);
        
        if (request.getIncludeSource()) {
            symbol = sourceService.enrichWithSourceCode(symbol, true, properties.getMaxLines());
        }
        
        return symbol;
    }

    /**
     * Find all candidate symbols and return them ranked by match quality.
     */
    public List<CodeSymbol> findSymbolCandidates(SymbolLookupRequest request) {
        log.info("Finding symbol candidates: name={}, type={}", request.getName(), request.getSymbolType());
        List<CodeSymbol> candidates = neo4jService.findSymbolCandidates(request);

        if (request.getIncludeSource()) {
            return sourceService.enrichWithSourceCode(candidates, true, properties.getMaxLines());
        }

        return candidates;
    }
    
    /**
     * Find symbol by ID and return with dependencies.
     */
    public CodeSearchResponse findSymbolWithContext(String symbolId, int dependencyDepth, boolean includeSource) {
        log.info("Finding symbol with context: id={}, depth={}", symbolId, dependencyDepth);
        
        CodeSymbol symbol = neo4jService.findSymbolById(symbolId);
        
        List<CodeSymbol> allSymbols = new ArrayList<>();
        allSymbols.add(symbol);
        
        // Add dependencies if requested
        if (dependencyDepth > 0 && symbol.getSymbolType() == CodeSymbol.SymbolType.METHOD) {
            List<CodeSymbol> callers = neo4jService.findCallers(symbolId, dependencyDepth);
            List<CodeSymbol> called = neo4jService.findCalledMethods(symbolId, dependencyDepth);
            
            allSymbols.addAll(callers);
            allSymbols.addAll(called);
        }
        
        // Deduplicate
        allSymbols = rankingService.deduplicate(allSymbols);
        
        // Enrich with source if requested
        if (includeSource) {
            allSymbols = sourceService.enrichWithSourceCode(allSymbols, true, properties.getMaxLines());
        }
        
        String summary = String.format("Symbol: %s\nCallers: %d\nCalled methods: %d",
            symbolId,
            countByType(allSymbols, "caller"),
            countByType(allSymbols, "called"));
        
        return CodeSearchResponse.builder()
            .query(symbolId)
            .results(allSymbols)
            .dependencySummary(summary)
            .totalResults(allSymbols.size())
            .build();
    }
    
    /**
     * Enrich symbols with graph data (relationships, dependencies).
     */
    private List<CodeSymbol> enrichWithGraphData(List<CodeSymbol> symbols, int dependencyDepth) {
        if (dependencyDepth <= 0) {
            return symbols;
        }
        
        List<CodeSymbol> enriched = new ArrayList<>(symbols);
        
        for (CodeSymbol symbol : symbols) {
            if (symbol.getSymbolType() == CodeSymbol.SymbolType.METHOD && symbol.getSymbolId() != null) {
                try {
                    // Get immediate callers and callees
                    List<CodeSymbol> callers = neo4jService.findCallers(symbol.getSymbolId(), 1);
                    List<CodeSymbol> called = neo4jService.findCalledMethods(symbol.getSymbolId(), 1);
                    
                    // Update relationships
                    List<String> relationships = new ArrayList<>();
                    if (!callers.isEmpty()) {
                        relationships.add(String.format("Called by %d method(s)", callers.size()));
                    }
                    if (!called.isEmpty()) {
                        relationships.add(String.format("Calls %d method(s)", called.size()));
                    }
                    symbol.setRelationships(relationships);
                    
                    // Add related symbols if depth > 1
                    if (dependencyDepth > 1) {
                        enriched.addAll(callers.stream().limit(3).collect(Collectors.toList()));
                        enriched.addAll(called.stream().limit(3).collect(Collectors.toList()));
                    }
                } catch (Exception e) {
                    log.warn("Failed to enrich symbol {} with graph data: {}", symbol.getSymbolId(), e.getMessage());
                }
            }
        }
        
        return enriched;
    }
    
    private String generateDependencySummary(List<CodeSymbol> symbols) {
        if (symbols.isEmpty()) {
            return "No symbols found.";
        }
        
        long classCount = symbols.stream()
            .filter(s -> s.getSymbolType() == CodeSymbol.SymbolType.CLASS || 
                        s.getSymbolType() == CodeSymbol.SymbolType.INTERFACE)
            .count();
        
        long methodCount = symbols.stream()
            .filter(s -> s.getSymbolType() == CodeSymbol.SymbolType.METHOD)
            .count();
        
        return String.format("Found %d class(es) and %d method(s)", classCount, methodCount);
    }
    
    private int countByType(List<CodeSymbol> symbols, String type) {
        // Simplified for now
        return (int) symbols.stream()
            .filter(s -> s.getSymbolType() == CodeSymbol.SymbolType.METHOD)
            .count();
    }
    
    /**
     * Get exact source code context for a known symbol.
     */
    public String getCodeContext(CodeContextRequest request) {
        log.info("Getting code context for symbol: {}", request.getSymbolId());
        
        CodeSymbol symbol = neo4jService.findSymbolById(request.getSymbolId());
        
        // Enrich with source code based on includes
        int maxLines = request.getMaxLines() > 0 ? request.getMaxLines() : properties.getMaxLines();
        
        if (request.getInclude().contains(CodeContextRequest.ContextInclude.IMPLEMENTATION)) {
            String source = sourceService.retrieveSourceCodeWithContext(symbol, request.getContextLines());
            symbol.setSourceCode(source);
        }
        
        StringBuilder result = new StringBuilder();
        result.append(formatSymbolHeader(symbol));
        
        if (symbol.getSourceCode() != null) {
            result.append("\n\nSource Code:\n```java\n");
            result.append(symbol.getSourceCode());
            result.append("\n```");
        }
        
        return result.toString();
    }
    
    /**
     * Find dependencies (incoming/outgoing relationships).
     */
    public DependencyResponse findDependencies(DependencyRequest request) {
        log.info("Finding dependencies: symbol={}, direction={}, depth={}", 
            request.getSymbolId(), request.getDirection(), request.getDepth());
        
        List<CodeSymbol> incoming = new ArrayList<>();
        List<CodeSymbol> outgoing = new ArrayList<>();
        
        if (request.getDirection() == DependencyRequest.DependencyDirection.INCOMING || 
            request.getDirection() == DependencyRequest.DependencyDirection.BOTH) {
            incoming = neo4jService.findCallers(request.getSymbolId(), request.getDepth());
            incoming = rankingService.limitResults(incoming, request.getLimit());
        }
        
        if (request.getDirection() == DependencyRequest.DependencyDirection.OUTGOING || 
            request.getDirection() == DependencyRequest.DependencyDirection.BOTH) {
            outgoing = neo4jService.findCalledMethods(request.getSymbolId(), request.getDepth());
            outgoing = rankingService.limitResults(outgoing, request.getLimit());
        }
        
        String summary = String.format(
            "Total incoming: %d, Total outgoing: %d, Depth: %d",
            incoming.size(), outgoing.size(), request.getDepth()
        );
        
        return DependencyResponse.builder()
            .symbolId(request.getSymbolId())
            .incoming(incoming)
            .outgoing(outgoing)
            .summary(summary)
            .build();
    }
    
    /**
     * Find impact of changing a symbol.
     */
    public ImpactResponse findImpact(ImpactRequest request) {
        log.info("Finding impact for symbol: {}, depth={}", request.getSymbolId(), request.getDepth());
        
        CodeSymbol symbol = neo4jService.findSymbolById(request.getSymbolId());
        
        // Direct callers
        List<CodeSymbol> directCallers = neo4jService.findCallers(request.getSymbolId(), 1);
        
        // Indirect callers (depth > 1)
        Set<CodeSymbol> indirectCallersSet = new HashSet<>();
        if (request.getDepth() > 1) {
            for (CodeSymbol caller : directCallers) {
                List<CodeSymbol> indirectCallers = neo4jService.findCallers(caller.getSymbolId(), request.getDepth() - 1);
                indirectCallersSet.addAll(indirectCallers);
            }
            // Remove direct callers from indirect set
            indirectCallersSet.removeAll(directCallers);
        }
        List<CodeSymbol> indirectCallers = new ArrayList<>(indirectCallersSet);
        
        // Methods called by this symbol
        List<CodeSymbol> callees = neo4jService.findCalledMethods(request.getSymbolId(), 1);
        
        // Find affected APIs
        List<CodeSymbol> affectedApis = new ArrayList<>();
        if (request.getIncludeApis()) {
            affectedApis = findAffectedApis(directCallers);
        }
        
        // Find affected tests
        List<CodeSymbol> affectedTests = new ArrayList<>();
        if (request.getIncludeTests()) {
            affectedTests = findAffectedTests(symbol);
        }
        
        // Calculate risk
        String riskSummary = calculateRiskSummary(
            directCallers.size(), 
            indirectCallers.size(),
            affectedApis.size(),
            affectedTests.size()
        );
        
        return ImpactResponse.builder()
            .symbolId(request.getSymbolId())
            .directCallers(rankingService.limitResults(directCallers, 20))
            .indirectCallers(rankingService.limitResults(indirectCallers, 20))
            .callees(rankingService.limitResults(callees, 20))
            .dependentClasses(new ArrayList<>()) // Could be enhanced
            .affectedApis(affectedApis)
            .affectedTests(affectedTests)
            .riskSummary(riskSummary)
            .build();
    }
    
    /**
     * Get architecture context for a query.
     */
    public ArchitectureResponse getArchitectureContext(ArchitectureRequest request) {
        log.info("Getting architecture context: query={}, repo={}", request.getQuery(), request.getRepository());
        
        // Step 1: Semantic search to find relevant components
        CodeSearchRequest searchRequest = CodeSearchRequest.builder()
            .query(request.getQuery())
            .repository(request.getRepository())
            .module(request.getModule())
            .topK(15)
            .build();
        
        List<CodeSymbol> semanticResults = qdrantService.search(searchRequest);
        
        // Step 2: Identify architectural components
        List<CodeSymbol> controllers = new ArrayList<>();
        List<CodeSymbol> services = new ArrayList<>();
        List<CodeSymbol> repositories = new ArrayList<>();
        
        // Also get from graph
        if (request.getIncludeApis()) {
            var apiEndpoints = codeGraphService.findAllApiEndpoints().stream()
                .map(this::javaMethodToCodeSymbol)
                .filter(s -> matchesQuery(s, request.getQuery()))
                .limit(5)
                .toList();
            controllers.addAll(apiEndpoints);
        }
        
        if (request.getIncludeServices()) {
            var serviceClasses = codeGraphService.findAllServices().stream()
                .map(this::javaClassToCodeSymbol)
                .filter(s -> matchesQuery(s, request.getQuery()))
                .limit(5)
                .toList();
            services.addAll(serviceClasses);
        }
        
        if (request.getIncludeRepositories()) {
            var repoClasses = codeGraphService.findAllRepositories().stream()
                .map(this::javaClassToCodeSymbol)
                .filter(s -> matchesQuery(s, request.getQuery()))
                .limit(5)
                .toList();
            repositories.addAll(repoClasses);
        }
        
        // Also add from semantic results
        for (CodeSymbol symbol : semanticResults) {
            if (symbol.getClassName() != null) {
                String className = symbol.getClassName().toLowerCase();
                if (className.contains("controller") || className.contains("rest")) {
                    controllers.add(symbol);
                } else if (className.contains("service")) {
                    services.add(symbol);
                } else if (className.contains("repository") || className.contains("dao")) {
                    repositories.add(symbol);
                }
            }
        }
        
        // Deduplicate
        controllers = rankingService.deduplicate(controllers);
        services = rankingService.deduplicate(services);
        repositories = rankingService.deduplicate(repositories);
        
        // Build flow description
        List<String> flowDescription = buildFlowDescription(controllers, services, repositories);
        
        String summary = String.format(
            "Found %d controller(s), %d service(s), %d repository/repositories for query: %s",
            controllers.size(), services.size(), repositories.size(), request.getQuery()
        );
        
        return ArchitectureResponse.builder()
            .query(request.getQuery())
            .controllers(rankingService.limitResults(controllers, 5))
            .services(rankingService.limitResults(services, 5))
            .repositories(rankingService.limitResults(repositories, 5))
            .flowDescription(flowDescription)
            .summary(summary)
            .build();
    }
    
    // Helper methods
    
    private String formatSymbolHeader(CodeSymbol symbol) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Symbol: %s\n", symbol.getName()));
        sb.append(String.format("Type: %s\n", symbol.getSymbolType()));
        sb.append(String.format("Location: %s:%d-%d\n", 
            symbol.getFilePath(), symbol.getStartLine(), symbol.getEndLine()));
        if (symbol.getSignature() != null) {
            sb.append(String.format("Signature: %s\n", symbol.getSignature()));
        }
        return sb.toString();
    }
    
    private List<CodeSymbol> findAffectedApis(List<CodeSymbol> callers) {
        List<CodeSymbol> apis = new ArrayList<>();
        for (CodeSymbol caller : callers) {
            if (caller.getSymbolType() == CodeSymbol.SymbolType.METHOD) {
                // Check if caller is an API endpoint
                if (caller.getClassName() != null && 
                    (caller.getClassName().contains("Controller") || 
                     caller.getClassName().contains("Rest"))) {
                    apis.add(caller);
                }
            }
        }
        return apis;
    }
    
    private List<CodeSymbol> findAffectedTests(CodeSymbol symbol) {
        // Use Neo4j to find test methods
        try {
            var tests = codeGraphService.findTestsForMethod(symbol.getSymbolId());
            return tests.stream()
                .map(this::javaMethodToCodeSymbol)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to find tests for symbol: {}", symbol.getSymbolId(), e);
            return new ArrayList<>();
        }
    }
    
    private String calculateRiskSummary(int directCallers, int indirectCallers, int apis, int tests) {
        StringBuilder sb = new StringBuilder();
        
        String riskLevel;
        if (directCallers == 0) {
            riskLevel = "LOW";
        } else if (directCallers <= 3 && indirectCallers <= 10) {
            riskLevel = "MEDIUM";
        } else {
            riskLevel = "HIGH";
        }
        
        sb.append(String.format("Risk Level: %s\n", riskLevel));
        sb.append("Considerations:\n");
        
        if (directCallers > 0) {
            sb.append(String.format("  • %d method(s) directly depend on this\n", directCallers));
        }
        
        if (indirectCallers > 0) {
            sb.append(String.format("  • %d method(s) indirectly depend on this\n", indirectCallers));
        }
        
        if (apis > 0) {
            sb.append(String.format("  • %d API endpoint(s) may be affected\n", apis));
        }
        
        if (tests > 0) {
            sb.append(String.format("  • %d test(s) available for validation\n", tests));
        } else {
            sb.append("  • ⚠️  No tests found - changes are high risk\n");
        }
        
        return sb.toString();
    }
    
    private List<String> buildFlowDescription(
            List<CodeSymbol> controllers, 
            List<CodeSymbol> services, 
            List<CodeSymbol> repositories) {
        
        List<String> flow = new ArrayList<>();
        
        if (!controllers.isEmpty()) {
            flow.add("API Layer (Controllers):");
            for (CodeSymbol c : controllers.stream().limit(3).toList()) {
                flow.add(String.format("  → %s", c.getName()));
            }
        }
        
        if (!services.isEmpty()) {
            flow.add("Business Logic Layer (Services):");
            for (CodeSymbol s : services.stream().limit(3).toList()) {
                flow.add(String.format("  → %s", s.getName()));
            }
        }
        
        if (!repositories.isEmpty()) {
            flow.add("Data Access Layer (Repositories):");
            for (CodeSymbol r : repositories.stream().limit(3).toList()) {
                flow.add(String.format("  → %s", r.getName()));
            }
        }
        
        return flow;
    }
    
    private boolean matchesQuery(CodeSymbol symbol, String query) {
        if (query == null || query.isBlank()) return true;
        
        String lowerQuery = query.toLowerCase();
        String name = symbol.getName() != null ? symbol.getName().toLowerCase() : "";
        String className = symbol.getClassName() != null ? symbol.getClassName().toLowerCase() : "";
        
        return name.contains(lowerQuery) || className.contains(lowerQuery);
    }
    
    private CodeSymbol javaMethodToCodeSymbol(org.example.springaimcp.graph.model.JavaMethod method) {
        return CodeSymbol.builder()
            .symbolId(method.getMethodId())
            .symbolType(CodeSymbol.SymbolType.METHOD)
            .name(method.getName())
            .repository(method.getRepository())
            .module(method.getModule())
            .packageName(method.getPackageName())
            .className(method.getClassName())
            .methodName(method.getName())
            .filePath(method.getFilePath())
            .startLine(method.getStartLine())
            .endLine(method.getEndLine())
            .signature(method.getSignature())
            .build();
    }
    
    private CodeSymbol javaClassToCodeSymbol(org.example.springaimcp.graph.model.JavaClass javaClass) {
        return CodeSymbol.builder()
            .symbolId(javaClass.getClassId())
            .symbolType(CodeSymbol.SymbolType.CLASS)
            .name(javaClass.getSimpleName())
            .repository(javaClass.getRepository())
            .module(javaClass.getModule())
            .packageName(javaClass.getPackageName())
            .className(javaClass.getSimpleName())
            .filePath(javaClass.getFilePath())
            .startLine(javaClass.getStartLine())
            .endLine(javaClass.getEndLine())
            .build();
    }
}

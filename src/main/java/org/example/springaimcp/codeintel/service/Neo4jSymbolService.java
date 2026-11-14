package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.exception.InvalidQueryException;
import org.example.springaimcp.codeintel.exception.SymbolNotFoundException;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.codeintel.model.SymbolLookupRequest;
import org.example.springaimcp.codeintel.model.SymbolResolutionContext;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Service for looking up code symbols using Neo4j graph database.
 * Provides exact symbol resolution and relationship traversal.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class Neo4jSymbolService {
    
    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;
    private final CodeIntelProperties properties;
    private final SymbolResolutionService symbolResolutionService;
    
    /**
     * Find a symbol by exact lookup.
     * Supports class names, method names, and fully qualified names.
     */
    public CodeSymbol findSymbol(SymbolLookupRequest request) {
        List<CodeSymbol> candidates = findSymbolCandidates(request);
        if (candidates.isEmpty()) {
            throw new SymbolNotFoundException(request.getName());
        }

        CodeSymbol bestMatch = candidates.get(0);
        log.info("Found symbol: {} ({} candidate(s))", bestMatch.getSymbolId(), candidates.size());
        return bestMatch;
    }

    /**
     * Find and rank all candidates for an exact symbol query.
     */
    public List<CodeSymbol> findSymbolCandidates(SymbolLookupRequest request) {
        validateRequest(request);
        normalizeRequest(request);

        log.info("Looking up symbol candidates: name={}, type={}, repo={}",
            request.getName(), request.getSymbolType(), request.getRepository());

        List<CodeSymbol> candidates = new ArrayList<>();

        if (request.getSymbolType() == null || request.getSymbolType() == CodeSymbol.SymbolType.METHOD) {
            candidates.addAll(findMethodCandidates(request));
        }

        if (request.getSymbolType() == null
            || request.getSymbolType() == CodeSymbol.SymbolType.CLASS
            || request.getSymbolType() == CodeSymbol.SymbolType.INTERFACE) {
            candidates.addAll(findClassCandidates(request));
        }

        // Module filtering removed intentionally - only repository scopes the search.
        List<CodeSymbol> ranked = deduplicateBySymbolId(candidates).stream()
            .map(symbol -> {
                symbol.setRelevanceScore(calculateScore(symbol, request.getName()));
                return symbol;
            })
            .sorted(symbolRankingComparator())
            .limit(properties.getMaxResults())
            .toList();

        log.info("Resolved {} ranked candidate(s) for query={}", ranked.size(), request.getName());
        
        // Fallback 1: If no results and a repository filter was applied, retry without it
        if (ranked.isEmpty() && request.getRepository() != null) {
            log.info("No results with filter (repo={}). Retrying without repository filter...", 
                request.getRepository());
            
            SymbolLookupRequest fallbackRequest = SymbolLookupRequest.builder()
                .name(request.getName())
                .symbolType(request.getSymbolType())
                .repository(null)  // Remove repository filter
                .includeSource(request.getIncludeSource())
                .includeRelationships(request.getIncludeRelationships())
                .build();
            
            List<CodeSymbol> fallbackCandidates = new ArrayList<>();
            
            if (fallbackRequest.getSymbolType() == null || fallbackRequest.getSymbolType() == CodeSymbol.SymbolType.METHOD) {
                fallbackCandidates.addAll(findMethodCandidates(fallbackRequest));
            }
            
            if (fallbackRequest.getSymbolType() == null
                || fallbackRequest.getSymbolType() == CodeSymbol.SymbolType.CLASS
                || fallbackRequest.getSymbolType() == CodeSymbol.SymbolType.INTERFACE) {
                fallbackCandidates.addAll(findClassCandidates(fallbackRequest));
            }
            
            ranked = deduplicateBySymbolId(fallbackCandidates).stream()
                .map(symbol -> {
                    symbol.setRelevanceScore(calculateScore(symbol, fallbackRequest.getName()));
                    return symbol;
                })
                .sorted(symbolRankingComparator())
                .limit(properties.getMaxResults())
                .toList();
            
            log.info("Fallback search found {} candidate(s)", ranked.size());
        }
        
        // Fallback 2: If still no results, try file name and import-based searches
        if (ranked.isEmpty()) {
            log.info("Still no results. Trying cross-repository file and import-based search...");
            List<CodeSymbol> crossRepoResults = searchByFileNameAndImport(request.getName());
            
            if (!crossRepoResults.isEmpty()) {
                ranked = crossRepoResults.stream()
                    .map(symbol -> {
                        symbol.setRelevanceScore(calculateScore(symbol, request.getName()));
                        return symbol;
                    })
                    .sorted(symbolRankingComparator())
                    .limit(properties.getMaxResults())
                    .toList();
                
                log.info("Cross-repository search found {} candidate(s)", ranked.size());
            }
        }
        
        // Fallback 3: Advanced resolution using imports and FQN
        // This fallback resolves the symbol using SymbolResolutionService to:
        // 1. Get fully qualified name
        // 2. Extract repository/module context
        // 3. Search for related classes in the same context
        if (ranked.isEmpty()) {
            log.info("No results from basic searches. Trying advanced resolution with FQN and imports...");
            ranked = advancedSymbolResolution(request);
            
            if (!ranked.isEmpty()) {
                log.info("Advanced resolution found {} candidate(s)", ranked.size());
            }
        }
        
        return ranked;
    }
    
    /**
     * Search for classes by file name or import path (cross-repository).
     * Useful when user asks about a file from a different repository.
     * 
     * Examples:
     * - "MDCTrace" → searches for MDCTrace.java
     * - "com.axisbank.common.annotations.MDCTrace" → searches by fully qualified name
     */
    private List<CodeSymbol> searchByFileNameAndImport(String name) {
        List<CodeSymbol> results = new ArrayList<>();
        
        // Try 1: Search by file name (e.g., "MDCTrace" → "MDCTrace.java")
        String fileName = name.endsWith(".java") ? name : name + ".java";
        List<JavaClass> byFileName = classRepository.findByFileName(fileName, properties.getMaxResults());
        results.addAll(byFileName.stream().map(this::javaClassToCodeSymbol).toList());
        
        log.debug("File name search for '{}' found {} result(s)", fileName, byFileName.size());
        
        // Try 2: Search by fully qualified name (e.g., "com.axisbank.common.annotations.MDCTrace")
        if (name.contains(".")) {
            List<JavaClass> byFqn = classRepository.findByFullyQualifiedName(name, properties.getMaxResults());
            results.addAll(byFqn.stream().map(this::javaClassToCodeSymbol).toList());
            
            log.debug("Fully qualified name search for '{}' found {} result(s)", name, byFqn.size());
        }
        
        // Try 3: Extract class name from potential import path and search
        // e.g., "com.axisbank.common.annotations.MDCTrace" → search for "MDCTrace"
        if (name.contains(".")) {
            String simpleNameFromImport = name.substring(name.lastIndexOf('.') + 1);
            if (!simpleNameFromImport.equals(name)) {
                List<JavaClass> bySimpleName = classRepository.searchByName(
                    simpleNameFromImport, properties.getMaxResults());
                results.addAll(bySimpleName.stream().map(this::javaClassToCodeSymbol).toList());
                
                log.debug("Simple name from import '{}' found {} result(s)", 
                    simpleNameFromImport, bySimpleName.size());
            }
        }
        
        return deduplicateBySymbolId(results);
    }
    
    /**
     * Advanced symbol resolution using SymbolResolutionService.
     * This fallback:
     * 1. Resolves symbol to FQN using imports and package info
     * 2. Extracts repository/module context from discovered symbols
     * 3. Searches for related classes in the same context
     * 4. Uses discovered context to refine search parameters
     * 
     * Use case: When a class is asked about from a different repository
     * and initial searches fail, this method helps discover it by:
     * - Resolving FQN from simple name
     * - Finding which repositories contain this symbol
     * - Searching related classes in those repositories
     * 
     * @param request the original symbol lookup request
     * @return list of resolved and ranked symbols
     */
    private List<CodeSymbol> advancedSymbolResolution(SymbolLookupRequest request) {
        log.debug("Starting advanced resolution for: {}", request.getName());
        
        List<CodeSymbol> results = new ArrayList<>();
        
        // Step 1: Try to resolve the symbol using SymbolResolutionService
        SymbolResolutionContext context;
        if (request.getName().contains(".")) {
            // If name contains dots, try FQN resolution first
            context = symbolResolutionService.resolveByFullyQualifiedName(request.getName());
        } else {
            // Otherwise, resolve simple name
            context = symbolResolutionService.resolveSymbol(request.getName());
        }
        
        if (!context.isResolved()) {
            log.debug("Advanced resolution could not resolve symbol: {}", request.getName());
            return List.of();
        }
        
        log.info("Advanced resolution: '{}' → FQN='{}', repo='{}', module='{}', {} repositories, {} packages",
            request.getName(), context.getFullyQualifiedName(), 
            context.getRepository(), context.getModule(),
            context.getRepositories().size(), context.getPackages().size());
        
        // Step 2: Add all resolved candidates
        if (context.getCandidates() != null && !context.getCandidates().isEmpty()) {
            results.addAll(context.getCandidates());
            log.debug("Added {} candidates from resolution context", context.getCandidates().size());
        }
        
        // Step 3: Search for related classes using the discovered context
        // This helps find other classes that might be relevant in the same repository/module
        List<CodeSymbol> relatedSymbols = symbolResolutionService.findRelatedSymbols(context);
        if (!relatedSymbols.isEmpty()) {
            results.addAll(relatedSymbols);
            log.debug("Added {} related symbols from same context", relatedSymbols.size());
        }
        
        // Step 4: If user asked for a specific type (METHOD), search for methods in found classes
        if (request.getSymbolType() == CodeSymbol.SymbolType.METHOD && !results.isEmpty()) {
            List<CodeSymbol> methodResults = searchMethodsInResolvedContext(request.getName(), context);
            results.addAll(methodResults);
            log.debug("Added {} methods from resolved context", methodResults.size());
        }
        
        // Step 5: Deduplicate, score, and rank
        List<CodeSymbol> deduplicated = deduplicateBySymbolId(results);
        
        return deduplicated.stream()
            .map(symbol -> {
                // Boost relevance for symbols matching the original query name
                double baseScore = calculateScore(symbol, request.getName());
                
                // Bonus if symbol is from the primary resolved repository
                if (context.getRepository() != null && 
                    context.getRepository().equals(symbol.getRepository())) {
                    baseScore *= 1.2;
                }
                
                // Bonus if FQN matches exactly
                if (context.getFullyQualifiedName() != null &&
                    (context.getFullyQualifiedName().equals(symbol.getClassName()) ||
                     context.getFullyQualifiedName().equals(symbol.getFullyQualifiedName()))) {
                    baseScore *= 1.5;
                }
                
                symbol.setRelevanceScore(baseScore);
                return symbol;
            })
            .sorted(symbolRankingComparator())
            .limit(properties.getMaxResults())
            .toList();
    }
    
    /**
     * Search for methods in classes discovered by resolution context.
     */
    private List<CodeSymbol> searchMethodsInResolvedContext(String methodName, SymbolResolutionContext context) {
        List<CodeSymbol> methods = new ArrayList<>();
        
        // Search in each discovered repository
        for (String repository : context.getRepositories()) {
            List<JavaMethod> foundMethods = methodRepository.searchByNameInRepository(
                methodName, repository, properties.getMaxResults());
            methods.addAll(foundMethods.stream().map(this::javaMethodToCodeSymbol).toList());
        }
        
        return methods;
    }
    
    /**
     * Find a symbol by its unique ID.
     */
    public CodeSymbol findSymbolById(String symbolId) {
        if (symbolId == null || symbolId.isBlank()) {
            throw new InvalidQueryException("symbolId cannot be null or empty");
        }
        
        log.info("Finding symbol by ID: {}", symbolId);
        
        if (symbolId.startsWith("method:")) {
            Optional<JavaMethod> methodOpt = methodRepository.findByMethodId(symbolId);
            return methodOpt.map(this::javaMethodToCodeSymbol)
                .orElseThrow(() -> new SymbolNotFoundException(symbolId));
        } else if (symbolId.startsWith("class:")) {
            Optional<JavaClass> classOpt = classRepository.findByClassId(symbolId);
            return classOpt.map(this::javaClassToCodeSymbol)
                .orElseThrow(() -> new SymbolNotFoundException(symbolId));
        }
        
        throw new SymbolNotFoundException(symbolId);
    }
    
    /**
     * Find all callers of a method (incoming CALLS relationships).
     */
    public List<CodeSymbol> findCallers(String methodId, int maxDepth) {
        validateDepth(maxDepth);
        
        log.info("Finding callers of: {}, maxDepth={}", methodId, maxDepth);
        
        List<JavaMethod> callers = methodRepository.findCallers(methodId);
        
        return callers.stream()
            .limit(properties.getMaxResults())
            .map(this::javaMethodToCodeSymbol)
            .toList();
    }
    
    /**
     * Find all methods called by a method (outgoing CALLS relationships).
     */
    public List<CodeSymbol> findCalledMethods(String methodId, int maxDepth) {
        validateDepth(maxDepth);
        
        log.info("Finding methods called by: {}, maxDepth={}", methodId, maxDepth);
        
        List<JavaMethod> called = methodRepository.findCalledMethods(methodId);
        
        return called.stream()
            .limit(properties.getMaxResults())
            .map(this::javaMethodToCodeSymbol)
            .toList();
    }
    
    /**
     * Find implementations of an interface.
     */
    public List<CodeSymbol> findImplementations(String interfaceClassId) {
        log.info("Finding implementations of: {}", interfaceClassId);
        
        List<JavaClass> implementations = classRepository.findSubtypes(interfaceClassId);
        
        return implementations.stream()
            .limit(properties.getMaxResults())
            .map(this::javaClassToCodeSymbol)
            .toList();
    }
    
    /**
     * Search for classes by name (partial match).
     */
    public List<CodeSymbol> searchClassesByName(String name, String repository) {
        log.info("Searching classes by name: {}, repo={}", name, repository);
        
        List<JavaClass> classes = (repository != null && !repository.isBlank())
            ? classRepository.searchByPartialNameInRepository(name, repository, properties.getMaxResults())
            : classRepository.searchByPartialName(name, properties.getMaxResults());
        
        return classes.stream()
            .map(this::javaClassToCodeSymbol)
            .toList();
    }
    
    /**
     * Search for methods by name (partial match).
     */
    public List<CodeSymbol> searchMethodsByName(String name, String repository) {
        log.info("Searching methods by name: {}, repo={}", name, repository);
        
        List<JavaMethod> methods = (repository != null && !repository.isBlank())
            ? methodRepository.searchByPartialNameInRepository(name, repository, properties.getMaxResults())
            : methodRepository.searchByPartialName(name, properties.getMaxResults());
        
        return methods.stream()
            .map(this::javaMethodToCodeSymbol)
            .toList();
    }
    
    private List<CodeSymbol> findMethodCandidates(SymbolLookupRequest request) {
        List<CodeSymbol> candidates = new ArrayList<>();

        // Try exact method ID lookup first
        String possibleMethodId = String.format("method:%s:%s",
            request.getRepository() != null ? request.getRepository() : "*",
            request.getName());
        
        if (request.getRepository() != null) {
            Optional<JavaMethod> methodOpt = methodRepository.findByMethodId(possibleMethodId);
            if (methodOpt.isPresent()) {
                candidates.add(javaMethodToCodeSymbol(methodOpt.get()));
            }
        }

        List<JavaMethod> methods = searchMethodsByRepository(request.getName(), request.getRepository());
        for (JavaMethod method : methods) {
            candidates.add(javaMethodToCodeSymbol(method));
        }

        // Similar-name (CONTAINS) search on method name/FQN, scoped to the requested
        // repository. Mirrors the class fallback below - exact/name match first,
        // then a "contains" style match, before the caller (findSymbolCandidates)
        // falls back to searching without a repository filter across all repos.
        if (candidates.isEmpty() && shouldUsePartialNameFallback(request)) {
            List<JavaMethod> partialMatches = searchMethodsByPartialName(request.getName(), request.getRepository());
            for (JavaMethod method : partialMatches) {
                candidates.add(javaMethodToCodeSymbol(method));
            }
        }

        return candidates;
    }

    private List<CodeSymbol> findClassCandidates(SymbolLookupRequest request) {
        List<CodeSymbol> candidates = new ArrayList<>();

        // Try exact class ID lookup first
        String possibleClassId = String.format("class:%s:%s",
            request.getRepository() != null ? request.getRepository() : "*",
            request.getName());
        
        if (request.getRepository() != null) {
            Optional<JavaClass> classOpt = classRepository.findByClassId(possibleClassId);
            if (classOpt.isPresent()) {
                candidates.add(javaClassToCodeSymbol(classOpt.get()));
            }
        }

        List<JavaClass> classes = searchClassesByRepository(request.getName(), request.getRepository());
        for (JavaClass javaClass : classes) {
            candidates.add(javaClassToCodeSymbol(javaClass));
        }

        // Similar-name (CONTAINS) search on class name/FQN, scoped to the requested
        // repository, before falling back to a cross-repository search.
        if (candidates.isEmpty() && shouldUsePartialNameFallback(request)) {
            List<JavaClass> partialMatches = searchClassesByPartialName(request.getName(), request.getRepository());
            for (JavaClass javaClass : partialMatches) {
                candidates.add(javaClassToCodeSymbol(javaClass));
            }
        }

        return candidates;
    }
    
    private List<JavaMethod> searchMethodsByRepository(String name, String repository) {
        if (repository != null && !repository.isBlank()) {
            return methodRepository.searchByNameInRepository(name, repository, properties.getMaxResults());
        }
        return methodRepository.searchByName(name, properties.getMaxResults());
    }

    private List<JavaMethod> searchMethodsByPartialName(String name, String repository) {
        if (repository != null && !repository.isBlank()) {
            return methodRepository.searchByPartialNameInRepository(name, repository, properties.getMaxResults());
        }
        return methodRepository.searchByPartialName(name, properties.getMaxResults());
    }
    
    private List<JavaClass> searchClassesByRepository(String name, String repository) {
        if (repository != null && !repository.isBlank()) {
            return classRepository.searchByNameInRepository(name, repository, properties.getMaxResults());
        }
        return classRepository.searchByName(name, properties.getMaxResults());
    }

    private List<JavaClass> searchClassesByPartialName(String name, String repository) {
        if (repository != null && !repository.isBlank()) {
            return classRepository.searchByPartialNameInRepository(name, repository, properties.getMaxResults());
        }
        return classRepository.searchByPartialName(name, properties.getMaxResults());
    }

    /**
     * Guards the "contains"-style partial name fallback (used for both methods and
     * classes) so it isn't attempted for blank names or fully qualified methodId /
     * classId style queries (which contain ':').
     */
    private boolean shouldUsePartialNameFallback(SymbolLookupRequest request) {
        String name = request.getName();
        if (name == null) {
            return false;
        }
        String normalized = name.trim();
        return !normalized.isEmpty() && !normalized.contains(":");
    }
    
    private CodeSymbol javaMethodToCodeSymbol(JavaMethod method) {
        List<String> relationships = new ArrayList<>();
        int calledMethodCount = methodRepository.findCalledMethods(method.getMethodId()).size();
        if (calledMethodCount > 0) {
            relationships.add(String.format("CALLS %d method(s)", calledMethodCount));
        }
        
        return CodeSymbol.builder()
            .symbolId(method.getMethodId())
            .symbolType(CodeSymbol.SymbolType.METHOD)
            .name(method.getName())
            .fullyQualifiedName(method.getFullyQualifiedSignature())
            .repository(method.getRepository())
            .module(method.getModule())
            .packageName(method.getPackageName())
            .className(method.getClassName())
            .methodName(method.getName())
            .filePath(method.getFilePath())
            .startLine(method.getStartLine())
            .endLine(method.getEndLine())
            .branch(method.getBranch())
            .commit(method.getCommit())
            .signature(method.getSignature())
            .returnType(method.getReturnType())
            .parameterTypes(method.getParameterTypes())
            .relationships(relationships)
            .build();
    }
    
    private CodeSymbol javaClassToCodeSymbol(JavaClass javaClass) {
        List<String> relationships = new ArrayList<>();
        if (javaClass.getMethods() != null && !javaClass.getMethods().isEmpty()) {
            relationships.add(String.format("HAS %d method(s)", javaClass.getMethods().size()));
        }
        if (javaClass.getExtendedClasses() != null && !javaClass.getExtendedClasses().isEmpty()) {
            relationships.add(String.format("EXTENDS %d class(es)", javaClass.getExtendedClasses().size()));
        }
        if (javaClass.getImplementedInterfaces() != null && !javaClass.getImplementedInterfaces().isEmpty()) {
            relationships.add(String.format("IMPLEMENTS %d interface(s)", javaClass.getImplementedInterfaces().size()));
        }
        
        CodeSymbol.SymbolType symbolType = "INTERFACE".equals(javaClass.getType()) 
            ? CodeSymbol.SymbolType.INTERFACE 
            : CodeSymbol.SymbolType.CLASS;
        
        return CodeSymbol.builder()
            .symbolId(javaClass.getClassId())
            .symbolType(symbolType)
            .name(javaClass.getSimpleName())
            .fullyQualifiedName(javaClass.getFullyQualifiedName())
            .repository(javaClass.getRepository())
            .module(javaClass.getModule())
            .packageName(javaClass.getPackageName())
            .className(javaClass.getSimpleName())
            .filePath(javaClass.getFilePath())
            .startLine(javaClass.getStartLine())
            .endLine(javaClass.getEndLine())
            .branch(javaClass.getBranch())
            .commit(javaClass.getCommit())
            .relationships(relationships)
            .build();
    }
    
    private void validateRequest(SymbolLookupRequest request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new InvalidQueryException("Symbol name cannot be null or empty");
        }
    }

    private void normalizeRequest(SymbolLookupRequest request) {
        request.setName(request.getName().trim());
        if (request.getRepository() != null && request.getRepository().isBlank()) {
            request.setRepository(null);
        }
        if (request.getModule() != null && request.getModule().isBlank()) {
            request.setModule(null);
        }
    }
    
    private void validateDepth(int depth) {
        if (depth < 0) {
            throw new InvalidQueryException("Depth cannot be negative");
        }
        if (depth > properties.getMaxDepth()) {
            throw new InvalidQueryException(
                String.format("Depth (%d) exceeds maximum allowed (%d)", 
                    depth, properties.getMaxDepth()));
        }
    }

    private List<CodeSymbol> deduplicateBySymbolId(List<CodeSymbol> symbols) {
        Map<String, CodeSymbol> deduplicated = new LinkedHashMap<>();
        for (CodeSymbol symbol : symbols) {
            deduplicated.putIfAbsent(symbol.getSymbolId(), symbol);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private Comparator<CodeSymbol> symbolRankingComparator() {
        return Comparator
            .comparing(CodeSymbol::getRelevanceScore, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(CodeSymbol::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(CodeSymbol::getFullyQualifiedName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(CodeSymbol::getFilePath, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(CodeSymbol::getStartLine, Comparator.nullsLast(Integer::compareTo));
    }

    private double calculateScore(CodeSymbol symbol, String query) {
        if (symbol == null || query == null || query.isBlank()) {
            return 0.0;
        }

        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        String symbolId = normalize(symbol.getSymbolId());
        String name = normalize(symbol.getName());
        String signature = normalize(symbol.getSignature());
        String fqn = normalize(symbol.getFullyQualifiedName());
        String filePath = normalize(symbol.getFilePath());

        double score = 0.0;
        if (symbolId.equals(normalizedQuery)) {
            score += 1000;
        }
        if (fqn.equals(normalizedQuery)) {
            score += 950;
        }
        if (signature.equals(normalizedQuery)) {
            score += 900;
        }
        if (name.equals(normalizedQuery)) {
            score += 850;
        }
        if (fqn.endsWith("." + normalizedQuery) || signature.endsWith(normalizedQuery)) {
            score += 700;
        }
        if (fqn.contains(normalizedQuery)) {
            score += 550;
        }
        if (signature.contains(normalizedQuery)) {
            score += 500;
        }
        if (name.startsWith(normalizedQuery)) {
            score += 425;
        }
        if (name.contains(normalizedQuery)) {
            score += 350;
        }
        if (!filePath.contains("/test/")) {
            score += 25;
        }
        return score;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

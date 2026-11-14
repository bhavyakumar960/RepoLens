package org.example.springaimcp.rag.retrieval;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.codeintel.service.Neo4jSymbolService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts concrete entities (class/method/repository candidates, business terms) from a
 * query using deterministic pattern matching, then verifies symbol candidates against the
 * Neo4j graph via {@link Neo4jSymbolService} <b>before</b> falling back to free-text/vector
 * search.
 * <p>
 * This favors precision over guesswork: a token that merely "looks like" a class name is
 * only reported as {@code resolvedClassName} once it's confirmed to actually exist in the
 * graph. Unresolved candidate tokens are still returned (see
 * {@link QueryEntities#candidateSymbols()}) so callers can use them for logging/debugging.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class QueryEntityExtractor {

    /** Max distinct candidates verified against Neo4j per query, to bound graph calls. */
    private static final int MAX_CANDIDATES_TO_VERIFY = 5;

    // Dotted fully-qualified name, e.g. com.axis.checkout.FinancialOrderCheckoutServiceImpl
    private static final Pattern FQN_PATTERN =
            Pattern.compile("\\b(?:[a-z][a-zA-Z0-9]*\\.)+[A-Z][a-zA-Z0-9]*\\b");

    // PascalCase identifier, e.g. FinancialOrderCheckoutServiceImpl
    private static final Pattern PASCAL_CASE_PATTERN =
            Pattern.compile("\\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)*\\b");

    // camelCase identifier with at least one internal hump, e.g. processOrder, validateOtpV2
    private static final Pattern CAMEL_CASE_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)+\\b");

    // kebab-case token, e.g. business-loan-orchestrator (repository/module name hint)
    private static final Pattern KEBAB_CASE_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9]*(?:-[a-z0-9]+)+\\b");

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "is", "are", "what", "which", "who", "how", "does", "do", "why",
            "when", "where", "find", "show", "me", "get", "class", "method", "function",
            "interface", "enum", "in", "of", "for", "a", "an", "and", "or", "to", "on"
    );

    private final Neo4jSymbolService neo4jSymbolService;

    /**
     * Extracts entities from a (already normalized) query, verifying symbol candidates
     * against the graph. Never throws: graph lookup failures are logged and treated as
     * "not found" so extraction always degrades gracefully to heuristic behavior.
     */
    public QueryEntities extract(String query) {
        return extract(query, null);
    }

    /**
     * Extracts entities from a query, scoping graph verification lookups to a specific
     * repository when one is already known (narrows candidates, cheaper lookups).
     */
    public QueryEntities extract(String query, String repository) {
        if (query == null || query.isBlank()) {
            return new QueryEntities(List.of(), null, null, null, List.of());
        }

        Set<String> candidates = new LinkedHashSet<>();
        addMatches(FQN_PATTERN, query, candidates);
        addMatches(PASCAL_CASE_PATTERN, query, candidates);
        addMatches(CAMEL_CASE_PATTERN, query, candidates);

        String repositoryHint = firstMatch(KEBAB_CASE_PATTERN, query);

        String resolvedClassName = null;
        String resolvedMethodName = null;

        int verified = 0;
        for (String candidate : candidates) {
            if (verified >= MAX_CANDIDATES_TO_VERIFY) {
                break;
            }
            // Dotted FQNs: only the simple (last) segment is a valid graph symbol name.
            String simpleName = candidate.contains(".")
                    ? candidate.substring(candidate.lastIndexOf('.') + 1)
                    : candidate;

            if (resolvedClassName == null) {
                resolvedClassName = tryResolveClass(simpleName, repository);
                verified++;
                if (resolvedClassName != null) {
                    continue;
                }
            }
            if (resolvedMethodName == null) {
                resolvedMethodName = tryResolveMethod(simpleName, repository);
                verified++;
            }
            if (resolvedClassName != null && resolvedMethodName != null) {
                break;
            }
        }

        List<String> businessTerms = extractBusinessTerms(query, candidates);

        QueryEntities entities = new QueryEntities(
                List.copyOf(candidates), resolvedClassName, resolvedMethodName, repositoryHint, businessTerms);
        log.debug("Extracted query entities: {}", entities);
        return entities;
    }

    private String tryResolveClass(String name, String repository) {
        try {
            List<CodeSymbol> matches = neo4jSymbolService.searchClassesByName(name, repository);
            if (!matches.isEmpty()) {
                return matches.get(0).getName();
            }
        } catch (Exception e) {
            log.debug("Class resolution lookup failed for '{}': {}", name, e.getMessage());
        }
        return null;
    }

    private String tryResolveMethod(String name, String repository) {
        try {
            List<CodeSymbol> matches = neo4jSymbolService.searchMethodsByName(name, repository);
            if (!matches.isEmpty()) {
                return matches.get(0).getName();
            }
        } catch (Exception e) {
            log.debug("Method resolution lookup failed for '{}': {}", name, e.getMessage());
        }
        return null;
    }

    private List<String> extractBusinessTerms(String query, Set<String> candidates) {
        List<String> terms = new ArrayList<>();
        for (String word : query.split("\\s+")) {
            String cleaned = word.replaceAll("[^a-zA-Z0-9]", "");
            if (cleaned.isBlank() || candidates.contains(cleaned)) {
                continue;
            }
            String lower = cleaned.toLowerCase();
            if (STOP_WORDS.contains(lower) || cleaned.length() < 3) {
                continue;
            }
            terms.add(lower);
        }
        return terms;
    }

    private void addMatches(Pattern pattern, String query, Set<String> out) {
        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            out.add(matcher.group());
        }
    }

    private String firstMatch(Pattern pattern, String query) {
        Matcher matcher = pattern.matcher(query);
        return matcher.find() ? matcher.group() : null;
    }
}

package org.example.springaimcp.rag.retrieval;

import java.util.List;

/**
 * Concrete entities extracted from a natural-language query, used to sharpen retrieval
 * before falling back to broad semantic search. See {@link QueryEntityExtractor}.
 *
 * @param candidateSymbols  raw identifier-shaped tokens found in the query (camelCase,
 *                          PascalCase, snake_case, dotted FQNs), in order of appearance
 * @param resolvedClassName a class name confirmed to exist in the graph (Neo4j), if any
 * @param resolvedMethodName a method name confirmed to exist in the graph (Neo4j), if any
 * @param repositoryHint    a kebab-case token that looks like a repository/module name
 *                          (heuristic only, not graph-verified)
 * @param businessTerms     remaining significant words, useful for vector-search expansion
 */
public record QueryEntities(
        List<String> candidateSymbols,
        String resolvedClassName,
        String resolvedMethodName,
        String repositoryHint,
        List<String> businessTerms
) {
    public boolean hasResolvedSymbol() {
        return resolvedClassName != null || resolvedMethodName != null;
    }
}

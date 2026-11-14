package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

/**
 * Retrieval provenance/debug information: records the routing decisions made by
 * {@code AdaptiveRetrievalRouter} for a given query so callers can understand (and
 * troubleshoot) why a particular strategy was chosen, without needing to read logs.
 * <p>
 * Only populated when the request explicitly opts in (see {@code CodeSearchRequest#debug}),
 * keeping normal responses compact.
 */
@Data
@Builder
public class RetrievalDebugInfo {

    private String intent;
    private String strategy;
    private boolean vectorSearchUsed;
    private boolean graphSearchUsed;
    private int multiQueryCount;
    private int dependencyDepth;

    /** Class name resolved deterministically against the graph, if any. */
    private String resolvedClassName;

    /** Method name resolved deterministically against the graph, if any. */
    private String resolvedMethodName;

    /** Repository hint parsed from the query (heuristic, not graph-verified). */
    private String repositoryHint;

    private long tookMillis;

    public String toSummary() {
        return String.format(
                "intent=%s strategy=%s vector=%s graph=%s queries=%d depth=%d resolvedClass=%s resolvedMethod=%s repoHint=%s tookMs=%d",
                intent, strategy, vectorSearchUsed, graphSearchUsed, multiQueryCount, dependencyDepth,
                resolvedClassName, resolvedMethodName, repositoryHint, tookMillis);
    }
}

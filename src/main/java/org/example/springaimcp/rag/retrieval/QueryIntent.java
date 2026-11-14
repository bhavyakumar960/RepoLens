package org.example.springaimcp.rag.retrieval;

/**
 * Types of query intents for adaptive retrieval routing.
 * Based on query intent, different retrieval strategies are used.
 */
public enum QueryIntent {
    /**
     * Looking for an exact symbol (class, method, field).
     * Example: "Where is processOrder?" or "Find UserService class"
     * Strategy: Neo4j direct lookup, no vector search needed
     */
    EXACT_SYMBOL,

    /**
     * Looking for a specific file.
     * Example: "Show me OrderController.java"
     * Strategy: File path search, minimal retrieval
     */
    FILE_LOOKUP,

    /**
     * Understanding a concept or implementation approach.
     * Example: "How is FD interest calculated?"
     * Strategy: Qdrant vector search (2-3 queries)
     */
    CONCEPTUAL,

    /**
     * Understanding dependencies between components.
     * Example: "What depends on UserService?"
     * Strategy: Neo4j graph traversal
     */
    DEPENDENCY,

    /**
     * Understanding call relationships.
     * Example: "What calls processOrder?" or "What methods does checkout call?"
     * Strategy: Neo4j CALLS relationships
     */
    CALL_GRAPH,

    /**
     * Understanding impact of changes.
     * Example: "What breaks if I change processOrder?"
     * Strategy: Neo4j reverse dependencies + callers
     */
    IMPACT_ANALYSIS,

    /**
     * Understanding configuration or annotations.
     * Example: "What REST endpoints exist?" or "Show Spring @Service classes"
     * Strategy: Neo4j annotation/metadata search
     */
    CONFIGURATION,

    /**
     * Understanding business logic flow.
     * Example: "How does order processing work end-to-end?"
     * Strategy: Qdrant + Neo4j combined
     */
    BUSINESS_LOGIC,

    /**
     * Debugging or troubleshooting.
     * Example: "Why does processOrder fail when interest calculation fails?"
     * Strategy: Qdrant + Neo4j + call graph
     */
    DEBUGGING,

    /**
     * High-level architectural understanding.
     * Example: "Explain the checkout architecture" or "What are the main components?"
     * Strategy: Qdrant + Neo4j modules/packages
     */
    ARCHITECTURE,

    /**
     * Fallback for queries that don't match patterns.
     * Strategy: Full retrieval (Qdrant + Neo4j)
     */
    UNKNOWN
}

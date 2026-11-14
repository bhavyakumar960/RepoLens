package org.example.springaimcp.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    @DefaultValue("5")          int    topK,
    @DefaultValue("0.7")        double similarityThreshold,
    @DefaultValue("1000")       int    chunkSize,
    @DefaultValue("350")        int    minChunkSizeChars,
    @DefaultValue("documents/") String documentsPath,
    @DefaultValue("nomic-embed-text") String embeddingModel,
    @DefaultValue("false")      boolean autoIngestEnabled,
    @DefaultValue("")           String autoIngestJavaRoot,
    @DefaultValue("true")       boolean autoReingestFailedFilesOnly,
    @DefaultValue("false")      boolean rerankerEnabled,
    @DefaultValue("http://localhost:8081") String rerankerBaseUrl,
    @DefaultValue("3")          int    rerankerMinDocuments,
    @DefaultValue("EXACT_SYMBOL,CALL_GRAPH") String rerankerSkipForIntents,
    @DefaultValue("true")      boolean reconciliationEnabled,

    // Per-intent graph traversal depth (previously hardcoded in AdaptiveRetrievalRouter).
    @DefaultValue("2")         int    callGraphDepth,
    @DefaultValue("3")         int    dependencyGraphDepth,
    @DefaultValue("4")         int    impactAnalysisDepth,
    @DefaultValue("2")         int    businessLogicDepth,
    @DefaultValue("3")         int    debuggingDepth,
    @DefaultValue("2")         int    architectureDepth,
    @DefaultValue("2")         int    unknownIntentDepth,

    // Per-intent vector-search query variation counts (previously hardcoded).
    @DefaultValue("2")         int    conceptualQueryCount,
    @DefaultValue("3")         int    businessLogicQueryCount,
    @DefaultValue("3")         int    debuggingQueryCount,
    @DefaultValue("3")         int    architectureQueryCount,
    @DefaultValue("2")         int    unknownIntentQueryCount,

    // Retrieval provenance / debug mode: when enabled, responses can carry routing
    // decisions (intent, strategy, depths, resolved entities, timing) for troubleshooting.
    @DefaultValue("false")     boolean retrievalDebugEnabled
) {}

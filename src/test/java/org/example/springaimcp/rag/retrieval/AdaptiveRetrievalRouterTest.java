package org.example.springaimcp.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.springaimcp.codeintel.model.CodeSearchRequest;
import org.example.springaimcp.codeintel.model.CodeSearchResponse;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.codeintel.service.CodeIntelligenceOrchestrator;
import org.example.springaimcp.codeintel.service.Neo4jSymbolService;
import org.example.springaimcp.graph.service.CodeGraphService;
import org.example.springaimcp.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AdaptiveRetrievalRouter}: verifies intent-based routing decisions,
 * configurable per-intent depth/query-count (rather than hardcoded), query normalization,
 * and retrieval debug/provenance population.
 */
class AdaptiveRetrievalRouterTest {

    private CodeIntelligenceOrchestrator orchestrator;
    private Neo4jSymbolService neo4jSymbolService;
    private AdaptiveRetrievalRouter router;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        orchestrator = mock(CodeIntelligenceOrchestrator.class);
        neo4jSymbolService = mock(Neo4jSymbolService.class);
        CodeGraphService graphService = mock(CodeGraphService.class);
        QueryIntentClassifier intentClassifier = new QueryIntentClassifier();
        QueryNormalizer normalizer = new QueryNormalizer();
        QueryEntityExtractor extractor = new QueryEntityExtractor(neo4jSymbolService);

        ragProperties = new RagProperties(
                5, 0.7, 1000, 350, "documents/", "nomic-embed-text",
                false, "", true, false, "http://localhost:8081", 3,
                "EXACT_SYMBOL,CALL_GRAPH", true,
                2, 3, 4, 2, 3, 2, 2, // depths
                2, 3, 3, 3, 2,       // query counts
                false                 // retrievalDebugEnabled
        );

        router = new AdaptiveRetrievalRouter(intentClassifier, normalizer, extractor, orchestrator,
                neo4jSymbolService, graphService, ragProperties);

        when(neo4jSymbolService.searchClassesByName(anyString(), any())).thenReturn(List.of());
        when(neo4jSymbolService.searchMethodsByName(anyString(), any())).thenReturn(List.of());
        when(orchestrator.retrieveCodeContext(any(CodeSearchRequest.class), any(QueryIntent.class)))
                .thenAnswer(inv -> CodeSearchResponse.builder()
                        .query(((CodeSearchRequest) inv.getArgument(0)).getQuery())
                        .results(List.of())
                        .totalResults(0)
                        .build());
        when(orchestrator.searchCode(any(CodeSearchRequest.class)))
                .thenAnswer(inv -> CodeSearchResponse.builder()
                        .query(((CodeSearchRequest) inv.getArgument(0)).getQuery())
                        .results(List.of())
                        .totalResults(0)
                        .build());
    }

    @Test
    void callGraphIntentUsesConfiguredDepthAndSkipsVectorSearch() {
        CodeSearchRequest request = CodeSearchRequest.builder().query("What calls processOrder?").build();

        router.route(request);

        assertEquals(2, request.getDependencyDepth()); // rag.call-graph-depth default
        assertFalse(request.getUseVectorSearch());
        assertTrue(request.getUseGraphSearch());
        assertEquals(1, request.getMultiQueryCount());
    }

    @Test
    void dependencyIntentUsesConfiguredDepth() {
        CodeSearchRequest request = CodeSearchRequest.builder().query("What depends on UserService?").build();

        router.route(request);

        assertEquals(3, request.getDependencyDepth()); // rag.dependency-graph-depth default
        assertFalse(request.getUseVectorSearch());
    }

    @Test
    void impactAnalysisIntentUsesConfiguredDepth() {
        CodeSearchRequest request = CodeSearchRequest.builder().query("What breaks if I change processOrder?").build();

        router.route(request);

        assertEquals(4, request.getDependencyDepth()); // rag.impact-analysis-depth default
    }

    @Test
    void conceptualIntentUsesVectorSearchOnlyWithConfiguredQueryCount() {
        CodeSearchRequest request = CodeSearchRequest.builder().query("How is FD interest calculated?").build();

        router.route(request);

        assertTrue(request.getUseVectorSearch());
        assertFalse(request.getUseGraphSearch());
        assertEquals(2, request.getMultiQueryCount()); // rag.conceptual-query-count default
    }

    @Test
    void businessLogicIntentUsesBothBackendsWithConfiguredValues() {
        CodeSearchRequest request = CodeSearchRequest.builder().query("How does order processing work end-to-end?").build();

        router.route(request);

        assertTrue(request.getUseVectorSearch());
        assertTrue(request.getUseGraphSearch());
        assertEquals(2, request.getDependencyDepth()); // rag.business-logic-depth default
        assertEquals(3, request.getMultiQueryCount());  // rag.business-logic-query-count default
    }

    @Test
    void queryIsNormalizedBeforeRouting() {
        CodeSearchRequest request = CodeSearchRequest.builder()
                .query("  What   calls   processOrder??  ")
                .build();

        router.route(request);

        assertEquals("What calls processOrder?", request.getQuery());
    }

    @Test
    void debugInfoIsPopulatedOnlyWhenRequested() {
        CodeSearchRequest requestWithoutDebug = CodeSearchRequest.builder().query("What calls processOrder?").build();
        CodeSearchResponse responseWithoutDebug = router.route(requestWithoutDebug);
        assertNull(responseWithoutDebug.getDebugInfo());

        CodeSearchRequest requestWithDebug = CodeSearchRequest.builder()
                .query("What calls processOrder?")
                .debug(true)
                .build();
        CodeSearchResponse responseWithDebug = router.route(requestWithDebug);

        assertNotNull(responseWithDebug.getDebugInfo());
        assertEquals("CALL_GRAPH", responseWithDebug.getDebugInfo().getIntent());
        assertFalse(responseWithDebug.getDebugInfo().isVectorSearchUsed());
        assertTrue(responseWithDebug.getDebugInfo().isGraphSearchUsed());
    }

    @Test
    void exactSymbolResolvedAgainstGraphIsPreferredOverHeuristicExtraction() {
        Neo4jSymbolService verifyingService = mock(Neo4jSymbolService.class);
        CodeSymbol resolvedClass = CodeSymbol.builder().name("OrderService").build();
        when(verifyingService.searchClassesByName(anyString(), any())).thenReturn(List.of(resolvedClass));
        when(verifyingService.searchMethodsByName(anyString(), any())).thenReturn(List.of());

        QueryEntityExtractor extractor = new QueryEntityExtractor(verifyingService);
        AdaptiveRetrievalRouter routerWithResolution = new AdaptiveRetrievalRouter(
                new QueryIntentClassifier(), new QueryNormalizer(), extractor, orchestrator,
                verifyingService, mock(CodeGraphService.class), ragProperties);

        CodeSearchRequest request = CodeSearchRequest.builder().query("Where is class OrderService").build();
        CodeSearchResponse response = routerWithResolution.route(request);

        assertNotNull(response);
        assertFalse(request.getUseVectorSearch());
    }
}

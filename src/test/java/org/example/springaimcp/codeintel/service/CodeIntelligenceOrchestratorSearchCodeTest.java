package org.example.springaimcp.codeintel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.model.CodeSearchRequest;
import org.example.springaimcp.codeintel.model.CodeSearchResponse;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.graph.service.CodeGraphService;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link CodeIntelligenceOrchestrator#searchCode}, which should
 * broaden a repository-scoped search when no results are found by dropping the
 * repository filter entirely (cross-repository search). Module is never applied as
 * a search filter (see {@link QdrantCodeSearchService}), so there is no separate
 * module-dropping step.
 */
class CodeIntelligenceOrchestratorSearchCodeTest {

    private QdrantCodeSearchService qdrantService;
    private RankingService rankingService;
    private CodeIntelligenceOrchestrator orchestrator;

    private CodeIntelligenceOrchestrator newOrchestrator() {
        qdrantService = mock(QdrantCodeSearchService.class);
        Neo4jSymbolService neo4jService = mock(Neo4jSymbolService.class);
        SourceCodeContextService sourceService = mock(SourceCodeContextService.class);
        rankingService = mock(RankingService.class);
        CodeGraphService codeGraphService = mock(CodeGraphService.class);
        CodeIntelProperties properties = new CodeIntelProperties();
        ConditionalRerankService conditionalRerankService = mock(ConditionalRerankService.class);

        // Pass-through ranking/limiting so we can control results purely via qdrantService.
        when(rankingService.rankAndDeduplicate(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(rankingService.limitResults(any(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

        return new CodeIntelligenceOrchestrator(
                qdrantService,
                neo4jService,
                sourceService,
                rankingService,
                codeGraphService,
                properties,
                conditionalRerankService
        );
    }

    private CodeSymbol symbol(String id) {
        return CodeSymbol.builder().symbolId(id).name(id).build();
    }

    @Test
    void searchCodeReturnsResultsDirectlyWhenFilteredSearchSucceeds() {
        orchestrator = newOrchestrator();
        CodeSearchRequest request = CodeSearchRequest.builder()
                .query("maximus submit")
                .repository("axis-oms")
                .module("order-checkout")
                .topK(20)
                .build();

        when(qdrantService.search(any())).thenReturn(List.of(symbol("hit")));

        CodeSearchResponse response = orchestrator.searchCode(request);

        assertEquals(1, response.getResults().size());
        verify(qdrantService, times(1)).search(any());
    }

    @Test
    void searchCodeDropsRepositoryFilterWhenScopedSearchIsEmpty() {
        orchestrator = newOrchestrator();
        CodeSearchRequest request = CodeSearchRequest.builder()
                .query("maximus submit")
                .repository("axis-oms")
                .module("order-checkout")
                .topK(20)
                .build();

        when(qdrantService.search(any()))
                .thenReturn(List.of()) // repo scoped (module is never applied as a filter)
                .thenReturn(List.of(symbol("cross-repo-hit"))); // no filters (cross-repository)

        CodeSearchResponse response = orchestrator.searchCode(request);

        assertEquals(1, response.getResults().size());
        assertEquals("cross-repo-hit", response.getResults().get(0).getSymbolId());
        verify(qdrantService, times(2)).search(any());
    }

    @Test
    void searchCodeReturnsEmptyWhenNoFiltersToDropAndSearchIsEmpty() {
        orchestrator = newOrchestrator();
        CodeSearchRequest request = CodeSearchRequest.builder()
                .query("maximus submit")
                .topK(20)
                .build();

        when(qdrantService.search(any())).thenReturn(List.of());

        CodeSearchResponse response = orchestrator.searchCode(request);

        assertTrue(response.getResults().isEmpty());
        verify(qdrantService, times(1)).search(any());
    }
}

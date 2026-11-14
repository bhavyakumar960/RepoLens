package org.example.springaimcp.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.codeintel.service.Neo4jSymbolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link QueryEntityExtractor}: candidate token extraction and deterministic
 * graph-backed resolution (a candidate is only "resolved" once Neo4j confirms it exists).
 */
class QueryEntityExtractorTest {

    private Neo4jSymbolService neo4jSymbolService;
    private QueryEntityExtractor extractor;

    @BeforeEach
    void setUp() {
        neo4jSymbolService = mock(Neo4jSymbolService.class);
        extractor = new QueryEntityExtractor(neo4jSymbolService);
    }

    @Test
    void resolvesClassNameConfirmedByGraph() {
        when(neo4jSymbolService.searchClassesByName(anyString(), any()))
                .thenReturn(List.of(CodeSymbol.builder().name("FinancialOrderCheckoutServiceImpl").build()));
        when(neo4jSymbolService.searchMethodsByName(anyString(), any())).thenReturn(List.of());

        QueryEntities entities = extractor.extract("What calls processOrder in FinancialOrderCheckoutServiceImpl?");

        assertEquals("FinancialOrderCheckoutServiceImpl", entities.resolvedClassName());
        assertTrue(entities.hasResolvedSymbol());
    }

    @Test
    void doesNotResolveWhenGraphHasNoMatch() {
        when(neo4jSymbolService.searchClassesByName(anyString(), any())).thenReturn(List.of());
        when(neo4jSymbolService.searchMethodsByName(anyString(), any())).thenReturn(List.of());

        QueryEntities entities = extractor.extract("What calls processOrder?");

        assertNull(entities.resolvedClassName());
        assertNull(entities.resolvedMethodName());
        assertFalse(entities.hasResolvedSymbol());
        assertTrue(entities.candidateSymbols().contains("processOrder"));
    }

    @Test
    void extractsRepositoryHintFromKebabCaseToken() {
        when(neo4jSymbolService.searchClassesByName(anyString(), any())).thenReturn(List.of());
        when(neo4jSymbolService.searchMethodsByName(anyString(), any())).thenReturn(List.of());

        QueryEntities entities = extractor.extract("What does business-loan-orchestrator do?");

        assertEquals("business-loan-orchestrator", entities.repositoryHint());
    }

    @Test
    void gracefullyDegradesWhenGraphLookupThrows() {
        when(neo4jSymbolService.searchClassesByName(anyString(), any()))
                .thenThrow(new RuntimeException("Neo4j unavailable"));
        when(neo4jSymbolService.searchMethodsByName(anyString(), any()))
                .thenThrow(new RuntimeException("Neo4j unavailable"));

        QueryEntities entities = extractor.extract("Where is processOrder?");

        assertFalse(entities.hasResolvedSymbol());
    }

    @Test
    void returnsEmptyEntitiesForBlankQuery() {
        QueryEntities entities = extractor.extract("");

        assertTrue(entities.candidateSymbols().isEmpty());
        assertFalse(entities.hasResolvedSymbol());
    }
}

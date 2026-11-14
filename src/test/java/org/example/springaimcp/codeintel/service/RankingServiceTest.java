package org.example.springaimcp.codeintel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RankingService}: ranking (both ML-rerank-score-preserving and
 * heuristic paths), deduplication, and result limiting.
 */
class RankingServiceTest {

    private final RankingService rankingService = new RankingService();

    @Test
    void rankSymbolsPreservesRerankScoresWhenPresent() {
        CodeSymbol low = CodeSymbol.builder().symbolId("a").name("orderA").relevanceScore(0.2).build();
        CodeSymbol high = CodeSymbol.builder().symbolId("b").name("orderB").relevanceScore(0.9).build();

        List<CodeSymbol> ranked = rankingService.rankSymbols(List.of(low, high), "order");

        assertEquals("b", ranked.get(0).getSymbolId());
        assertEquals("a", ranked.get(1).getSymbolId());
    }

    @Test
    void rankSymbolsUsesHeuristicScoringWhenNoRerankScores() {
        CodeSymbol exactMatch = CodeSymbol.builder()
                .symbolId("exact")
                .name("processOrder")
                .symbolType(CodeSymbol.SymbolType.METHOD)
                .build();
        CodeSymbol unrelated = CodeSymbol.builder()
                .symbolId("unrelated")
                .name("somethingElse")
                .symbolType(CodeSymbol.SymbolType.FIELD)
                .build();

        List<CodeSymbol> ranked = rankingService.rankSymbols(
                List.of(unrelated, exactMatch), "processOrder");

        assertEquals("exact", ranked.get(0).getSymbolId());
    }

    @Test
    void deduplicateKeepsFirstOccurrenceBySymbolId() {
        CodeSymbol first = CodeSymbol.builder().symbolId("dup").name("first").build();
        CodeSymbol second = CodeSymbol.builder().symbolId("dup").name("second").build();

        List<CodeSymbol> result = rankingService.deduplicate(List.of(first, second));

        assertEquals(1, result.size());
        assertEquals("first", result.get(0).getName());
    }

    @Test
    void deduplicateHandlesNullOrEmptyInput() {
        assertEquals(0, rankingService.deduplicate(List.of()).size());
    }

    @Test
    void limitResultsCapsToRequestedSize() {
        List<CodeSymbol> symbols = List.of(
                CodeSymbol.builder().symbolId("1").build(),
                CodeSymbol.builder().symbolId("2").build(),
                CodeSymbol.builder().symbolId("3").build());

        List<CodeSymbol> limited = rankingService.limitResults(symbols, 2);

        assertEquals(2, limited.size());
    }

    @Test
    void limitResultsReturnsOriginalWhenUnderLimit() {
        List<CodeSymbol> symbols = List.of(CodeSymbol.builder().symbolId("1").build());

        assertEquals(1, rankingService.limitResults(symbols, 5).size());
    }

    @Test
    void rankAndDeduplicateCombinesBothSteps() {
        CodeSymbol dupLow = CodeSymbol.builder().symbolId("x").name("x").relevanceScore(0.1).build();
        CodeSymbol dupHigh = CodeSymbol.builder().symbolId("x").name("x").relevanceScore(0.1).build();
        CodeSymbol other = CodeSymbol.builder().symbolId("y").name("y").relevanceScore(0.9).build();

        List<CodeSymbol> result = rankingService.rankAndDeduplicate(List.of(dupLow, dupHigh, other), "y");

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> s.getSymbolId().equals("y")));
    }
}

package org.example.springaimcp.rag.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QueryNormalizerTest {

    private final QueryNormalizer normalizer = new QueryNormalizer();

    @Test
    void collapsesWhitespaceAndTrims() {
        assertEquals("What calls processOrder?", normalizer.normalize("  What   calls  processOrder?  "));
    }

    @Test
    void stripsWrappingQuotes() {
        assertEquals("processOrder", normalizer.normalize("\"processOrder\""));
    }

    @Test
    void collapsesRepeatedPunctuation() {
        assertEquals("Why does this fail?", normalizer.normalize("Why does this fail???"));
    }

    @Test
    void preservesIdentifierCasing() {
        assertEquals("FinancialOrderCheckoutServiceImpl", normalizer.normalize("FinancialOrderCheckoutServiceImpl"));
    }

    @Test
    void returnsEmptyStringForBlankInput() {
        assertEquals("", normalizer.normalize(null));
        assertEquals("", normalizer.normalize("   "));
    }
}

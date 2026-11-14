package org.example.springaimcp.rag.retrieval;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Normalizes raw user queries before intent classification, entity extraction, and
 * downstream retrieval so that trivial textual noise (extra whitespace, stray quotes,
 * repeated punctuation) doesn't affect pattern matching or symbol resolution.
 * <p>
 * Deliberately conservative: it must never change the semantic meaning of identifiers.
 * In particular it does <b>not</b> lower-case the whole query, since code identifiers
 * are case-sensitive (e.g. {@code processOrder} vs {@code ProcessOrder}).
 */
@Component
public class QueryNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile("([?!.,])\\1+");
    private static final Pattern WRAPPING_QUOTES = Pattern.compile("^[\"'`]+|[\"'`]+$");

    /**
     * Normalizes a query: trims, collapses internal whitespace/newlines to single spaces,
     * strips wrapping quotes, and collapses repeated punctuation (e.g. "???" -> "?").
     * Returns an empty string for {@code null}/blank input.
     */
    public String normalize(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        String normalized = query.trim();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        normalized = WRAPPING_QUOTES.matcher(normalized).replaceAll("");
        normalized = REPEATED_PUNCTUATION.matcher(normalized).replaceAll("$1");
        return normalized.trim();
    }
}

package org.example.springaimcp.rag.reranker;

import static org.example.springaimcp.rag.RagMetadataKeys.RERANK_SCORE;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
@Slf4j
public class TeiDocumentReranker implements DocumentReranker {

    private final RestClient restClient;
    private final boolean enabled;
    private final int minDocuments;

    public TeiDocumentReranker(RagProperties ragProperties) {
        this.enabled = ragProperties.rerankerEnabled();
        this.minDocuments = ragProperties.rerankerMinDocuments();
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(ragProperties.rerankerBaseUrl()))
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public List<Document> rerank(String query, List<Document> documents) {
        if (!enabled) {
            log.debug("Reranking is disabled");
            return documents;
        }
        
        if (documents.size() < minDocuments) {
            log.debug("Skipping rerank: {} documents < {} minimum", documents.size(), minDocuments);
            return documents;
        }

        List<String> texts = documents.stream()
                .map(Document::getText)
                .toList();

        Rank[] ranks;
        try {
            ranks = restClient.post()
                    .uri("/rerank")
                    .body(new RerankRequest(query, texts, false, false))
                    .retrieve()
                    .body(Rank[].class);
        } catch (RestClientException ex) {
            // Graceful fallback: log warning and return original documents
            log.warn("Failed to rerank documents via TEI at /rerank (service may be unavailable). " +
                    "Returning original document order. Error: {}", ex.getMessage());
            log.debug("Reranking error details", ex);
            return documents;
        }

        if (ranks == null || ranks.length != documents.size()) {
            log.warn("TEI reranker returned {} ranks for {} documents. Returning original order.",
                    ranks == null ? 0 : ranks.length, documents.size());
            return documents;
        }

        log.debug("Reranked {} document(s) for query={}", documents.size(), query);
        return List.of(ranks).stream()
                .map(rank -> toRerankedDocument(rank, documents))
                .toList();
    }

    private static Document toRerankedDocument(Rank rank, List<Document> documents) {
        if (rank.index() < 0 || rank.index() >= documents.size()) {
            throw new IllegalStateException("TEI reranker returned out-of-range index %d".formatted(rank.index()));
        }

        Document original = documents.get(rank.index());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (original.getMetadata() != null) {
            metadata.putAll(original.getMetadata());
        }
        
        // Normalize TEI score to [0, 1] range using sigmoid function
        // TEI scores typically range from -10 to +10
        double normalizedScore = normalizeScore(rank.score());
        metadata.put(RERANK_SCORE, normalizedScore);

        return Document.builder()
                .id(original.getId())
                .text(original.getText())
                .metadata(metadata)
                .build();
    }
    
    /**
     * Normalize rerank scores to [0, 1] range using sigmoid function.
     * This ensures consistent scoring across all symbols and prevents
     * heuristic scores from overwhelming ML-based rerank scores.
     * 
     * @param rawScore raw score from TEI reranker (typically -10 to +10)
     * @return normalized score in [0, 1] range
     */
    private static double normalizeScore(double rawScore) {
        // Sigmoid normalization: 1 / (1 + e^(-x))
        // For TEI scores, we apply scaling to map typical range to [0, 1]
        return 1.0 / (1.0 + Math.exp(-rawScore));
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        return value.replaceAll("/+$", "");
    }

    private record RerankRequest(
            String query,
            List<String> texts,
            @JsonProperty("raw_scores") boolean rawScores,
            @JsonProperty("return_text") boolean returnText
    ) {
    }

    private record Rank(int index, double score) {
    }
}

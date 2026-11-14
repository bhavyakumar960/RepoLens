package org.example.springaimcp.rag.retriever;

import static org.example.springaimcp.rag.RagMetadataKeys.BRANCH;
import static org.example.springaimcp.rag.RagMetadataKeys.CLASS_NAME;
import static org.example.springaimcp.rag.RagMetadataKeys.FILE_PATH;
import static org.example.springaimcp.rag.RagMetadataKeys.MEMBER_NAME;
import static org.example.springaimcp.rag.RagMetadataKeys.MEMBER_TYPE;
import static org.example.springaimcp.rag.RagMetadataKeys.MODULE;
import static org.example.springaimcp.rag.RagMetadataKeys.PACKAGE;
import static org.example.springaimcp.rag.RagMetadataKeys.REPO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.config.RagProperties;
import org.example.springaimcp.rag.reranker.DocumentReranker;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

/**
 * Performs semantic similarity search against the configured VectorStore.
 * topK and similarityThreshold are driven by {@link RagProperties}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VectorStoreDocumentRetriever implements DocumentRetriever {

    private static final Set<String> SUPPORTED_FILTER_KEYS = Set.of(
            REPO, MODULE, "service", BRANCH, FILE_PATH, PACKAGE, CLASS_NAME, MEMBER_NAME, MEMBER_TYPE
    );

    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final DocumentReranker documentReranker;

    @Override
    public List<Document> retrieve(String query) {
        return retrieve(query, Map.of(), false);
    }

    @Override
    public List<Document> retrieve(String query, Map<String, String> metadataFilters, boolean includeRelatedContext) {
        log.debug("Retrieving documents: topK={}, similarityThreshold={}",
                ragProperties.topK(), ragProperties.similarityThreshold());

        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(ragProperties.topK())
                .similarityThreshold(ragProperties.similarityThreshold());

        buildFilterExpression(metadataFilters).ifPresent(requestBuilder::filterExpression);
        List<Document> primary = vectorStore.similaritySearch(requestBuilder.build());
        List<Document> combined = new ArrayList<>(primary);

        if (includeRelatedContext && metadataFilters != null && metadataFilters.containsKey(CLASS_NAME)) {
            Map<String, String> classLevelFilters = new LinkedHashMap<>(metadataFilters);
            classLevelFilters.remove(MEMBER_NAME);
            classLevelFilters.remove(MEMBER_TYPE);

            SearchRequest.Builder expansionBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(2, Math.min(5, ragProperties.topK())))
                    .similarityThreshold(ragProperties.similarityThreshold());
            buildFilterExpression(classLevelFilters).ifPresent(expansionBuilder::filterExpression);
            combined.addAll(vectorStore.similaritySearch(expansionBuilder.build()));
        }

        List<Document> deduplicated = deduplicate(combined);
        log.info("Retrieved {} document(s) for query after deduplication", deduplicated.size());
        return documentReranker.rerank(query, deduplicated);
    }

    private Optional<Filter.Expression> buildFilterExpression(Map<String, String> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return Optional.empty();
        }

        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        List<FilterExpressionBuilder.Op> operations = metadataFilters.entrySet().stream()
                .filter(entry -> SUPPORTED_FILTER_KEYS.contains(entry.getKey()))
                .filter(entry -> entry.getValue() != null && !entry.getValue().isBlank())
                .map(entry -> builder.eq(entry.getKey(), entry.getValue()))
                .toList();

        if (operations.isEmpty()) {
            return Optional.empty();
        }

        FilterExpressionBuilder.Op merged = operations.get(0);
        for (int i = 1; i < operations.size(); i++) {
            merged = builder.and(merged, operations.get(i));
        }
        return Optional.of(merged.build());
    }

    private static List<Document> deduplicate(List<Document> documents) {
        Map<String, Document> unique = new LinkedHashMap<>();
        for (Document document : documents) {
            String key = (document.getId() == null || document.getId().isBlank())
                    ? Integer.toHexString(document.getText().hashCode())
                    : document.getId();
            unique.putIfAbsent(key, document);
        }
        return new ArrayList<>(unique.values());
    }
}

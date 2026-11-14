package org.example.springaimcp.rag.retriever;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

public interface DocumentRetriever {

    List<Document> retrieve(String query);

    List<Document> retrieve(String query, Map<String, String> metadataFilters, boolean includeRelatedContext);
}

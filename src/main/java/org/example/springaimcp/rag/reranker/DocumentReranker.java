package org.example.springaimcp.rag.reranker;

import java.util.List;
import org.springframework.ai.document.Document;

public interface DocumentReranker {

    List<Document> rerank(String query, List<Document> documents);
}

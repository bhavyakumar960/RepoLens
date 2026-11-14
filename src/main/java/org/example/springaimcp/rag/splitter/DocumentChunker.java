package org.example.springaimcp.rag.splitter;

import java.util.List;
import org.springframework.ai.document.Document;

/**
 * Splits a list of documents into smaller chunks suitable for embedding and retrieval.
 */
public interface DocumentChunker {

    List<Document> chunk(List<Document> documents);
}

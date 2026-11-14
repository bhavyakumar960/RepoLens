package org.example.springaimcp.rag.indexer;

import org.springframework.ai.document.Document;

import java.util.List;

public interface DocumentIndexer {

    void index(List<Document> documents);

    void deleteByIds(List<String> documentIds);

    void deleteByFileIdentity(String repo, String module, String branch, String filePath);

    boolean areDocumentIdsPresent(List<String> documentIds);
}

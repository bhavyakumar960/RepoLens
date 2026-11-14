package org.example.springaimcp.rag.retriever;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.example.springaimcp.rag.config.RagProperties;
import org.example.springaimcp.rag.reranker.DocumentReranker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class VectorStoreDocumentRetrieverTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final DocumentReranker documentReranker = mock(DocumentReranker.class);
    private final VectorStoreDocumentRetriever retriever = new VectorStoreDocumentRetriever(
            vectorStore,
            new RagProperties(5, 0.7, 1000, 350, "documents/", "nomic-embed-text", false, "", true, false, "http://localhost:8081", 3, "EXACT_SYMBOL", true,
                    2, 3, 4, 2, 3, 2, 2, 2, 3, 3, 3, 2, false),
            documentReranker);

    @Test
    void retrieveReranksDeduplicatedDocumentsBeforeReturning() {
        Document alpha = Document.builder().id("a").text("alpha").metadata(Map.of()).build();
        Document beta = Document.builder().id("b").text("beta").metadata(Map.of()).build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(alpha, beta, alpha));
        when(documentReranker.rerank("find alpha", List.of(alpha, beta)))
                .thenReturn(List.of(beta, alpha));

        List<Document> result = retriever.retrieve("find alpha", Map.of(), false);

        assertEquals(List.of(beta, alpha), result);
        verify(documentReranker).rerank("find alpha", List.of(alpha, beta));
    }

    @Test
    void retrieveExpandsClassContextBeforeReranking() {
        Document primary = Document.builder().id("a").text("primary").metadata(Map.of()).build();
        Document related = Document.builder().id("b").text("related").metadata(Map.of()).build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(primary))
                .thenReturn(List.of(related));
        when(documentReranker.rerank("find member", List.of(primary, related)))
                .thenReturn(List.of(primary, related));

        List<Document> result = retriever.retrieve("find member", Map.of("class_name", "Sample"), true);

        assertEquals(List.of(primary, related), result);
        verify(documentReranker).rerank(eq("find member"), eq(List.of(primary, related)));
    }
}

package org.example.springaimcp.rag.splitter;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.config.RagProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

/**
 * Splits documents using Spring AI's TokenTextSplitter (tiktoken-based).
 * Chunk size and minimum size are driven by {@link RagProperties}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TokenDocumentChunker implements DocumentChunker {

    private final RagProperties ragProperties;

    @Override
    public List<Document> chunk(List<Document> documents) {
        if (documents.isEmpty()) {
            log.warn("chunk() called with empty document list — nothing to chunk");
            return documents;
        }
        log.debug("Chunking {} document(s): chunkSize={}, minChunkSizeChars={}",
                documents.size(), ragProperties.chunkSize(), ragProperties.minChunkSizeChars());

        var splitter = new TokenTextSplitter(
                ragProperties.chunkSize(),         // defaultChunkSize (tokens)
                ragProperties.minChunkSizeChars(), // minChunkSizeChars — skip trivial tail fragments
                5,       // minChunkLengthToEmbed — ignore very short pieces
                10_000,  // maxNumChunks — safety ceiling per document
                true,    // keepSeparator — preserve sentence / paragraph boundaries
                List.of('.', '!', '?', '\n') // punctuation marks cannot be empty in Spring AI 2.0.0
        );

        var chunks = splitter.apply(documents);
        log.info("Produced {} chunk(s) from {} document(s)", chunks.size(), documents.size());
        return chunks;
    }
}

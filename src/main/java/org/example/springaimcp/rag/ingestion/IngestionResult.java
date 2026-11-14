package org.example.springaimcp.rag.ingestion;

/**
 * Summary of a completed document ingestion run.
 *
 * @param source              the original file path or URL
 * @param parsedDocumentCount number of raw documents returned by the parser
 * @param chunkCount          number of chunks actually indexed into the vector store
 */
public record IngestionResult(
    String source,
    int    parsedDocumentCount,
    int    chunkCount,
    int    indexedChunkCount,
    int    skippedChunkCount,
    boolean skippedFile
) {}

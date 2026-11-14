package org.example.springaimcp.rag.parser;

import org.springframework.ai.document.Document;

import java.util.List;

public interface DocumentParser {

    List<Document> parse(String source);
}

package org.example.springaimcp.rag.parser;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Primary {@link DocumentParser} that delegates to the correct implementation
 * based on the source file extension or scheme:
 *
 * <ul>
 *   <li>{@code *.java}  → {@link JavaDocumentParser} (AST-level extraction)</li>
 *   <li>Everything else → {@link TikaDocumentParser} (Apache Tika, handles PDF/DOCX/MD/TXT…)</li>
 * </ul>
 *
 * <p>This is marked {@code @Primary} so Spring injects this router wherever
 * a single {@link DocumentParser} bean is required (e.g. {@code DocumentIngestionService}).
 */
@Primary
@Component
@Slf4j
@RequiredArgsConstructor
public class RoutingDocumentParser implements DocumentParser {

    private final JavaDocumentParser  javaDocumentParser;
    private final TikaDocumentParser  tikaDocumentParser;

    @Override
    public List<Document> parse(String source) {
        var parser = resolveParser(source);
        log.debug("Routing '{}' → {}", source, parser.getClass().getSimpleName());
        return parser.parse(source);
    }

    private DocumentParser resolveParser(String source) {
        if (source != null && source.toLowerCase().endsWith(".java")) {
            return javaDocumentParser;
        }
        return tikaDocumentParser;
    }
}

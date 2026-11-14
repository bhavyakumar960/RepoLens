package org.example.springaimcp.rag.parser;

import java.net.MalformedURLException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;

/**
 * Parses documents from a local file path or HTTP(S) URL using Apache Tika.
 * Supports: PDF, DOCX, PPTX, XLSX, plain text, Markdown, HTML, and more.
 */
@Component
@Slf4j
public class TikaDocumentParser implements DocumentParser {

    @Override
    public List<Document> parse(String source) {
        log.info("Parsing document: source={}", source);
        try {
            var resource = isUrl(source)
                    ? new UrlResource(source)
                    : new FileSystemResource(source);
            var documents = new TikaDocumentReader(resource).get();
            log.info("Parsed {} document(s): source={}", documents.size(), source);
            return documents;
        } catch (MalformedURLException e) {
            throw new DocumentParseException("Malformed URL: " + source, e);
        } catch (Exception e) {
            throw new DocumentParseException("Failed to parse document: " + source, e);
        }
    }

    private static boolean isUrl(String source) {
        return source.startsWith("http://") || source.startsWith("https://");
    }
}

package org.example.springaimcp.codeintel.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for retrieving exact source code from files.
 * Provides efficient line-range extraction for code symbols.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SourceCodeContextService {
    
    private final CodeIntelProperties properties;
    
    /**
     * Retrieve source code for a symbol.
     * 
     * @param symbol the code symbol
     * @param maxLines maximum lines to retrieve (0 = no limit)
     * @return source code text
     */
    public String retrieveSourceCode(CodeSymbol symbol, int maxLines) {
        if (symbol.getFilePath() == null) {
            log.warn("Cannot retrieve source: no file path for symbol {}", symbol.getSymbolId());
            return null;
        }
        
        try {
            Path filePath = Paths.get(symbol.getFilePath());
            if (!Files.exists(filePath)) {
                log.warn("File does not exist: {}", filePath);
                return null;
            }
            
            List<String> allLines = Files.readAllLines(filePath);
            
            int startLine = symbol.getStartLine() != null ? symbol.getStartLine() : 1;
            int endLine = symbol.getEndLine() != null ? symbol.getEndLine() : allLines.size();
            
            // Apply max lines limit
            if (maxLines > 0 && (endLine - startLine + 1) > maxLines) {
                endLine = startLine + maxLines - 1;
                log.info("Truncating source code to {} lines (from {} to {})", 
                    maxLines, startLine, endLine);
            }
            
            // Validate line numbers
            startLine = Math.max(1, Math.min(startLine, allLines.size()));
            endLine = Math.max(startLine, Math.min(endLine, allLines.size()));
            
            // Extract lines (convert from 1-based to 0-based indexing)
            List<String> extractedLines = allLines.subList(startLine - 1, endLine);
            
            return String.join("\n", extractedLines);
            
        } catch (IOException e) {
            log.error("Failed to read source file: {}", symbol.getFilePath(), e);
            return null;
        }
    }
    
    /**
     * Retrieve source code with context (surrounding lines).
     * 
     * @param symbol the code symbol
     * @param contextLines number of lines before and after
     * @return source code with context
     */
    public String retrieveSourceCodeWithContext(CodeSymbol symbol, int contextLines) {
        if (symbol.getFilePath() == null) {
            return null;
        }
        
        try {
            Path filePath = Paths.get(symbol.getFilePath());
            if (!Files.exists(filePath)) {
                return null;
            }
            
            List<String> allLines = Files.readAllLines(filePath);
            
            int startLine = symbol.getStartLine() != null ? symbol.getStartLine() : 1;
            int endLine = symbol.getEndLine() != null ? symbol.getEndLine() : allLines.size();
            
            // Add context
            int contextStart = Math.max(1, startLine - contextLines);
            int contextEnd = Math.min(allLines.size(), endLine + contextLines);
            
            // Apply max lines limit
            int totalLines = contextEnd - contextStart + 1;
            if (totalLines > properties.getMaxLines()) {
                // Prioritize the actual symbol over context
                int availableContext = (properties.getMaxLines() - (endLine - startLine + 1)) / 2;
                contextStart = Math.max(1, startLine - availableContext);
                contextEnd = Math.min(allLines.size(), endLine + availableContext);
            }
            
            List<String> extractedLines = allLines.subList(contextStart - 1, contextEnd);
            
            return String.join("\n", extractedLines);
            
        } catch (IOException e) {
            log.error("Failed to read source file with context: {}", symbol.getFilePath(), e);
            return null;
        }
    }
    
    /**
     * Enrich a symbol with its source code.
     */
    public CodeSymbol enrichWithSourceCode(CodeSymbol symbol, boolean includeSource, int maxLines) {
        if (!includeSource || symbol.getFilePath() == null) {
            return symbol;
        }
        
        String sourceCode = retrieveSourceCode(symbol, maxLines > 0 ? maxLines : properties.getMaxLines());
        if (sourceCode != null) {
            symbol.setSourceCode(sourceCode);
        }
        
        return symbol;
    }
    
    /**
     * Enrich multiple symbols with their source code.
     */
    public List<CodeSymbol> enrichWithSourceCode(List<CodeSymbol> symbols, boolean includeSource, int maxLines) {
        if (!includeSource) {
            return symbols;
        }
        
        List<CodeSymbol> enriched = new ArrayList<>();
        for (CodeSymbol symbol : symbols) {
            enriched.add(enrichWithSourceCode(symbol, true, maxLines));
        }
        
        return enriched;
    }
}

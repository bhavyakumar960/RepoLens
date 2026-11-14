package org.example.springaimcp.codeintel.exception;

/**
 * Thrown when a requested symbol cannot be found.
 */
public class SymbolNotFoundException extends CodeIntelligenceException {
    
    public SymbolNotFoundException(String symbolId) {
        super("Symbol not found: " + symbolId);
    }
    
    public SymbolNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}

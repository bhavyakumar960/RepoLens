package org.example.springaimcp.codeintel.exception;

/**
 * Base exception for code intelligence operations.
 */
public class CodeIntelligenceException extends RuntimeException {
    
    public CodeIntelligenceException(String message) {
        super(message);
    }
    
    public CodeIntelligenceException(String message, Throwable cause) {
        super(message, cause);
    }
}

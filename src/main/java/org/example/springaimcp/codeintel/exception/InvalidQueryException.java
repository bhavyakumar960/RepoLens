package org.example.springaimcp.codeintel.exception;

/**
 * Thrown when an invalid query is provided.
 */
public class InvalidQueryException extends CodeIntelligenceException {
    
    public InvalidQueryException(String message) {
        super("Invalid query: " + message);
    }
    
    public InvalidQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}

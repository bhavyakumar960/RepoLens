package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response for dependency traversal.
 */
@Data
@Builder
public class DependencyResponse {
    
    private String symbolId;
    private List<CodeSymbol> incoming;
    private List<CodeSymbol> outgoing;
    private String summary;
    
    public String toCompactSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Dependencies for: %s\n\n", symbolId));
        
        if (incoming != null && !incoming.isEmpty()) {
            sb.append(String.format("Incoming (%d):\n", incoming.size()));
            for (CodeSymbol symbol : incoming) {
                sb.append(String.format("  ← %s (%s)\n", symbol.getName(), symbol.getSymbolType()));
            }
            sb.append("\n");
        }
        
        if (outgoing != null && !outgoing.isEmpty()) {
            sb.append(String.format("Outgoing (%d):\n", outgoing.size()));
            for (CodeSymbol symbol : outgoing) {
                sb.append(String.format("  → %s (%s)\n", symbol.getName(), symbol.getSymbolType()));
            }
            sb.append("\n");
        }
        
        if (summary != null && !summary.isBlank()) {
            sb.append("Summary:\n").append(summary);
        }
        
        return sb.toString();
    }
}

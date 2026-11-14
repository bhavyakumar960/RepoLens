package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response for architecture context queries.
 */
@Data
@Builder
public class ArchitectureResponse {
    
    private String query;
    private List<CodeSymbol> controllers;
    private List<CodeSymbol> services;
    private List<CodeSymbol> repositories;
    private List<String> flowDescription;
    private String summary;
    
    public String toCompactSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Architecture: %s\n\n", query));
        
        // Build flow
        if (controllers != null && !controllers.isEmpty()) {
            sb.append("Controllers:\n");
            for (CodeSymbol controller : controllers.stream().limit(3).toList()) {
                sb.append(String.format("  • %s (%s)\n", 
                    controller.getName(), controller.getFilePath()));
            }
            sb.append("\n");
        }
        
        if (services != null && !services.isEmpty()) {
            sb.append("Services:\n");
            for (CodeSymbol service : services.stream().limit(3).toList()) {
                sb.append(String.format("  • %s (%s)\n", 
                    service.getName(), service.getFilePath()));
            }
            sb.append("\n");
        }
        
        if (repositories != null && !repositories.isEmpty()) {
            sb.append("Repositories:\n");
            for (CodeSymbol repo : repositories.stream().limit(3).toList()) {
                sb.append(String.format("  • %s (%s)\n", 
                    repo.getName(), repo.getFilePath()));
            }
            sb.append("\n");
        }
        
        if (flowDescription != null && !flowDescription.isEmpty()) {
            sb.append("Flow:\n");
            for (String flow : flowDescription) {
                sb.append(String.format("  %s\n", flow));
            }
            sb.append("\n");
        }
        
        if (summary != null && !summary.isBlank()) {
            sb.append(summary);
        }
        
        return sb.toString();
    }
}

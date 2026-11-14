package org.example.springaimcp.codeintel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response for impact analysis.
 */
@Data
@Builder
public class ImpactResponse {
    
    private String symbolId;
    private List<CodeSymbol> directCallers;
    private List<CodeSymbol> indirectCallers;
    private List<CodeSymbol> callees;
    private List<CodeSymbol> dependentClasses;
    private List<CodeSymbol> affectedApis;
    private List<CodeSymbol> affectedTests;
    private String riskSummary;
    
    public String toCompactSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Impact Analysis: %s\n\n", symbolId));
        
        sb.append(String.format("Direct Impact:\n"));
        sb.append(String.format("  • Direct callers: %d\n", 
            directCallers != null ? directCallers.size() : 0));
        sb.append(String.format("  • Methods called: %d\n", 
            callees != null ? callees.size() : 0));
        
        if (directCallers != null && !directCallers.isEmpty()) {
            sb.append("\nDirect Callers:\n");
            for (CodeSymbol caller : directCallers.stream().limit(5).toList()) {
                sb.append(String.format("  ← %s.%s (%s)\n", 
                    caller.getClassName(), 
                    caller.getMethodName(), 
                    caller.getFilePath()));
            }
            if (directCallers.size() > 5) {
                sb.append(String.format("  ... and %d more\n", directCallers.size() - 5));
            }
        }
        
        sb.append(String.format("\nIndirect Impact:\n"));
        sb.append(String.format("  • Indirect callers: %d\n", 
            indirectCallers != null ? indirectCallers.size() : 0));
        sb.append(String.format("  • Dependent classes: %d\n", 
            dependentClasses != null ? dependentClasses.size() : 0));
        sb.append(String.format("  • Affected APIs: %d\n", 
            affectedApis != null ? affectedApis.size() : 0));
        sb.append(String.format("  • Affected tests: %d\n", 
            affectedTests != null ? affectedTests.size() : 0));
        
        if (riskSummary != null && !riskSummary.isBlank()) {
            sb.append("\n").append(riskSummary);
        }
        
        return sb.toString();
    }
}

package org.example.springaimcp.graph.runner;

import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.service.RelationshipReconciliationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Example usage of the Relationship Reconciliation Job.
 * 
 * This file demonstrates different ways to run the reconciliation job:
 * 
 * 1. After repository ingestion (automatic)
 * 1b. After all repositories ingestion (global CALLS reconciliation)
 * 2. As a scheduled job (cron)
 * 3. Via REST endpoint (on-demand)
 * 4. Via command line (manual)
 */
@SpringBootApplication
@Slf4j
public class ReconciliationUsageExample {

    /**
     * Example 1: Run reconciliation immediately after ingestion.
     * This is the most common use case.
     */
    public void afterIngestionExample(
        RelationshipReconciliationService reconciliationService,
        String repository,
        String module
    ) {
        log.info("Ingestion complete. Running relationship reconciliation...");
        
        RelationshipReconciliationService.ReconciliationResult result = 
            reconciliationService.reconcileRelationships(repository);
        
        log.info("Reconciliation complete: {}", result);
        
        if (result.getTotalFixed() > 0) {
            log.warn("Fixed {} relationships. Consider reviewing ingestion order.", result.getTotalFixed());
        }
    }

    public void afterAllRepositoriesExample(RelationshipReconciliationService reconciliationService) {
        RelationshipReconciliationService.ReconciliationResult result =
                reconciliationService.reconcileAllRelationships();
        log.info("Global CALLS reconciliation complete: {}", result.callStats.totalCallsDiscovered);
    }

    /**
     * Example 2: Scheduled reconciliation job (runs daily at 2 AM).
     * Use this for periodic maintenance.
     */
    // @Scheduled(cron = "0 0 2 * * ?")
    public void scheduledReconciliation(RelationshipReconciliationService reconciliationService) {
        log.info("Starting scheduled reconciliation...");
        
        // Get all repositories and modules from database
        String[] repositories = {"axis-oms", "axis-utilities", "axis-common"};
        String[] modules = {"core", "api", "service"};
        
        for (String repo : repositories) {
            for (String module : modules) {
                try {
                    RelationshipReconciliationService.ReconciliationResult result = 
                        reconciliationService.reconcileRelationships(repo);
                    
                    if (result.getTotalFixed() > 0) {
                        log.warn("Fixed {} relationships in {}:{}", 
                            result.getTotalFixed(), repo, module);
                    } else {
                        log.info("No relationships to fix in {}:{}", repo, module);
                    }
                } catch (Exception e) {
                    log.error("Reconciliation failed for {}:{}", repo, module, e);
                }
            }
        }
    }

    /**
     * Example 3: REST endpoint for on-demand reconciliation.
     */
    // @RestController
    // @RequestMapping("/api/admin/reconciliation")
    public class ReconciliationController {
        
        private final RelationshipReconciliationService reconciliationService;
        
        public ReconciliationController(RelationshipReconciliationService reconciliationService) {
            this.reconciliationService = reconciliationService;
        }
        
        // @PostMapping("/{repository}/{module}")
        public Object reconcile(String repository, String module) {
            log.info("REST: Reconciliation requested for {}:{}", repository, module);
            
            RelationshipReconciliationService.ReconciliationResult result = 
                reconciliationService.reconcileRelationships(repository);
            
            return result;
        }
    }

    /**
     * Example 4: Command-line runner (manual execution).
     * Run with: java -jar app.jar --spring.profiles.active=reconcile --repo=axis-oms --module=core
     */
    @Bean
    @Profile("reconcile")
    public CommandLineRunner reconcileRunner(RelationshipReconciliationService reconciliationService) {
        return args -> {
            String repo = null;
            String module = null;
            
            // Parse command-line arguments
            for (String arg : args) {
                if (arg.startsWith("--repo=")) {
                    repo = arg.substring("--repo=".length());
                } else if (arg.startsWith("--module=")) {
                    module = arg.substring("--module=".length());
                }
            }
            
            if (repo == null || module == null) {
                log.error("Usage: --repo=<repository> --module=<module>");
                return;
            }
            
            log.info("Command-line reconciliation for {}:{}", repo, module);
            
            RelationshipReconciliationService.ReconciliationResult result = 
                reconciliationService.reconcileRelationships(repo);
            
            System.out.println("\n" + formatResult(result));
        };
    }

    /**
     * Example 5: Integration with ingestion pipeline.
     */
    public class HybridIngestionServiceExample {
        
        private final RelationshipReconciliationService reconciliationService;
        
        public HybridIngestionServiceExample(RelationshipReconciliationService reconciliationService) {
            this.reconciliationService = reconciliationService;
        }
        
        public void ingestRepository(String rootPath, String repository, String module) {
            // 1. Ingest all files
            log.info("Ingesting repository: {}", rootPath);
            // ... ingestion code ...
            
            // 2. Run reconciliation
            log.info("Running relationship reconciliation...");
            RelationshipReconciliationService.ReconciliationResult result = 
                reconciliationService.reconcileRelationships(repository);
            
            // 3. Report results
            log.info("Ingestion complete. Reconciled {} relationships.", result.getTotalFixed());
        }
    }

    /**
     * Format reconciliation result for display.
     */
    private static String formatResult(RelationshipReconciliationService.ReconciliationResult result) {
        return String.format("""
            ╔════════════════════════════════════════════════════════════════
            ║ Relationship Reconciliation Results
            ╠════════════════════════════════════════════════════════════════
            ║ Stubs Completed:           %6d
            ║ EXTENDS Fixed:             %6d
            ║ IMPLEMENTS Fixed:          %6d
            ║ USES Fixed:                %6d
            ║ CALLS Fixed:               %6d
            ║ Method Relations Fixed:    %6d
            ║ Total Calls Discovered:    %6d
            ║ Resolved Calls:            %6d
            ║ Stub Calls:                %6d
            ║ Reconciled Calls:          %6d
            ║ Ambiguous Calls:           %6d
            ║ Unresolved Calls:          %6d
            ║ Cross-Repo Calls:          %6d
            ║ Self Calls:                %6d
            ║ Remaining Stub Methods:    %6d
            ╠════════════════════════════════════════════════════════════════
            ║ Total Relationships Fixed: %6d
            ║ Execution Time:            %6d ms
            ╚════════════════════════════════════════════════════════════════
            """,
            result.stubsCompleted,
            result.extendsFixed,
            result.implementsFixed,
            result.usesFixed,
            result.callsFixed,
            result.methodRelationsFixed,
            result.callStats.totalCallsDiscovered,
            result.callStats.resolvedCalls,
            result.callStats.stubCalls,
            result.callStats.reconciledCalls,
            result.callStats.ambiguousCalls,
            result.callStats.unresolvedCalls,
            result.callStats.crossRepositoryCalls,
            result.callStats.selfCalls,
            result.callStats.remainingStubs,
            result.getTotalFixed(),
            result.totalTimeMs
        );
    }

    /**
     * Example 6: Batch reconciliation for multiple repositories.
     */
    public void batchReconciliation(RelationshipReconciliationService reconciliationService) {
        log.info("Starting batch reconciliation for all repositories...");
        
        // Define repositories and modules to reconcile
        var targets = new Object[][] {
            {"axis-oms", "core"},
            {"axis-oms", "api"},
            {"axis-utilities", "common"},
            {"axis-common", "shared"}
        };
        
        int totalFixed = 0;
        long totalTime = 0;
        
        for (Object[] target : targets) {
            String repo = (String) target[0];
            String module = (String) target[1];
            
            try {
                log.info("Reconciling {}:{}...", repo, module);
                
                RelationshipReconciliationService.ReconciliationResult result = 
                    reconciliationService.reconcileRelationships(repo);
                
                totalFixed += result.getTotalFixed();
                totalTime += result.totalTimeMs;
                
                log.info("  Fixed {} relationships in {}ms", result.getTotalFixed(), result.totalTimeMs);
                
            } catch (Exception e) {
                log.error("Failed to reconcile {}:{}", repo, module, e);
            }
        }
        
        log.info("Batch reconciliation complete: {} total relationships fixed in {}ms", 
            totalFixed, totalTime);
    }
}

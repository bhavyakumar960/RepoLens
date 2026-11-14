package org.example.springaimcp.graph.runner;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.service.RelationshipReconciliationService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Command-line runner for executing relationship reconciliation job.
 * 
 * Usage:
 * 1. As a Spring Boot CommandLineRunner (disabled by default):
 *    Set spring.profiles.active=reconciliation to enable
 * 
 * 2. Via main method:
 *    Run this class directly with arguments: <repository>
 * 
 * 3. Programmatically:
 *    Call reconciliationService.reconcileRelationships(repository)
 * 
 * Example:
 *   java -jar app.jar --spring.profiles.active=reconciliation --repository=axis-oms
 *   
 * Or from IDE:
 *   Run main method with program arguments: axis-oms
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RelationshipReconciliationRunner implements CommandLineRunner {

    private final RelationshipReconciliationService reconciliationService;
    private final ApplicationContext applicationContext;

    /**
     * Main entry point for running reconciliation as a standalone job.
     * 
     * Arguments:
     *   args[0] - repository name (required)
     * 
     * Example:
     *   RelationshipReconciliationRunner.main(new String[]{"axis-oms"})
     */
    public static void main(String[] args) {
       /* if (args.length < 1) {
            System.err.println("Usage: RelationshipReconciliationRunner <repository>");
            System.err.println("Example: RelationshipReconciliationRunner axis-oms");
            System.exit(1);
        }*/

        String repository = args.length > 0 ? args[0] : "axis-oms";

        log.info("╔════════════════════════════════════════════════════════════════");
        log.info("║ Relationship Reconciliation Job");
        log.info("║ Repository: {}", repository);
        log.info("╚════════════════════════════════════════════════════════════════");

        // Run Spring Boot application to get the service bean
        System.setProperty("spring.profiles.active", "reconciliation");
        SpringApplication app = new SpringApplication(RelationshipReconciliationRunner.class);
        app.setAdditionalProfiles("reconciliation");
        
        ApplicationContext context = app.run(args);
        
        RelationshipReconciliationService service = context.getBean(RelationshipReconciliationService.class);
        
        try {
            RelationshipReconciliationService.ReconciliationResult result = 
                service.reconcileRelationships(repository);
            
            printResult(result, repository);
            
            System.exit(0);
        } catch (Exception e) {
            log.error("Reconciliation failed", e);
            System.exit(1);
        }
    }

    /**
     * CommandLineRunner implementation.
     * Only runs when 'reconciliation' profile is active.
     */
    @Override
    public void run(String... args) throws Exception {
        // Only run if reconciliation profile is active
        String[] profiles = applicationContext.getEnvironment().getActiveProfiles();
        boolean isReconciliationProfile = false;
        for (String profile : profiles) {
            if ("reconciliation".equals(profile)) {
                isReconciliationProfile = true;
                break;
            }
        }

        if (!isReconciliationProfile) {
            log.debug("Reconciliation runner not active (profile: reconciliation)");
            return;
        }

        if (args.length < 1) {
            log.error("Usage: --repository=<repo>");
            return;
        }

        String repository = null;

        // Parse arguments
        for (String arg : args) {
            if (arg.startsWith("--repository=")) {
                repository = arg.substring("--repository=".length());
            }
        }

        if (repository == null) {
            log.error("--repository argument is required");
            return;
        }

        log.info("╔════════════════════════════════════════════════════════════════");
        log.info("║ Relationship Reconciliation Job (CommandLineRunner)");
        log.info("║ Repository: {}", repository);
        log.info("╚════════════════════════════════════════════════════════════════");

        RelationshipReconciliationService.ReconciliationResult result = 
            reconciliationService.reconcileRelationships(repository);

        printResult(result, repository);
    }

    /**
     * Print formatted reconciliation results.
     */
    private static void printResult(
        RelationshipReconciliationService.ReconciliationResult result,
        String repository
    ) {
        System.out.println("\n╔════════════════════════════════════════════════════════════════");
        System.out.println("║ Relationship Reconciliation Results");
        System.out.println("║ Repository: " + repository);
        System.out.println("╠════════════════════════════════════════════════════════════════");
        System.out.println("║ Stubs Completed:           " + String.format("%6d", result.stubsCompleted));
        System.out.println("║ EXTENDS Fixed:             " + String.format("%6d", result.extendsFixed));
        System.out.println("║ IMPLEMENTS Fixed:          " + String.format("%6d", result.implementsFixed));
        System.out.println("║ USES Fixed:                " + String.format("%6d", result.usesFixed));
        System.out.println("║ CALLS Fixed:               " + String.format("%6d", result.callsFixed));
        System.out.println("║ Method Relations Fixed:    " + String.format("%6d", result.methodRelationsFixed));
        System.out.println("║ Total Calls Discovered:    " + String.format("%6d", result.callStats.totalCallsDiscovered));
        System.out.println("║ Resolved Calls:            " + String.format("%6d", result.callStats.resolvedCalls));
        System.out.println("║ Stub Calls:                " + String.format("%6d", result.callStats.stubCalls));
        System.out.println("║ Reconciled Calls:          " + String.format("%6d", result.callStats.reconciledCalls));
        System.out.println("║ Ambiguous Calls:           " + String.format("%6d", result.callStats.ambiguousCalls));
        System.out.println("║ Unresolved Calls:          " + String.format("%6d", result.callStats.unresolvedCalls));
        System.out.println("║ Cross-Repo Calls:          " + String.format("%6d", result.callStats.crossRepositoryCalls));
        System.out.println("║ Self Calls:                " + String.format("%6d", result.callStats.selfCalls));
        System.out.println("║ Remaining Stub Methods:    " + String.format("%6d", result.callStats.remainingStubs));
        System.out.println("╠════════════════════════════════════════════════════════════════");
        System.out.println("║ Total Relationships Fixed: " + String.format("%6d", result.getTotalFixed()));
        System.out.println("║ Execution Time:            " + String.format("%6d", result.totalTimeMs) + " ms");
        System.out.println("╚════════════════════════════════════════════════════════════════\n");

        if (result.getTotalFixed() == 0) {
            System.out.println("✓ No relationships needed reconciliation - graph is consistent!");
        } else {
            System.out.println("✓ Successfully reconciled " + result.getTotalFixed() + " relationships!");
        }
    }

    /**
     * Utility method for programmatic execution.
     * Can be called from tests or other services.
     */
    public RelationshipReconciliationService.ReconciliationResult executeReconciliation(
        String repository
    ) {
        log.info("Executing reconciliation programmatically: repository={}", repository);
        return reconciliationService.reconcileRelationships(repository);
    }
}

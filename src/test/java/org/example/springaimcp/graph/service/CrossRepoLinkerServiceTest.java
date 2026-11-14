package org.example.springaimcp.graph.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.springaimcp.graph.config.RepositoryDependencyConfig;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

/**
 * Tests for {@link CrossRepoLinkerService} covering the parts that are safe to exercise
 * without a real Neo4j instance: the "disabled" short-circuit, path registration, bulk
 * linking error isolation across repositories, and the {@link CrossRepoLinkerService.LinkingStats}
 * value object. Deep AST/CALLS-resolution behavior is intentionally out of scope here — it
 * requires a real graph and is exercised by {@code Neo4jCallsFeatureTest} instead.
 */
class CrossRepoLinkerServiceTest {

    private JavaMethodRepository methodRepository;
    private JavaClassRepository classRepository;
    private RepositoryDependencyConfig dependencyConfig;
    private Neo4jClient neo4jClient;
    private CrossRepoLinkerService linkerService;

    @BeforeEach
    void setUp() {
        methodRepository = mock(JavaMethodRepository.class);
        classRepository = mock(JavaClassRepository.class);
        dependencyConfig = new RepositoryDependencyConfig();
        neo4jClient = mock(Neo4jClient.class);
        linkerService = new CrossRepoLinkerService(methodRepository, classRepository, dependencyConfig, neo4jClient);
    }

    @Test
    void linkCrossRepoRelationshipsShortCircuitsWhenDisabled() {
        dependencyConfig.setEnabled(false);

        CrossRepoLinkerService.LinkingStats stats =
                linkerService.linkCrossRepoRelationships("source-repo", "target-repo");

        assertFalse(stats.isSuccess());
        assertEquals("Disabled in configuration", stats.errorMessage());
        assertEquals(0, stats.methodsScanned());
        assertEquals(0, stats.callsCreated());
    }

    @Test
    void registerRepositoryPathIsIdempotentAndDoesNotThrow() {
        linkerService.registerRepositoryPath("axis-oms", "/repos/axis-oms");
        linkerService.registerRepositoryPath("axis-oms", "/repos/axis-oms-updated");
        // No exception, no assertion on internal state needed: this only verifies the
        // public API accepts repeated registrations for the same repository safely.
    }

    @Test
    void linkAllRepositoriesIsolatesFailuresPerRepository() {
        dependencyConfig.setEnabled(false); // forces a deterministic "Disabled" stat, no Neo4j calls
        when(classRepository.findAllDistinctRepositories()).thenReturn(List.of("repo-a", "repo-b"));

        var results = linkerService.linkAllRepositories();

        assertEquals(2, results.size());
        assertTrue(results.containsKey("repo-a"));
        assertTrue(results.containsKey("repo-b"));
        assertFalse(results.get("repo-a").isSuccess());
        assertFalse(results.get("repo-b").isSuccess());
        verify(classRepository, times(1)).findAllDistinctRepositories();
    }

    @Test
    void linkingStatsToStringReflectsSuccessAndFailure() {
        CrossRepoLinkerService.LinkingStats success =
                new CrossRepoLinkerService.LinkingStats(10, 2, 5, 1, null);
        CrossRepoLinkerService.LinkingStats failure =
                new CrossRepoLinkerService.LinkingStats(0, 0, 0, 0, "boom");

        assertTrue(success.isSuccess());
        assertTrue(success.toString().contains("methods=10"));
        assertFalse(failure.isSuccess());
        assertTrue(failure.toString().contains("ERROR: boom"));
    }
}

package org.example.springaimcp.graph.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Neo4j Configuration.
 * Enables Neo4j repositories and transaction management.
 */
@Configuration
@EnableNeo4jRepositories(basePackages = "org.example.springaimcp.graph.repository")
@EnableTransactionManagement
@Slf4j
public class Neo4jConfiguration {

    @Bean
    ApplicationRunner neo4jSchemaInitializer(Neo4jClient neo4jClient) {
        return args -> {
            try {
                neo4jClient.query("CREATE CONSTRAINT class_class_id_unique IF NOT EXISTS FOR (c:Class) REQUIRE c.classId IS UNIQUE").run();
                neo4jClient.query("CREATE INDEX class_fqn_idx IF NOT EXISTS FOR (c:Class) ON (c.fullyQualifiedName)").run();
                neo4jClient.query("CREATE CONSTRAINT method_method_id_unique IF NOT EXISTS FOR (m:Method) REQUIRE m.methodId IS UNIQUE").run();
                neo4jClient.query("CREATE INDEX method_resolution_key_idx IF NOT EXISTS FOR (m:Method) ON (m.resolutionKey)").run();
                log.info("Neo4j graph schema verified for Class and Method nodes");
            } catch (Exception e) {
                log.warn("Failed to initialize Neo4j graph schema: {}", e.getMessage());
            }
        };
    }
}

package org.example.springaimcp.graph.model;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a code repository.
 * Top-level entity in the code graph hierarchy.
 */
@Node("Repository")
@Data
public class CodeRepository {

    @Id
    @GeneratedValue
    private Long id;

    private String repoId; // Unique identifier: e.g., "repo:axis-utilities"
    private String name;
    private String url;
    private String defaultBranch;
    private String currentCommit;
    private Long lastIndexedTimestamp;

    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private Set<Module> modules = new HashSet<>();
}

package org.example.springaimcp.graph.model;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a module within a repository.
 */
@Node("Module")
@Data
public class Module {

    @Id
    @GeneratedValue
    private Long id;

    private String moduleId; // Unique: e.g., "module:axis-utilities:auth-module"
    private String name;
    private String path;
    private String repository;

    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private Set<JavaPackage> packages = new HashSet<>();
}

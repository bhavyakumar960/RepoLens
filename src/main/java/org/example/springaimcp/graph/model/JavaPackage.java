package org.example.springaimcp.graph.model;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a Java package.
 */
@Node("Package")
@Data
public class JavaPackage {

    @Id
    @GeneratedValue
    private Long id;

    private String packageId; // Unique: e.g., "package:axis-utilities:com.axis.auth"
    private String fullyQualifiedName;
    private String repository;
    private String module;

    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private Set<JavaFile> files = new HashSet<>();
}

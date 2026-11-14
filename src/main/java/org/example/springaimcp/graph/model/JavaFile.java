package org.example.springaimcp.graph.model;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a Java source file.
 */
@Node("File")
@Data
public class JavaFile {

    @Id
    @GeneratedValue
    private Long id;

    private String fileId; // Unique: e.g., "file:axis-utilities:src/main/java/com/axis/Auth.java"
    private String path;
    private String repository;
    private String module;
    private String packageName;
    private String fileHash; // For incremental indexing
    private String branch;
    private String commit;
    private Long lastModifiedTimestamp;

    @Relationship(type = "DECLARES", direction = Relationship.Direction.OUTGOING)
    private Set<JavaClass> classes = new HashSet<>();
}

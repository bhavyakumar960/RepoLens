package org.example.springaimcp.graph.model;

import lombok.Data;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a Java field.
 */
@Node("Field")
@Data
public class JavaField {

    @Id
    @GeneratedValue
    private Long id;

    private String fieldId; // Unique: e.g., "field:axis-utilities:com.axis.auth.AuthService.userRepository"
    private String name;
    private String type;
    private String repository;
    private String module;
    private String packageName;
    private String className;
    private String filePath;
    private Integer lineNumber;
    private String branch;
    private String commit;

    // Spring annotations
    private Set<String> springAnnotations = new HashSet<>(); // e.g., @Autowired, @Value

    @Relationship(type = "HAS_TYPE", direction = Relationship.Direction.OUTGOING)
    private JavaClass fieldType;
}

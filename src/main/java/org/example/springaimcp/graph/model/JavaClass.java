package org.example.springaimcp.graph.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j node representing a Java class, interface, or enum.
 */
@Node("Class")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class JavaClass {

    @Id
    @GeneratedValue
    private Long id;

    @EqualsAndHashCode.Include
    @ToString.Include
    private String classId; // Unique: e.g., "class:axis-utilities:com.axis.auth.AuthService"
    @ToString.Include
    private String fullyQualifiedName;
    @ToString.Include
    private String simpleName;
    private String type; // CLASS, INTERFACE, ENUM, ABSTRACT_CLASS
    private String repository;
    private String module;
    private String packageName;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private String branch;
    private String commit;
    private String nodeStatus;
    private String resolutionKey;

    // Spring annotations
    private Set<String> springAnnotations = new HashSet<>(); // e.g., @RestController, @Service

    @Relationship(type = "HAS_METHOD", direction = Relationship.Direction.OUTGOING)
    private Set<JavaMethod> methods = new HashSet<>();

    @Relationship(type = "HAS_FIELD", direction = Relationship.Direction.OUTGOING)
    private Set<JavaField> fields = new HashSet<>();

    @Relationship(type = "EXTENDS", direction = Relationship.Direction.OUTGOING)
    private Set<JavaClass> extendedClasses = new HashSet<>();

    @Relationship(type = "IMPLEMENTS", direction = Relationship.Direction.OUTGOING)
    private Set<JavaClass> implementedInterfaces = new HashSet<>();

    @Relationship(type = "USES", direction = Relationship.Direction.OUTGOING)
    private Set<JavaClass> usedClasses = new HashSet<>();
    
    @Relationship(type = "INJECTS", direction = Relationship.Direction.OUTGOING)
    private Set<JavaClass> injectedClasses = new HashSet<>();
}

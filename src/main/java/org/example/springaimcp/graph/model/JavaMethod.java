package org.example.springaimcp.graph.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Neo4j node representing a Java method.
 */
@Node("Method")
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class JavaMethod {

    @Id
    @GeneratedValue
    private Long id;

    @EqualsAndHashCode.Include
    @ToString.Include
    private String methodId; // Unique: e.g., "method:axis-utilities:com.axis.auth.AuthService.authenticate(String,String)"
    @ToString.Include
    private String name;
    private String signature; // Full signature with parameters
    private String fullyQualifiedSignature;
    private String returnType;
    private List<String> parameterTypes = new ArrayList<>();
    private String repository;
    private String module;
    private String packageName;
    private String className;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private String branch;
    private String commit;
    private String nodeStatus;
    private String resolutionKey;

    // Spring annotations
    private Set<String> springAnnotations = new HashSet<>(); // e.g., @GetMapping, @PostMapping
    
    // API-specific metadata
    private String httpMethod; // GET, POST, PUT, DELETE, PATCH
    private String apiPath;

    @Relationship(type = "ACCEPTS", direction = Relationship.Direction.OUTGOING)
    private Set<JavaClass> parameterClasses = new HashSet<>();

    @Relationship(type = "RETURNS", direction = Relationship.Direction.OUTGOING)
    private JavaClass returnClass;

    // Note: TESTED_BY relationships are created separately via Cypher queries
    // to avoid eager loading issues during entity save operations.
    // Use JavaMethodRepository.findTestsForMethod() to query test relationships.
}

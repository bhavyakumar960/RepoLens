package org.example.springaimcp.graph.repository;

import org.example.springaimcp.graph.model.JavaPackage;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JavaPackageRepository extends Neo4jRepository<JavaPackage, Long> {
    Optional<JavaPackage> findByPackageId(String packageId);
}

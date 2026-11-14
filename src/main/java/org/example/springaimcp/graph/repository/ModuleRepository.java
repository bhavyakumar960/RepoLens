package org.example.springaimcp.graph.repository;

import org.example.springaimcp.graph.model.Module;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ModuleRepository extends Neo4jRepository<Module, Long> {
    Optional<Module> findByModuleId(String moduleId);
}

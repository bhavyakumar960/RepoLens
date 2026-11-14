package org.example.springaimcp.codeintel.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.springaimcp.codeintel.config.CodeIntelProperties;
import org.example.springaimcp.codeintel.model.CodeSymbol;
import org.example.springaimcp.codeintel.model.SymbolLookupRequest;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.junit.jupiter.api.Test;

class Neo4jSymbolServiceTest {

    @Test
    void findSymbolCandidatesFallsBackToPartialClassNameSearch() {
        JavaClassRepository classRepository = mock(JavaClassRepository.class);
        JavaMethodRepository methodRepository = mock(JavaMethodRepository.class);
        SymbolResolutionService symbolResolutionService = mock(SymbolResolutionService.class);
        CodeIntelProperties properties = new CodeIntelProperties();

        JavaClass javaClass = new JavaClass();
        javaClass.setClassId("class:repo-a:com.example.MaximusService");
        javaClass.setSimpleName("MaximusService");
        javaClass.setFullyQualifiedName("com.example.MaximusService");
        javaClass.setRepository("repo-a");
        javaClass.setModule("core");
        javaClass.setPackageName("com.example");

        when(classRepository.searchByName("maximus", properties.getMaxResults())).thenReturn(List.of());
        when(classRepository.searchByPartialName("maximus", properties.getMaxResults())).thenReturn(List.of(javaClass));

        Neo4jSymbolService service = new Neo4jSymbolService(
                classRepository,
                methodRepository,
                properties,
                symbolResolutionService
        );

        List<CodeSymbol> candidates = service.findSymbolCandidates(SymbolLookupRequest.builder()
                .name("maximus")
                .symbolType(CodeSymbol.SymbolType.CLASS)
                .build());

        assertEquals(1, candidates.size());
        assertEquals("com.example.MaximusService", candidates.get(0).getFullyQualifiedName());
        assertTrue(candidates.get(0).getRelevanceScore() > 0);
        verify(classRepository).searchByName("maximus", properties.getMaxResults());
        verify(classRepository).searchByPartialName("maximus", properties.getMaxResults());
    }
}

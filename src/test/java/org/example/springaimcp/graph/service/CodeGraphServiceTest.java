package org.example.springaimcp.graph.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for {@link CodeGraphService} methodId resolution.
 * Reproduces a reported issue where a caller supplies a methodId built with
 * simple (unqualified) parameter type names, e.g.
 * {@code method:axis-utilities:com.example.MaximusServiceClient.submitApplication(CreateSubmitApplicationRequestBody)}
 * while the graph stores the fully qualified parameter type,
 * {@code com.example.request.CreateSubmitApplicationRequestBody}.
 */
class CodeGraphServiceTest {

    private static final String REPOSITORY = "axis-utilities";
    private static final String CLASS_NAME = "com.axisbank.client.enc.maximus.fdcc.MaximusServiceClient";
    private static final String METHOD_NAME = "submitApplication";
    private static final String FQN_PARAM = "com.axisbank.client.enc.maximus.fdcc.request.CreateSubmitApplicationRequestBody";
    private static final String SIMPLE_PARAM_METHOD_ID =
            "method:" + REPOSITORY + ":" + CLASS_NAME + "." + METHOD_NAME + "(CreateSubmitApplicationRequestBody)";
    private static final String FQN_METHOD_ID =
            "method:" + REPOSITORY + ":" + CLASS_NAME + "." + METHOD_NAME + "(" + FQN_PARAM + ")";

    @Test
    void findCallersOfMethodResolvesSimpleParameterTypeNameToStoredMethodId() {
        JavaClassRepository classRepository = mock(JavaClassRepository.class);
        JavaMethodRepository methodRepository = mock(JavaMethodRepository.class);
        CodeGraphService service = new CodeGraphService(classRepository, methodRepository);

        JavaMethod storedMethod = new JavaMethod();
        storedMethod.setMethodId(FQN_METHOD_ID);
        storedMethod.setParameterTypes(List.of(FQN_PARAM));

        JavaMethod caller = new JavaMethod();
        caller.setMethodId("method:" + REPOSITORY + ":com.example.Caller.invoke()");

        when(methodRepository.findCallers(SIMPLE_PARAM_METHOD_ID)).thenReturn(List.of());
        when(methodRepository.findByRepositoryAndClassNameAndName(REPOSITORY, CLASS_NAME, METHOD_NAME))
                .thenReturn(List.of(storedMethod));
        when(methodRepository.findCallers(FQN_METHOD_ID)).thenReturn(List.of(caller));

        List<JavaMethod> callers = service.findCallersOfMethod(SIMPLE_PARAM_METHOD_ID);

        assertEquals(1, callers.size());
        assertEquals(caller.getMethodId(), callers.get(0).getMethodId());
    }

    @Test
    void findCalledByMethodResolvesSimpleParameterTypeNameToStoredMethodId() {
        JavaClassRepository classRepository = mock(JavaClassRepository.class);
        JavaMethodRepository methodRepository = mock(JavaMethodRepository.class);
        CodeGraphService service = new CodeGraphService(classRepository, methodRepository);

        JavaMethod storedMethod = new JavaMethod();
        storedMethod.setMethodId(FQN_METHOD_ID);
        storedMethod.setParameterTypes(List.of(FQN_PARAM));

        JavaMethod callee = new JavaMethod();
        callee.setMethodId("method:" + REPOSITORY + ":com.example.Callee.helper()");

        when(methodRepository.findCalledMethods(SIMPLE_PARAM_METHOD_ID)).thenReturn(List.of());
        when(methodRepository.findByRepositoryAndClassNameAndName(REPOSITORY, CLASS_NAME, METHOD_NAME))
                .thenReturn(List.of(storedMethod));
        when(methodRepository.findCalledMethods(FQN_METHOD_ID)).thenReturn(List.of(callee));

        List<JavaMethod> called = service.findCalledByMethod(SIMPLE_PARAM_METHOD_ID);

        assertEquals(1, called.size());
        assertEquals(callee.getMethodId(), called.get(0).getMethodId());
    }

    @Test
    void findCallersOfMethodReturnsEmptyWhenMultipleCandidatesMatchAmbiguously() {
        JavaClassRepository classRepository = mock(JavaClassRepository.class);
        JavaMethodRepository methodRepository = mock(JavaMethodRepository.class);
        CodeGraphService service = new CodeGraphService(classRepository, methodRepository);

        JavaMethod overloadOne = new JavaMethod();
        overloadOne.setMethodId(FQN_METHOD_ID);
        overloadOne.setParameterTypes(List.of(FQN_PARAM));

        JavaMethod overloadTwo = new JavaMethod();
        overloadTwo.setMethodId("method:" + REPOSITORY + ":" + CLASS_NAME + "." + METHOD_NAME
                + "(com.other.pkg.CreateSubmitApplicationRequestBody)");
        overloadTwo.setParameterTypes(List.of("com.other.pkg.CreateSubmitApplicationRequestBody"));

        when(methodRepository.findCallers(SIMPLE_PARAM_METHOD_ID)).thenReturn(List.of());
        when(methodRepository.findByRepositoryAndClassNameAndName(REPOSITORY, CLASS_NAME, METHOD_NAME))
                .thenReturn(List.of(overloadOne, overloadTwo));

        List<JavaMethod> callers = service.findCallersOfMethod(SIMPLE_PARAM_METHOD_ID);

        assertTrue(callers.isEmpty());
    }
}

package org.example.springaimcp.graph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.example.springaimcp.graph.util.GraphNodeIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for querying the Neo4j code graph.
 * Provides high-level operations for code relationship analysis.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeGraphService {

    private final JavaClassRepository classRepository;
    private final JavaMethodRepository methodRepository;

    private static final Pattern METHOD_ID_PATTERN =
            Pattern.compile("^method:(?<repo>[^:]+):(?<rest>.+)\\((?<params>[^)]*)\\)$");

    /**
     * Find all classes that implement a given interface.
     */
    @Transactional(readOnly = true)
    public List<JavaClass> findImplementations(String interfaceClassId) {
        return classRepository.findSubtypes(interfaceClassId);
    }

    /**
     * Find all methods that call a given method.
     * Falls back to resolving the methodId (e.g. simple parameter type names
     * instead of fully qualified ones) when an exact match isn't found.
     */
    @Transactional(readOnly = true)
    public List<JavaMethod> findCallersOfMethod(String methodId) {
        List<JavaMethod> callers = methodRepository.findCallers(methodId);
        if (!callers.isEmpty()) {
            return callers;
        }
        return resolveMethod(methodId)
                .map(resolved -> methodRepository.findCallers(resolved.method().getMethodId()))
                .orElse(callers);
    }

    /**
     * Find all methods called by a given method.
     * Falls back to resolving the methodId (e.g. simple parameter type names
     * instead of fully qualified ones) when an exact match isn't found.
     */
    @Transactional(readOnly = true)
    public List<JavaMethod> findCalledByMethod(String methodId) {
        List<JavaMethod> called = methodRepository.findCalledMethods(methodId);
        if (!called.isEmpty()) {
            return called;
        }
        return resolveMethod(methodId)
                .map(resolved -> methodRepository.findCalledMethods(resolved.method().getMethodId()))
                .orElse(called);
    }

    /**
     * Result of resolving a possibly-inexact/mis-repository methodId to the actual
     * stored method node. {@code crossRepository} is {@code true} when the match was
     * only found by ignoring the repository supplied in the original methodId
     * (i.e. the caller named the wrong repository), so callers can surface a note
     * telling the user which repository the method actually lives in.
     */
    public record ResolvedMethod(JavaMethod method, boolean crossRepository, String requestedRepository) {
    }

    /**
     * Resolves a possibly-inexact methodId (e.g. built with simple parameter type
     * names such as {@code CreateSubmitApplicationRequestBody} instead of the fully
     * qualified {@code com.axisbank...CreateSubmitApplicationRequestBody} used in the
     * graph) to the actual stored method node. Also falls back to a cross-repository
     * search when no match exists in the requested repository, so a wrong repository
     * name in the supplied methodId doesn't silently return "no results". Returns
     * empty if the methodId can't be parsed or no unambiguous match is found.
     */
    public Optional<ResolvedMethod> resolveMethod(String methodId) {
        if (methodId == null) {
            return Optional.empty();
        }
        Matcher matcher = METHOD_ID_PATTERN.matcher(methodId);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String repository = matcher.group("repo");
        String rest = matcher.group("rest");
        int lastDot = rest.lastIndexOf('.');
        if (lastDot < 0) {
            return Optional.empty();
        }
        String className = rest.substring(0, lastDot);
        String methodName = rest.substring(lastDot + 1);
        String paramsRaw = matcher.group("params");
        List<String> requestedParams = paramsRaw.isBlank()
                ? List.of()
                : Arrays.asList(paramsRaw.split(","));

        List<JavaMethod> candidates = methodRepository.findByRepositoryAndClassNameAndName(
                repository, className, methodName);

        List<JavaMethod> matches = candidates.stream()
                .filter(candidate -> parameterTypesMatchBySimpleName(candidate.getParameterTypes(), requestedParams))
                .toList();

        if (matches.size() == 1) {
            return Optional.of(new ResolvedMethod(matches.get(0), false, repository));
        }
        if (!matches.isEmpty()) {
            // Ambiguous within the requested repository - don't guess.
            return Optional.empty();
        }

        // Nothing in the requested repository at all - the caller may have named the
        // wrong repository. Try again across every repository before giving up.
        List<JavaMethod> crossRepoCandidates = methodRepository.findByClassNameAndName(className, methodName);
        List<JavaMethod> crossRepoMatches = crossRepoCandidates.stream()
                .filter(candidate -> parameterTypesMatchBySimpleName(candidate.getParameterTypes(), requestedParams))
                .toList();

        if (crossRepoMatches.size() == 1) {
            return Optional.of(new ResolvedMethod(crossRepoMatches.get(0), true, repository));
        }
        return Optional.empty();
    }

    private boolean parameterTypesMatchBySimpleName(List<String> actualParams, List<String> requestedParams) {
        List<String> actual = actualParams == null ? List.of() : actualParams;
        if (actual.size() != requestedParams.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            String actualSimple = GraphNodeIdentity.simpleName(actual.get(i));
            String requestedSimple = GraphNodeIdentity.simpleName(requestedParams.get(i).trim());
            if (actualSimple == null ? requestedSimple != null : !actualSimple.equals(requestedSimple)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Find all API endpoints (REST controllers).
     */
    @Transactional(readOnly = true)
    public List<JavaMethod> findAllApiEndpoints() {
        return methodRepository.findAllApiEndpoints();
    }

    /**
     * Find all Spring services.
     */
    @Transactional(readOnly = true)
    public List<JavaClass> findAllServices() {
        return classRepository.findBySpringAnnotation("@Service");
    }

    /**
     * Find all Spring controllers.
     */
    @Transactional(readOnly = true)
    public List<JavaClass> findAllControllers() {
        var restControllers = classRepository.findBySpringAnnotation("@RestController");
        var controllers = classRepository.findBySpringAnnotation("@Controller");
        restControllers.addAll(controllers);
        return restControllers;
    }

    /**
     * Find all Spring repositories.
     */
    @Transactional(readOnly = true)
    public List<JavaClass> findAllRepositories() {
        return classRepository.findBySpringAnnotation("@Repository");
    }

    /**
     * Find test coverage for a method.
     */
    @Transactional(readOnly = true)
    public List<JavaMethod> findTestsForMethod(String methodId) {
        return methodRepository.findTestsForMethod(methodId);
    }

    /**
     * Find the call chain starting from a method.
     */
    @Transactional(readOnly = true)
    public List<Object> findCallChain(String startMethodId) {
        return methodRepository.findCallChain(startMethodId);
    }

    /**
     * Find a class by its unique ID.
     */
    @Transactional(readOnly = true)
    public Optional<JavaClass> findClassById(String classId) {
        return classRepository.findByClassId(classId);
    }

    /**
     * Find a method by its unique ID.
     */
    @Transactional(readOnly = true)
    public Optional<JavaMethod> findMethodById(String methodId) {
        return methodRepository.findByMethodId(methodId);
    }

    /**
     * Save a class node.
     */
    @Transactional
    public JavaClass saveClass(JavaClass javaClass) {
        return classRepository.save(javaClass);
    }

    /**
     * Save a method node.
     */
    @Transactional
    public JavaMethod saveMethod(JavaMethod javaMethod) {
        return methodRepository.save(javaMethod);
    }

    /**
     * Find all classes in a repository.
     */
    @Transactional(readOnly = true)
    public List<JavaClass> findClassesByRepository(String repository) {
        return classRepository.findByRepository(repository);
    }

    /**
     * Find all methods in a repository.
     */
    @Transactional(readOnly = true)
    public List<JavaMethod> findMethodsByRepository(String repository) {
        return methodRepository.findByRepository(repository);
    }
}

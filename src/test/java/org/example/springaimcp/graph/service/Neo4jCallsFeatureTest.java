package org.example.springaimcp.graph.service;

import org.example.springaimcp.graph.config.RepositoryDependencyConfig;
import org.example.springaimcp.graph.model.CodeRepository;
import org.example.springaimcp.graph.model.JavaClass;
import org.example.springaimcp.graph.model.JavaFile;
import org.example.springaimcp.graph.model.JavaMethod;
import org.example.springaimcp.graph.model.JavaPackage;
import org.example.springaimcp.graph.model.Module;
import org.example.springaimcp.graph.repository.CodeRepositoryRepository;
import org.example.springaimcp.graph.repository.JavaClassRepository;
import org.example.springaimcp.graph.repository.JavaFileRepository;
import org.example.springaimcp.graph.repository.JavaMethodRepository;
import org.example.springaimcp.graph.repository.JavaPackageRepository;
import org.example.springaimcp.graph.repository.ModuleRepository;
import org.example.springaimcp.graph.util.GraphNodeIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.neo4j.driver.QueryRunner;
import org.neo4j.driver.summary.ResultSummary;
import org.springframework.data.neo4j.core.DatabaseSelection;
import org.springframework.data.neo4j.core.DatabaseSelectionProvider;
import org.springframework.data.neo4j.core.Neo4jClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Neo4jCallsFeatureTest {

    private static final String REPO_A = "repo-a";
    private static final String REPO_B = "repo-b";
    private static final String MODULE = "core";
    private static final String BRANCH = "main";
    private static final String COMMIT = "test-commit";

    private GraphState state;
    private Neo4jIngestionService ingestionService;
    private RelationshipReconciliationService reconciliationService;
    private JavaClassRepository classRepository;
    private JavaMethodRepository methodRepository;

    @BeforeEach
    void setUp() {
        state = new GraphState();

        CodeRepositoryRepository repositoryRepository = mock(CodeRepositoryRepository.class);
        JavaFileRepository fileRepository = mock(JavaFileRepository.class);
        classRepository = mock(JavaClassRepository.class);
        methodRepository = mock(JavaMethodRepository.class);
        JavaPackageRepository packageRepository = mock(JavaPackageRepository.class);
        ModuleRepository moduleRepository = mock(ModuleRepository.class);

        RepositoryDependencyConfig dependencyConfig = new RepositoryDependencyConfig();
        dependencyConfig.setEnabled(true);
        dependencyConfig.setDependencyMap(Map.of(REPO_A, List.of(REPO_B)));

        stubRepositoryRepository(repositoryRepository, state);
        stubFileRepository(fileRepository, state);
        stubClassRepository(classRepository, state);
        stubMethodRepository(methodRepository, state);
        stubPackageRepository(packageRepository, state);
        stubModuleRepository(moduleRepository, state);

        Neo4jClient neo4jClient = new TestNeo4jClient(state);

        ingestionService = new Neo4jIngestionService(
                repositoryRepository,
                fileRepository,
                classRepository,
                methodRepository,
                packageRepository,
                moduleRepository,
                dependencyConfig,
                neo4jClient
        );
        reconciliationService = new RelationshipReconciliationService(
                classRepository,
                methodRepository,
                neo4jClient
        );
    }

    @Test
    void ingestsSameClassFieldConstructorStaticInheritedAndInterfaceCalls() {
        ingestRepoA("OrchestratorService.java");
        ingestRepoA("BaseService.java");
        ingestRepoA("DependencyService.java");
        ingestRepoA("Handler.java");
        ingestRepoA("HandlerImpl.java");
        ingestRepoA("MultiHandler.java");
        ingestRepoA("FirstMultiHandler.java");
        ingestRepoA("SecondMultiHandler.java");
        ingestRepoA("Utility.java");
        ingestRepoA("ChainFactory.java");
        ingestRepoA("ChainStep.java");

        RelationshipReconciliationService.ReconciliationResult result =
                reconciliationService.reconcileRelationships(REPO_A);

        String sourceMethodId = methodId(REPO_A, "com.example.calls.OrchestratorService", "orchestrate");
        Map<String, CallRelationshipState> callDetails = state.findCallsFrom(sourceMethodId);

        assertThat(callDetails).containsKeys(
                methodId(REPO_A, "com.example.calls.OrchestratorService", "helper"),
                methodId(REPO_A, "com.example.calls.OrchestratorService", "recursive"),
                methodId(REPO_A, "com.example.calls.DependencyService", "process", "java.lang.String"),
                methodId(REPO_A, "com.example.calls.DependencyService", "process", "int"),
                methodId(REPO_A, "com.example.calls.BaseService", "audit", "java.lang.String"),
                methodId(REPO_A, "com.example.calls.Utility", "validate", "java.lang.String")
        );

        assertThat(callDetails.get(methodId(REPO_A, "com.example.calls.OrchestratorService", "helper")).resolutionType)
                .isEqualTo("SAME_CLASS");
        assertThat(callDetails.get(methodId(REPO_A, "com.example.calls.OrchestratorService", "recursive")).resolutionStatus)
                .isEqualTo("RESOLVED");
        assertThat(callDetails.get(methodId(REPO_A, "com.example.calls.DependencyService", "process", "java.lang.String")).resolutionType)
                .isEqualTo("CONSTRUCTOR_INJECTION");
        assertThat(callDetails.get(methodId(REPO_A, "com.example.calls.BaseService", "audit", "java.lang.String")).resolutionType)
                .isEqualTo("INHERITED");
        assertThat(callDetails.get(methodId(REPO_A, "com.example.calls.Utility", "validate", "java.lang.String")).resolutionType)
                .isEqualTo("STATIC");

        assertThat(callDetails.values().stream()
                .filter(rel -> "INTERFACE".equals(rel.resolutionType))
                .map(rel -> rel.resolutionStatus))
                .contains("RESOLVED", "AMBIGUOUS");
        assertThat(result.callStats.selfCalls).isGreaterThanOrEqualTo(1);
        assertThat(result.callStats.ambiguousCalls).isGreaterThanOrEqualTo(1);
    }

    @Test
    void mergesDuplicateCallSitesAndRemainsIdempotent() {
        ingestRepoA("FieldCaller.java");
        ingestRepoA("DependencyService.java");

        reconciliationService.reconcileRelationships(REPO_A);
        ingestRepoA("FieldCaller.java");

        String sourceMethodId = methodId(REPO_A, "com.example.calls.FieldCaller", "callField");
        CallRelationshipState relationship = state.findCallsFrom(sourceMethodId).values().iterator().next();

        assertThat(state.findCallsFrom(sourceMethodId)).hasSize(1);
        assertThat(relationship.targetMethodId)
                .isEqualTo(methodId(REPO_A, "com.example.calls.DependencyService", "process", "java.lang.String"));
        assertThat(relationship.invocationCount).isEqualTo(2);
        assertThat(relationship.callSites).containsExactly(7, 8);
        assertThat(relationship.resolutionType).isEqualTo("FIELD");
    }

    @Test
    void reconcilesCrossRepositoryStubsWhenTargetRepositoryArrivesLater() {
        ingestRepoA("CrossRepoCaller.java");
        reconciliationService.reconcileRelationships(REPO_A);

        String sourceMethodId = methodId(REPO_A, "com.example.calls.CrossRepoCaller", "callExternal");
        assertThat(state.findCallsFrom(sourceMethodId).values()).singleElement()
                .satisfies(rel -> assertThat(rel.resolutionStatus).isEqualTo("STUB"));

        ingestRepoB("ExternalService.java");
        RelationshipReconciliationService.ReconciliationResult result = reconciliationService.reconcileAllRelationships();

        assertThat(state.findCallsFrom(sourceMethodId).values()).singleElement()
                .satisfies(rel -> {
                    assertThat(rel.targetMethodId)
                            .isEqualTo(methodId(REPO_B, "com.external.ExternalService", "remote", "java.lang.String"));
                    assertThat(rel.resolutionStatus).isEqualTo("RESOLVED");
                });
        assertThat(result.callsFixed).isGreaterThanOrEqualTo(1);
        assertThat(result.callStats.crossRepositoryCalls).isGreaterThanOrEqualTo(1);
    }

    @Test
    void capturesCircularAndRecursiveCallsWhileSkippingExternalLibraryStubs() {
        ingestRepoA("CircularA.java");
        ingestRepoA("CircularB.java");
        ingestRepoA("OrchestratorService.java");
        ingestRepoA("DynamicCaller.java");

        reconciliationService.reconcileRelationships(REPO_A);

        assertThat(state.findCallsFrom(methodId(REPO_A, "com.example.calls.CircularA", "ping")).values()).singleElement()
                .satisfies(rel -> assertThat(rel.targetMethodId)
                        .isEqualTo(methodId(REPO_A, "com.example.calls.CircularB", "pong")));
        assertThat(state.findCallsFrom(methodId(REPO_A, "com.example.calls.CircularB", "pong")).values()).singleElement()
                .satisfies(rel -> assertThat(rel.targetMethodId)
                        .isEqualTo(methodId(REPO_A, "com.example.calls.CircularA", "ping")));

        assertThat(state.findCallsFrom(methodId(REPO_A, "com.example.calls.OrchestratorService", "recursive")).values()).singleElement()
                .satisfies(rel -> assertThat(rel.targetMethodId)
                        .isEqualTo(methodId(REPO_A, "com.example.calls.OrchestratorService", "recursive")));

        assertThat(state.findCallsFrom(methodId(REPO_A, "com.example.calls.DynamicCaller", "callLibrary", "java.lang.Object"))).isEmpty();
        assertThat(state.classesById.values().stream()
                .map(JavaClass::getFullyQualifiedName))
                .doesNotContain("java.lang.Object");
    }

    private void ingestRepoA(String fileName) {
        ingest(filePath("repo-a", fileName), REPO_A);
    }

    private void ingestRepoB(String fileName) {
        ingest(filePath("repo-b", fileName), REPO_B);
    }

    private void ingest(Path file, String repository) {
        ingestionService.ingestJavaFile(file, repository, MODULE, BRANCH, COMMIT);
    }

    private Path filePath(String fixtureRepo, String fileName) {
        if ("repo-b".equals(fixtureRepo)) {
            return Path.of("test-fixtures/graph-call-fixtures", fixtureRepo, "src/main/java/com/external", fileName);
        }
        return Path.of("test-fixtures/graph-call-fixtures", fixtureRepo, "src/main/java/com/example/calls", fileName);
    }

    private String methodId(String repository, String className, String methodName, String... parameterTypes) {
        return GraphNodeIdentity.methodId(repository, className, methodName, List.of(parameterTypes));
    }

    private void stubRepositoryRepository(CodeRepositoryRepository repositoryRepository, GraphState graphState) {
        when(repositoryRepository.findByRepoId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(graphState.repositoriesById.get(invocation.getArgument(0))));
        when(repositoryRepository.save(any(CodeRepository.class))).thenAnswer(invocation -> {
            CodeRepository repository = invocation.getArgument(0);
            graphState.repositoriesById.put(repository.getRepoId(), repository);
            return repository;
        });
    }

    private void stubFileRepository(JavaFileRepository fileRepository, GraphState graphState) {
        when(fileRepository.findByFileId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(graphState.filesById.get(invocation.getArgument(0))));
        when(fileRepository.save(any(JavaFile.class))).thenAnswer(invocation -> {
            JavaFile javaFile = invocation.getArgument(0);
            graphState.filesById.put(javaFile.getFileId(), javaFile);
            return javaFile;
        });
    }

    private void stubPackageRepository(JavaPackageRepository packageRepository, GraphState graphState) {
        when(packageRepository.findByPackageId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(graphState.packagesById.get(invocation.getArgument(0))));
        when(packageRepository.save(any(JavaPackage.class))).thenAnswer(invocation -> {
            JavaPackage javaPackage = invocation.getArgument(0);
            graphState.packagesById.put(javaPackage.getPackageId(), javaPackage);
            return javaPackage;
        });
    }

    private void stubModuleRepository(ModuleRepository moduleRepository, GraphState graphState) {
        when(moduleRepository.findByModuleId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(graphState.modulesById.get(invocation.getArgument(0))));
        when(moduleRepository.save(any(Module.class))).thenAnswer(invocation -> {
            Module module = invocation.getArgument(0);
            graphState.modulesById.put(module.getModuleId(), module);
            return module;
        });
    }

    private void stubClassRepository(JavaClassRepository repository, GraphState graphState) {
        when(repository.findByClassId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(graphState.classesById.get(invocation.getArgument(0))));
        when(repository.save(any(JavaClass.class))).thenAnswer(invocation -> {
            JavaClass javaClass = invocation.getArgument(0);
            graphState.classesById.put(javaClass.getClassId(), javaClass);
            return javaClass;
        });
        when(repository.findByFullyQualifiedName(anyString(), anyInt())).thenAnswer(invocation -> {
            String fqn = invocation.getArgument(0);
            return graphState.classesById.values().stream()
                    .filter(javaClass -> fqn.equals(javaClass.getFullyQualifiedName()))
                    .toList();
        });
        when(repository.findByFullyQualifiedNameInRepositories(anyString(), anyList())).thenAnswer(invocation -> {
            String fqn = invocation.getArgument(0);
            List<String> repos = invocation.getArgument(1);
            return graphState.classesById.values().stream()
                    .filter(javaClass -> fqn.equals(javaClass.getFullyQualifiedName()))
                    .filter(javaClass -> repos.contains(javaClass.getRepository()))
                    .toList();
        });
        when(repository.findImplementationsByFullyQualifiedName(anyString())).thenAnswer(invocation -> {
            String fqn = invocation.getArgument(0);
            return graphState.classesById.values().stream()
                    .filter(javaClass -> "CLASS".equals(javaClass.getType()) || "ABSTRACT_CLASS".equals(javaClass.getType()))
                    .filter(javaClass -> graphState.implementsOrExtends(javaClass, fqn, new HashSet<>()))
                    .toList();
        });
        when(repository.findByRepositoryAndSimpleName(anyString(), anyString())).thenAnswer(invocation -> {
            String repositoryName = invocation.getArgument(0);
            String simpleName = invocation.getArgument(1);
            return graphState.classesById.values().stream()
                    .filter(javaClass -> repositoryName.equals(javaClass.getRepository()))
                    .filter(javaClass -> simpleName.equals(javaClass.getSimpleName()))
                    .toList();
        });
        when(repository.findByRepositoryAndModule(anyString(), anyString())).thenAnswer(invocation -> {
            String repositoryName = invocation.getArgument(0);
            String moduleName = invocation.getArgument(1);
            return graphState.classesById.values().stream()
                    .filter(javaClass -> repositoryName.equals(javaClass.getRepository()))
                    .filter(javaClass -> moduleName.equals(javaClass.getModule()))
                    .toList();
        });
        when(repository.findByRepositoryPaginated(anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String repositoryName = invocation.getArgument(0);
            int skip = invocation.getArgument(1);
            int limit = invocation.getArgument(2);
            return graphState.classesById.values().stream()
                    .filter(javaClass -> repositoryName.equals(javaClass.getRepository()))
                    .sorted(Comparator.comparing(JavaClass::getClassId))
                    .skip(skip)
                    .limit(limit)
                    .toList();
        });
    }

    private void stubMethodRepository(JavaMethodRepository repository, GraphState graphState) {
        when(repository.findByMethodId(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(graphState.methodsById.get(invocation.getArgument(0))));
        when(repository.save(any(JavaMethod.class))).thenAnswer(invocation -> {
            JavaMethod method = invocation.getArgument(0);
            graphState.methodsById.put(method.getMethodId(), method);
            return method;
        });
        when(repository.findExactMethodInRepository(anyString(), anyString(), anyString(), anyList())).thenAnswer(invocation ->
                graphState.methodsById.values().stream()
                        .filter(method -> invocation.getArgument(0).equals(method.getRepository()))
                        .filter(method -> invocation.getArgument(1).equals(method.getClassName()))
                        .filter(method -> invocation.getArgument(2).equals(method.getName()))
                        .filter(method -> invocation.getArgument(3).equals(method.getParameterTypes()))
                        .sorted(Comparator.comparing(JavaMethod::getMethodId))
                        .toList());
        when(repository.findExactMethodAcrossRepositories(anyList(), anyString(), anyString(), anyList())).thenAnswer(invocation -> {
            List<String> repositories = invocation.getArgument(0);
            String className = invocation.getArgument(1);
            String methodName = invocation.getArgument(2);
            List<String> parameterTypes = invocation.getArgument(3);
            return graphState.methodsById.values().stream()
                    .filter(method -> repositories.contains(method.getRepository()))
                    .filter(method -> className.equals(method.getClassName()))
                    .filter(method -> methodName.equals(method.getName()))
                    .filter(method -> parameterTypes.equals(method.getParameterTypes()))
                    .sorted(Comparator.comparing(JavaMethod::getMethodId))
                    .toList();
        });
        when(repository.findRealMethodsByResolutionKey(anyString())).thenAnswer(invocation ->
                graphState.methodsById.values().stream()
                        .filter(method -> invocation.getArgument(0).equals(method.getResolutionKey()))
                        .filter(method -> !GraphNodeIdentity.STUB.equals(method.getNodeStatus()))
                        .toList());
        when(repository.findStubMethods()).thenAnswer(invocation ->
                graphState.methodsById.values().stream()
                        .filter(method -> GraphNodeIdentity.STUB.equals(method.getNodeStatus()))
                        .sorted(Comparator.comparing(JavaMethod::getMethodId))
                        .toList());
        when(repository.findStubMethodsPaginated(anyList(), anyInt())).thenAnswer(invocation -> {
            List<String> excludedIds = invocation.getArgument(0);
            int limit = invocation.getArgument(1);
            return graphState.methodsById.values().stream()
                    .filter(method -> GraphNodeIdentity.STUB.equals(method.getNodeStatus()))
                    .filter(method -> !excludedIds.contains(method.getMethodId()))
                    .sorted(Comparator.comparing(JavaMethod::getMethodId))
                    .limit(limit)
                    .toList();
        });
        when(repository.findByClassNameAndName(anyString(), anyString())).thenAnswer(invocation -> {
            String className = invocation.getArgument(0);
            String name = invocation.getArgument(1);
            return graphState.methodsById.values().stream()
                    .filter(method -> className.equals(method.getClassName()))
                    .filter(method -> name.equals(method.getName()))
                    .sorted(Comparator.comparing(JavaMethod::getMethodId))
                    .toList();
        });
        when(repository.findCalledMethods(anyString())).thenAnswer(invocation -> {
            String sourceMethodId = invocation.getArgument(0);
            return graphState.callRelationships.values().stream()
                    .filter(rel -> sourceMethodId.equals(rel.sourceMethodId))
                    .map(rel -> graphState.methodsById.get(rel.targetMethodId))
                    .filter(java.util.Objects::nonNull)
                    .toList();
        });
        when(repository.findCallRelationshipDetails(anyString())).thenAnswer(invocation ->
                new ArrayList<>(graphState.findCallsFrom(invocation.getArgument(0)).values()).stream()
                        .map(rel -> rel.toMap(graphState.methodsById.get(rel.targetMethodId)))
                        .toList());
        when(repository.findByRepositoryPaginated(anyString(), anyInt(), anyInt())).thenAnswer(invocation -> {
            String repositoryName = invocation.getArgument(0);
            return graphState.methodsById.values().stream()
                    .filter(method -> repositoryName.equals(method.getRepository()))
                    .sorted(Comparator.comparing(JavaMethod::getMethodId))
                    .toList();
        });
    }

    private static final class GraphState {
        private final Map<String, JavaFile> filesById = new HashMap<>();
        private final Map<String, JavaClass> classesById = new HashMap<>();
        private final Map<String, JavaMethod> methodsById = new HashMap<>();
        private final Map<String, Module> modulesById = new HashMap<>();
        private final Map<String, JavaPackage> packagesById = new HashMap<>();
        private final Map<String, CodeRepository> repositoriesById = new HashMap<>();
        private final Map<String, CallRelationshipState> callRelationships = new HashMap<>();

        private Map<String, CallRelationshipState> findCallsFrom(String sourceMethodId) {
            return callRelationships.values().stream()
                    .filter(rel -> sourceMethodId.equals(rel.sourceMethodId))
                    .collect(Collectors.toMap(rel -> rel.targetMethodId, Function.identity(), (left, right) -> left));
        }

        private boolean implementsOrExtends(JavaClass javaClass, String fullyQualifiedName, Set<String> visited) {
            if (!visited.add(javaClass.getClassId())) {
                return false;
            }
            if (javaClass.getImplementedInterfaces() != null && javaClass.getImplementedInterfaces().stream()
                    .anyMatch(parent -> fullyQualifiedName.equals(parent.getFullyQualifiedName())
                            || implementsOrExtends(parent, fullyQualifiedName, visited))) {
                return true;
            }
            return javaClass.getExtendedClasses() != null && javaClass.getExtendedClasses().stream()
                    .anyMatch(parent -> fullyQualifiedName.equals(parent.getFullyQualifiedName())
                            || implementsOrExtends(parent, fullyQualifiedName, visited));
        }
    }

    private static final class CallRelationshipState {
        private String sourceMethodId;
        private String targetMethodId;
        private String relationshipKey;
        private String invocationName;
        private int argumentCount;
        private int line;
        private int column;
        private String resolutionStatus;
        private String resolutionType;
        private int invocationCount;
        private List<Integer> callSites = new ArrayList<>();
        private String declaredType;

        private Map<String, Object> toMap(JavaMethod targetMethod) {
            Map<String, Object> values = new HashMap<>();
            values.put("targetMethodId", targetMethodId);
            values.put("targetResolutionKey", targetMethod == null ? null : targetMethod.getResolutionKey());
            values.put("invocationName", invocationName);
            values.put("argumentCount", argumentCount);
            values.put("line", line);
            values.put("column", column);
            values.put("resolutionStatus", resolutionStatus);
            values.put("resolutionType", resolutionType);
            values.put("invocationCount", invocationCount);
            values.put("callSites", callSites);
            values.put("declaredType", declaredType);
            return values;
        }
    }

    private static final class TestNeo4jClient implements Neo4jClient {
        private final GraphState state;

        private TestNeo4jClient(GraphState state) {
            this.state = state;
        }

        @Override
        public QueryRunner getQueryRunner(DatabaseSelection databaseSelection, org.springframework.data.neo4j.core.UserSelection userSelection) {
            return null;
        }

        @Override
        public UnboundRunnableSpec query(String cypher) {
            return new TestRunnableSpec(state, cypher);
        }

        @Override
        public UnboundRunnableSpec query(Supplier<String> supplier) {
            return new TestRunnableSpec(state, supplier.get());
        }

        @Override
        public <T> OngoingDelegation<T> delegateTo(Function<QueryRunner, Optional<T>> function) {
            return null;
        }

        @Override
        public DatabaseSelectionProvider getDatabaseSelectionProvider() {
            return null;
        }
    }

    private static final class TestRunnableSpec implements Neo4jClient.UnboundRunnableSpec {
        private final GraphState state;
        private final String cypher;
        private final Map<String, Object> params = new HashMap<>();

        private TestRunnableSpec(GraphState state, String cypher) {
            this.state = state;
            this.cypher = cypher;
        }

        @Override
        public Neo4jClient.RunnableSpecBoundToDatabase in(String databaseName) {
            return null;
        }

        @Override
        public Neo4jClient.RunnableSpecBoundToUser asUser(String userName) {
            return null;
        }

        @Override
        public <T> Neo4jClient.OngoingBindSpec<T, Neo4jClient.RunnableSpec> bind(T value) {
            return new Neo4jClient.OngoingBindSpec<>() {
                @Override
                public Neo4jClient.RunnableSpec to(String name) {
                    params.put(name, value);
                    return TestRunnableSpec.this;
                }

                @Override
                public Neo4jClient.RunnableSpec with(Function<T, Map<String, Object>> binder) {
                    params.putAll(binder.apply(value));
                    return TestRunnableSpec.this;
                }
            };
        }

        @Override
        public Neo4jClient.RunnableSpec bindAll(Map<String, Object> newParams) {
            params.putAll(newParams);
            return this;
        }

        @Override
        public <T> Neo4jClient.MappingSpec<T> fetchAs(Class<T> targetClass) {
            return new Neo4jClient.MappingSpec<>() {
                @Override
                public Neo4jClient.RecordFetchSpec<T> mappedBy(BiFunction<org.neo4j.driver.types.TypeSystem, org.neo4j.driver.Record, T> mappingFunction) {
                    return fetchSpec(targetClass);
                }

                @Override
                public Optional<T> one() {
                    return fetchSpec(targetClass).one();
                }

                @Override
                public Optional<T> first() {
                    return fetchSpec(targetClass).first();
                }

                @Override
                public Collection<T> all() {
                    return fetchSpec(targetClass).all();
                }
            };
        }

        @Override
        public Neo4jClient.RecordFetchSpec<Map<String, Object>> fetch() {
            return fetchSpec((Class<Map<String, Object>>) (Class<?>) Map.class);
        }

        @Override
        public ResultSummary run() {
            executeMutation();
            return Mockito.mock(ResultSummary.class);
        }

        private <T> Neo4jClient.RecordFetchSpec<T> fetchSpec(Class<T> targetClass) {
            return new Neo4jClient.RecordFetchSpec<>() {
                @Override
                public Optional<T> one() {
                    Object value = executeFetch(targetClass);
                    return Optional.ofNullable(targetClass.cast(value));
                }

                @Override
                public Optional<T> first() {
                    return one();
                }

                @Override
                public Collection<T> all() {
                    Optional<T> one = one();
                    return one.map(List::of).orElse(List.of());
                }
            };
        }

        private Object executeFetch(Class<?> targetClass) {
            if (cypher.contains("MATCH (stub:Method {methodId: $stubMethodId})<-[r:CALLS]-(source:Method)")
                    && cypher.contains("MATCH (real:Method {methodId: $realMethodId})")) {
                return Integer.valueOf(rewireCalls());
            }
            if (cypher.contains("MATCH (:Method {methodId: $stubMethodId})<-[r:CALLS]-(:Method)")) {
                return Integer.valueOf(markAmbiguous());
            }
            if (cypher.contains("MATCH (source:Method)-[r:CALLS]->(target:Method)")) {
                return statistics();
            }
            return null;
        }

        private void executeMutation() {
            if (cypher.contains("MERGE (stubClass:Class {classId: $classId})")) {
                createStub();
            } else if (cypher.contains("MATCH (:Method {methodId: $sourceMethodId})-[r:CALLS]->(:Method) DELETE r")) {
                deleteCallsForSource();
            } else if (cypher.contains("MATCH (source:Method {methodId: $sourceMethodId}), (target:Method {methodId: $targetMethodId})")) {
                upsertCall();
            } else if (cypher.contains("MATCH (stub:Method {methodId: $methodId})")) {
                cleanupStubMethod();
            } else if (cypher.contains("MATCH (stubClass:Class {classId: $classId})")) {
                cleanupStubClass();
            }
        }

        private void createStub() {
            String classId = (String) params.get("classId");
            String className = (String) params.get("className");
            JavaClass javaClass = state.classesById.getOrDefault(classId, new JavaClass());
            javaClass.setClassId(classId);
            javaClass.setFullyQualifiedName(className);
            javaClass.setSimpleName((String) params.get("simpleName"));
            javaClass.setRepository((String) params.get("repository"));
            javaClass.setModule((String) params.get("module"));
            javaClass.setPackageName((String) params.get("packageName"));
            javaClass.setNodeStatus(GraphNodeIdentity.STUB);
            javaClass.setResolutionKey(className);
            state.classesById.put(classId, javaClass);

            JavaMethod method = state.methodsById.getOrDefault((String) params.get("methodId"), new JavaMethod());
            method.setMethodId((String) params.get("methodId"));
            method.setName((String) params.get("methodName"));
            method.setSignature((String) params.get("signature"));
            method.setFullyQualifiedSignature((String) params.get("resolutionKey"));
            method.setParameterTypes((List<String>) params.get("parameterTypes"));
            method.setRepository((String) params.get("repository"));
            method.setModule((String) params.get("module"));
            method.setPackageName((String) params.get("packageName"));
            method.setClassName(className);
            method.setNodeStatus(GraphNodeIdentity.STUB);
            method.setResolutionKey((String) params.get("resolutionKey"));
            state.methodsById.put(method.getMethodId(), method);

            if (javaClass.getMethods() == null) {
                javaClass.setMethods(new HashSet<>());
            }
            javaClass.getMethods().add(method);
        }

        private void deleteCallsForSource() {
            String sourceMethodId = (String) params.get("sourceMethodId");
            state.callRelationships.values().removeIf(rel -> sourceMethodId.equals(rel.sourceMethodId));
        }

        private void upsertCall() {
            CallRelationshipState relationship = state.callRelationships.computeIfAbsent(
                    (String) params.get("relationshipKey"), ignored -> new CallRelationshipState());
            relationship.sourceMethodId = (String) params.get("sourceMethodId");
            relationship.targetMethodId = (String) params.get("targetMethodId");
            relationship.relationshipKey = (String) params.get("relationshipKey");
            relationship.invocationName = (String) params.get("invocationName");
            relationship.argumentCount = ((Number) params.get("argumentCount")).intValue();
            relationship.line = ((Number) params.get("line")).intValue();
            relationship.column = ((Number) params.get("column")).intValue();
            relationship.resolutionStatus = (String) params.get("resolutionStatus");
            relationship.resolutionType = (String) params.get("resolutionType");
            relationship.invocationCount = ((Number) params.get("invocationCount")).intValue();
            relationship.callSites = ((List<?>) params.get("callSites")).stream()
                    .map(value -> ((Number) value).intValue())
                    .collect(Collectors.toCollection(ArrayList::new));
            relationship.declaredType = (String) params.get("declaredType");
        }

        private int rewireCalls() {
            String stubMethodId = (String) params.get("stubMethodId");
            String realMethodId = (String) params.get("realMethodId");
            List<CallRelationshipState> stubRels = state.callRelationships.values().stream()
                    .filter(rel -> stubMethodId.equals(rel.targetMethodId))
                    .toList();
            int fixed = stubRels.size();
            for (CallRelationshipState stubRel : stubRels) {
                String newKey = stubRel.sourceMethodId + "->" + realMethodId;
                CallRelationshipState merged = state.callRelationships.get(newKey);
                if (merged == null) {
                    merged = new CallRelationshipState();
                    merged.sourceMethodId = stubRel.sourceMethodId;
                    merged.targetMethodId = realMethodId;
                    merged.relationshipKey = newKey;
                    merged.invocationName = stubRel.invocationName;
                    merged.argumentCount = stubRel.argumentCount;
                    merged.line = stubRel.line;
                    merged.column = stubRel.column;
                    merged.resolutionType = (String) params.get("resolutionType");
                    if (merged.resolutionType == null) {
                        merged.resolutionType = stubRel.resolutionType;
                    }
                    merged.declaredType = stubRel.declaredType;
                    merged.callSites = new ArrayList<>(stubRel.callSites);
                    merged.invocationCount = stubRel.invocationCount;
                    state.callRelationships.put(newKey, merged);
                } else {
                    merged.invocationCount += stubRel.invocationCount;
                    Set<Integer> mergedSites = new HashSet<>(merged.callSites);
                    mergedSites.addAll(stubRel.callSites);
                    merged.callSites = mergedSites.stream().sorted().toList();
                }
                merged.resolutionStatus = "RESOLVED";
                if (params.get("resolutionType") != null) {
                    merged.resolutionType = (String) params.get("resolutionType");
                }
                state.callRelationships.remove(stubRel.relationshipKey);
            }
            return fixed;
        }

        private int markAmbiguous() {
            String stubMethodId = (String) params.get("stubMethodId");
            int updated = 0;
            for (CallRelationshipState relationship : state.callRelationships.values()) {
                if (stubMethodId.equals(relationship.targetMethodId)) {
                    relationship.resolutionStatus = "AMBIGUOUS";
                    if (params.get("resolutionType") != null) {
                        relationship.resolutionType = (String) params.get("resolutionType");
                    } else if (relationship.resolutionType == null || relationship.resolutionType.isBlank()) {
                        relationship.resolutionType = "INTERFACE";
                    }
                    updated++;
                }
            }
            return updated;
        }

        private void cleanupStubMethod() {
            String methodId = (String) params.get("methodId");
            boolean referenced = state.callRelationships.values().stream()
                    .anyMatch(rel -> methodId.equals(rel.targetMethodId) || methodId.equals(rel.sourceMethodId));
            if (!referenced) {
                state.methodsById.remove(methodId);
            }
        }

        private void cleanupStubClass() {
            String classId = (String) params.get("classId");
            JavaClass javaClass = state.classesById.get(classId);
            if (javaClass == null) {
                return;
            }
            boolean referencedMethod = state.methodsById.values().stream()
                    .anyMatch(method -> classId.equals(GraphNodeIdentity.classId(method.getRepository(), method.getClassName())));
            if (!referencedMethod) {
                state.classesById.remove(classId);
            }
        }

        private Map<String, Object> statistics() {
            Map<String, Object> stats = new HashMap<>();
            int total = state.callRelationships.values().stream().mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount).sum();
            int resolved = state.callRelationships.values().stream()
                    .filter(rel -> "RESOLVED".equals(rel.resolutionStatus))
                    .mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount)
                    .sum();
            int stub = state.callRelationships.values().stream()
                    .filter(rel -> "STUB".equals(rel.resolutionStatus))
                    .mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount)
                    .sum();
            int ambiguous = state.callRelationships.values().stream()
                    .filter(rel -> "AMBIGUOUS".equals(rel.resolutionStatus))
                    .mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount)
                    .sum();
            int unresolved = state.callRelationships.values().stream()
                    .filter(rel -> "UNRESOLVED".equals(rel.resolutionStatus))
                    .mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount)
                    .sum();
            int crossRepo = state.callRelationships.values().stream()
                    .filter(rel -> {
                        JavaMethod source = state.methodsById.get(rel.sourceMethodId);
                        JavaMethod target = state.methodsById.get(rel.targetMethodId);
                        return source != null && target != null && !source.getRepository().equals(target.getRepository());
                    })
                    .mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount)
                    .sum();
            int selfCalls = state.callRelationships.values().stream()
                    .filter(rel -> rel.sourceMethodId.equals(rel.targetMethodId))
                    .mapToInt(rel -> rel.invocationCount == 0 ? 1 : rel.invocationCount)
                    .sum();
            long remainingStubs = state.callRelationships.values().stream()
                    .map(rel -> state.methodsById.get(rel.targetMethodId))
                    .filter(java.util.Objects::nonNull)
                    .filter(method -> GraphNodeIdentity.STUB.equals(method.getNodeStatus()))
                    .map(JavaMethod::getMethodId)
                    .distinct()
                    .count();
            stats.put("totalCallsDiscovered", total);
            stats.put("resolvedCalls", resolved);
            stats.put("stubCalls", stub);
            stats.put("ambiguousCalls", ambiguous);
            stats.put("unresolvedCalls", unresolved);
            stats.put("crossRepositoryCalls", crossRepo);
            stats.put("selfCalls", selfCalls);
            stats.put("remainingStubs", remainingStubs);
            return stats;
        }
    }
}

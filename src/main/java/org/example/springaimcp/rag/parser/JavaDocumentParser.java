package org.example.springaimcp.rag.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithJavadoc;
import lombok.extern.slf4j.Slf4j;
import org.example.springaimcp.rag.RagMetadataKeys;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses {@code .java} source files into structured {@link Document} instances
 * using a full AST via JavaParser.
 *
 * <p>Extraction strategy (one {@link Document} per unit of meaning):
 * <ul>
 *   <li>One document per top-level type (class / interface / enum / record) —
 *       contains Javadoc, header, and fields summary.</li>
 *   <li>One document per method and constructor — contains signature, Javadoc,
 *       and full body.</li>
 * </ul>
 *
 * <p>Every document is enriched with metadata keys:
 * {@code source}, {@code language}, {@code package}, {@code class},
 * {@code member}, {@code member_type}.
 */
@Component
@Slf4j
public class JavaDocumentParser implements DocumentParser {

    private static final ParserConfiguration PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

    private static final ThreadLocal<JavaParser> JAVA_PARSER =
            ThreadLocal.withInitial(() -> new JavaParser(PARSER_CONFIG));

    @Override
    public List<Document> parse(String source) {
        log.info("Parsing Java source: source={}", source);
        try {
            CompilationUnit cu = JAVA_PARSER.get().parse(Path.of(source).toFile())
                    .getResult()
                    .orElseThrow(() -> new DocumentParseException("Failed to parse Java source: " + source, null));
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("(default)");
            String fileName = Path.of(source).getFileName().toString();
            
            // Extract imports
            List<String> imports = cu.getImports().stream()
                    .map(ImportDeclaration::getNameAsString)
                    .collect(Collectors.toList());

            List<Document> documents = new ArrayList<>();
            extractTypes(cu, source, packageName, fileName, imports, documents);

            log.info("Extracted {} document(s) from Java source: source={}", documents.size(), source);
            return documents;
        } catch (FileNotFoundException e) {
            throw new DocumentParseException("Java source file not found: " + source, e);
        } catch (Exception e) {
            throw new DocumentParseException("Failed to parse Java source: " + source, e);
        }
    }

    // ── Type-level extraction ─────────────────────────────────────────────────

    private void extractTypes(CompilationUnit cu, String source, String pkg,
                              String fileName, List<String> imports, List<Document> out) {
        // Classes and interfaces
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(decl ->
                extractFromType(decl, source, pkg, fileName, imports,
                        decl.isInterface() ? "interface" : "class", out));

        // Enums
        cu.findAll(EnumDeclaration.class).forEach(decl ->
                extractFromType(decl, source, pkg, fileName, imports, "enum", out));

        // Records (Java 16+)
        cu.findAll(RecordDeclaration.class).forEach(decl ->
                extractFromType(decl, source, pkg, fileName, imports, "record", out));
    }

    private <T extends TypeDeclaration<T> & NodeWithJavadoc<T> & NodeWithAnnotations<T>> void extractFromType(
            T decl, String source, String pkg, String fileName, List<String> imports,
            String memberType, List<Document> out) {

        String className = decl.getNameAsString();

        // ── Class-level document (header + fields + Javadoc) ─────────────────
        out.add(buildTypeDocument(decl, source, pkg, className, fileName, memberType, imports));

        // ── Method-level documents ────────────────────────────────────────────
        decl.findAll(MethodDeclaration.class).forEach(method ->
                out.add(buildCallableDocument(method, source, pkg, className, fileName, "method",
                        method.getDeclarationAsString(true, true, true), imports)));

        // ── Constructor-level documents ───────────────────────────────────────
        decl.findAll(ConstructorDeclaration.class).forEach(ctor ->
                out.add(buildCallableDocument(ctor, source, pkg, className, fileName, "constructor",
                        ctor.getDeclarationAsString(true, true, true), imports)));
    }

    // ── Document builders ─────────────────────────────────────────────────────

    private <T extends TypeDeclaration<T> & NodeWithJavadoc<T> & NodeWithAnnotations<T>> Document buildTypeDocument(
            T decl, String source, String pkg, String className,
            String fileName, String memberType, List<String> imports) {

        String javadoc = decl.getJavadoc()
                .map(jd -> jd.toText().strip())
                .orElse("");

        String fields = decl.findAll(FieldDeclaration.class).stream()
                .map(f -> "  " + f.toString().trim())
                .collect(Collectors.joining("\n"));

        String content = buildTypeContent(pkg, fileName, memberType, className, javadoc,
                decl.toString(), fields);

        String signature = decl.getNameAsString();
        
        int startLine = decl.getBegin().map(pos -> pos.line).orElse(0);
        int endLine = decl.getEnd().map(pos -> pos.line).orElse(0);
        
        List<String> annotations = extractAnnotations(decl);
        
        return new Document(content,
                buildMetadata(source, pkg, className, className, memberType, signature, fileName, 
                        startLine, endLine, imports, annotations, null, null));
    }

    private <T extends CallableDeclaration<T> & NodeWithJavadoc<T> & NodeWithAnnotations<T>> Document buildCallableDocument(
            T decl, String source, String pkg, String className, String fileName,
            String memberType, String signature, List<String> imports) {

        String javadoc = decl.getJavadoc()
                .map(jd -> jd.toText().strip())
                .orElse("");

        String content = buildCallableContent(pkg, className, memberType,
                decl.getNameAsString(), signature, javadoc, decl.toString());

        int startLine = decl.getBegin().map(pos -> pos.line).orElse(0);
        int endLine = decl.getEnd().map(pos -> pos.line).orElse(0);
        
        List<String> annotations = extractAnnotations(decl);
        ApiMetadata apiMetadata = extractApiMetadata(decl);
        
        return new Document(content,
                buildMetadata(source, pkg, className, decl.getNameAsString(), memberType, signature, fileName, 
                        startLine, endLine, imports, annotations, apiMetadata, null));
    }

    // ── Content templates ─────────────────────────────────────────────────────

    private static String buildTypeContent(String pkg, String fileName, String type,
                                           String name, String javadoc,
                                           String fullDeclaration, String fields) {
        var sb = new StringBuilder();
        sb.append("// Package: ").append(pkg).append("\n");
        sb.append("// File:    ").append(fileName).append("\n");
        sb.append("// Type:    ").append(type).append(" ").append(name).append("\n");
        if (!javadoc.isBlank()) {
            sb.append("\n/** ").append(javadoc).append(" */\n");
        }
        if (!fields.isBlank()) {
            sb.append("\n// Fields:\n").append(fields).append("\n");
        }
        sb.append("\n").append(fullDeclaration);
        return sb.toString();
    }

    private static String buildCallableContent(String pkg, String className, String memberType,
                                               String memberName, String signature,
                                               String javadoc, String fullDeclaration) {
        var sb = new StringBuilder();
        sb.append("// Package: ").append(pkg).append("\n");
        sb.append("// Class:   ").append(className).append("\n");
        sb.append("// Member:  ").append(memberType).append(" ").append(memberName).append("\n");
        sb.append("// Signature: ").append(signature).append("\n");
        if (!javadoc.isBlank()) {
            sb.append("\n/** ").append(javadoc).append(" */\n");
        }
        sb.append("\n").append(fullDeclaration);
        return sb.toString();
    }

    // ── Metadata ─────────────────────────────────────────────────────────────

    private static Map<String, Object> buildMetadata(String source, String pkg,
                                                     String className, String member,
                                                     String memberType, String signature,
                                                     String fileName, int startLine, int endLine,
                                                     List<String> imports, List<String> annotations,
                                                     ApiMetadata apiMetadata, List<String> dependencies) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RagMetadataKeys.SOURCE, source);
        metadata.put(RagMetadataKeys.LANGUAGE, "java");
        metadata.put(RagMetadataKeys.DOCUMENT_TYPE, "java");
        metadata.put(RagMetadataKeys.PACKAGE, pkg);
        metadata.put(RagMetadataKeys.FILE_NAME, fileName);
        metadata.put(RagMetadataKeys.CLASS_NAME, className);
        metadata.put(RagMetadataKeys.MEMBER_NAME, member);
        metadata.put(RagMetadataKeys.MEMBER_TYPE, memberType);
        metadata.put(RagMetadataKeys.CHUNK_TYPE, memberType);
        metadata.put(RagMetadataKeys.SIGNATURE, signature);
        metadata.put(RagMetadataKeys.START_LINE, startLine);
        metadata.put(RagMetadataKeys.END_LINE, endLine);
        
        // Add imports
        if (imports != null && !imports.isEmpty()) {
            metadata.put(RagMetadataKeys.IMPORTS, String.join(", ", imports));
        }
        
        // Add annotations
        if (annotations != null && !annotations.isEmpty()) {
            metadata.put(RagMetadataKeys.ANNOTATIONS, String.join(", ", annotations));
        }
        
        // Add API metadata if present
        if (apiMetadata != null) {
            if (apiMetadata.httpMethod != null) {
                metadata.put(RagMetadataKeys.HTTP_METHOD, apiMetadata.httpMethod);
            }
            if (apiMetadata.endpointPath != null) {
                metadata.put(RagMetadataKeys.ENDPOINT_PATH, apiMetadata.endpointPath);
            }
            if (apiMetadata.requestHeaders != null && !apiMetadata.requestHeaders.isEmpty()) {
                metadata.put(RagMetadataKeys.REQUEST_HEADERS, String.join(", ", apiMetadata.requestHeaders));
            }
        }

        return metadata;
    }
    
    private static <T extends NodeWithAnnotations<?>> List<String> extractAnnotations(T node) {
        return node.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());
    }
    
    private static ApiMetadata extractApiMetadata(CallableDeclaration<?> decl) {
        ApiMetadata api = new ApiMetadata();
        
        for (AnnotationExpr annotation : decl.getAnnotations()) {
            String name = annotation.getNameAsString();
            
            // Check for Spring REST annotations
            if (name.equals("GetMapping") || name.equals("RequestMapping") && hasMethodGet(annotation)) {
                api.httpMethod = "GET";
                api.endpointPath = getAnnotationValue(annotation, "value", "path");
            } else if (name.equals("PostMapping")) {
                api.httpMethod = "POST";
                api.endpointPath = getAnnotationValue(annotation, "value", "path");
            } else if (name.equals("PutMapping")) {
                api.httpMethod = "PUT";
                api.endpointPath = getAnnotationValue(annotation, "value", "path");
            } else if (name.equals("DeleteMapping")) {
                api.httpMethod = "DELETE";
                api.endpointPath = getAnnotationValue(annotation, "value", "path");
            } else if (name.equals("PatchMapping")) {
                api.httpMethod = "PATCH";
                api.endpointPath = getAnnotationValue(annotation, "value", "path");
            }
        }
        
        // Extract headers from parameters
        if (decl instanceof MethodDeclaration method) {
            api.requestHeaders = method.getParameters().stream()
                    .filter(param -> param.getAnnotations().stream()
                            .anyMatch(ann -> ann.getNameAsString().equals("RequestHeader")))
                    .map(param -> param.getAnnotations().stream()
                            .filter(ann -> ann.getNameAsString().equals("RequestHeader"))
                            .findFirst()
                            .map(ann -> getAnnotationValue(ann, "value", "name"))
                            .orElse(param.getNameAsString()))
                    .collect(Collectors.toList());
        }
        
        return api.httpMethod != null ? api : null;
    }
    
    private static boolean hasMethodGet(AnnotationExpr annotation) {
        // Check if RequestMapping has method = RequestMethod.GET
        return annotation.isNormalAnnotationExpr() &&
                annotation.asNormalAnnotationExpr().getPairs().stream()
                        .anyMatch(pair -> pair.getNameAsString().equals("method") &&
                                pair.getValue().toString().contains("GET"));
    }
    
    private static String getAnnotationValue(AnnotationExpr annotation, String... names) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return cleanQuotes(annotation.asSingleMemberAnnotationExpr().getMemberValue().toString());
        } else if (annotation.isNormalAnnotationExpr()) {
            for (String name : names) {
                for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                    if (pair.getNameAsString().equals(name)) {
                        return cleanQuotes(pair.getValue().toString());
                    }
                }
            }
        }
        return null;
    }
    
    private static String cleanQuotes(String value) {
        if (value == null) return null;
        return value.replaceAll("^\"|\"$", "").replaceAll("^\\{|\\}$", "");
    }
    
    private static class ApiMetadata {
        String httpMethod;
        String endpointPath;
        List<String> requestHeaders;
    }
}

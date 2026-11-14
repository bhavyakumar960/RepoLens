package org.example.springaimcp.graph.util;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Stable business-key identity helpers for graph nodes.
 */
public final class GraphNodeIdentity {

    public static final String REAL = "REAL";
    public static final String STUB = "STUB";
    public static final String UNKNOWN_REPOSITORY = "unknown";

    private GraphNodeIdentity() {
    }

    public static String classId(String repository, String fullyQualifiedName) {
        return "class:" + repository + ":" + fullyQualifiedName;
    }

    public static String methodResolutionKey(String className, String methodName, List<String> parameterTypes) {
        return className + "." + methodName + "(" + joinParameterTypes(parameterTypes) + ")";
    }

    public static String methodId(String repository, String className, String methodName, List<String> parameterTypes) {
        return "method:" + repository + ":" + methodResolutionKey(className, methodName, parameterTypes);
    }

    public static String fieldId(String repository, String className, String fieldName) {
        return "field:" + repository + ":" + className + "." + fieldName;
    }

    public static String joinParameterTypes(List<String> parameterTypes) {
        if (parameterTypes == null || parameterTypes.isEmpty()) {
            return "";
        }
        return parameterTypes.stream()
                .map(type -> type == null ? "unknown" : type)
                .collect(Collectors.joining(","));
    }

    public static String simpleName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return fullyQualifiedName;
        }
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;
    }

    public static String packageName(String fullyQualifiedName) {
        if (fullyQualifiedName == null || fullyQualifiedName.isBlank()) {
            return null;
        }
        int lastDot = fullyQualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? fullyQualifiedName.substring(0, lastDot) : "(default)";
    }
}

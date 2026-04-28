package dev.javapp.compiler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class ExtensionRegistry {
    private final Map<String, Map<String, String>> methodsByType = new LinkedHashMap<>();

    void add(String targetType, String methodName, String extensionClassName) {
        methodsByType
                .computeIfAbsent(simpleType(targetType), ignored -> new LinkedHashMap<>())
                .put(methodName, extensionClassName);
    }

    Set<String> targetTypes() {
        return methodsByType.keySet();
    }

    String extensionClass(String targetType, String methodName) {
        Map<String, String> methods = methodsByType.get(simpleType(targetType));
        if (methods == null) {
            return null;
        }
        return methods.get(methodName);
    }

    boolean isEmpty() {
        return methodsByType.isEmpty();
    }

    static String simpleType(String targetType) {
        String stripped = targetType.replaceAll("<.*>", "").replace("[]", "").strip();
        int dot = stripped.lastIndexOf('.');
        return dot >= 0 ? stripped.substring(dot + 1) : stripped;
    }
}


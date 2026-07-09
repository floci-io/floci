package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards against the native-image persistence bug class fixed in PR #1695: every type
 * reachable from a {@link StorageFactory#create} {@code TypeReference<Map<String, V>>}
 * must carry {@code @RegisterForReflection} (directly or via a {@code targets = ...}
 * registration), or native-image builds fail to deserialize persisted state on restart.
 *
 * <p>Persistence roots are detected statically: anonymous {@link TypeReference} subclasses
 * whose enclosing class owns a {@link StorageBackend}/{@link StorageBackedMap} field or a
 * {@link StorageFactory} constructor parameter, and whose captured type has the persistence
 * shape {@code Map<String, V>}. The reachable graph then follows non-static, non-transient,
 * non-{@code @JsonIgnore} fields (including generic type arguments and superclasses) within
 * the project package.
 */
class PersistedModelReflectionTest {

    private static final String BASE_PACKAGE = "io.github.hectorvent.floci";

    @Test
    void persistedTypesAreRegisteredForReflection() throws Exception {
        List<Class<?>> projectClasses = loadProjectClasses();
        assertFalse(projectClasses.isEmpty(), "Project class scan found nothing — scan is broken");

        Set<Class<?>> registered = collectRegistered(projectClasses);
        Set<Class<?>> roots = findPersistenceRoots(projectClasses);
        assertFalse(roots.isEmpty(), "No persistence roots found — root detection is broken");

        Set<Class<?>> reachable = walkReachable(roots);
        List<String> violations = reachable.stream()
                .filter(c -> !c.isInterface())
                .filter(c -> !registered.contains(c))
                .map(Class::getName)
                .sorted()
                .toList();

        assertTrue(violations.isEmpty(), () ->
                "Types reachable from persisted storage are missing @RegisterForReflection"
                        + " (native-image persistence breaks on restart without it):\n  "
                        + String.join("\n  ", violations));
    }

    private List<Class<?>> loadProjectClasses() throws Exception {
        URI codeSource = StorageFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path classesDir = Path.of(codeSource);
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classesDir)) {
            paths.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        String name = classesDir.relativize(p).toString()
                                .replace(java.io.File.separatorChar, '.')
                                .replaceAll("\\.class$", "");
                        if (!name.startsWith(BASE_PACKAGE)) {
                            return;
                        }
                        try {
                            classes.add(Class.forName(name, false, getClass().getClassLoader()));
                        } catch (Throwable ignored) {
                            // Optional dependencies may be absent at test time; such classes
                            // cannot host persisted state reachable from a loadable root.
                        }
                    });
        }
        return classes;
    }

    private Set<Class<?>> collectRegistered(List<Class<?>> projectClasses) {
        Set<Class<?>> registered = new LinkedHashSet<>();
        for (Class<?> c : projectClasses) {
            RegisterForReflection annotation = c.getAnnotation(RegisterForReflection.class);
            if (annotation == null) {
                continue;
            }
            registered.add(c);
            for (Class<?> target : annotation.targets()) {
                registered.add(target);
            }
        }
        return registered;
    }

    private Set<Class<?>> findPersistenceRoots(List<Class<?>> projectClasses) {
        Set<Class<?>> roots = new LinkedHashSet<>();
        for (Class<?> c : projectClasses) {
            if (!TypeReference.class.isAssignableFrom(c) || c == TypeReference.class) {
                continue;
            }
            Class<?> enclosing = c.getEnclosingClass();
            if (enclosing == null || !ownsStorage(enclosing)) {
                continue;
            }
            Type captured = capturedType(c);
            if (captured instanceof ParameterizedType map
                    && map.getRawType() == Map.class
                    && map.getActualTypeArguments()[0] == String.class) {
                collectProjectTypes(map.getActualTypeArguments()[1], roots);
            }
        }
        return roots;
    }

    private static boolean ownsStorage(Class<?> enclosing) {
        for (Class<?> c = enclosing; c != null && c.getName().startsWith(BASE_PACKAGE); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == StorageBackend.class || f.getType() == StorageBackedMap.class) {
                    return true;
                }
            }
            for (var constructor : c.getDeclaredConstructors()) {
                for (Class<?> param : constructor.getParameterTypes()) {
                    if (param == StorageFactory.class) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Type capturedType(Class<?> typeReferenceSubclass) {
        Type superType = typeReferenceSubclass.getGenericSuperclass();
        if (superType instanceof ParameterizedType p && p.getRawType() == TypeReference.class) {
            return p.getActualTypeArguments()[0];
        }
        return null;
    }

    private Set<Class<?>> walkReachable(Set<Class<?>> roots) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Class<?> c = queue.pop();
            if (!visited.add(c) || c.isEnum()) {
                continue;
            }
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())
                        || Modifier.isTransient(f.getModifiers())
                        || f.isAnnotationPresent(JsonIgnore.class)) {
                    continue;
                }
                Set<Class<?>> fieldTypes = new LinkedHashSet<>();
                collectProjectTypes(f.getGenericType(), fieldTypes);
                queue.addAll(fieldTypes);
            }
            Class<?> superclass = c.getSuperclass();
            if (superclass != null && superclass.getName().startsWith(BASE_PACKAGE)) {
                queue.add(superclass);
            }
        }
        return visited;
    }

    private static void collectProjectTypes(Type type, Set<Class<?>> out) {
        if (type instanceof Class<?> c) {
            if (c.isArray()) {
                collectProjectTypes(c.getComponentType(), out);
            } else if (c.getName().startsWith(BASE_PACKAGE)) {
                out.add(c);
            }
        } else if (type instanceof ParameterizedType p) {
            collectProjectTypes(p.getRawType(), out);
            for (Type arg : p.getActualTypeArguments()) {
                collectProjectTypes(arg, out);
            }
        } else if (type instanceof GenericArrayType g) {
            collectProjectTypes(g.getGenericComponentType(), out);
        } else if (type instanceof WildcardType w) {
            for (Type bound : w.getUpperBounds()) {
                collectProjectTypes(bound, out);
            }
        }
    }
}

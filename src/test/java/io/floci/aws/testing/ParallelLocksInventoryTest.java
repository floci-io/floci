package io.floci.aws.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ci.yml runs the test classes that start no Quarkus application four at a time. A plain class that opens a socket
 * declares {@link ParallelLocks#PORTS}, so a class that releases a port and binds or dials it later never runs beside one
 * the kernel could hand that port to. The list of socket APIs is the one {@code .github/ci/split-concurrent.py}'s
 * rollout was annotated from; a class it misses fails here instead of flaking in a shard.
 */
class ParallelLocksInventoryTest {

    private static final Path TESTS = Path.of("src/test/java");
    private static final Pattern QUARKUS = Pattern.compile("^\\s*@QuarkusTest\\b", Pattern.MULTILINE);
    private static final Pattern EXTENDS = Pattern.compile(
            "^\\s*(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?class\\s+\\w+(?:<[^>]*>)?\\s+extends\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final Pattern OPENS_A_SOCKET = Pattern.compile(
            "FreePorts|new ServerSocket|ServerSocketChannel|DatagramSocket|HttpServer\\.create|new Socket\\("
                    + "|\\.bind\\(|localhost:[0-9]{4}|127\\.0\\.0\\.1:[0-9]{4}|getLocalPort");
    private static final Pattern PORTS_LOCK = Pattern.compile(
            "@ResourceLock\\(\\s*(?:value\\s*=\\s*)?ParallelLocks\\.PORTS\\b");

    @Test
    void everyPlainClassThatOpensASocketDeclaresThePortsLock() {
        Map<String, String> sources = sources();
        Set<String> missing = new TreeSet<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            String name = entry.getKey();
            String source = entry.getValue();
            // This class names the socket APIs it looks for without opening any.
            if (isTestClass(name) && !name.equals(ParallelLocksInventoryTest.class.getSimpleName())
                    && !isQuarkus(name, sources, new TreeSet<>())
                    && OPENS_A_SOCKET.matcher(source).find() && !PORTS_LOCK.matcher(source).find()) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "Plain test classes that open a socket without @ResourceLock(ParallelLocks.PORTS): "
                + missing + ". Use the default mode when the class releases a port and binds or dials it later, "
                + "ResourceAccessMode.READ otherwise.");
    }

    private static boolean isTestClass(String name) {
        return name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("TestCase") || name.startsWith("Test");
    }

    private static boolean isQuarkus(String name, Map<String, String> sources, Set<String> seen) {
        String source = sources.get(name);
        if (source == null || !seen.add(name)) {
            return false;
        }
        if (QUARKUS.matcher(source).find()) {
            return true;
        }
        Matcher parent = EXTENDS.matcher(source);
        return parent.find() && isQuarkus(parent.group(1), sources, seen);
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                String fileName = file.getFileName().toString();
                try {
                    sources.put(fileName.substring(0, fileName.length() - ".java".length()),
                            Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sources;
    }
}

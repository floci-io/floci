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
 * the kernel could hand that port to. A class opens a socket when its source names one of the socket APIs below, or
 * names, or extends, another src/test/java class that does (a helper such as {@link LocalHttpsServer}); a class
 * this misses fails here instead of flaking in a shard. A plain class that builds the {@code EmulatorConfig}
 * mapping with a {@code SmallRyeConfigBuilder} declares {@link ParallelLocks#CONFIG_MAPPING} the same way.
 */
class ParallelLocksInventoryTest {

    private static final Path TESTS = Path.of("src/test/java");
    private static final Pattern QUARKUS = Pattern.compile("^\\s*@QuarkusTest\\b", Pattern.MULTILINE);
    private static final Pattern EXTENDS = Pattern.compile(
            "^\\s*(?:public\\s+)?(?:abstract\\s+)?(?:final\\s+)?class\\s+\\w+(?:<[^>]*>)?\\s+extends\\s+(\\w+)",
            Pattern.MULTILINE);
    private static final String SOCKET_APIS = "FreePorts|new ServerSocket|ServerSocketChannel|DatagramSocket"
            + "|Https?Server\\.create|new Socket\\(|createHttpServer|createNetServer|\\.listen\\(|\\.bind\\(";
    private static final Pattern CALLS_A_SOCKET_API = Pattern.compile(SOCKET_APIS);
    private static final Pattern OPENS_A_SOCKET = Pattern.compile(
            SOCKET_APIS + "|localhost:[0-9]{4}|127\\.0\\.0\\.1:[0-9]{4}|getLocalPort");
    private static final Pattern PORTS_LOCK = Pattern.compile(
            "@ResourceLock\\(\\s*(?:value\\s*=\\s*)?ParallelLocks\\.PORTS\\b");
    private static final Pattern BUILDS_THE_CONFIG_MAPPING = Pattern.compile("withMapping\\(\\s*EmulatorConfig\\.class");
    private static final Pattern CONFIG_MAPPING_LOCK = Pattern.compile(
            "@ResourceLock\\(\\s*(?:value\\s*=\\s*)?ParallelLocks\\.CONFIG_MAPPING\\b");

    private static final Pattern USES_FLOCI_DOCKER_STREAMS = Pattern.compile(
            "new DockerClientProducer\\s*\\(|DockerClientProducer\\.newDockerClient\\s*\\("
                    + "|new FlociInvocationBuilder\\s*\\(");
    private static final Pattern DOCKER_ACTIVE_STREAMS_LOCK = Pattern.compile(
            "@ResourceLock\\(\\s*(?:value\\s*=\\s*)?ParallelLocks\\.DOCKER_ACTIVE_STREAMS\\b");

    @Test
    void everyPlainFlociDockerStreamConsumerDeclaresItsLock() {
        Set<String> missing = missingLocks(USES_FLOCI_DOCKER_STREAMS, DOCKER_ACTIVE_STREAMS_LOCK);
        assertTrue(missing.isEmpty(), "Plain Floci Docker stream consumers without "
                + "@ResourceLock(ParallelLocks.DOCKER_ACTIVE_STREAMS): " + missing);
    }

    @Test
    void everyPlainClassThatOpensASocketDeclaresThePortsLock() {
        Map<String, String> sources = sources();
        Set<String> openers = openers(sources);
        Set<String> missing = new TreeSet<>();
        for (String name : openers) {
            if (isTestClass(name) && !isQuarkus(name, sources, new TreeSet<>())
                    && !PORTS_LOCK.matcher(sources.get(name)).find()) {
                missing.add(name);
            }
        }
        assertTrue(missing.isEmpty(), "Plain test classes that open a socket without @ResourceLock(ParallelLocks.PORTS): "
                + missing + ". Use the default mode when the class releases a port and binds or dials it later, "
                + "ResourceAccessMode.READ otherwise.");
    }

    @Test
    void everyPlainClassThatBuildsTheConfigMappingDeclaresItsLock() {
        Set<String> missing = missingLocks(BUILDS_THE_CONFIG_MAPPING, CONFIG_MAPPING_LOCK);
        assertTrue(missing.isEmpty(), "Plain test classes that build the EmulatorConfig mapping without "
                + "@ResourceLock(ParallelLocks.CONFIG_MAPPING): " + missing);
    }

    private static Set<String> missingLocks(Pattern usage, Pattern lock) {
        Map<String, String> sources = sources();
        Set<String> missing = new TreeSet<>();
        sources.forEach((name, source) -> {
            if (isTestClass(name) && usage.matcher(source).find()
                    && !isQuarkus(name, sources, new TreeSet<>()) && !lock.matcher(source).find()) {
                missing.add(name);
            }
        });
        return missing;
    }

    /**
     * The classes whose source names a socket API, and the test classes that name, or extend, a helper whose source
     * calls one: one hop, because a fixture that only names an address (a {@code localhost:4566} endpoint) would
     * otherwise pull in every class that uses it. ParallelLocks and this class name the APIs without opening any.
     */
    private static Set<String> openers(Map<String, String> sources) {
        Set<String> skipped = Set.of(ParallelLocks.class.getSimpleName(),
                ParallelLocksInventoryTest.class.getSimpleName());
        Set<String> openers = new TreeSet<>();
        Set<String> helpers = new TreeSet<>();
        sources.forEach((name, source) -> {
            if (!skipped.contains(name) && OPENS_A_SOCKET.matcher(source).find()) {
                openers.add(name);
                if (!isTestClass(name) && CALLS_A_SOCKET_API.matcher(source).find()) {
                    helpers.add(name);
                }
            }
        });
        if (!helpers.isEmpty()) {
            Pattern names = Pattern.compile("\\b(?:" + String.join("|", helpers) + ")\\b");
            sources.forEach((name, source) -> {
                if (!skipped.contains(name) && names.matcher(source).find()) {
                    openers.add(name);
                }
            });
        }
        return openers;
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

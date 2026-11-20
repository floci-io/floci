package io.floci.aws.testing;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the helper shells Floci execs inside a guest (the IMDS proxy's, the sshd install probe, an
 * EKS node's Pod Identity forward) on this host with {@code /bin/sh}, against fake system tools on
 * PATH, with the environment an application image hands a {@code docker exec}.
 */
public final class HelperShellFixture {

    /** What an application image can carry into a {@code docker exec}, plus one setting the shells must keep. */
    public static final Map<String, String> APPLICATION_ENVIRONMENT = Map.of(
            "LD_LIBRARY_PATH", "/fixture/application/lib",
            "PYTHONPATH", "/fixture/application/modules",
            "PYTHONHOME", "/fixture/application/python",
            "KEEP_SETTING", "fixture-value");

    private HelperShellFixture() {}

    /**
     * The start of a fake tool that fails unless the application's library and Python paths are
     * unset while its other settings survive, and then records the tool's name in TEST_RECORD.
     */
    public static String checkedToolEnvironment() {
        return "[ -z \"${LD_LIBRARY_PATH+x}\" ] || exit 91\n"
                + "[ -z \"${PYTHONPATH+x}\" ] || exit 92\n"
                + "[ -z \"${PYTHONHOME+x}\" ] || exit 93\n"
                + "[ \"$KEEP_SETTING\" = fixture-value ] || exit 94\n"
                + "printf '%s\\n' \"${0##*/}\" >> \"$TEST_RECORD\"\n";
    }

    /** {@link #APPLICATION_ENVIRONMENT} plus the test's own settings. */
    public static Map<String, String> applicationEnvironment(Map<String, String> settings) {
        Map<String, String> environment = new HashMap<>(APPLICATION_ENVIRONMENT);
        environment.putAll(settings);
        return environment;
    }

    public static void writeTool(Path bin, String name, String script) throws IOException {
        Path tool = bin.resolve(name);
        Files.writeString(tool, "#!/bin/sh\nset -eu\n" + script);
        assertTrue(tool.toFile().setExecutable(true));
    }

    /** Where {@code tool} is on this host's PATH, if it is. */
    public static Optional<Path> onPath(String tool) {
        for (String dir : System.getenv().getOrDefault("PATH", "").split(File.pathSeparator)) {
            Path candidate = Path.of(dir, tool);
            if (!dir.isEmpty() && Files.isExecutable(candidate)) {
                return Optional.of(candidate.toAbsolutePath());
            }
        }
        return Optional.empty();
    }

    /**
     * Links each of this host's {@code tools} into {@code bin}, so a PATH of {@code bin} alone
     * offers them and nothing else. Skips the test when the host lacks one.
     */
    public static void linkHostTools(Path bin, List<String> tools) throws IOException {
        for (String tool : tools) {
            Optional<Path> found = onPath(tool);
            assumeTrue(found.isPresent(), tool + " is not installed on this host");
            Files.createSymbolicLink(bin.resolve(tool), found.get());
        }
    }

    /** A proxy that records each request it is sent and answers 502, so its answer is never mistaken for the endpoint's. */
    public static HttpServer failingProxy(List<String> proxied) throws IOException {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxied.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            exchange.sendResponseHeaders(502, -1);
            exchange.close();
        });
        proxy.start();
        return proxy;
    }

    /** The proxy settings an image could carry, with nothing exempted. */
    public static Map<String, String> proxyEnvironment(HttpServer proxy) {
        String proxyUrl = "http://127.0.0.1:" + proxy.getAddress().getPort();
        return Map.of("http_proxy", proxyUrl, "HTTP_PROXY", proxyUrl, "all_proxy", proxyUrl,
                "ALL_PROXY", proxyUrl, "no_proxy", "", "NO_PROXY", "");
    }

    /** Runs a generated helper shell with {@code /bin/sh}, its output going to {@link #output}. */
    public static int run(String[] command, Path directory, Map<String, String> environment) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.set(0, "/bin/sh");
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("shell-output").toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Generated helper shell exceeded its bound");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    public static String output(Path directory) throws IOException {
        return Files.readString(directory.resolve("shell-output"));
    }
}

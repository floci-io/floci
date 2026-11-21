package io.floci.aws.services.elasticache;

import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.services.elasticache.model.AuthMode;
import io.floci.aws.services.elasticache.model.CacheCluster;
import io.floci.aws.services.elasticache.model.ClusterNode;
import io.floci.aws.services.elasticache.model.ReplicationGroup;
import io.floci.aws.testing.TlsTrust;
import jakarta.enterprise.inject.spi.CDI;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real CA validation for cache data-plane tests, which never uses a trust-all manager, and the Docker
 * CLI and RESP helpers the Docker-backed cache tests share.
 */
public final class ElastiCacheTestConnections {
    private ElastiCacheTestConnections() {}

    static Socket openSocket(String host, int port) throws IOException {
        ElastiCacheService service = CDI.current().select(ElastiCacheService.class).get();
        for (ReplicationGroup group : service.listReplicationGroups(null)) {
            if (!group.isEncryptedInTransit()) {
                continue;
            }
            if (group.getProxyPort() == port) {
                return openSocket(host, port, true);
            }
            for (ClusterNode node : group.getClusterNodes()) {
                if (node.getProxyPort() == port) {
                    return openSocket(host, port, true);
                }
            }
        }
        for (CacheCluster cluster : service.findCacheClusters(null)) {
            if (cluster.getAuthMode() != AuthMode.NO_AUTH && cluster.getConfigurationEndpoint() != null
                    && cluster.getConfigurationEndpoint().port() == port) {
                return openSocket(host, port, true);
            }
        }
        return openSocket(host, port, false);
    }

    static Socket openSocket(String host, int port, boolean encrypted) throws IOException {
        if (!encrypted) {
            return new Socket(host, port);
        }
        try {
            FlociCertificateAuthority authority = CDI.current().select(FlociCertificateAuthority.class).get();
            SSLSocket socket = (SSLSocket) TlsTrust.trusting(authority.certificate()).getSocketFactory()
                    .createSocket(host, port);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(10000);
            socket.startHandshake();
            return socket;
        } catch (Exception e) {
            throw new IOException("Could not establish a trusted cache TLS connection", e);
        }
    }

    /** The id of the backend container of {@code resourceId}, or the empty string when there is none. */
    static String containerOf(String resourceId) throws Exception {
        return docker("ps", "-aq", "--filter", "label=io.floci.service=elasticache",
                "--filter", "label=io.floci.resource-id=" + resourceId);
    }

    /** Runs the Docker CLI, which must succeed, and returns its trimmed output. */
    static String docker(String... arguments) throws Exception {
        Process process = startDocker(arguments);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "docker timed out");
        assertEquals(0, process.exitValue(), "docker " + String.join(" ", arguments) + ": " + output);
        return output;
    }

    /** Runs the Docker CLI and returns its exit code. */
    static int dockerExit(String... arguments) throws Exception {
        Process process = startDocker(arguments);
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "docker timed out");
        return process.exitValue();
    }

    private static Process startDocker(String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    /** Sends one command and returns the first reply line, without its line ending. */
    static String command(Socket socket, String... parts) throws IOException {
        write(socket, parts);
        return readLine(socket.getInputStream());
    }

    /** Sends one command whose reply is a bulk string and returns its content. */
    static String bulk(Socket socket, String... parts) throws IOException {
        write(socket, parts);
        InputStream in = socket.getInputStream();
        String header = readLine(in);
        assertTrue(header.startsWith("$"), "bulk reply expected, got " + header);
        int length = Integer.parseInt(header.substring(1));
        byte[] payload = in.readNBytes(length + 2);
        return new String(payload, 0, length, StandardCharsets.UTF_8);
    }

    static void write(Socket socket, String... parts) throws IOException {
        StringBuilder request = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            request.append('$').append(part.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(part).append("\r\n");
        }
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\r') {
                in.read();
                break;
            }
            line.append((char) read);
        }
        return line.toString();
    }
}

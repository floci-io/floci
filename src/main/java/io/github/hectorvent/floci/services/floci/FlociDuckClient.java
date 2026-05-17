package io.github.hectorvent.floci.services.floci;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the floci-duck sidecar.
 *
 * Wraps the two duck endpoints used across services:
 * - /execute — fire-and-forget SQL, writes results to S3 (used by Athena)
 * - /query   — SQL that returns rows as JSON maps (used by S3 Select)
 */
@ApplicationScoped
public class FlociDuckClient {

    private final FlociDuckManager duckManager;
    private final EmulatorConfig config;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final DockerHostResolver dockerHostResolver;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public FlociDuckClient(FlociDuckManager duckManager,
                           EmulatorConfig config,
                           EmbeddedDnsServer embeddedDnsServer,
                           DockerHostResolver dockerHostResolver,
                           ObjectMapper mapper) {
        this.duckManager = duckManager;
        this.config = config;
        this.embeddedDnsServer = embeddedDnsServer;
        this.dockerHostResolver = dockerHostResolver;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Returns true if floci-duck is currently reachable without starting it.
     */
    public boolean isAvailable() {
        return duckManager.isAvailable();
    }

    /**
     * Executes SQL via floci-duck and writes the result as a CSV to {@code outputS3Path}.
     * Used by Athena query execution.
     *
     * @param sql         the SQL to run (FROM clauses reference s3:// paths)
     * @param setupDdl    optional DDL to run before the query (e.g. CREATE VIEW)
     * @param outputS3Path the S3 path where the result CSV will be written
     */
    public void execute(String sql, String setupDdl, String outputS3Path) {
        execute(sql, setupDdl, outputS3Path, null);
    }

    /**
     * Variant of {@link #execute(String, String, String)} that authenticates
     * the DuckDB-side S3 client as a specific Floci account. Pass a 12-digit
     * AWS account ID as {@code accessKeyId} so that {@link
     * io.github.hectorvent.floci.core.common.AccountResolver} routes the
     * subsequent reads/writes to that account's S3 partition rather than the
     * default account.
     */
    public void execute(String sql, String setupDdl, String outputS3Path, String accessKeyId) {
        String duckUrl = duckManager.ensureReady();

        Map<String, Object> body = buildBaseBody(sql, setupDdl, accessKeyId);
        body.put("output_s3_path", outputS3Path);

        post(duckUrl + "/execute", body, "execute");
    }

    /**
     * Executes SQL via floci-duck and returns the result rows as a list of column→value maps.
     * Used by S3 Select for formats that require DuckDB evaluation (e.g. Parquet).
     *
     * @param sql      the SQL to run (FROM clauses reference s3:// paths)
     * @param setupDdl optional DDL to run before the query
     * @return result rows; empty list if the query matched no rows
     */
    public List<Map<String, Object>> query(String sql, String setupDdl) {
        return query(sql, setupDdl, null);
    }

    /**
     * Variant of {@link #query(String, String)} that authenticates the
     * DuckDB-side S3 client as a specific Floci account. See
     * {@link #execute(String, String, String, String)} for details.
     */
    public List<Map<String, Object>> query(String sql, String setupDdl, String accessKeyId) {
        String duckUrl = duckManager.ensureReady();

        Map<String, Object> body = buildBaseBody(sql, setupDdl, accessKeyId);

        String responseBody = post(duckUrl + "/query", body, "query");
        return parseQueryRows(responseBody);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildBaseBody(String sql, String setupDdl) {
        return buildBaseBody(sql, setupDdl, null);
    }

    private Map<String, Object> buildBaseBody(String sql, String setupDdl, String accessKeyId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        if (setupDdl != null && !setupDdl.isBlank()) {
            body.put("setup_sql", setupDdl);
        }
        body.put("s3_endpoint", resolveFlociEndpoint());
        body.put("s3_region", config.defaultRegion());
        // A 12-digit access key flows through AccountResolver and selects the
        // matching account partition for any S3 read/write the SQL performs.
        // Anything else falls back to the configured default account.
        body.put("s3_access_key", accessKeyId == null || accessKeyId.isEmpty() ? "test" : accessKeyId);
        body.put("s3_secret_key", "test");
        body.put("s3_url_style", "path");
        return body;
    }

    /**
     * Returns the Floci S3 endpoint URL as reachable from inside a floci-duck container.
     * Uses the embedded DNS hostname when the DNS server is active, falls back to the
     * raw Docker host IP for local dev mode.
     */
    private String resolveFlociEndpoint() {
        int port = URI.create(config.baseUrl()).getPort();
        String hostname = embeddedDnsServer.getServerIp().isPresent()
                ? config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : dockerHostResolver.resolve();
        return "http://" + hostname + ":" + port;
    }

    private String post(String url, Map<String, Object> body, String operation) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("floci-duck " + operation + " returned HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call floci-duck " + operation + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseQueryRows(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            if (!"success".equals(root.path("status").asText())) {
                throw new RuntimeException("floci-duck query error: " + root.path("message").asText());
            }
            JsonNode rowsNode = root.path("rows");
            if (rowsNode.isMissingNode() || rowsNode.isNull()) {
                return List.of();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode row : rowsNode) {
                rows.add((Map<String, Object>) mapper.treeToValue(row, Map.class));
            }
            return rows;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse floci-duck query response: " + e.getMessage(), e);
        }
    }
}

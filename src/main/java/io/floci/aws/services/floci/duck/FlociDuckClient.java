package io.floci.aws.services.floci.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * HTTP client for the floci-duck sidecar.
 *
 * Wraps the two duck endpoints used across services:
 * - /execute — fire-and-forget SQL, writes results to S3 (used by Athena)
 * - /query   — SQL that returns rows as JSON maps (used by S3 Select)
 */
@ApplicationScoped
public class FlociDuckClient {

    /** A bucket an {@code s3://} path in the SQL, the setup or the output path names. */
    private static final Pattern S3_BUCKET = Pattern.compile("s3://([a-z0-9][a-z0-9.-]{1,62})");

    private final FlociDuckManager duckManager;
    private final EmulatorConfig config;
    private final ContainerReachableEndpoint containerEndpoint;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private final Function<String, Optional<String>> bucketRegions;

    @Inject
    public FlociDuckClient(FlociDuckManager duckManager,
                           EmulatorConfig config,
                           ContainerReachableEndpoint containerEndpoint,
                           ObjectMapper mapper,
                           Instance<S3Service> s3Service) {
        // Looked up per call: S3 Select, a part of S3, uses this client.
        this(duckManager, config, containerEndpoint, mapper, bucket -> s3Service.get().findBucketRegion(bucket));
    }

    FlociDuckClient(FlociDuckManager duckManager,
                    EmulatorConfig config,
                    ContainerReachableEndpoint containerEndpoint,
                    ObjectMapper mapper) {
        this(duckManager, config, containerEndpoint, mapper, bucket -> Optional.empty());
    }

    FlociDuckClient(FlociDuckManager duckManager,
                    EmulatorConfig config,
                    ContainerReachableEndpoint containerEndpoint,
                    ObjectMapper mapper,
                    Function<String, Optional<String>> bucketRegions) {
        this.duckManager = duckManager;
        this.config = config;
        this.containerEndpoint = containerEndpoint;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
        this.bucketRegions = bucketRegions;
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
     * @param outputS3Path the S3 path where the result CSV will be written,
     *                     or {@code null} to execute without exporting results
     */
    public void execute(String sql, String setupDdl, String outputS3Path) {
        execute(sql, setupDdl, outputS3Path, null);
    }

    /**
     * Variant of {@link #execute(String, String, String)} that authenticates
     * the DuckDB-side S3 client as a specific Floci account. Pass a 12-digit
     * AWS account ID as {@code accessKeyId} so that {@link
     * io.floci.aws.core.common.AccountResolver} routes the
     * subsequent reads/writes to that account's S3 partition rather than the
     * default account.
     */
    public void execute(String sql, String setupDdl, String outputS3Path, String accessKeyId) {
        execute(sql, setupDdl, outputS3Path, accessKeyId, null, null);
    }

    /**
     * Variant of {@link #execute(String, String, String, String)} that passes
     * explicit credentials (including session token for SigV4 authentication).
     */
    public void execute(String sql, String setupDdl, String outputS3Path,
                        String accessKeyId, String secretAccessKey, String sessionToken) {
        String duckUrl = duckManager.ensureReady();

        String effectiveSetup = withBucketRegions(injectSessionToken(setupDdl, sessionToken),
                accessKeyId, secretAccessKey, sessionToken, sql, setupDdl, outputS3Path);
        Map<String, Object> body = buildBaseBody(sql, effectiveSetup, accessKeyId, secretAccessKey);
        if (outputS3Path != null) {
            body.put("output_s3_path", outputS3Path);
        }

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
        return query(sql, setupDdl, accessKeyId, null, null);
    }

    /**
     * Variant of {@link #query(String, String, String)} that passes
     * explicit credentials (including session token for SigV4 authentication).
     */
    public List<Map<String, Object>> query(String sql, String setupDdl,
                                           String accessKeyId, String secretAccessKey, String sessionToken) {
        String duckUrl = duckManager.ensureReady();

        String effectiveSetup = withBucketRegions(injectSessionToken(setupDdl, sessionToken),
                accessKeyId, secretAccessKey, sessionToken, sql, setupDdl, null);
        Map<String, Object> body = buildBaseBody(sql, effectiveSetup, accessKeyId, secretAccessKey);

        String responseBody = post(duckUrl + "/query", body, "query");
        return parseQueryRows(responseBody);
    }

    static String injectSessionToken(String setupDdl, String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return setupDdl;
        }
        String setToken = "SET s3_session_token = '" + sessionToken.replace("'", "''") + "';\n";
        return setupDdl == null || setupDdl.isBlank() ? setToken : setToken + setupDdl;
    }

    /**
     * S3 answers a bucket only in its own Region, and DuckDB signs every request for its one {@code s3_region}: it
     * re-signs a read that gets 301 PermanentRedirect but not a write. Each bucket the statement names in another
     * Region than the default therefore gets a secret scoped to it and signed for its Region, with the request's
     * credentials; DuckDB picks the secret whose scope matches a path and the {@code s3_*} settings for the rest.
     * Every request runs on its own connection, so the secrets end with it.
     */
    String withBucketRegions(String setupDdl, String accessKeyId, String secretAccessKey, String sessionToken,
                             String... statements) {
        Set<String> buckets = new LinkedHashSet<>();
        for (String text : Stream.of(statements).filter(t -> t != null && !t.isEmpty()).toList()) {
            Matcher matcher = S3_BUCKET.matcher(text);
            while (matcher.find()) {
                buckets.add(matcher.group(1));
            }
        }
        StringBuilder secrets = new StringBuilder();
        URI endpoint = URI.create(containerEndpoint.baseUrl());
        int index = 0;
        for (String bucket : buckets) {
            Optional<String> region = bucketRegions.apply(bucket);
            if (region.isEmpty() || region.get().equals(config.defaultRegion())) {
                continue;
            }
            secrets.append("CREATE OR REPLACE SECRET floci_bucket_").append(index++).append(" (TYPE s3")
                    .append(", KEY_ID ").append(literal(accessKeyId == null || accessKeyId.isEmpty() ? "test" : accessKeyId))
                    .append(", SECRET ").append(literal(secretAccessKey == null || secretAccessKey.isEmpty()
                            ? "test" : secretAccessKey));
            if (sessionToken != null && !sessionToken.isBlank()) {
                secrets.append(", SESSION_TOKEN ").append(literal(sessionToken));
            }
            secrets.append(", REGION ").append(literal(region.get()))
                    .append(", ENDPOINT ").append(literal(endpoint.getAuthority()))
                    .append(", URL_STYLE 'path', USE_SSL ").append("https".equals(endpoint.getScheme()))
                    .append(", SCOPE ").append(literal("s3://" + bucket)).append(");\n");
        }
        if (secrets.isEmpty()) {
            return setupDdl;
        }
        return setupDdl == null || setupDdl.isBlank() ? secrets.toString() : secrets + setupDdl;
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildBaseBody(String sql, String setupDdl) {
        return buildBaseBody(sql, setupDdl, null, null);
    }

    private Map<String, Object> buildBaseBody(String sql, String setupDdl, String accessKeyId) {
        return buildBaseBody(sql, setupDdl, accessKeyId, null);
    }

    private Map<String, Object> buildBaseBody(String sql, String setupDdl, String accessKeyId, String secretAccessKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        if (setupDdl != null && !setupDdl.isBlank()) {
            body.put("setup_sql", setupDdl);
        }
        body.put("s3_endpoint", containerEndpoint.baseUrl());
        body.put("s3_region", config.defaultRegion());
        // A 12-digit access key flows through AccountResolver and selects the
        // matching account partition for any S3 read/write the SQL performs.
        // Anything else falls back to the configured default account.
        body.put("s3_access_key", accessKeyId == null || accessKeyId.isEmpty() ? "test" : accessKeyId);
        body.put("s3_secret_key", secretAccessKey == null || secretAccessKey.isEmpty() ? "test" : secretAccessKey);
        body.put("s3_url_style", "path");
        return body;
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

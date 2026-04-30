package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.*;
import io.github.hectorvent.floci.services.athena.FlociDuckManager;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class AthenaService {

    private static final Logger LOG = Logger.getLogger(AthenaService.class);
    private static final String DEFAULT_OUTPUT_BUCKET = "floci-athena-results";

    private final StorageBackend<String, QueryExecution> queryStore;
    private final FlociDuckManager duckManager;
    private final GlueService glueService;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final DockerHostResolver dockerHostResolver;
    private final EmbeddedDnsServer embeddedDnsServer;
    private final Vertx vertx;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    @Inject
    public AthenaService(StorageFactory storageFactory,
                         FlociDuckManager duckManager,
                         GlueService glueService,
                         S3Service s3Service,
                         EmulatorConfig config,
                         DockerHostResolver dockerHostResolver,
                         EmbeddedDnsServer embeddedDnsServer,
                         Vertx vertx,
                         ObjectMapper mapper) {
        this.queryStore = storageFactory.create("athena", "queries.json",
                new TypeReference<Map<String, QueryExecution>>() {});
        this.duckManager = duckManager;
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.config = config;
        this.dockerHostResolver = dockerHostResolver;
        this.embeddedDnsServer = embeddedDnsServer;
        this.vertx = vertx;
        this.mapper = mapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    public String startQueryExecution(String query,
                                      String workGroup,
                                      QueryExecutionContext context,
                                      ResultConfiguration resultConfiguration) {
        String id = UUID.randomUUID().toString();
        String database = context != null && context.getDatabase() != null ? context.getDatabase() : "default";

        // Ensure output location has a trailing slash so floci-duck writes into the prefix
        String outputLocation = resolveOutputLocation(resultConfiguration, id);
        ResultConfiguration resolvedResult = new ResultConfiguration(outputLocation);

        QueryExecution execution = new QueryExecution(id, query, workGroup, resolvedResult, context);
        execution.getStatus().setState(QueryExecutionState.RUNNING);
        queryStore.put(id, execution);

        if (config.services().athena().mock()) {
            execution.getStatus().setState(QueryExecutionState.SUCCEEDED);
            execution.getStatus().setCompletionDateTime(Instant.now());
            queryStore.put(id, execution);
            LOG.infov("Query {0} accepted (mock mode)", id);
            return id;
        }

        // Submit async — caller gets the ID immediately while execution runs in background
        String finalDatabase = database;
        vertx.executeBlocking(() -> {
            String duckUrl = duckManager.ensureReady();
            String setupDdl = buildGlueDdl(finalDatabase);
            callDuck(duckUrl, query, setupDdl, outputLocation, id);
            return null;
        }).onSuccess(v -> {
            execution.getStatus().setState(QueryExecutionState.SUCCEEDED);
            execution.getStatus().setCompletionDateTime(Instant.now());
            queryStore.put(id, execution);
            LOG.infov("Query {0} succeeded", id);
        }).onFailure(e -> {
            execution.getStatus().setState(QueryExecutionState.FAILED);
            execution.getStatus().setStateChangeReason(e.getMessage());
            queryStore.put(id, execution);
            LOG.warnv("Query {0} failed: {1}", id, e.getMessage());
        });

        return id;
    }

    public QueryExecution getQueryExecution(String id) {
        return queryStore.get(id)
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "Query execution not found: " + id, 400));
    }

    public List<QueryExecution> listQueryExecutions() {
        return queryStore.scan(k -> true);
    }

    public ResultSet getQueryResults(String id) {
        QueryExecution execution = getQueryExecution(id);

        if (execution.getStatus().getState() != QueryExecutionState.SUCCEEDED) {
            throw new AwsException("InvalidRequestException", "Query has not succeeded yet", 400);
        }

        if (config.services().athena().mock()
                || execution.getResultConfiguration() == null
                || execution.getResultConfiguration().getOutputLocation() == null) {
            return new ResultSet(List.of(), new ResultSet.ResultSetMetadata(List.of()));
        }

        return readResultsFromS3(execution.getResultConfiguration().getOutputLocation(), id);
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String buildGlueDdl(String database) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Table> tables = glueService.getTables(database);
            for (Table table : tables) {
                String location = table.getStorageDescriptor() != null
                        ? table.getStorageDescriptor().getLocation()
                        : null;
                if (location == null || location.isBlank()) {
                    continue;
                }
                String readFn = inferReadFunction(table);
                String normalizedLocation = location.endsWith("/")
                        ? location.substring(0, location.length() - 1) : location;
                sb.append("CREATE OR REPLACE VIEW \"")
                  .append(table.getName())
                  .append("\" AS SELECT * FROM ")
                  .append(readFn)
                  .append("('").append(normalizedLocation).append("/**');\n");
            }
        } catch (Exception e) {
            LOG.debugv("Could not inject Glue DDL for database {0}: {1}", database, e.getMessage());
        }
        return sb.toString();
    }

    private String inferReadFunction(Table table) {
        if (table.getStorageDescriptor() == null) {
            return "read_csv_auto";
        }
        String format = table.getStorageDescriptor().getInputFormat();
        String serde = table.getStorageDescriptor().getSerdeInfo() != null
                ? table.getStorageDescriptor().getSerdeInfo().getSerializationLibrary()
                : null;
        if (containsIgnoreCase(format, "parquet") || containsIgnoreCase(serde, "parquet")) {
            return "read_parquet";
        }
        if (containsIgnoreCase(format, "json") || containsIgnoreCase(serde, "json")
                || containsIgnoreCase(format, "hive")) {
            return "read_json_auto";
        }
        return "read_csv_auto";
    }

    private static boolean containsIgnoreCase(String str, String sub) {
        return str != null && str.toLowerCase().contains(sub);
    }

    private String resolveOutputLocation(ResultConfiguration rc, String queryId) {
        String base = (rc != null && rc.getOutputLocation() != null && !rc.getOutputLocation().isBlank())
                ? rc.getOutputLocation()
                : "s3://" + DEFAULT_OUTPUT_BUCKET + "/results/";
        return base.endsWith("/") ? base + queryId + "/" : base + "/" + queryId + "/";
    }

    private void callDuck(String duckUrl, String sql, String setupDdl, String outputS3Path, String queryId) {
        try {
            // Ensure the output bucket exists
            String bucket = extractBucket(outputS3Path);
            if (bucket != null) {
                try {
                    s3Service.createBucket(bucket, config.defaultRegion());
                } catch (Exception ignored) {}
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sql", sql);
            if (setupDdl != null && !setupDdl.isBlank()) {
                body.put("setup_sql", setupDdl);
            }
            body.put("s3_endpoint", resolveS3EndpointForDuck(
                    config.baseUrl(),
                    embeddedDnsServer.getServerIp(),
                    config.hostname(),
                    dockerHostResolver.resolve()));
            body.put("s3_region", config.defaultRegion());
            body.put("s3_access_key", "test");
            body.put("s3_secret_key", "test");
            body.put("s3_url_style", "path");
            body.put("output_s3_path", outputS3Path + "results.csv");

            String json = mapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(duckUrl + "/execute"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("floci-duck returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to call floci-duck for query " + queryId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the Floci S3 endpoint URL that floci-duck uses to read source data.
     *
     * <p>{@code config.baseUrl()} typically points to {@code http://localhost:4566}, which
     * inside the floci-duck container resolves to the duck container itself, not Floci.
     * We follow the same pattern as
     * {@code services.lambda.launcher.ContainerLauncher}: prefer the DNS-resolvable
     * Floci hostname when the embedded DNS is active, otherwise fall back to the
     * Docker-host address (Floci's container IP, or {@code host.docker.internal}).
     *
     * <p>Visible for testing.
     */
    static String resolveS3EndpointForDuck(String configBaseUrl,
                                            Optional<String> embeddedDnsServerIp,
                                            Optional<String> configHostname,
                                            String dockerHostAddress) {
        int flociPort = URI.create(configBaseUrl).getPort();
        String hostname = embeddedDnsServerIp.isPresent()
                ? configHostname.orElse(EmbeddedDnsServer.DEFAULT_SUFFIX)
                : dockerHostAddress;
        return "http://" + hostname + ":" + flociPort;
    }

    private ResultSet readResultsFromS3(String outputLocation, String queryId) {
        try {
            String bucket = extractBucket(outputLocation);
            String prefix = extractKey(outputLocation);
            if (bucket == null) {
                return emptyResultSet();
            }

            List<S3Object> objects = s3Service.listObjects(bucket, prefix, null, 10);
            Optional<S3Object> csv = objects.stream()
                    .filter(o -> o.getKey().endsWith(".csv"))
                    .findFirst()
                    .map(o -> s3Service.getObject(bucket, o.getKey()));

            if (csv.isEmpty()) {
                return emptyResultSet();
            }

            return parseCsv(csv.get().getData());
        } catch (Exception e) {
            LOG.warnv("Could not read query results for {0}: {1}", queryId, e.getMessage());
            return emptyResultSet();
        }
    }

    private ResultSet parseCsv(byte[] data) {
        List<ResultSet.Row> rows = new ArrayList<>();
        List<ResultSet.ColumnInfo> columns = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return emptyResultSet();
            }

            String[] headers = splitCsv(headerLine);
            for (String h : headers) {
                columns.add(new ResultSet.ColumnInfo(h, "varchar"));
            }

            // Header row is included in GetQueryResults per AWS spec
            rows.add(toRow(headers));

            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(toRow(splitCsv(line)));
            }
        } catch (Exception e) {
            LOG.debugv("CSV parse error: {0}", e.getMessage());
        }

        return new ResultSet(rows, new ResultSet.ResultSetMetadata(columns));
    }

    private ResultSet.Row toRow(String[] values) {
        List<ResultSet.Datum> data = new ArrayList<>();
        for (String v : values) {
            data.add(new ResultSet.Datum(v));
        }
        return new ResultSet.Row(data);
    }

    /** Minimal CSV split — handles quoted fields. */
    private String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    private String extractBucket(String s3Path) {
        if (s3Path == null || !s3Path.startsWith("s3://")) {
            return null;
        }
        String without = s3Path.substring(5);
        int slash = without.indexOf('/');
        return slash < 0 ? without : without.substring(0, slash);
    }

    private String extractKey(String s3Path) {
        if (s3Path == null || !s3Path.startsWith("s3://")) {
            return "";
        }
        String without = s3Path.substring(5);
        int slash = without.indexOf('/');
        return slash < 0 ? "" : without.substring(slash + 1);
    }

    private ResultSet emptyResultSet() {
        return new ResultSet(List.of(), new ResultSet.ResultSetMetadata(List.of()));
    }
}

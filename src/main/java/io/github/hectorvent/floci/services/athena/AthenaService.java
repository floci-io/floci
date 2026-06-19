package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CsvParser;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.athena.model.*;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.floci.duck.FlociDuckClient;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Database;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@ApplicationScoped
public class AthenaService {

    private static final Logger LOG = Logger.getLogger(AthenaService.class);
    public static final String DEFAULT_CATALOG = "AwsDataCatalog";
    private static final String DEFAULT_OUTPUT_BUCKET = "floci-athena-results";
    private static final String DEFAULT_WORKGROUP = "primary";
    private static final String DEFAULT_ENGINE_VERSION = "Athena engine version 3";

    private final StorageBackend<String, QueryExecution> queryStore;
    private final StorageBackend<String, WorkGroup> workGroupStore;
    private final FlociDuckClient duckClient;
    private final GlueService glueService;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final Vertx vertx;

    @Inject
    public AthenaService(StorageFactory storageFactory,
                         FlociDuckClient duckClient,
                         GlueService glueService,
                         S3Service s3Service,
                         EmulatorConfig config,
                         Vertx vertx) {
        this.queryStore = storageFactory.create("athena", "queries.json",
                new TypeReference<>() {});
        this.workGroupStore = storageFactory.create("athena", "workgroups.json",
                new TypeReference<>() {});
        this.duckClient = duckClient;
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.config = config;
        this.vertx = vertx;
    }

    public String startQueryExecution(String query,
                                      String workGroup,
                                      QueryExecutionContext context,
                                      ResultConfiguration resultConfiguration) {
        String id = UUID.randomUUID().toString();
        String database = context != null && context.getDatabase() != null ? context.getDatabase() : "default";
        QueryExecutionContext resolvedContext = context != null ? context : new QueryExecutionContext();
        resolvedContext.setDatabase(database);
        if (resolvedContext.getCatalog() == null || resolvedContext.getCatalog().isBlank()) {
            resolvedContext.setCatalog(DEFAULT_CATALOG);
        }

        // Ensure output location has a trailing slash so floci-duck writes into the prefix
        String outputLocation = resolveOutputLocation(resultConfiguration, id);
        ResultConfiguration resolvedResult = new ResultConfiguration(outputLocation);

        QueryExecution execution = new QueryExecution(id, query, workGroup, resolvedResult, resolvedContext);
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
        vertx.executeBlocking(() -> {
            String setupDdl = buildGlueDdl(database);
            ensureOutputBucket(outputLocation);
            duckClient.execute(query, setupDdl, outputLocation + "results.csv");
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

    public void stopQueryExecution(String id) {
        QueryExecution execution = getQueryExecution(id);
        execution.getStatus().setState(QueryExecutionState.CANCELLED);
        execution.getStatus().setCompletionDateTime(Instant.now());
        queryStore.put(id, execution);
    }

    public WorkGroup createWorkGroup(CreateWorkGroupRequest request, String region) {
        validateWorkGroupName(request.getName());
        if (DEFAULT_WORKGROUP.equals(request.getName())) {
            throw new AwsException("InvalidRequestException",
                    DEFAULT_WORKGROUP + " workGroup could not be created", 400);
        }
        String key = workGroupKey(region, request.getName());
        if (workGroupStore.get(key).isPresent()) {
            throw new AwsException("InvalidRequestException", "WorkGroup already exists", 400);
        }

        WorkGroup workGroup = new WorkGroup();
        workGroup.setName(request.getName());
        workGroup.setDescription(request.getDescription());
        workGroup.setState("ENABLED");
        workGroup.setCreationTime(Instant.now());
        workGroup.setTags(normalizeTags(request.getTags()));
        workGroup.setConfiguration(normalizeWorkGroupConfiguration(request.getConfiguration()));
        workGroupStore.put(key, workGroup);
        return workGroup;
    }

    public Map<String, Object> getWorkGroup(String name, String region) {
        String resolved = name == null || name.isBlank() ? DEFAULT_WORKGROUP : name;
        if (DEFAULT_WORKGROUP.equals(resolved)) {
            return primaryWorkGroupSummary();
        }
        WorkGroup workGroup = workGroupStore.get(workGroupKey(region, resolved))
                .orElseThrow(() -> new AwsException("InvalidRequestException",
                        "WorkGroup " + resolved + " is not found.", 400));
        return toWorkGroupDetail(workGroup);
    }

    public void deleteWorkGroup(String name, String region) {
        workGroupStore.delete(workGroupKey(region, name));
    }

    public List<Map<String, Object>> listWorkGroups(String region) {
        List<Map<String, Object>> workGroups = new ArrayList<>();
        workGroups.add(primaryWorkGroupSummary());
        workGroups.addAll(workGroupStore.scan(k -> k.startsWith(region + ":")).stream()
                .sorted(Comparator.comparing(WorkGroup::getName))
                .map(this::toWorkGroupSummary)
                .toList());
        return workGroups;
    }

    public List<Map<String, Object>> listDataCatalogs() {
        return List.of(Map.of("CatalogName", DEFAULT_CATALOG, "Type", "GLUE"));
    }

    public Map<String, Object> getDataCatalog(String name) {
        return Map.of("Name", name == null || name.isBlank() ? DEFAULT_CATALOG : name, "Type", "GLUE");
    }

    public List<Map<String, Object>> listDatabases(String catalog) {
        return glueService.getDatabases().stream()
                .map(Database::getName)
                .sorted()
                .map(name -> Map.<String, Object>of("Name", name))
                .toList();
    }

    public List<Map<String, Object>> listTableMetadata(String catalog, String database) {
        return glueService.getTables(database).stream()
                .sorted(Comparator.comparing(Table::getName))
                .map(table -> tableMetadata(catalog, database, table))
                .toList();
    }

    public Map<String, Object> getTableMetadata(String catalog, String database, String tableName) {
        return tableMetadata(catalog, database, glueService.getTable(database, tableName));
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
                  .append(readExpression(readFn, normalizedLocation))
                  .append(";\n");
            }
        } catch (Exception e) {
            LOG.debugv("Could not inject Glue DDL for database {0}: {1}", database, e.getMessage());
        }
        return sb.toString();
    }

    private String readExpression(String readFn, String normalizedLocation) {
        String glob = normalizedLocation + "/**";
        if ("read_parquet".equals(readFn)) {
            return "read_parquet('" + glob + "', union_by_name = true)";
        }
        return readFn + "('" + glob + "')";
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

    private WorkGroupConfiguration normalizeWorkGroupConfiguration(CreateWorkGroupConfigurationRequest configuration) {
        WorkGroupConfiguration normalized = defaultWorkGroupConfiguration();
        if (configuration == null) {
            return normalized;
        }

        if (configuration.getResultConfiguration() != null
                && configuration.getResultConfiguration().getOutputLocation() != null
                && !configuration.getResultConfiguration().getOutputLocation().isBlank()) {
            normalized.setResultConfiguration(
                    new ResultConfiguration(configuration.getResultConfiguration().getOutputLocation()));
        }
        if (configuration.getEnforceWorkGroupConfiguration() != null) {
            normalized.setEnforceWorkGroupConfiguration(configuration.getEnforceWorkGroupConfiguration());
        }
        if (configuration.getPublishCloudWatchMetricsEnabled() != null) {
            normalized.setPublishCloudWatchMetricsEnabled(configuration.getPublishCloudWatchMetricsEnabled());
        }
        if (configuration.getRequesterPaysEnabled() != null) {
            normalized.setRequesterPaysEnabled(configuration.getRequesterPaysEnabled());
        }
        if (configuration.getBytesScannedCutoffPerQuery() != null) {
            normalized.setBytesScannedCutoffPerQuery(configuration.getBytesScannedCutoffPerQuery());
        }
        if (configuration.getEngineVersion() != null) {
            String selectedEngineVersion = configuration.getEngineVersion().getSelectedEngineVersion();
            boolean hasSelectedEngineVersion = selectedEngineVersion != null && !selectedEngineVersion.isBlank();

            if (hasSelectedEngineVersion) {
                QueryExecution.EngineVersion engineVersion = new QueryExecution.EngineVersion();
                engineVersion.setSelectedEngineVersion(selectedEngineVersion);
                engineVersion.setEffectiveEngineVersion(resolveEffectiveEngineVersion(selectedEngineVersion));
                normalized.setEngineVersion(engineVersion);
            }
        }
        return normalized;
    }

    private String resolveEffectiveEngineVersion(String selectedEngineVersion) {
        if (selectedEngineVersion == null || selectedEngineVersion.isBlank() || "AUTO".equals(selectedEngineVersion)) {
            return DEFAULT_ENGINE_VERSION;
        }
        return selectedEngineVersion;
    }

    private WorkGroupConfiguration defaultWorkGroupConfiguration() {
        WorkGroupConfiguration configuration = new WorkGroupConfiguration();
        configuration.setResultConfiguration(new ResultConfiguration("s3://" + DEFAULT_OUTPUT_BUCKET + "/results/"));
        configuration.setEnforceWorkGroupConfiguration(false);
        configuration.setPublishCloudWatchMetricsEnabled(false);
        configuration.setRequesterPaysEnabled(false);
        configuration.setEngineVersion(defaultEngineVersion());
        return configuration;
    }

    private QueryExecution.EngineVersion defaultEngineVersion() {
        QueryExecution.EngineVersion engineVersion = new QueryExecution.EngineVersion();
        engineVersion.setSelectedEngineVersion(DEFAULT_ENGINE_VERSION);
        engineVersion.setEffectiveEngineVersion(DEFAULT_ENGINE_VERSION);
        return engineVersion;
    }

    private Map<String, Object> primaryWorkGroupSummary() {
        return Map.of(
                "Name", DEFAULT_WORKGROUP,
                "State", "ENABLED",
                "Configuration", Map.of(
                        "EngineVersion", Map.of(
                                "SelectedEngineVersion", DEFAULT_ENGINE_VERSION,
                                "EffectiveEngineVersion", DEFAULT_ENGINE_VERSION
                        ),
                        "ResultConfiguration", Map.of("OutputLocation", "s3://" + DEFAULT_OUTPUT_BUCKET + "/results/"),
                        "EnforceWorkGroupConfiguration", false,
                        "PublishCloudWatchMetricsEnabled", false,
                        "RequesterPaysEnabled", false
                )
        );
    }

    private Map<String, Object> toWorkGroupDetail(WorkGroup workGroup) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("Name", workGroup.getName());
        detail.put("State", workGroup.getState());
        if (workGroup.getDescription() != null) {
            detail.put("Description", workGroup.getDescription());
        }
        if (workGroup.getCreationTime() != null) {
            detail.put("CreationTime", workGroup.getCreationTime());
        }
        if (workGroup.getConfiguration() != null) {
            detail.put("Configuration", workGroup.getConfiguration());
        }
        return detail;
    }

    private Map<String, Object> toWorkGroupSummary(WorkGroup workGroup) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Name", workGroup.getName());
        result.put("State", workGroup.getState());
        return result;
    }

    private List<WorkGroupTag> normalizeTags(List<WorkGroupTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return tags.stream()
                .filter(Objects::nonNull)
                .map(tag -> new WorkGroupTag(tag.getKey(), tag.getValue()))
                .toList();
    }

    private String workGroupKey(String region, String name) {
        return region + ":" + name;
    }

    private void validateWorkGroupName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidRequestException", "WorkGroup name is required", 400);
        }
        if (!name.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new AwsException("InvalidRequestException", "Invalid WorkGroup name: " + name, 400);
        }
    }

    private void ensureOutputBucket(String s3Path) {
        String bucket = extractBucket(s3Path);
        if (bucket != null) {
            try {
                s3Service.createBucket(bucket, config.defaultRegion());
            } catch (Exception ignored) {}
        }
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

            String[] headers = CsvParser.parseLine(headerLine).toArray(String[]::new);
            for (String h : headers) {
                columns.add(new ResultSet.ColumnInfo(DEFAULT_CATALOG, "", "", h, "varchar"));
            }

            // Header row is included in GetQueryResults per AWS spec
            rows.add(toRow(headers));

            String line;
            while ((line = reader.readLine()) != null) {
                rows.add(toRow(CsvParser.parseLine(line).toArray(String[]::new)));
            }
        } catch (Exception e) {
            LOG.debugv("CSV parse error: {0}", e.getMessage());
        }

        return new ResultSet(rows, new ResultSet.ResultSetMetadata(columns));
    }

    private Map<String, Object> tableMetadata(String catalog, String database, Table table) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("Name", table.getName());
        metadata.put("CreateTime", table.getCreateTime() != null ? table.getCreateTime() : Instant.now());
        metadata.put("LastAccessTime", table.getLastAccessTime() != null ? table.getLastAccessTime() : Instant.now());
        metadata.put("TableType", table.getTableType() != null ? table.getTableType() : "EXTERNAL_TABLE");
        metadata.put("Columns", athenaColumns(table));
        metadata.put("Parameters", table.getParameters() != null ? table.getParameters() : Map.of());
        metadata.put("PartitionKeys", athenaColumns(table.getPartitionKeys()));
        return metadata;
    }

    private List<Map<String, String>> athenaColumns(Table table) {
        if (table.getStorageDescriptor() == null) {
            return List.of();
        }
        return athenaColumns(table.getStorageDescriptor().getColumns());
    }

    private List<Map<String, String>> athenaColumns(List<Column> columns) {
        if (columns == null) {
            return List.of();
        }
        return columns.stream()
                .map(column -> Map.of(
                        "Name", column.getName(),
                        "Type", glueTypeToAthena(column)
                ))
                .toList();
    }

    private String glueTypeToAthena(Column column) {
        String type = column.getType() == null ? "string" : column.getType().toLowerCase(Locale.ROOT);
        if (type.equals("string") || type.equals("char") || type.equals("varchar")
                || type.startsWith("struct<") || type.startsWith("array<") || type.startsWith("map<")) {
            return "varchar";
        }
        return type;
    }

    private ResultSet.Row toRow(String[] values) {
        List<ResultSet.Datum> data = new ArrayList<>();
        for (String v : values) {
            data.add(new ResultSet.Datum(v));
        }
        return new ResultSet.Row(data);
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

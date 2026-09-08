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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class AthenaService {

    private static final Logger LOG = Logger.getLogger(AthenaService.class);
    public static final String DEFAULT_CATALOG = "AwsDataCatalog";
    private static final String DEFAULT_OUTPUT_BUCKET = "floci-athena-results";
    private static final String DEFAULT_WORKGROUP = "primary";
    private static final String DEFAULT_ENGINE_VERSION = "Athena engine version 3";
    private static final Pattern CREATE_DATABASE_PATTERN = Pattern.compile(
            "^\\s*CREATE\\s+(?:DATABASE|SCHEMA)\\s+"
                    + "(IF\\s+NOT\\s+EXISTS\\s+)?"
                    + "(?:`([^`]+)`|\\\"([^\\\"]+)\\\"|([a-zA-Z0-9_-]+))"
                    + "(?:\\s+COMMENT\\s+'((?:''|[^'])*)')?"
                    + "(?:\\s+LOCATION\\s+'((?:''|[^'])*)')?"
                    + "(?:\\s+WITH\\s+DBPROPERTIES\\s*\\((.*?)\\))?"
                    + "\\s*;?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CREATE_DATABASE_PREFIX_PATTERN = Pattern.compile(
            "^\\s*CREATE\\s+(?:DATABASE|SCHEMA)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DATABASE_PROPERTY_PATTERN = Pattern.compile(
            "\\s*'((?:''|[^'])*)'\\s*=\\s*'((?:''|[^'])*)'\\s*");
    private static final Pattern RESULT_STATEMENT_PATTERN = Pattern.compile(
            "^(?:SELECT|WITH|SHOW|DESCRIBE|DESC|EXPLAIN|VALUES)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final StorageBackend<String, QueryExecution> queryStore;
    private final StorageBackend<String, WorkGroup> workGroupStore;
    private final FlociDuckClient duckClient;
    private final GlueService glueService;
    private final S3Service s3Service;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final GlueViewDdlBuilder ddlBuilder;

    @Inject
    public AthenaService(StorageFactory storageFactory,
                         FlociDuckClient duckClient,
                         GlueService glueService,
                         S3Service s3Service,
                         EmulatorConfig config,
                         Vertx vertx,
                         GlueViewDdlBuilder ddlBuilder) {
        this.queryStore = storageFactory.create("athena", "queries.json",
                new TypeReference<>() {});
        this.workGroupStore = storageFactory.create("athena", "workgroups.json",
                new TypeReference<>() {});
        this.duckClient = duckClient;
        this.glueService = glueService;
        this.s3Service = s3Service;
        this.config = config;
        this.vertx = vertx;
        this.ddlBuilder = ddlBuilder;
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

        boolean createDatabaseStatement = CREATE_DATABASE_PREFIX_PATTERN.matcher(statementText(query)).find();
        // AWS reports the result CSV object itself as the OutputLocation, not a
        // directory prefix — the same key is written and returned to the client.
        // Statements that do not return rows must not be wrapped in a result COPY.
        String outputLocation = producesResultRows(query)
                ? resolveOutputLocation(resultConfiguration, id)
                : null;
        ResultConfiguration resolvedResult = outputLocation != null
                ? new ResultConfiguration(outputLocation)
                : null;

        QueryExecution execution = new QueryExecution(id, query, workGroup, resolvedResult, resolvedContext);
        if (createDatabaseStatement) {
            execution.setStatementType("DDL");
        }
        execution.getStatus().setState(QueryExecutionState.RUNNING);
        queryStore.put(id, execution);

        if (createDatabaseStatement) {
            try {
                CreateDatabaseDdl createDatabaseDdl = parseCreateDatabase(query);
                if (createDatabaseDdl == null) {
                    throw new AwsException("InvalidRequestException", "Invalid CREATE DATABASE statement", 400);
                }
                createGlueDatabase(createDatabaseDdl);
                markSucceeded(id, execution);
            } catch (Exception e) {
                markFailed(id, execution, e);
            }
            return id;
        }

        if (config.services().athena().mock()) {
            markSucceeded(id, execution);
            LOG.infov("Query {0} accepted (mock mode)", id);
            return id;
        }

        // Submit async — caller gets the ID immediately while execution runs in background
        vertx.executeBlocking(() -> {
            String setupDdl = ddlBuilder.build(database);
            if (outputLocation != null) {
                ensureOutputBucket(outputLocation);
            }
            duckClient.execute(query, setupDdl, outputLocation);
            return null;
        }).onSuccess(v -> {
            markSucceeded(id, execution);
            LOG.infov("Query {0} succeeded", id);
        }).onFailure(e -> {
            markFailed(id, execution, e);
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

    private CreateDatabaseDdl parseCreateDatabase(String query) {
        Matcher matcher = CREATE_DATABASE_PATTERN.matcher(statementText(query));
        if (!matcher.matches()) {
            return null;
        }
        String name = firstNonNull(matcher.group(2), matcher.group(3), matcher.group(4));
        return new CreateDatabaseDdl(
                name,
                matcher.group(1) != null,
                unescapeSqlString(matcher.group(5)),
                unescapeSqlString(matcher.group(6)),
                parseDatabaseProperties(matcher.group(7)));
    }

    private void createGlueDatabase(CreateDatabaseDdl statement) {
        Database database = new Database(statement.name());
        database.setDescription(statement.comment());
        database.setLocationUri(statement.location());
        database.setParameters(statement.properties());
        try {
            glueService.createDatabase(database);
        } catch (AwsException e) {
            if (!statement.ifNotExists() || !"AlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }

    private static Map<String, String> parseDatabaseProperties(String clause) {
        if (clause == null) {
            return null;
        }
        Map<String, String> properties = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < clause.length()) {
            Matcher matcher = DATABASE_PROPERTY_PATTERN.matcher(clause);
            matcher.region(cursor, clause.length());
            if (!matcher.lookingAt()) {
                throw new AwsException("InvalidRequestException", "Invalid database properties", 400);
            }
            properties.put(unescapeSqlString(matcher.group(1)), unescapeSqlString(matcher.group(2)));
            cursor = matcher.end();
            if (cursor == clause.length()) {
                break;
            }
            if (clause.charAt(cursor) != ',') {
                throw new AwsException("InvalidRequestException", "Invalid database properties", 400);
            }
            cursor++;
            if (clause.substring(cursor).isBlank()) {
                throw new AwsException("InvalidRequestException", "Invalid database properties", 400);
            }
        }
        return properties;
    }

    private static boolean producesResultRows(String query) {
        return RESULT_STATEMENT_PATTERN.matcher(statementText(query)).find();
    }

    private static String statementText(String query) {
        String statement = query == null ? "" : query.stripLeading();
        while (true) {
            if (statement.startsWith("--")) {
                int lineEnd = statement.indexOf('\n');
                statement = lineEnd >= 0 ? statement.substring(lineEnd + 1).stripLeading() : "";
                continue;
            }
            if (statement.startsWith("/*")) {
                int commentEnd = statement.indexOf("*/", 2);
                if (commentEnd < 0) {
                    return statement;
                }
                statement = statement.substring(commentEnd + 2).stripLeading();
                continue;
            }
            return statement;
        }
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String unescapeSqlString(String value) {
        return value == null ? null : value.replace("''", "'");
    }

    private void markSucceeded(String id, QueryExecution execution) {
        execution.getStatus().setState(QueryExecutionState.SUCCEEDED);
        execution.getStatus().setCompletionDateTime(Instant.now());
        queryStore.put(id, execution);
    }

    private void markFailed(String id, QueryExecution execution, Throwable failure) {
        execution.getStatus().setState(QueryExecutionState.FAILED);
        execution.getStatus().setStateChangeReason(failure.getMessage());
        queryStore.put(id, execution);
    }

    private record CreateDatabaseDdl(String name, boolean ifNotExists, String comment,
                                     String location, Map<String, String> properties) {
    }

    private String resolveOutputLocation(ResultConfiguration rc, String queryId) {
        String base = (rc != null && rc.getOutputLocation() != null && !rc.getOutputLocation().isBlank())
                ? rc.getOutputLocation()
                : "s3://" + DEFAULT_OUTPUT_BUCKET + "/results/";
        return base.endsWith("/") ? base + queryId + ".csv" : base + "/" + queryId + ".csv";
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
            detail.put("CreationTime", workGroup.getCreationTime().getEpochSecond());
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
        metadata.put("CreateTime", (table.getCreateTime() != null ? table.getCreateTime() : Instant.now()).getEpochSecond());
        metadata.put("LastAccessTime", (table.getLastAccessTime() != null ? table.getLastAccessTime() : Instant.now()).getEpochSecond());
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

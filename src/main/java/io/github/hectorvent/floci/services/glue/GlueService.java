package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.Classifier;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Connection;
import io.github.hectorvent.floci.services.glue.model.ConnectionInput;
import io.github.hectorvent.floci.services.glue.model.ConnectionPasswordEncryption;
import io.github.hectorvent.floci.services.glue.model.Crawler;
import io.github.hectorvent.floci.services.glue.model.CrawlerTargets;
import io.github.hectorvent.floci.services.glue.model.DataCatalogEncryptionSettings;
import io.github.hectorvent.floci.services.glue.model.Database;
import io.github.hectorvent.floci.services.glue.model.EncryptionAtRest;
import io.github.hectorvent.floci.services.glue.model.GluePolicy;
import io.github.hectorvent.floci.services.glue.model.Job;
import io.github.hectorvent.floci.services.glue.model.JobUpdate;
import io.github.hectorvent.floci.services.glue.model.KeySchemaElement;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.PartitionIndex;
import io.github.hectorvent.floci.services.glue.model.PartitionIndexDescriptor;
import io.github.hectorvent.floci.services.glue.model.Predicate;
import io.github.hectorvent.floci.services.glue.model.Schedule;
import io.github.hectorvent.floci.services.glue.model.SchemaReference;
import io.github.hectorvent.floci.services.glue.model.SecurityConfiguration;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import io.github.hectorvent.floci.services.glue.model.Trigger;
import io.github.hectorvent.floci.services.glue.model.TriggerAction;
import io.github.hectorvent.floci.services.glue.model.TriggerCondition;
import io.github.hectorvent.floci.services.glue.model.UserDefinedFunction;
import io.github.hectorvent.floci.services.glue.model.Workflow;
import io.github.hectorvent.floci.services.glue.schemaregistry.GlueSchemaRegistryService;
import io.github.hectorvent.floci.services.glue.schemaregistry.SchemaToColumnsConverter;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaId;
import io.github.hectorvent.floci.services.glue.schemaregistry.model.SchemaVersion;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@ApplicationScoped
public class GlueService {

    private static final Logger LOG = Logger.getLogger(GlueService.class);
    private static final int MAX_FUNCTION_PATTERN_LENGTH = 255;
    private static final int MAX_FUNCTION_RESULTS = 100;
    private static final int MAX_SECURITY_CONFIGURATION_NAME_LENGTH = 255;
    private static final Set<String> CSV_HEADER_VALUES = Set.of("UNKNOWN", "PRESENT", "ABSENT");
    private static final Set<String> CSV_SERDE_VALUES = Set.of("OpenCSVSerDe", "LazySimpleSerDe", "None");
    private static final Set<String> CSV_CUSTOM_DATATYPES = Set.of(
            "BINARY", "BOOLEAN", "DATE", "DECIMAL", "DOUBLE", "FLOAT",
            "INT", "LONG", "SHORT", "STRING", "TIMESTAMP");
    static final String COLUMN_NAME = "ColumnName";
    static final String COLUMN_TYPE = "ColumnType";
    static final String ANALYZED_TIME = "AnalyzedTime";
    static final String STATISTICS_DATA = "StatisticsData";
    private static final Pattern COMPARISON_EXPRESSION = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(=|<>|<=|>=|<|>)\\s*('?[^']*'?|[^\\s]+)\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_EXPRESSION = Pattern.compile(
            "\\s*([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s*\\((.*)\\)\\s*",
            Pattern.CASE_INSENSITIVE);

    /** Measured against real Glue (us-west-2): a fourth index reports the limit. */
    private static final int MAX_PARTITION_INDEXES_PER_TABLE = 3;

    // Connection limits and enumerations from the Glue API reference (ConnectionInput and the
    // Connection structure). AWS rejects a value outside them with InvalidInputException.
    private static final int MAX_CONNECTION_NAME_LENGTH = 255;
    private static final int MAX_CONNECTION_DESCRIPTION_LENGTH = 2048;
    private static final int MAX_CONNECTION_MATCH_CRITERIA = 10;
    private static final int MAX_CONNECTION_PROPERTIES = 100;
    private static final int MAX_BATCH_DELETE_CONNECTIONS = 25;
    private static final Set<String> CONNECTION_TYPES = Set.of(
            "JDBC", "SFTP", "MONGODB", "KAFKA", "NETWORK", "MARKETPLACE", "CUSTOM", "SALESFORCE",
            "VIEW_VALIDATION_REDSHIFT", "VIEW_VALIDATION_ATHENA", "GOOGLEADS", "GOOGLESHEETS",
            "GOOGLEANALYTICS4", "SERVICENOW", "MARKETO", "SAPODATA", "ZENDESK", "JIRACLOUD", "NETSUITEERP",
            "HUBSPOT", "FACEBOOKADS", "INSTAGRAMADS", "ZOHOCRM", "SALESFORCEPARDOT",
            "SALESFORCEMARKETINGCLOUD", "ADOBEANALYTICS", "SLACK", "LINKEDIN", "MIXPANEL", "ASANA", "STRIPE",
            "SMARTSHEET", "DATADOG", "WOOCOMMERCE", "INTERCOM", "SNAPCHATADS", "PAYPAL", "QUICKBOOKS",
            "FACEBOOKPAGEINSIGHTS", "FRESHDESK", "TWILIO", "DOCUSIGNMONITOR", "FRESHSALES", "ZOOM",
            "GOOGLESEARCHCONSOLE", "SALESFORCECOMMERCECLOUD", "SAPCONCUR", "DYNATRACE",
            "MICROSOFTDYNAMIC365FINANCEANDOPS", "MICROSOFTTEAMS", "BLACKBAUDRAISEREDGENXT", "MAILCHIMP",
            "GITLAB", "PENDO", "PRODUCTBOARD", "CIRCLECI", "PIPEDIVE", "SENDGRID", "AZURECOSMOS", "AZURESQL",
            "BIGQUERY", "BLACKBAUD", "CLOUDERAHIVE", "CLOUDERAIMPALA", "CLOUDWATCH", "CLOUDWATCHMETRICS",
            "CMDB", "DATALAKEGEN2", "DB2", "DB2AS400", "DOCUMENTDB", "DOMO", "DYNAMODB",
            "GOOGLECLOUDSTORAGE", "HBASE", "KUSTOMER", "MICROSOFTDYNAMICS365CRM", "MONDAY", "MYSQL", "OKTA",
            "OPENSEARCH", "ORACLE", "PIPEDRIVE", "POSTGRESQL", "SAPHANA", "SQLSERVER", "SYNAPSE", "TERADATA",
            "TERADATANOS", "TIMESTREAM", "TPCDS", "VERTICA");
    private static final Set<String> CONNECTION_PROPERTY_KEYS = Set.of(
            "HOST", "PORT", "USERNAME", "PASSWORD", "ENCRYPTED_PASSWORD", "JDBC_DRIVER_JAR_URI",
            "JDBC_DRIVER_CLASS_NAME", "JDBC_ENGINE", "JDBC_ENGINE_VERSION", "CONFIG_FILES", "INSTANCE_ID",
            "JDBC_CONNECTION_URL", "JDBC_ENFORCE_SSL", "CUSTOM_JDBC_CERT", "SKIP_CUSTOM_JDBC_CERT_VALIDATION",
            "CUSTOM_JDBC_CERT_STRING", "CONNECTION_URL", "KAFKA_BOOTSTRAP_SERVERS", "KAFKA_SSL_ENABLED",
            "KAFKA_CUSTOM_CERT", "KAFKA_SKIP_CUSTOM_CERT_VALIDATION", "KAFKA_CLIENT_KEYSTORE",
            "KAFKA_CLIENT_KEYSTORE_PASSWORD", "KAFKA_CLIENT_KEY_PASSWORD",
            "ENCRYPTED_KAFKA_CLIENT_KEYSTORE_PASSWORD", "ENCRYPTED_KAFKA_CLIENT_KEY_PASSWORD",
            "KAFKA_SASL_MECHANISM", "KAFKA_SASL_PLAIN_USERNAME", "KAFKA_SASL_PLAIN_PASSWORD",
            "ENCRYPTED_KAFKA_SASL_PLAIN_PASSWORD", "KAFKA_SASL_SCRAM_USERNAME", "KAFKA_SASL_SCRAM_PASSWORD",
            "KAFKA_SASL_SCRAM_SECRETS_ARN", "ENCRYPTED_KAFKA_SASL_SCRAM_PASSWORD", "KAFKA_SASL_GSSAPI_KEYTAB",
            "KAFKA_SASL_GSSAPI_KRB5_CONF", "KAFKA_SASL_GSSAPI_SERVICE", "KAFKA_SASL_GSSAPI_PRINCIPAL",
            "SECRET_ID", "CONNECTOR_URL", "CONNECTOR_TYPE", "CONNECTOR_CLASS_NAME", "ENDPOINT",
            "ENDPOINT_TYPE", "ROLE_ARN", "REGION", "WORKGROUP_NAME", "CLUSTER_IDENTIFIER", "DATABASE");
    private static final String CONNECTION_STATUS_READY = "READY";

    // The catalog holds one resource policy and one security configuration; both stores are
    // account-scoped by StorageFactory, so a fixed key is the whole address.
    private static final String CATALOG_KEY = "catalog";
    private static final ObjectMapper POLICY_JSON = new ObjectMapper();
    private static final Set<String> POLICY_EXISTS_CONDITIONS = Set.of("MUST_EXIST", "NOT_EXIST", "NONE");
    private static final Set<String> ENABLE_HYBRID_VALUES = Set.of("TRUE", "FALSE");
    private static final Set<String> CATALOG_ENCRYPTION_MODES = Set.of(
            "DISABLED", "SSE-KMS", "SSE-KMS-WITH-SERVICE-ROLE");
    // The Connection structure names the stored form of each password when the catalog's
    // ConnectionPasswordEncryption is on: the plaintext key is replaced by its ENCRYPTED_ twin.
    private static final Map<String, String> ENCRYPTED_CONNECTION_PROPERTY_KEYS = Map.of(
            "PASSWORD", "ENCRYPTED_PASSWORD",
            "KAFKA_CLIENT_KEYSTORE_PASSWORD", "ENCRYPTED_KAFKA_CLIENT_KEYSTORE_PASSWORD",
            "KAFKA_CLIENT_KEY_PASSWORD", "ENCRYPTED_KAFKA_CLIENT_KEY_PASSWORD",
            "KAFKA_SASL_PLAIN_PASSWORD", "ENCRYPTED_KAFKA_SASL_PLAIN_PASSWORD",
            "KAFKA_SASL_SCRAM_PASSWORD", "ENCRYPTED_KAFKA_SASL_SCRAM_PASSWORD");

    // Glue's partition index states. FAILED also exists but is only reachable through a backfill
    // failure, which is not emulated.
    private static final String INDEX_STATUS_CREATING = "CREATING";
    private static final String INDEX_STATUS_ACTIVE = "ACTIVE";
    private static final String INDEX_STATUS_DELETING = "DELETING";

    private static final String SCHEDULE_SCHEDULED = "SCHEDULED";
    private static final String SCHEDULE_NOT_SCHEDULED = "NOT_SCHEDULED";
    private static final Pattern CRON_FIELD = Pattern.compile("[0-9A-Za-z*?/,#-]+");
    private static final Set<String> TRIGGER_TYPES = Set.of("SCHEDULED", "CONDITIONAL", "ON_DEMAND", "EVENT");
    // The job run and crawl states a trigger condition may name (JobRunState / CrawlState in the model
    // allow more, but only final states are meaningful as conditions).
    private static final Set<String> CONDITION_JOB_STATES = Set.of("SUCCEEDED", "STOPPED", "FAILED", "TIMEOUT");
    private static final Set<String> CONDITION_CRAWL_STATES = Set.of("SUCCEEDED", "CANCELLED", "FAILED");
    private static final int MAX_TRIGGERS_PAGE_SIZE = 200;
    private static final int MAX_WORKFLOWS_PAGE_SIZE = 25;
    private static final int MAX_BATCH_GET_WORKFLOWS = 25;
    // BatchGetCrawlers takes a CrawlerNameList, which the API model caps at 100 names.
    private static final int MAX_BATCH_GET_CRAWLERS = 100;

    private final StorageBackend<String, Database> databaseStore;
    private final StorageBackend<String, Table> tableStore;
    private final StorageBackend<String, Table> tableVersionStore;
    private final StorageBackend<String, Map<String, Object>> columnStatisticsStore;
    private final StorageBackend<String, Partition> partitionStore;
    private final StorageBackend<String, PartitionIndexDescriptor> partitionIndexStore;
    private final StorageBackend<String, Map<String, Object>> partitionColumnStatisticsStore;
    private final StorageBackend<String, UserDefinedFunction> functionStore;
    private final StorageBackend<String, Job> jobStore;
    private final StorageBackend<String, Crawler> crawlerStore;
    private final StorageBackend<String, Classifier> classifierStore;
    private final StorageBackend<String, Connection> connectionStore;
    private final StorageBackend<String, GluePolicy> resourcePolicyStore;
    private final StorageBackend<String, DataCatalogEncryptionSettings> encryptionSettingsStore;
    private final StorageBackend<String, SecurityConfiguration> securityConfigurationStore;
    private final StorageBackend<String, Trigger> triggerStore;
    private final StorageBackend<String, Workflow> workflowStore;
    private final GlueSchemaRegistryService schemaRegistryService;
    private final RegionResolver regionResolver;
    private final ResourceGroupsTaggingService resourceGroupsTaggingService;
    private final KmsService kmsService;

    @Inject
    public GlueService(StorageFactory storageFactory,
                       GlueSchemaRegistryService schemaRegistryService,
                       RegionResolver regionResolver,
                       ResourceGroupsTaggingService resourceGroupsTaggingService,
                       KmsService kmsService) {
        this.databaseStore = storageFactory.create("glue", "databases.json", new TypeReference<>() {});
        this.tableStore = storageFactory.create("glue", "tables.json", new TypeReference<>() {});
        this.tableVersionStore = storageFactory.create("glue", "table_versions.json", new TypeReference<>() {});
        this.columnStatisticsStore = storageFactory.create("glue", "column_statistics.json", new TypeReference<>() {});
        this.partitionStore = storageFactory.create("glue", "partitions.json", new TypeReference<>() {});
        this.partitionIndexStore = storageFactory.create(
                "glue", "partition_indexes.json", new TypeReference<>() {});
        this.partitionColumnStatisticsStore = storageFactory.create(
                "glue", "partition_column_statistics.json", new TypeReference<>() {});
        this.functionStore = storageFactory.create("glue", "functions.json", new TypeReference<>() {});
        this.jobStore = storageFactory.create("glue", "jobs.json", new TypeReference<>() {});
        this.crawlerStore = storageFactory.create("glue", "crawlers.json", new TypeReference<>() {});
        this.classifierStore = storageFactory.create("glue", "classifiers.json", new TypeReference<>() {});
        this.connectionStore = storageFactory.create("glue", "connections.json", new TypeReference<>() {});
        this.resourcePolicyStore = storageFactory.create("glue", "resource_policy.json", new TypeReference<>() {});
        this.encryptionSettingsStore = storageFactory.create(
                "glue", "catalog_encryption_settings.json", new TypeReference<>() {});
        this.securityConfigurationStore = storageFactory.create(
            "glue", "security_configurations.json", new TypeReference<>() {});
        this.triggerStore = storageFactory.create("glue", "triggers.json", new TypeReference<>() {});
        this.workflowStore = storageFactory.create("glue", "workflows.json", new TypeReference<>() {});
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
        this.resourceGroupsTaggingService = resourceGroupsTaggingService;
        this.kmsService = kmsService;
    }

    GlueService(StorageBackend<String, Database> databaseStore,
                StorageBackend<String, Table> tableStore,
                StorageBackend<String, Table> tableVersionStore,
                StorageBackend<String, Map<String, Object>> columnStatisticsStore,
                StorageBackend<String, Partition> partitionStore,
                StorageBackend<String, PartitionIndexDescriptor> partitionIndexStore,
                StorageBackend<String, Map<String, Object>> partitionColumnStatisticsStore,
                StorageBackend<String, UserDefinedFunction> functionStore,
                StorageBackend<String, Job> jobStore,
                StorageBackend<String, Crawler> crawlerStore,
                StorageBackend<String, Classifier> classifierStore,
                StorageBackend<String, Connection> connectionStore,
                StorageBackend<String, GluePolicy> resourcePolicyStore,
                StorageBackend<String, DataCatalogEncryptionSettings> encryptionSettingsStore,
                StorageBackend<String, SecurityConfiguration> securityConfigurationStore,
                StorageBackend<String, Trigger> triggerStore,
                StorageBackend<String, Workflow> workflowStore,
                GlueSchemaRegistryService schemaRegistryService,
                RegionResolver regionResolver,
                ResourceGroupsTaggingService resourceGroupsTaggingService,
                KmsService kmsService) {
        this.databaseStore = databaseStore;
        this.tableStore = tableStore;
        this.tableVersionStore = tableVersionStore;
        this.columnStatisticsStore = columnStatisticsStore;
        this.partitionStore = partitionStore;
        this.partitionIndexStore = partitionIndexStore;
        this.partitionColumnStatisticsStore = partitionColumnStatisticsStore;
        this.functionStore = functionStore;
        this.jobStore = jobStore;
        this.crawlerStore = crawlerStore;
        this.classifierStore = classifierStore;
        this.connectionStore = connectionStore;
        this.resourcePolicyStore = resourcePolicyStore;
        this.encryptionSettingsStore = encryptionSettingsStore;
        this.securityConfigurationStore = securityConfigurationStore;
        this.triggerStore = triggerStore;
        this.workflowStore = workflowStore;
        this.schemaRegistryService = schemaRegistryService;
        this.regionResolver = regionResolver;
        this.resourceGroupsTaggingService = resourceGroupsTaggingService;
        this.kmsService = kmsService;
    }

    public SecurityConfiguration createSecurityConfiguration(String name, JsonNode encryptionConfiguration,
                                                              String region) {
        validateSecurityConfigurationName(name);
        if (encryptionConfiguration == null || encryptionConfiguration.isNull()) {
            throw new AwsException("InvalidInputException", "EncryptionConfiguration is required.", 400);
        }
        String key = securityConfigurationKey(region, name);
        if (securityConfigurationStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Security configuration already exists: " + name, 400);
        }
        SecurityConfiguration configuration = new SecurityConfiguration();
        configuration.setName(name);
        configuration.setCreatedTimeStamp(Instant.now());
        configuration.setEncryptionConfiguration(encryptionConfiguration.deepCopy());
        securityConfigurationStore.put(key, configuration);
        return configuration;
    }

    public SecurityConfiguration getSecurityConfiguration(String name, String region) {
        validateSecurityConfigurationName(name);
        return securityConfigurationStore.get(securityConfigurationKey(region, name))
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Security configuration not found: " + name, 400));
    }

    public void deleteSecurityConfiguration(String name, String region) {
        validateSecurityConfigurationName(name);
        String key = securityConfigurationKey(region, name);
        if (securityConfigurationStore.get(key).isEmpty()) {
            throw new AwsException("EntityNotFoundException",
                    "Security configuration not found: " + name, 400);
        }
        securityConfigurationStore.delete(key);
    }

    public List<SecurityConfiguration> getSecurityConfigurations(String region) {
        String prefix = region + ":";
        return securityConfigurationStore.scan(key -> key.startsWith(prefix));
    }

    private static String securityConfigurationKey(String region, String name) {
        return region + ":" + name;
    }

    private static void validateSecurityConfigurationName(String name) {
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException", "Name is required.", 400);
        }
        if (name.length() > MAX_SECURITY_CONFIGURATION_NAME_LENGTH) {
            throw new AwsException("InvalidInputException",
                    "Name must be between 1 and " + MAX_SECURITY_CONFIGURATION_NAME_LENGTH + " characters.", 400);
        }
    }

    public void createDatabase(Database database) {
        createDatabase(database, null, regionResolver.getDefaultRegion());
    }

    public void createDatabase(Database database, Map<String, String> tags, String region) {
        String databaseName = normalizeName(database.getName());
        if (databaseStore.get(databaseName).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Database already exists: " + database.getName(), 400);
        }
        database.setName(databaseName);
        database.setCatalogId(regionResolver.getAccountId());
        databaseStore.put(databaseName, database);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(databaseArn(region, databaseName)), tags, region);
        }
        LOG.infov("Created Glue Database: {0}", database.getName());
    }

    public Database getDatabase(String name) {
        Database database = databaseStore.get(normalizeName(name))
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Database not found: " + name, 400));
        backfillCatalogId(database);
        return database;
    }

    public List<Database> getDatabases() {
        List<Database> databases = databaseStore.scan(k -> true);
        databases.forEach(this::backfillCatalogId);
        return databases;
    }

    /**
     * Heals a database persisted before CatalogId was modelled. The store is namespaced by
     * account, so the reader is always the owning account and the healed value is stable —
     * the same migrate-on-read the account-aware backend does for un-prefixed keys.
     */
    private void backfillCatalogId(Database database) {
        if (database.getCatalogId() == null) {
            database.setCatalogId(regionResolver.getAccountId());
        }
    }

    public void updateDatabase(String name, Database database) {
        Database existing = getDatabase(name);
        String databaseName = normalizeName(database.getName());
        if (!existing.getName().equals(databaseName)) {
            throw new AwsException("InvalidInputException", "Database cannot be renamed", 400);
        }
        Database updated = new Database();
        updated.setName(databaseName);
        updated.setDescription(database.getDescription());
        updated.setLocationUri(database.getLocationUri());
        updated.setParameters(database.getParameters() == null ? null : new LinkedHashMap<>(database.getParameters()));
        updated.setCreateTime(existing.getCreateTime());
        updated.setCatalogId(regionResolver.getAccountId());
        databaseStore.put(databaseName, updated);
        LOG.infov("Updated Glue Database: {0}", databaseName);
    }

    public void deleteDatabase(String name) {
        deleteDatabase(name, regionResolver.getDefaultRegion());
    }

    public void deleteDatabase(String name, String region) {
        String databaseName = getDatabase(name).getName();
        List<String> tableNames = tableStore.scan(k -> true).stream()
                .filter(table -> databaseName.equals(table.getDatabaseName()))
                .map(Table::getName)
                .toList();
        tableNames.forEach(tableName -> deleteTable(name, tableName));
        databaseStore.delete(databaseName);
        resourceGroupsTaggingService.deleteResources(List.of(databaseArn(region, databaseName)), region);
        LOG.infov("Deleted Glue Database: {0}", name);
    }

    public void createTable(String databaseName, Table table) {
        Database database = getDatabase(databaseName);
        String key = tableKey(databaseName, table.getName());
        if (tableStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Table already exists: " + table.getName(), 400);
        }
        validateSchemaReference(table);
        table.setName(normalizeName(table.getName()));
        table.setDatabaseName(database.getName());
        if (table.getCreateTime() == null) {
            table.setCreateTime(Instant.now());
        }
        table.setVersionId("0");
        tableStore.put(key, table);
        LOG.infov("Created Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public Table getTable(String databaseName, String tableName) {
        Table table = tableStore.get(tableKey(databaseName, tableName))
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Table not found: " + databaseName + "." + tableName, 400));
        return withResolvedSchemaReference(table);
    }

    public List<Table> getTables(String databaseName) {
        String prefix = normalizeName(databaseName) + ":";
        List<Table> tables = tableStore.scan(k -> k.startsWith(prefix));
        List<Table> resolved = new ArrayList<>(tables.size());
        for (Table table : tables) {
            resolved.add(withResolvedSchemaReference(table));
        }
        return resolved;
    }

    /**
     * SearchTables over every table of the catalog. SearchText matches a substring of the name,
     * database, description, owner, column names and comments and parameter values, or the whole
     * name when quoted, as the reference describes. String filters use the reference's tokenised
     * match (the field split on punctuation, each token compared whole), time filters honour the
     * Comparator, and any other key is looked up in the table's parameters.
     */
    public Page<Table> searchTables(String searchText, List<SearchFilter> filters,
                                    List<SearchSort> sortCriteria, Integer maxResults, String nextToken) {
        List<Table> matches = new ArrayList<>();
        for (Table stored : tableStore.scan(k -> true)) {
            Table table = withResolvedSchemaReference(stored);
            if (matchesSearchText(table, searchText) && matchesFilters(table, filters)) {
                matches.add(table);
            }
        }
        matches.sort(searchComparator(sortCriteria));
        return paginate(matches, maxResults, nextToken);
    }

    @RegisterForReflection
    public record SearchFilter(
            @JsonProperty("Key") String key,
            @JsonProperty("Value") String value,
            @JsonProperty("Comparator") String comparator) {}

    @RegisterForReflection
    public record SearchSort(
            @JsonProperty("FieldName") String fieldName,
            @JsonProperty("Sort") String sort) {}

    private static boolean matchesSearchText(Table table, String searchText) {
        if (searchText == null || searchText.isBlank()) {
            return true;
        }
        String text = searchText.trim();
        boolean exact = text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"");
        if (exact) {
            text = text.substring(1, text.length() - 1);
        }
        String needle = text.toLowerCase(Locale.ROOT);
        if (exact) {
            return searchableValues(table).stream().anyMatch(value -> value.equalsIgnoreCase(needle));
        }
        return searchableValues(table).stream()
                .anyMatch(value -> value.toLowerCase(Locale.ROOT).contains(needle));
    }

    private static List<String> searchableValues(Table table) {
        List<String> values = new ArrayList<>();
        addIfPresent(values, table.getName());
        addIfPresent(values, table.getDatabaseName());
        addIfPresent(values, table.getDescription());
        addIfPresent(values, table.getOwner());
        if (table.getStorageDescriptor() != null && table.getStorageDescriptor().getColumns() != null) {
            for (Column column : table.getStorageDescriptor().getColumns()) {
                addIfPresent(values, column.getName());
                addIfPresent(values, column.getComment());
            }
        }
        if (table.getPartitionKeys() != null) {
            for (Column column : table.getPartitionKeys()) {
                addIfPresent(values, column.getName());
                addIfPresent(values, column.getComment());
            }
        }
        if (table.getParameters() != null) {
            table.getParameters().values().forEach(value -> addIfPresent(values, value));
        }
        return values;
    }

    private static void addIfPresent(List<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static boolean matchesFilters(Table table, List<SearchFilter> filters) {
        if (filters == null) {
            return true;
        }
        for (SearchFilter filter : filters) {
            if (filter == null || filter.key() == null || filter.key().isBlank()) {
                throw new AwsException("InvalidInputException", "Filter Key is required.", 400);
            }
            if (!matchesFilter(table, filter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesFilter(Table table, SearchFilter filter) {
        String key = filter.key();
        String value = filter.value() == null ? "" : filter.value();
        switch (key) {
            case "Name": return tokenMatch(table.getName(), value);
            case "DatabaseName": return tokenMatch(table.getDatabaseName(), value);
            case "Owner": return tokenMatch(table.getOwner(), value);
            case "TableType": return tokenMatch(table.getTableType(), value);
            case "Description": return tokenMatch(table.getDescription(), value);
            case "CreateTime": return timeMatch(table.getCreateTime(), value, filter.comparator());
            case "UpdateTime": return timeMatch(table.getUpdateTime(), value, filter.comparator());
            case "LastAccessTime": return timeMatch(table.getLastAccessTime(), value, filter.comparator());
            default:
                return table.getParameters() != null && tokenMatch(table.getParameters().get(key), value);
        }
    }

    /** The reference's fuzzy match: the field split on punctuation, each token compared whole. */
    private static boolean tokenMatch(String field, String value) {
        if (field == null) {
            return false;
        }
        if (field.equalsIgnoreCase(value)) {
            return true;
        }
        for (String token : field.split("[\\p{Punct}\\s]+")) {
            if (!token.isEmpty() && token.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean timeMatch(Instant field, String value, String comparator) {
        if (field == null) {
            return false;
        }
        Instant bound = parseSearchTime(value);
        int cmp = field.compareTo(bound);
        String op = comparator == null || comparator.isBlank() ? "EQUALS" : comparator;
        return switch (op) {
            case "EQUALS" -> cmp == 0;
            case "GREATER_THAN" -> cmp > 0;
            case "LESS_THAN" -> cmp < 0;
            case "GREATER_THAN_EQUALS" -> cmp >= 0;
            case "LESS_THAN_EQUALS" -> cmp <= 0;
            default -> throw new AwsException("InvalidInputException",
                    "Invalid Comparator: " + comparator, 400);
        };
    }

    /** A time filter value: epoch seconds as AWS timestamps travel, or an ISO-8601 instant. */
    private static Instant parseSearchTime(String value) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            // not epoch seconds
        }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new AwsException("InvalidInputException", "Invalid time value: " + value, 400);
        }
    }

    private static Comparator<Table> searchComparator(List<SearchSort> sortCriteria) {
        Comparator<Table> byName = Comparator.comparing(Table::getDatabaseName, Comparator.nullsLast(String::compareTo))
                .thenComparing(Table::getName, Comparator.nullsLast(String::compareTo));
        if (sortCriteria == null || sortCriteria.isEmpty()) {
            return byName;
        }
        Comparator<Table> result = null;
        for (SearchSort criterion : sortCriteria) {
            Comparator<Table> next = sortField(criterion.fieldName());
            if ("DESC".equalsIgnoreCase(criterion.sort())) {
                next = next.reversed();
            } else if (criterion.sort() != null && !"ASC".equalsIgnoreCase(criterion.sort())) {
                throw new AwsException("InvalidInputException", "Invalid Sort: " + criterion.sort(), 400);
            }
            result = result == null ? next : result.thenComparing(next);
        }
        return result.thenComparing(byName);
    }

    private static Comparator<Table> sortField(String fieldName) {
        String field = fieldName == null ? "" : fieldName;
        return switch (field) {
            case "Name" -> Comparator.comparing(Table::getName, Comparator.nullsLast(String::compareTo));
            case "DatabaseName" -> Comparator.comparing(Table::getDatabaseName, Comparator.nullsLast(String::compareTo));
            case "Owner" -> Comparator.comparing(Table::getOwner, Comparator.nullsLast(String::compareTo));
            case "TableType" -> Comparator.comparing(Table::getTableType, Comparator.nullsLast(String::compareTo));
            case "CreateTime" -> Comparator.comparing(Table::getCreateTime, Comparator.nullsLast(Instant::compareTo));
            case "UpdateTime" -> Comparator.comparing(Table::getUpdateTime, Comparator.nullsLast(Instant::compareTo));
            case "LastAccessTime" -> Comparator.comparing(Table::getLastAccessTime, Comparator.nullsLast(Instant::compareTo));
            default -> throw new AwsException("InvalidInputException",
                    "Invalid sort FieldName: " + fieldName, 400);
        };
    }

    public synchronized void updateTable(String databaseName, Table table, String versionId, boolean skipArchive) {
        Database database = getDatabase(databaseName);
        String key = tableKey(databaseName, table.getName());
        Table existing = tableStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Table not found: " + databaseName + "." + table.getName(), 400));
        if (versionId != null && !versionId.equals(existing.getVersionId())) {
            throw new AwsException("ConcurrentModificationException",
                    "Update table failed due to concurrent modifications.", 400);
        }
        if (!skipArchive) {
            tableVersionStore.put(tableVersionKey(existing.getDatabaseName(), existing.getName(), existing.getVersionId()),
                    copyTable(existing));
        }
        validateSchemaReference(table);
        table.setName(normalizeName(table.getName()));
        table.setDatabaseName(database.getName());
        table.setCreateTime(existing.getCreateTime());
        table.setUpdateTime(Instant.now());
        table.setVersionId(nextVersionId(existing.getVersionId()));
        tableStore.put(key, table);
        LOG.infov("Updated Glue Table: {0}.{1}", databaseName, table.getName());
    }

    public List<Map<String, Object>> getTableVersions(String databaseName, String tableName) {
        Table current = getTable(databaseName, tableName);
        String prefix = tableKey(databaseName, tableName) + ":";
        List<Table> versions = new ArrayList<>();
        versions.add(current);
        versions.addAll(tableVersionStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(GlueService::versionIdAsLong).reversed())
                .toList());
        return versions.stream()
                .map(table -> Map.<String, Object>of(
                        "Table", withResolvedSchemaReference(table),
                        "VersionId", table.getVersionId()))
                .toList();
    }

    /** One archived version, or the current table when no VersionId is given, as AWS answers. */
    public Map<String, Object> getTableVersion(String databaseName, String tableName, String versionId) {
        Table current = getTable(databaseName, tableName);
        Table version;
        if (versionId == null || versionId.isBlank() || versionId.equals(current.getVersionId())) {
            version = current;
        } else {
            requireIntegerVersionId(versionId);
            version = tableVersionStore.get(tableVersionKey(databaseName, tableName, versionId))
                    .map(this::withResolvedSchemaReference)
                    .orElseThrow(() -> new AwsException("EntityNotFoundException", "Version not found.", 400));
        }
        return Map.of("Table", version, "VersionId", version.getVersionId());
    }

    /**
     * Drops an archived version. The current version is never deletable on AWS (DeleteTable is
     * the way to drop it), so it is refused rather than silently kept.
     */
    public synchronized void deleteTableVersion(String databaseName, String tableName, String versionId) {
        Table current = getTable(databaseName, tableName);
        requireIntegerVersionId(versionId);
        if (versionId.equals(current.getVersionId())) {
            throw new AwsException("InvalidInputException",
                    "Cannot delete the latest version " + versionId + " of table " + tableName
                    + "; delete the table instead.", 400);
        }
        String key = tableVersionKey(databaseName, tableName, versionId);
        if (tableVersionStore.get(key).isEmpty()) {
            throw new AwsException("EntityNotFoundException", "Version not found.", 400);
        }
        tableVersionStore.delete(key);
        LOG.infov("Deleted Glue Table version: {0}.{1} v{2}", databaseName, tableName, versionId);
    }

    public synchronized List<TableVersionError> batchDeleteTableVersions(
            String databaseName, String tableName, List<String> versionIds) {
        Table current = getTable(databaseName, tableName);
        if (versionIds.size() > 100) {
            throw new AwsException("InvalidInputException",
                    "VersionIds must contain at most 100 versions.", 400);
        }
        List<TableVersionError> errors = new ArrayList<>();
        for (String versionId : versionIds) {
            try {
                deleteTableVersion(databaseName, current.getName(), versionId);
            } catch (AwsException e) {
                errors.add(new TableVersionError(current.getName(), versionId,
                        new ErrorDetail(e.getErrorCode(), e.getMessage())));
            }
        }
        return errors;
    }

    private static void requireIntegerVersionId(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new AwsException("InvalidInputException", "VersionId is required.", 400);
        }
        try {
            Long.parseLong(versionId);
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidInputException", "Invalid table VersionId: " + versionId, 400);
        }
    }

    public void deleteTable(String databaseName, String tableName) {
        getTable(databaseName, tableName);
        String key = tableKey(databaseName, tableName);
        tableStore.delete(key);
        tableVersionStore.keys().stream()
                .filter(versionKey -> versionKey.startsWith(key + ":"))
                .forEach(tableVersionStore::delete);
        columnStatisticsStore.keys().stream()
                .filter(statisticsKey -> statisticsKey.startsWith(key + ":"))
                .forEach(columnStatisticsStore::delete);
        partitionStore.keys().stream()
                .filter(partitionKey -> partitionKey.startsWith(key + ":"))
                .forEach(partitionStore::delete);
        partitionColumnStatisticsStore.keys().stream()
                .filter(statisticsKey -> statisticsKey.startsWith(key + ":"))
                .forEach(partitionColumnStatisticsStore::delete);
        partitionIndexStore.keys().stream()
                .filter(indexKey -> indexKey.startsWith(key + ":"))
                .forEach(partitionIndexStore::delete);
        LOG.infov("Deleted Glue Table: {0}.{1}", databaseName, tableName);
    }

    public List<BatchDeleteTableError> batchDeleteTables(String databaseName, List<String> tableNames) {
        getDatabase(databaseName);
        List<BatchDeleteTableError> errors = new ArrayList<>();
        for (String tableName : tableNames) {
            String key = tableKey(databaseName, tableName);
            Optional<Table> table = tableStore.get(key);
            if (table.isEmpty()) {
                errors.add(new BatchDeleteTableError(
                        tableName,
                        new ErrorDetail("EntityNotFoundException", "Table " + tableName + " not found")));
                continue;
            }
            deleteTable(databaseName, tableName);
        }
        return errors;
    }

    public void updateColumnStatisticsForTable(
            String databaseName,
            String tableName,
            List<Map<String, Object>> columnStatistics) {
        Table table = getTable(databaseName, tableName);
        for (Map<String, Object> statistics : columnStatistics) {
            String columnNameString = requireColumnStatisticsString(statistics, COLUMN_NAME);
            requireColumnStatisticsString(statistics, COLUMN_TYPE);
            requireColumnStatisticsField(statistics, ANALYZED_TIME);
            requireColumnStatisticsField(statistics, STATISTICS_DATA);
            columnStatisticsStore.put(
                    columnStatisticsKey(table.getDatabaseName(), table.getName(), columnNameString),
                    new LinkedHashMap<>(statistics));
        }
    }

    public ColumnStatisticsResult getColumnStatisticsForTable(
            String databaseName,
            String tableName,
            List<String> columnNames) {
        Table table = getTable(databaseName, tableName);
        List<Map<String, Object>> columnStatistics = new ArrayList<>();
        List<ColumnError> errors = new ArrayList<>();
        for (String columnName : columnNames) {
            Optional<Map<String, Object>> statistics =
                    columnStatisticsStore.get(columnStatisticsKey(table.getDatabaseName(), table.getName(), columnName));
            if (statistics.isPresent()) {
                columnStatistics.add(new LinkedHashMap<>(statistics.get()));
            }
            else {
                errors.add(new ColumnError(
                        columnName,
                        new ErrorDetail("EntityNotFoundException", "Statistics do not exist for this column")));
            }
        }
        return new ColumnStatisticsResult(columnStatistics, errors);
    }

    public void deleteColumnStatisticsForTable(String databaseName, String tableName, String columnName) {
        getTable(databaseName, tableName);
        columnStatisticsStore.delete(columnStatisticsKey(databaseName, tableName, columnName));
    }

    public void createPartition(String databaseName, String tableName, Partition partition) {
        Table table = getTable(databaseName, tableName);
        String key = partitionKey(databaseName, tableName, partition.getValues());
        partition.setDatabaseName(table.getDatabaseName());
        partition.setTableName(table.getName());
        if (partition.getCreationTime() == null) {
            partition.setCreationTime(Instant.now());
        }
        partitionStore.put(key, partition);
    }

    public List<BatchCreatePartitionError> batchCreatePartitions(
            String databaseName,
            String tableName,
            List<Partition> partitions) {
        getTable(databaseName, tableName);
        List<BatchCreatePartitionError> errors = new ArrayList<>();
        for (Partition partition : partitions) {
            String key = partitionKey(databaseName, tableName, partition.getValues());
            if (partitionStore.get(key).isPresent()) {
                errors.add(new BatchCreatePartitionError(
                        partition.getValues(),
                        new ErrorDetail("AlreadyExistsException", "Partition already exists.")));
                continue;
            }
            createPartition(databaseName, tableName, partition);
        }
        return errors;
    }

    public List<Partition> getPartitions(String databaseName, String tableName) {
        return getPartitions(databaseName, tableName, null);
    }

    public Partition getPartition(String databaseName, String tableName, List<String> partitionValues) {
        getTable(databaseName, tableName);
        return partitionStore.get(partitionKey(databaseName, tableName, partitionValues))
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Cannot find partition.", 400));
    }

    public List<Partition> batchGetPartitions(String databaseName, String tableName, List<List<String>> partitionValues) {
        getTable(databaseName, tableName);
        List<Partition> partitions = new ArrayList<>();
        for (List<String> values : partitionValues) {
            partitionStore.get(partitionKey(databaseName, tableName, values)).ifPresent(partitions::add);
        }
        return partitions;
    }

    public List<BatchUpdatePartitionError> batchUpdatePartitions(
            String databaseName,
            String tableName,
            List<BatchUpdatePartitionEntry> entries) {
        Table table = getTable(databaseName, tableName);
        List<BatchUpdatePartitionError> errors = new ArrayList<>();
        for (BatchUpdatePartitionEntry entry : entries) {
            String key = partitionKey(databaseName, tableName, entry.partitionValueList());
            Optional<Partition> existing = partitionStore.get(key);
            if (existing.isEmpty()) {
                errors.add(new BatchUpdatePartitionError(
                        entry.partitionValueList(),
                        new ErrorDetail(
                                "EntityNotFoundException",
                                "Partition [" + String.join(", ", entry.partitionValueList()) + "] not found")));
                continue;
            }

            putUpdatedPartition(table, key, existing.get(), entry.partitionInput());
        }
        return errors;
    }

    public List<Partition> getPartitions(String databaseName, String tableName, String expression) {
        Table table = getTable(databaseName, tableName);
        String prefix = tableKey(databaseName, tableName) + ":";
        return partitionStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(partition -> matchesPartitionExpression(table, partition, expression))
                .sorted(GlueService::comparePartitionValues)
                .toList();
    }

    /**
     * Registers a partition index on a table.
     *
     * <p>The index is stored {@code CREATING}, as real Glue reports it while the backfill runs. It
     * settles to {@code ACTIVE} on the next {@code GetPartitionIndexes}, so a client that polls
     * sees the transition without the emulator depending on elapsed time.
     *
     * <p>Synchronized with the other partition-index methods, and with {@code updateTable}: the cap
     * and duplicate checks read the stored indexes before writing, so concurrent creates would
     * otherwise both pass a check that only one of them should.
     */
    public synchronized void createPartitionIndex(String databaseName, String tableName, PartitionIndex index) {
        Table table = getTable(databaseName, tableName);

        if (index == null || index.getIndexName() == null || index.getIndexName().isBlank()) {
            throw new AwsException("InvalidInputException", "IndexName is required", 400);
        }
        List<String> keyNames = index.getKeys();
        if (keyNames == null || keyNames.isEmpty()) {
            throw new AwsException("InvalidInputException", "Keys is required", 400);
        }

        // Every key must name one of the table's partition keys: an index exists to narrow a
        // partition scan, so a key outside that set could never be used.
        List<KeySchemaElement> resolvedKeys = new ArrayList<>();
        for (String keyName : keyNames) {
            int position = partitionKeyIndex(table, keyName);
            if (position < 0) {
                throw new AwsException("InvalidInputException",
                        "IndexKeys not a part of PartitionColumns. Verify the indexKeys : [" + keyName + "]", 400);
            }
            Column partitionKey = table.getPartitionKeys().get(position);
            resolvedKeys.add(new KeySchemaElement(partitionKey.getName(), partitionKey.getType()));
        }

        // Measured against real Glue (us-west-2): the cap is evaluated before the duplicate
        // checks, so a create that is both over the limit and a duplicate reports the limit.
        List<PartitionIndexDescriptor> existing = readPartitionIndexes(databaseName, tableName);
        requireNoIndexInProgress(existing);
        if (existing.size() >= MAX_PARTITION_INDEXES_PER_TABLE) {
            throw new AwsException("ResourceNumberLimitExceededException",
                    "Partition index limit exceeded. Maximum: " + MAX_PARTITION_INDEXES_PER_TABLE, 400);
        }

        String key = partitionIndexKey(databaseName, tableName, index.getIndexName());
        if (partitionIndexStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Partition Index " + index.getIndexName() + " already exists with the same name.", 400);
        }

        // Glue also refuses a second index over the same keys under a different name. The
        // comparison is order sensitive: [year, month] and [month, year] are distinct indexes.
        List<String> resolvedKeyNames = resolvedKeys.stream().map(KeySchemaElement::getName).toList();
        for (PartitionIndexDescriptor other : existing) {
            List<String> otherKeyNames = other.getKeys() == null
                    ? List.of()
                    : other.getKeys().stream().map(KeySchemaElement::getName).toList();
            if (otherKeyNames.equals(resolvedKeyNames)) {
                throw new AwsException("AlreadyExistsException",
                        "Partition Index " + other.getIndexName() + " already exists with the same keys.", 400);
            }
        }

        PartitionIndexDescriptor descriptor = new PartitionIndexDescriptor();
        descriptor.setIndexName(index.getIndexName());
        descriptor.setIndexStatus(INDEX_STATUS_CREATING);
        descriptor.setKeys(resolvedKeys);
        partitionIndexStore.put(key, descriptor);
        LOG.infov("Created Glue partition index: {0}.{1} {2}", databaseName, tableName, index.getIndexName());
    }

    public synchronized void deletePartitionIndex(String databaseName, String tableName, String indexName) {
        getTable(databaseName, tableName);
        if (indexName == null || indexName.isBlank()) {
            throw new AwsException("InvalidInputException", "IndexName is required", 400);
        }
        String key = partitionIndexKey(databaseName, tableName, indexName);
        PartitionIndexDescriptor target = partitionIndexStore.get(key).orElse(null);
        // An index still being created is not addressable by name yet: real Glue reports it as
        // absent even while GetPartitionIndexes lists it as CREATING (measured in isolation).
        if (target == null || INDEX_STATUS_CREATING.equals(target.getIndexStatus())) {
            throw new AwsException("EntityNotFoundException",
                    "Index with the given indexName : " + indexName + " does not exist.", 400);
        }
        if (INDEX_STATUS_DELETING.equals(target.getIndexStatus())) {
            throw new AwsException("EntityNotFoundException",
                    "Index with the given indexName : " + indexName + " does not exist.", 400);
        }
        requireNoIndexInProgress(readPartitionIndexes(databaseName, tableName));

        // Deletion is observable: the index reports DELETING before it disappears.
        target.setIndexStatus(INDEX_STATUS_DELETING);
        partitionIndexStore.put(key, target);
        LOG.infov("Deleting Glue partition index: {0}.{1} {2}", databaseName, tableName, indexName);
    }

    /**
     * Returns a table's partition indexes and then advances any that are mid-lifecycle.
     *
     * <p>Real Glue creates and deletes an index asynchronously, so a caller sees {@code CREATING}
     * before {@code ACTIVE} and {@code DELETING} before the index disappears. Rather than tie those
     * transitions to wall-clock time, which would make tests racy, each read reports the current
     * state and settles it: the next read sees the outcome. A client that polls for {@code ACTIVE}
     * or for the index to vanish, as the Terraform provider does, converges on its second read.
     */
    public synchronized List<PartitionIndexDescriptor> getPartitionIndexes(String databaseName, String tableName) {
        List<PartitionIndexDescriptor> current = readPartitionIndexes(databaseName, tableName);
        for (PartitionIndexDescriptor descriptor : current) {
            String key = partitionIndexKey(databaseName, tableName, descriptor.getIndexName());
            if (INDEX_STATUS_CREATING.equals(descriptor.getIndexStatus())) {
                PartitionIndexDescriptor settled = copyWithStatus(descriptor, INDEX_STATUS_ACTIVE);
                partitionIndexStore.put(key, settled);
            } else if (INDEX_STATUS_DELETING.equals(descriptor.getIndexStatus())) {
                partitionIndexStore.delete(key);
            }
        }
        return current;
    }

    /** Reads the stored indexes without advancing the lifecycle. */
    private List<PartitionIndexDescriptor> readPartitionIndexes(String databaseName, String tableName) {
        getTable(databaseName, tableName);
        String prefix = tableKey(databaseName, tableName) + ":";
        return partitionIndexStore.scan(k -> k.startsWith(prefix)).stream()
                .sorted(Comparator.comparing(PartitionIndexDescriptor::getIndexName))
                .toList();
    }

    private static PartitionIndexDescriptor copyWithStatus(PartitionIndexDescriptor source, String status) {
        PartitionIndexDescriptor copy = new PartitionIndexDescriptor();
        copy.setIndexName(source.getIndexName());
        copy.setIndexStatus(status);
        copy.setKeys(source.getKeys());
        copy.setBackfillErrors(source.getBackfillErrors());
        return copy;
    }

    /**
     * Glue allows one index per table to be created or deleted at a time. Measured message, with
     * the offending index and its state named.
     */
    private static void requireNoIndexInProgress(List<PartitionIndexDescriptor> existing) {
        for (PartitionIndexDescriptor descriptor : existing) {
            String status = descriptor.getIndexStatus();
            if (INDEX_STATUS_CREATING.equals(status) || INDEX_STATUS_DELETING.equals(status)) {
                throw new AwsException("ResourceNumberLimitExceededException",
                        "Index " + descriptor.getIndexName() + " is in " + status
                                + " state. Only 1 index can be created or deleted simultaneously per table.", 400);
            }
        }
    }

    // The partition store scan returns values in storage iteration order, which is not stable.
    // Return partitions ordered by their values so GetPartitions is deterministic across calls.
    private static int comparePartitionValues(Partition a, Partition b) {
        List<String> aValues = a.getValues() == null ? List.of() : a.getValues();
        List<String> bValues = b.getValues() == null ? List.of() : b.getValues();
        int shared = Math.min(aValues.size(), bValues.size());
        for (int i = 0; i < shared; i++) {
            int comparison = Comparator.nullsFirst(String::compareTo).compare(aValues.get(i), bValues.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(aValues.size(), bValues.size());
    }

    public void deletePartition(String databaseName, String tableName, List<String> partitionValues) {
        getPartition(databaseName, tableName, partitionValues);
        String key = partitionKey(databaseName, tableName, partitionValues);
        partitionStore.delete(key);
        partitionColumnStatisticsStore.keys().stream()
                .filter(statisticsKey -> statisticsKey.startsWith(key + ":"))
                .forEach(partitionColumnStatisticsStore::delete);
    }

    /** Deletes what exists and reports the rest, the batch shape BatchCreatePartition uses. */
    public List<BatchCreatePartitionError> batchDeletePartitions(
            String databaseName, String tableName, List<List<String>> partitionsToDelete) {
        getTable(databaseName, tableName);
        if (partitionsToDelete.size() > 25) {
            throw new AwsException("InvalidInputException",
                    "PartitionsToDelete must contain at most 25 partitions.", 400);
        }
        List<BatchCreatePartitionError> errors = new ArrayList<>();
        for (List<String> values : partitionsToDelete) {
            if (partitionStore.get(partitionKey(databaseName, tableName, values)).isEmpty()) {
                errors.add(new BatchCreatePartitionError(values,
                        new ErrorDetail("EntityNotFoundException", "Cannot find partition.")));
                continue;
            }
            deletePartition(databaseName, tableName, values);
        }
        return errors;
    }

    public void updatePartition(String databaseName, String tableName, List<String> partitionValues, Partition partition) {
        Table table = getTable(databaseName, tableName);
        String key = partitionKey(databaseName, tableName, partitionValues);
        Partition existing = partitionStore.get(key)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Partition not found.", 400));
        putUpdatedPartition(table, key, existing, partition);
    }

    public void updateColumnStatisticsForPartition(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            List<Map<String, Object>> columnStatistics) {
        getPartition(databaseName, tableName, partitionValues);
        for (Map<String, Object> statistics : columnStatistics) {
            String columnNameString = requireColumnStatisticsString(statistics, COLUMN_NAME);
            requireColumnStatisticsString(statistics, COLUMN_TYPE);
            requireColumnStatisticsField(statistics, ANALYZED_TIME);
            requireColumnStatisticsField(statistics, STATISTICS_DATA);
            partitionColumnStatisticsStore.put(
                    partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnNameString),
                    new LinkedHashMap<>(statistics));
        }
    }

    public ColumnStatisticsResult getColumnStatisticsForPartition(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            List<String> columnNames) {
        getPartition(databaseName, tableName, partitionValues);
        List<Map<String, Object>> columnStatistics = new ArrayList<>();
        List<ColumnError> errors = new ArrayList<>();
        for (String columnName : columnNames) {
            partitionColumnStatisticsStore.get(partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnName))
                    .ifPresentOrElse(columnStatistics::add, () -> errors.add(columnStatisticsNotFoundError(columnName)));
        }
        return new ColumnStatisticsResult(columnStatistics, errors);
    }

    public void deleteColumnStatisticsForPartition(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            String columnName) {
        getPartition(databaseName, tableName, partitionValues);
        partitionColumnStatisticsStore.delete(partitionColumnStatisticsKey(databaseName, tableName, partitionValues, columnName));
    }

    public void createUserDefinedFunction(String databaseName, UserDefinedFunction function) {
        Database database = getDatabase(databaseName);
        String key = functionKey(databaseName, function.getFunctionName());
        if (functionStore.get(key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "Function already exists: " + databaseName + "." + function.getFunctionName(), 400);
        }
        function.setDatabaseName(database.getName());
        function.setFunctionName(normalizeName(function.getFunctionName()));
        function.setCreateTime(Instant.now());
        functionStore.put(key, function);
    }

    public UserDefinedFunction getUserDefinedFunction(String databaseName, String functionName) {
        getDatabase(databaseName);
        return functionStore.get(functionKey(databaseName, functionName))
                .orElseThrow(() -> new AwsException("EntityNotFoundException",
                        "Function not found: " + databaseName + "." + functionName, 400));
    }

    public List<UserDefinedFunction> getUserDefinedFunctions(String databaseName, String pattern) {
        return getUserDefinedFunctions(databaseName, pattern, null, null, null).functions();
    }

    public UserDefinedFunctionPage getUserDefinedFunctions(
            String databaseName,
            String pattern,
            String functionType,
            Integer maxResults,
            String nextToken) {
        String databaseNameFilter = databaseName == null ? null : getDatabase(databaseName).getName();
        Pattern compiledPattern = compileFunctionPattern(pattern);
        int offset = decodeFunctionNextToken(nextToken);
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_FUNCTION_RESULTS)) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 100", 400);
        }
        List<UserDefinedFunction> functions = functionStore.scan(k -> true).stream()
                .filter(function -> databaseNameFilter == null || databaseNameFilter.equals(function.getDatabaseName()))
                .filter(function -> functionType == null || functionType.equals(function.getFunctionType()))
                .filter(function -> function.getFunctionName() != null)
                .filter(function -> compiledPattern.matcher(function.getFunctionName()).matches())
                .sorted(Comparator.comparing(
                                UserDefinedFunction::getDatabaseName,
                                Comparator.nullsFirst(String::compareTo))
                        .thenComparing(UserDefinedFunction::getFunctionName, Comparator.nullsFirst(String::compareTo)))
                .toList();
        if (offset > functions.size()) {
            throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
        }
        int limit = maxResults == null ? functions.size() : maxResults;
        int end = Math.min(functions.size(), offset + limit);
        String newNextToken = end < functions.size() ? Integer.toString(end) : null;
        return new UserDefinedFunctionPage(functions.subList(offset, end), newNextToken);
    }

    public void updateUserDefinedFunction(String databaseName, String functionName, UserDefinedFunction function) {
        UserDefinedFunction existing = getUserDefinedFunction(databaseName, functionName);
        function.setDatabaseName(existing.getDatabaseName());
        function.setFunctionName(existing.getFunctionName());
        function.setCreateTime(existing.getCreateTime());
        functionStore.put(functionKey(databaseName, functionName), function);
    }

    public void deleteUserDefinedFunction(String databaseName, String functionName) {
        getUserDefinedFunction(databaseName, functionName);
        functionStore.delete(functionKey(databaseName, functionName));
    }

    private void validateSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return;
        }
        // Throws EntityNotFoundException / InvalidInputException if reference is broken.
        resolveSchemaVersion(ref);
    }

    private Table withResolvedSchemaReference(Table table) {
        SchemaReference ref = schemaReferenceOf(table);
        if (ref == null) {
            return table;
        }
        try {
            SchemaVersion version = resolveSchemaVersion(ref);
            List<Column> columns = SchemaToColumnsConverter.toColumns(
                    version.getDataFormat(), version.getSchemaDefinition());
            if (!columns.isEmpty()) {
                Table resolved = copyTable(table);
                resolved.getStorageDescriptor().setColumns(columns);
                return resolved;
            }
        } catch (AwsException e) {
            LOG.warnv("SchemaReference resolution failed for {0}.{1}: {2}",
                    table.getDatabaseName(), table.getName(), e.getMessage());
        }
        return table;
    }

    private SchemaVersion resolveSchemaVersion(SchemaReference ref) {
        boolean latest = ref.getSchemaVersionId() == null && ref.getSchemaVersionNumber() == null;
        return schemaRegistryService.getSchemaVersion(
                ref.getSchemaId(), ref.getSchemaVersionId(),
                ref.getSchemaVersionNumber(), latest, regionResolver.getDefaultRegion());
    }

    private static SchemaReference schemaReferenceOf(Table table) {
        StorageDescriptor sd = table != null ? table.getStorageDescriptor() : null;
        return sd != null ? sd.getSchemaReference() : null;
    }

    private static String functionKey(String databaseName, String functionName) {
        return normalizeName(databaseName) + ":" + normalizeName(functionName);
    }

    private static String tableKey(String databaseName, String tableName) {
        return normalizeName(databaseName) + ":" + normalizeName(tableName);
    }

    private static String tableVersionKey(String databaseName, String tableName, String versionId) {
        return tableKey(databaseName, tableName) + ":" + versionId;
    }

    private static String columnStatisticsKey(String databaseName, String tableName, String columnName) {
        return tableKey(databaseName, tableName) + ":" + normalizeName(columnName);
    }

    private static String partitionIndexKey(String databaseName, String tableName, String indexName) {
        return tableKey(databaseName, tableName) + ":" + normalizeName(indexName);
    }

    private static String partitionKey(String databaseName, String tableName, List<String> partitionValues) {
        return tableKey(databaseName, tableName) + ":" + String.join(":", partitionValues.stream()
                .map(GlueService::encodePartitionValue)
                .toList());
    }

    private static String encodePartitionValue(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String partitionColumnStatisticsKey(
            String databaseName,
            String tableName,
            List<String> partitionValues,
            String columnName) {
        return partitionKey(databaseName, tableName, partitionValues) + ":" + normalizeName(columnName);
    }

    private void putUpdatedPartition(Table table, String key, Partition existing, Partition partition) {
        partition.setDatabaseName(table.getDatabaseName());
        partition.setTableName(table.getName());
        partition.setCreationTime(existing.getCreationTime());
        String updatedKey = partitionKey(table.getDatabaseName(), table.getName(), partition.getValues());
        if (!key.equals(updatedKey)) {
            partitionStore.delete(key);
        }
        partitionStore.put(updatedKey, partition);
    }

    private static String requireColumnStatisticsString(Map<String, Object> statistics, String field) {
        Object value = requireColumnStatisticsField(statistics, field);
        if (!(value instanceof String stringValue) || stringValue.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return stringValue;
    }

    private static Object requireColumnStatisticsField(Map<String, Object> statistics, String field) {
        Object value = statistics.get(field);
        if (value == null) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return value;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesPartitionExpression(Table table, Partition partition, String expression) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        return matchesExpression(table, partition, expression);
    }

    private static boolean matchesExpression(Table table, Partition partition, String expression) {
        String stripped = stripParentheses(expression.trim());
        List<String> disjuncts = splitTopLevel(stripped, "OR");
        if (disjuncts.size() > 1) {
            return disjuncts.stream().anyMatch(disjunct -> matchesExpression(table, partition, disjunct));
        }
        List<String> conjuncts = splitTopLevel(stripped, "AND");
        if (conjuncts.size() > 1) {
            return conjuncts.stream().allMatch(conjunct -> matchesExpression(table, partition, conjunct));
        }
        return matchesPredicate(table, partition, stripped);
    }

    private static boolean matchesPredicate(Table table, Partition partition, String expression) {
        Matcher inMatcher = IN_EXPRESSION.matcher(expression);
        if (inMatcher.matches()) {
            String partitionValue = partitionValue(table, partition, inMatcher.group(1));
            if (partitionValue == null) {
                return false;
            }
            return splitValues(inMatcher.group(2)).stream()
                    .map(GlueService::unquote)
                    .anyMatch(partitionValue::equals);
        }
        Matcher comparisonMatcher = COMPARISON_EXPRESSION.matcher(expression);
        if (comparisonMatcher.matches()) {
            String partitionValue = partitionValue(table, partition, comparisonMatcher.group(1));
            if (partitionValue == null) {
                return false;
            }
            return compare(partitionValue, comparisonMatcher.group(2), unquote(comparisonMatcher.group(3)));
        }
        throw new AwsException("InvalidInputException", "Unsupported partition expression: " + expression, 400);
    }

    private static String partitionValue(Table table, Partition partition, String partitionKeyName) {
        int index = partitionKeyIndex(table, partitionKeyName);
        if (index < 0 || partition.getValues() == null || partition.getValues().size() <= index) {
            return null;
        }
        return partition.getValues().get(index);
    }

    private static boolean compare(String actual, String operator, String expected) {
        int comparison = compareValues(actual, expected);
        return switch (operator) {
            case "=" -> comparison == 0;
            case "<>" -> comparison != 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            default -> true;
        };
    }

    private static int compareValues(String left, String right) {
        try {
            return new BigDecimal(left).compareTo(new BigDecimal(right));
        }
        catch (NumberFormatException e) {
            return left.compareTo(right);
        }
    }

    private static String stripParentheses(String expression) {
        String stripped = expression;
        while (stripped.startsWith("(") && stripped.endsWith(")") && matchingOuterParentheses(stripped)) {
            stripped = stripped.substring(1, stripped.length() - 1).trim();
        }
        return stripped;
    }

    private static boolean matchingOuterParentheses(String expression) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (current == '(') {
                depth++;
            }
            else if (current == ')') {
                depth--;
                if (depth == 0 && index < expression.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    private static List<String> splitTopLevel(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            if (quoted) {
                continue;
            }
            if (current == '(') {
                depth++;
            }
            else if (current == ')') {
                depth--;
            }
            else if (depth == 0 && matchesOperator(expression, index, operator)) {
                parts.add(expression.substring(start, index).trim());
                index += operator.length() - 1;
                start = index + 1;
            }
        }
        if (parts.isEmpty()) {
            return List.of(expression);
        }
        parts.add(expression.substring(start).trim());
        return parts;
    }

    private static boolean matchesOperator(String expression, int index, String operator) {
        if (!expression.regionMatches(true, index, operator, 0, operator.length())) {
            return false;
        }
        return isBoundary(expression, index - 1) && isBoundary(expression, index + operator.length());
    }

    private static boolean isBoundary(String expression, int index) {
        return index < 0 || index >= expression.length() || Character.isWhitespace(expression.charAt(index));
    }

    private static List<String> splitValues(String values) {
        List<String> result = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int index = 0; index < values.length(); index++) {
            char current = values.charAt(index);
            if (current == '\'') {
                quoted = !quoted;
            }
            else if (current == ',' && !quoted) {
                result.add(values.substring(start, index).trim());
                start = index + 1;
            }
        }
        result.add(values.substring(start).trim());
        return result;
    }

    private static String unquote(String value) {
        String stripped = value.trim();
        if (stripped.length() >= 2 && stripped.startsWith("'") && stripped.endsWith("'")) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static int partitionKeyIndex(Table table, String partitionKeyName) {
        List<Column> partitionKeys = table.getPartitionKeys();
        if (partitionKeys == null) {
            return -1;
        }
        for (int index = 0; index < partitionKeys.size(); index++) {
            if (partitionKeyName.equals(partitionKeys.get(index).getName())) {
                return index;
            }
        }
        return -1;
    }

    private static ColumnError columnStatisticsNotFoundError(String columnName) {
        return new ColumnError(
                columnName,
                new ErrorDetail("EntityNotFoundException", "Statistics do not exist for this column"));
    }

    private String databaseArn(String region, String databaseName) {
        return regionResolver.buildArn("glue", region, "database/" + databaseName);
    }

    private String jobArn(String region, String jobName) {
        return regionResolver.buildArn("glue", region, "job/" + jobName);
    }

    private String connectionArn(String region, String connectionName) {
        return regionResolver.buildArn("glue", region, "connection/" + connectionName);
    }

    private String crawlerArn(String region, String crawlerName) {
        return regionResolver.buildArn("glue", region, "crawler/" + crawlerName);
    }

    private String triggerArn(String region, String triggerName) {
        return regionResolver.buildArn("glue", region, "trigger/" + triggerName);
    }

    private String workflowArn(String region, String workflowName) {
        return regionResolver.buildArn("glue", region, "workflow/" + workflowName);
    }

    private static Pattern compileFunctionPattern(String pattern) {
        if (pattern == null) {
            return Pattern.compile(".*");
        }
        if (pattern.length() > MAX_FUNCTION_PATTERN_LENGTH) {
            throw new AwsException("InvalidInputException", "Invalid function pattern: pattern is too long", 400);
        }
        try {
            return Pattern.compile(pattern);
        }
        catch (PatternSyntaxException e) {
            throw new AwsException("InvalidInputException", "Invalid function pattern: " + pattern, 400);
        }
    }

    private static int decodeFunctionNextToken(String nextToken) {
        if (nextToken == null) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(nextToken);
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        }
        catch (NumberFormatException e) {
            throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
        }
    }

    public record UserDefinedFunctionPage(List<UserDefinedFunction> functions, String nextToken) {}

    public record BatchDeleteTableError(
            @JsonProperty("TableName") String tableName,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    @RegisterForReflection
    public record TableVersionError(
            @JsonProperty("TableName") String tableName,
            @JsonProperty("VersionId") String versionId,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    @RegisterForReflection
    public record BatchCreatePartitionError(
            @JsonProperty("PartitionValues") List<String> partitionValues,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    @RegisterForReflection
    public record BatchUpdatePartitionEntry(
            @JsonProperty("PartitionValueList") List<String> partitionValueList,
            @JsonProperty("PartitionInput") Partition partitionInput) {}

    @RegisterForReflection
    public record BatchUpdatePartitionError(
            @JsonProperty("PartitionValueList") List<String> partitionValueList,
            @JsonProperty("ErrorDetail") ErrorDetail errorDetail) {}

    @RegisterForReflection
    public record ErrorDetail(
            @JsonProperty("ErrorCode") String errorCode,
            @JsonProperty("ErrorMessage") String errorMessage) {}

    public record ColumnStatisticsResult(
            @JsonProperty("ColumnStatisticsList") List<Map<String, Object>> columnStatisticsList,
            @JsonProperty("Errors") List<ColumnError> errors) {}

    @RegisterForReflection
    public record ColumnError(
            @JsonProperty("ColumnName") String columnName,
            @JsonProperty("Error") ErrorDetail error) {}

    private static Table copyTable(Table source) {
        Table copy = new Table();
        copy.setName(source.getName());
        copy.setDatabaseName(source.getDatabaseName());
        copy.setDescription(source.getDescription());
        copy.setOwner(source.getOwner());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setLastAccessTime(source.getLastAccessTime());
        copy.setPartitionKeys(copyColumns(source.getPartitionKeys()));
        copy.setStorageDescriptor(copyStorageDescriptor(source.getStorageDescriptor()));
        copy.setTableType(source.getTableType());
        copy.setViewOriginalText(source.getViewOriginalText());
        copy.setViewExpandedText(source.getViewExpandedText());
        copy.setVersionId(source.getVersionId());
        copy.setParameters(copyMap(source.getParameters()));
        return copy;
    }

    private static String nextVersionId(String versionId) {
        if (versionId == null) {
            return "1";
        }
        try {
            return Long.toString(Math.addExact(Long.parseLong(versionId), 1));
        }
        catch (ArithmeticException | NumberFormatException e) {
            throw new AwsException("InvalidInputException", "Invalid table VersionId: " + versionId, 400);
        }
    }

    private static long versionIdAsLong(Table table) {
        return Long.parseLong(table.getVersionId());
    }

    private static StorageDescriptor copyStorageDescriptor(StorageDescriptor source) {
        if (source == null) {
            return null;
        }
        StorageDescriptor copy = new StorageDescriptor();
        copy.setColumns(copyColumns(source.getColumns()));
        copy.setLocation(source.getLocation());
        copy.setInputFormat(source.getInputFormat());
        copy.setOutputFormat(source.getOutputFormat());
        copy.setCompressed(source.getCompressed());
        copy.setNumberOfBuckets(source.getNumberOfBuckets());
        copy.setSerdeInfo(copySerDeInfo(source.getSerdeInfo()));
        copy.setParameters(copyMap(source.getParameters()));
        copy.setSchemaReference(copySchemaReference(source.getSchemaReference()));
        return copy;
    }

    private static StorageDescriptor.SerDeInfo copySerDeInfo(StorageDescriptor.SerDeInfo source) {
        if (source == null) {
            return null;
        }
        StorageDescriptor.SerDeInfo copy = new StorageDescriptor.SerDeInfo();
        copy.setName(source.getName());
        copy.setSerializationLibrary(source.getSerializationLibrary());
        copy.setParameters(copyMap(source.getParameters()));
        return copy;
    }

    private static SchemaReference copySchemaReference(SchemaReference source) {
        if (source == null) {
            return null;
        }
        SchemaReference copy = new SchemaReference();
        SchemaId schemaId = source.getSchemaId();
        if (schemaId != null) {
            copy.setSchemaId(new SchemaId(
                    schemaId.getRegistryName(), schemaId.getSchemaName(), schemaId.getSchemaArn()));
        }
        copy.setSchemaVersionId(source.getSchemaVersionId());
        copy.setSchemaVersionNumber(source.getSchemaVersionNumber());
        return copy;
    }

    private static List<Column> copyColumns(List<Column> source) {
        if (source == null) {
            return null;
        }
        List<Column> copy = new ArrayList<>(source.size());
        for (Column column : source) {
            Column columnCopy = new Column();
            columnCopy.setName(column.getName());
            columnCopy.setType(column.getType());
            columnCopy.setComment(column.getComment());
            columnCopy.setParameters(copyMap(column.getParameters()));
            copy.add(columnCopy);
        }
        return copy;
    }

    private static Map<String, String> copyMap(Map<String, String> source) {
        return source != null ? new LinkedHashMap<>(source) : null;
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidInputException", fieldName + " is required.", 400);
        }
    }

    private void validateRequired(Object value, String fieldName) {
        if (value == null) {
            throw new AwsException("InvalidInputException", fieldName + " is required.", 400);
        }
    }

    public void createJob(Job job) {
        createJob(job, null, regionResolver.getDefaultRegion());
    }

    public void createJob(Job job, Map<String, String> tags, String region) {
        validateRequired(job.getName(), "Name");
        validateRequired(job.getRole(), "Role");
        validateRequired(job.getCommand(), "Command");
        
        // Jobs and crawlers skip normalizeName because AWS preserves their case
        String name = job.getName();
        if (jobStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Job " + name + " already exists.", 400);
        }
        
        if (job.getGlueVersion() == null) {
            job.setGlueVersion("5.1");
        }
        if (job.getTimeout() == null) {
            try {
                double version = Double.parseDouble(job.getGlueVersion());
                job.setTimeout(version >= 5.0 ? 480 : 2880);
            } catch (NumberFormatException e) {
                job.setTimeout(2880);
            }
        }
        
        Instant now = Instant.now();
        job.setCreatedOn(now);
        job.setLastModifiedOn(now);
        jobStore.put(name, job);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(jobArn(region, name)), tags, region);
        }
        LOG.infov("Created Glue Job: {0}", name);
    }

    public Job getJob(String name) {
        validateRequired(name, "JobName");
        return jobStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Job " + name + " not found.", 400));
    }

    public List<Job> getJobs() {
        return jobStore.scan(k -> true);
    }

    public Page<Job> getJobs(Integer maxResults, String nextToken) {
        List<Job> all = jobStore.scan(k -> true);
        all.sort(Comparator.comparing(Job::getName));
        return paginate(all, maxResults, nextToken);
    }

    public Page<String> listJobs(Integer maxResults, String nextToken, Map<String, String> tags, String region) {
        List<String> names = new ArrayList<>();
        for (Job job : jobStore.scan(k -> true)) {
            if (tags == null || tags.isEmpty() || hasTags(jobArn(region, job.getName()), tags, region)) {
                names.add(job.getName());
            }
        }
        names.sort(Comparator.naturalOrder());
        return paginate(names, maxResults, nextToken);
    }

    public BatchGetJobsResult batchGetJobs(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new AwsException("InvalidInputException", "JobNames is required.", 400);
        }
        List<Job> jobs = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String name : names) {
            Optional<Job> job = name == null ? Optional.empty() : jobStore.get(name);
            if (job.isPresent()) {
                jobs.add(job.get());
            } else {
                notFound.add(name);
            }
        }
        return new BatchGetJobsResult(jobs, notFound);
    }

    public record BatchGetJobsResult(List<Job> jobs, List<String> jobsNotFound) {}

    private boolean hasTags(String arn, Map<String, String> wanted, String region) {
        Map<String, String> actual = resourceGroupsTaggingService.getTagsForResource(region, arn);
        for (Map.Entry<String, String> tag : wanted.entrySet()) {
            if (!Objects.equals(actual.get(tag.getKey()), tag.getValue())) {
                return false;
            }
        }
        return true;
    }

    public void updateJob(String name, JobUpdate update) {
        validateRequired(name, "JobName");
        validateRequired(update, "JobUpdate");
        // Jobs and crawlers skip normalizeName because AWS preserves their case
        String normalizedName = name;
        Job existing = jobStore.get(normalizedName)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Job " + name + " not found.", 400));

        // UpdateJob replaces unspecified configuration with nulls (full replacement) rather than merging.
        // This is deliberate and matches AWS behavior. We only copy fields that the user cannot specify.
        Job updated = new Job();
        updated.setName(existing.getName());
        updated.setCreatedOn(existing.getCreatedOn());
        updated.setProfileName(existing.getProfileName());
        updated.setLastModifiedOn(Instant.now());

        updated.setAllocatedCapacity(update.getAllocatedCapacity());
        updated.setCodeGenConfigurationNodes(update.getCodeGenConfigurationNodes());
        updated.setCommand(update.getCommand());
        updated.setConnections(update.getConnections());
        updated.setDefaultArguments(update.getDefaultArguments());
        updated.setDescription(update.getDescription());
        updated.setExecutionClass(update.getExecutionClass());
        updated.setExecutionProperty(update.getExecutionProperty());
        updated.setGlueVersion(update.getGlueVersion());
        updated.setJobMode(update.getJobMode());
        updated.setJobRunQueuingEnabled(update.getJobRunQueuingEnabled());
        updated.setLogUri(update.getLogUri());
        updated.setMaintenanceWindow(update.getMaintenanceWindow());
        updated.setMaxCapacity(update.getMaxCapacity());
        updated.setMaxRetries(update.getMaxRetries());
        updated.setNonOverridableArguments(update.getNonOverridableArguments());
        updated.setNotificationProperty(update.getNotificationProperty());
        updated.setNumberOfWorkers(update.getNumberOfWorkers());
        updated.setRole(update.getRole());
        updated.setSecurityConfiguration(update.getSecurityConfiguration());
        updated.setSourceControlDetails(update.getSourceControlDetails());
        updated.setTimeout(update.getTimeout());
        updated.setWorkerType(update.getWorkerType());

        jobStore.put(normalizedName, updated);
        LOG.infov("Updated Glue Job: {0}", normalizedName);
    }

    public void deleteJob(String name, String region) {
        validateRequired(name, "JobName");
        // Jobs and crawlers skip normalizeName because AWS preserves their case
        String normalizedName = name;
        jobStore.delete(normalizedName);
        resourceGroupsTaggingService.deleteResources(List.of(jobArn(region, normalizedName)), region);
        LOG.infov("Deleted Glue Job: {0}", name);
    }

    public void createClassifier(Classifier classifier) {
        validateClassifierShape(classifier);
        String name = classifier.name();
        validateClassifierName(name);
        if (classifierStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Classifier " + name + " already exists.", 400);
        }

        Map<String, Object> details = new LinkedHashMap<>(classifier.selectedDetails());
        validateClassifierDetails(classifier.selectedKind(), details);
        double now = Instant.now().toEpochMilli() / 1000.0d;
        details.put("CreationTime", now);
        details.put("LastUpdated", now);
        details.put("Version", 1L);
        classifierStore.put(name, classifier.copyWithDetails(details));
        LOG.infov("Created Glue Classifier: {0}", name);
    }

    public Classifier getClassifier(String name) {
        validateClassifierName(name);
        return classifierStore.get(name)
                .orElseThrow(() -> new AwsException(
                        "EntityNotFoundException", "Classifier " + name + " not found.", 400));
    }

    public Page<Classifier> getClassifiers(Integer maxResults, String nextToken) {
        List<Classifier> classifiers = classifierStore.scan(key -> true);
        classifiers.sort(Comparator.comparing(Classifier::name));
        return paginate(classifiers, maxResults, nextToken);
    }

    public void updateClassifier(Classifier update) {
        validateClassifierShape(update);
        String name = update.name();
        validateClassifierName(name);
        Classifier existing = getClassifier(name);
        if (!existing.selectedKind().equals(update.selectedKind())) {
            throw new AwsException("InvalidInputException", "Classifier type cannot be changed.", 400);
        }

        Map<String, Object> details = new LinkedHashMap<>(existing.selectedDetails());
        update.selectedDetails().forEach((key, value) -> {
            if (!"CreationTime".equals(key) && !"LastUpdated".equals(key) && !"Version".equals(key)) {
                details.put(key, value);
            }
        });
        validateClassifierDetails(existing.selectedKind(), details);
        Number version = (Number) existing.selectedDetails().get("Version");
        details.put("CreationTime", existing.selectedDetails().get("CreationTime"));
        details.put("LastUpdated", Instant.now().toEpochMilli() / 1000.0d);
        details.put("Version", (version == null ? 1L : version.longValue()) + 1L);
        classifierStore.put(name, existing.copyWithDetails(details));
        LOG.infov("Updated Glue Classifier: {0}", name);
    }

    public void deleteClassifier(String name) {
        validateClassifierName(name);
        if (classifierStore.get(name).isEmpty()) {
            throw new AwsException("EntityNotFoundException", "Classifier " + name + " not found.", 400);
        }
        classifierStore.delete(name);
        LOG.infov("Deleted Glue Classifier: {0}", name);
    }

    private void validateClassifierShape(Classifier classifier) {
        if (classifier == null || classifier.selectedKindCount() != 1) {
            throw new AwsException(
                    "InvalidInputException", "Exactly one classifier type must be specified.", 400);
        }
    }

    private void validateClassifierName(String name) {
        validateRequired(name, "Name");
        if (name.length() > 255 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
            throw new AwsException("InvalidInputException", "Name must be between 1 and 255 characters.", 400);
        }
    }

    private void validateClassifierDetails(String kind, Map<String, Object> details) {
        validateClassifierName(stringValue(details, "Name", true));
        switch (kind) {
            case "GrokClassifier" -> validateGrokClassifier(details);
            case "XMLClassifier" -> stringValue(details, "Classification", true);
            case "JsonClassifier" -> stringValue(details, "JsonPath", true);
            case "CsvClassifier" -> validateCsvClassifier(details);
            default -> throw new AwsException("InvalidInputException", "Unsupported classifier type.", 400);
        }
    }

    private void validateGrokClassifier(Map<String, Object> details) {
        stringValue(details, "Classification", true);
        String grokPattern = stringValue(details, "GrokPattern", true);
        if (grokPattern.length() > 2048) {
            throw new AwsException("InvalidInputException", "GrokPattern must not exceed 2048 characters.", 400);
        }
        String customPatterns = stringValue(details, "CustomPatterns", false);
        if (customPatterns != null && customPatterns.length() > 16000) {
            throw new AwsException("InvalidInputException", "CustomPatterns must not exceed 16000 characters.", 400);
        }
    }

    private void validateCsvClassifier(Map<String, Object> details) {
        String delimiter = validateSingleCharacter(details, "Delimiter");
        String quoteSymbol = validateSingleCharacter(details, "QuoteSymbol");
        if (delimiter != null && delimiter.equals(quoteSymbol)) {
            throw new AwsException(
                    "InvalidInputException", "QuoteSymbol must be different from Delimiter.", 400);
        }
        validateEnum(details, "ContainsHeader", CSV_HEADER_VALUES);
        validateEnum(details, "Serde", CSV_SERDE_VALUES);
        validateStringList(details, "Header", null);
        validateStringList(details, "CustomDatatypes", CSV_CUSTOM_DATATYPES);
    }

    private String validateSingleCharacter(Map<String, Object> details, String field) {
        String value = stringValue(details, field, false);
        if (value != null && (value.codePointCount(0, value.length()) != 1
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)) {
            throw new AwsException("InvalidInputException", field + " must be exactly one character.", 400);
        }
        return value;
    }

    private void validateEnum(Map<String, Object> details, String field, Set<String> allowed) {
        String value = stringValue(details, field, false);
        if (value != null && !allowed.contains(value)) {
            throw new AwsException("InvalidInputException", "Invalid " + field + ": " + value, 400);
        }
    }

    private void validateStringList(Map<String, Object> details, String field, Set<String> allowed) {
        Object value = details.get(field);
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> values)) {
            throw new AwsException("InvalidInputException", field + " must be a list.", 400);
        }
        for (Object item : values) {
            if (!(item instanceof String text) || text.isEmpty() || text.length() > 255
                    || (allowed != null && !allowed.contains(text))) {
                throw new AwsException("InvalidInputException", "Invalid value in " + field + ".", 400);
            }
        }
    }

    private String stringValue(Map<String, Object> details, String field, boolean required) {
        Object value = details.get(field);
        if (value == null) {
            if (required) {
                throw new AwsException("InvalidInputException", field + " is required.", 400);
            }
            return null;
        }
        if (!(value instanceof String text) || (required && text.isBlank())) {
            throw new AwsException("InvalidInputException", field + " must be a string.", 400);
        }
        return text;
    }

    public void createCrawler(Crawler crawler) {
        createCrawler(crawler, null, regionResolver.getDefaultRegion());
    }

    public void createCrawler(Crawler crawler, Map<String, String> tags, String region) {
        validateRequired(crawler.getName(), "Name");
        validateRequired(crawler.getRole(), "Role");
        validateRequired(crawler.getTargets(), "Targets");
        
        CrawlerTargets t = crawler.getTargets();
        boolean hasTargets = (t.getS3Targets() != null && !t.getS3Targets().isEmpty()) ||
                             (t.getJdbcTargets() != null && !t.getJdbcTargets().isEmpty()) ||
                             (t.getDynamoDBTargets() != null && !t.getDynamoDBTargets().isEmpty()) ||
                             (t.getCatalogTargets() != null && !t.getCatalogTargets().isEmpty()) ||
                             (t.getDeltaTargets() != null && !t.getDeltaTargets().isEmpty()) ||
                             (t.getIcebergTargets() != null && !t.getIcebergTargets().isEmpty()) ||
                             (t.getMongoDBTargets() != null && !t.getMongoDBTargets().isEmpty()) ||
                             (t.getHudiTargets() != null && !t.getHudiTargets().isEmpty());
        if (!hasTargets) {
            throw new AwsException("InvalidInputException", "At least one crawl target must be specified.", 400);
        }

        // Jobs and crawlers skip normalizeName because AWS preserves their case
        String name = crawler.getName();
        if (crawlerStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Crawler " + name + " already exists.", 400);
        }
        Instant now = Instant.now();
        crawler.setCreationTime(now);
        crawler.setLastUpdated(now);
        crawler.setState("READY");
        crawler.setVersion(1L);
        crawlerStore.put(name, crawler);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(crawlerArn(region, name)), tags, region);
        }
        LOG.infov("Created Glue Crawler: {0}", name);
    }

    public Crawler getCrawler(String name) {
        validateRequired(name, "Name");
        return crawlerStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Crawler " + name + " not found.", 400));
    }

    public List<Crawler> getCrawlers() {
        return crawlerStore.scan(k -> true);
    }

    public Page<Crawler> getCrawlers(Integer maxResults, String nextToken) {
        List<Crawler> all = crawlerStore.scan(k -> true);
        all.sort(Comparator.comparing(Crawler::getName));
        return paginate(all, maxResults, nextToken);
    }

    public Page<String> listCrawlers(Integer maxResults, String nextToken, Map<String, String> tags,
                                     String region) {
        List<String> names = new ArrayList<>();
        for (Crawler crawler : crawlerStore.scan(k -> true)) {
            if (tags == null || tags.isEmpty() || hasTags(crawlerArn(region, crawler.getName()), tags, region)) {
                names.add(crawler.getName());
            }
        }
        names.sort(Comparator.naturalOrder());
        return paginate(names, maxResults, nextToken);
    }

    public BatchGetCrawlersResult batchGetCrawlers(List<String> names) {
        if (names == null) {
            throw new AwsException("InvalidInputException", "CrawlerNames is required.", 400);
        }
        if (names.size() > MAX_BATCH_GET_CRAWLERS) {
            throw new AwsException("InvalidInputException",
                    "CrawlerNames must contain at most " + MAX_BATCH_GET_CRAWLERS + " names.", 400);
        }
        List<Crawler> crawlers = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String name : names) {
            Optional<Crawler> crawler = name == null ? Optional.empty() : crawlerStore.get(name);
            if (crawler.isPresent()) {
                crawlers.add(crawler.get());
            } else {
                notFound.add(name);
            }
        }
        return new BatchGetCrawlersResult(crawlers, notFound);
    }

    public record BatchGetCrawlersResult(List<Crawler> crawlers, List<String> crawlersNotFound) {}

    public void updateCrawlerSchedule(String name, String expression) {
        validateRequired(name, "CrawlerName");
        Crawler crawler = getCrawler(name);
        if (expression == null || expression.isBlank()) {
            crawler.setSchedule(null);
        } else {
            validateCronExpression(expression);
            Schedule schedule = crawler.getSchedule() != null ? crawler.getSchedule() : new Schedule();
            schedule.setScheduleExpression(expression);
            if (schedule.getState() == null) {
                schedule.setState(SCHEDULE_SCHEDULED);
            }
            crawler.setSchedule(schedule);
        }
        crawler.setLastUpdated(Instant.now());
        crawlerStore.put(name, crawler);
    }

    /**
     * Glue schedules are AWS cron expressions: cron(Minutes Hours Day-of-month Month Day-of-week Year),
     * with {@code ?} in exactly one of the two day fields.
     */
    static void validateCronExpression(String expression) {
        String message = "Schedule must be a cron expression, for example cron(15 12 * * ? *).";
        if (expression == null || !expression.startsWith("cron(") || !expression.endsWith(")")) {
            throw new AwsException("InvalidInputException", message, 400);
        }
        String[] fields = expression.substring(5, expression.length() - 1).trim().split("\\s+");
        if (fields.length != 6) {
            throw new AwsException("InvalidInputException", message, 400);
        }
        for (String field : fields) {
            if (!CRON_FIELD.matcher(field).matches()) {
                throw new AwsException("InvalidInputException", message, 400);
            }
        }
        if ("?".equals(fields[2]) == "?".equals(fields[4])) {
            throw new AwsException("InvalidInputException",
                    "Exactly one of day-of-month and day-of-week must be '?' in " + expression, 400);
        }
    }

    public void startCrawlerSchedule(String name) {
        validateRequired(name, "CrawlerName");
        Crawler crawler = getCrawler(name);
        Schedule schedule = crawler.getSchedule();
        if (schedule == null || schedule.getScheduleExpression() == null) {
            throw new AwsException("NoScheduleException", "Crawler " + name + " has no schedule.", 400);
        }
        if (SCHEDULE_SCHEDULED.equals(schedule.getState())) {
            throw new AwsException("SchedulerRunningException",
                    "The schedule of crawler " + name + " is already running.", 400);
        }
        schedule.setState(SCHEDULE_SCHEDULED);
        crawlerStore.put(name, crawler);
    }

    public void stopCrawlerSchedule(String name) {
        validateRequired(name, "CrawlerName");
        Crawler crawler = getCrawler(name);
        Schedule schedule = crawler.getSchedule();
        if (schedule == null || !SCHEDULE_SCHEDULED.equals(schedule.getState())) {
            throw new AwsException("SchedulerNotRunningException",
                    "The schedule of crawler " + name + " is not running.", 400);
        }
        schedule.setState(SCHEDULE_NOT_SCHEDULED);
        crawlerStore.put(name, crawler);
    }

    // ---- Workflows --------------------------------------------------------------------------

    public void createWorkflow(Workflow workflow, Map<String, String> tags, String region) {
        validateRequired(workflow.getName(), "Name");
        validateWorkflowConcurrency(workflow.getMaxConcurrentRuns());
        String name = workflow.getName();
        if (workflowStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Workflow " + name + " already exists.", 400);
        }
        Instant now = Instant.now();
        workflow.setCreatedOn(now);
        workflow.setLastModifiedOn(now);
        workflowStore.put(name, workflow);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(workflowArn(region, name)), tags, region);
        }
        LOG.infov("Created Glue workflow {0}", name);
    }

    public Workflow getWorkflow(String name) {
        validateRequired(name, "Name");
        return workflowStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Workflow " + name + " not found.", 400));
    }

    public Page<String> listWorkflows(Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_WORKFLOWS_PAGE_SIZE)) {
            throw new AwsException("InvalidInputException",
                    "MaxResults must be between 1 and " + MAX_WORKFLOWS_PAGE_SIZE, 400);
        }
        List<String> names = new ArrayList<>();
        for (Workflow workflow : workflowStore.scan(k -> true)) {
            names.add(workflow.getName());
        }
        names.sort(Comparator.naturalOrder());
        return paginate(names, maxResults, nextToken);
    }

    public BatchGetWorkflowsResult batchGetWorkflows(List<String> names) {
        if (names == null || names.isEmpty() || names.size() > MAX_BATCH_GET_WORKFLOWS) {
            throw new AwsException("InvalidInputException",
                    "Names must contain between 1 and " + MAX_BATCH_GET_WORKFLOWS + " names.", 400);
        }
        List<Workflow> workflows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Optional<Workflow> workflow = name == null ? Optional.empty() : workflowStore.get(name);
            if (workflow.isPresent()) {
                workflows.add(workflow.get());
            } else {
                missing.add(name);
            }
        }
        return new BatchGetWorkflowsResult(workflows, missing);
    }

    public record BatchGetWorkflowsResult(List<Workflow> workflows, List<String> missingWorkflows) {}

    /** Applies the members an UpdateWorkflow request sets; the rest of the workflow is kept. */
    public void updateWorkflow(String name, String description, Map<String, String> defaultRunProperties,
                               Integer maxConcurrentRuns) {
        Workflow workflow = getWorkflow(name);
        validateWorkflowConcurrency(maxConcurrentRuns);
        if (description != null) {
            workflow.setDescription(description);
        }
        if (defaultRunProperties != null) {
            workflow.setDefaultRunProperties(defaultRunProperties);
        }
        if (maxConcurrentRuns != null) {
            workflow.setMaxConcurrentRuns(maxConcurrentRuns);
        }
        workflow.setLastModifiedOn(Instant.now());
        workflowStore.put(name, workflow);
    }

    /**
     * DeleteWorkflow declares no EntityNotFoundException: deleting a missing workflow succeeds. The
     * workflow's triggers are left in place, as the API does not say they are removed.
     */
    public void deleteWorkflow(String name, String region) {
        validateRequired(name, "Name");
        workflowStore.delete(name);
        resourceGroupsTaggingService.deleteResources(List.of(workflowArn(region, name)), region);
    }

    public List<Trigger> triggersOfWorkflow(String workflowName) {
        List<Trigger> triggers = new ArrayList<>();
        for (Trigger trigger : allTriggers()) {
            if (workflowName.equals(trigger.getWorkflowName())) {
                triggers.add(trigger);
            }
        }
        return triggers;
    }

    private static void validateWorkflowConcurrency(Integer maxConcurrentRuns) {
        if (maxConcurrentRuns != null && maxConcurrentRuns < 1) {
            throw new AwsException("InvalidInputException", "MaxConcurrentRuns must be at least 1.", 400);
        }
    }

    // ---- Triggers ---------------------------------------------------------------------------

    public void createTrigger(Trigger trigger, Map<String, String> tags, String region) {
        validateRequired(trigger.getName(), "Name");
        validateRequired(trigger.getType(), "Type");
        if (!TRIGGER_TYPES.contains(trigger.getType())) {
            throw new AwsException("InvalidInputException", "Invalid trigger type: " + trigger.getType(), 400);
        }
        if (trigger.getWorkflowName() != null) {
            getWorkflow(trigger.getWorkflowName());
        } else if ("EVENT".equals(trigger.getType())) {
            throw new AwsException("InvalidInputException", "An EVENT trigger must belong to a workflow.", 400);
        }
        validateTriggerDefinition(trigger);
        String name = trigger.getName();
        if (triggerStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Trigger " + name + " already exists.", 400);
        }
        triggerStore.put(name, trigger);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(triggerArn(region, name)), tags, region);
        }
        LOG.infov("Created Glue trigger {0}", name);
    }

    public Trigger getTrigger(String name) {
        validateRequired(name, "Name");
        return triggerStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Trigger " + name + " not found.", 400));
    }

    public List<Trigger> allTriggers() {
        List<Trigger> triggers = triggerStore.scan(k -> true);
        triggers.sort(Comparator.comparing(Trigger::getName));
        return triggers;
    }

    public void putTrigger(Trigger trigger) {
        triggerStore.put(trigger.getName(), trigger);
    }

    /**
     * GetTriggers. With DependentJobName, the triggers that can start that job; AWS documents that
     * all triggers are returned when none can.
     */
    public Page<Trigger> getTriggers(String dependentJobName, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > MAX_TRIGGERS_PAGE_SIZE)) {
            throw new AwsException("InvalidInputException",
                    "MaxResults must be between 1 and " + MAX_TRIGGERS_PAGE_SIZE, 400);
        }
        return paginate(triggersStarting(dependentJobName), maxResults, nextToken);
    }

    public Page<String> listTriggers(String dependentJobName, Integer maxResults, String nextToken,
                                     Map<String, String> tags, String region) {
        List<String> names = new ArrayList<>();
        for (Trigger trigger : triggersStarting(dependentJobName)) {
            if (tags == null || tags.isEmpty() || hasTags(triggerArn(region, trigger.getName()), tags, region)) {
                names.add(trigger.getName());
            }
        }
        return paginate(names, maxResults, nextToken);
    }

    public BatchGetTriggersResult batchGetTriggers(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new AwsException("InvalidInputException", "TriggerNames is required.", 400);
        }
        List<Trigger> triggers = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String name : names) {
            Optional<Trigger> trigger = name == null ? Optional.empty() : triggerStore.get(name);
            if (trigger.isPresent()) {
                triggers.add(trigger.get());
            } else {
                notFound.add(name);
            }
        }
        return new BatchGetTriggersResult(triggers, notFound);
    }

    public record BatchGetTriggersResult(List<Trigger> triggers, List<String> triggersNotFound) {}

    /**
     * Applies the members a TriggerUpdate sets; the rest of the trigger is kept. The update is built
     * and validated on a copy, so a rejected update leaves the stored trigger as it was.
     */
    public Trigger updateTrigger(String name, Trigger update) {
        validateRequired(update, "TriggerUpdate");
        Trigger stored = getTrigger(name);
        Trigger trigger = new Trigger();
        trigger.setName(stored.getName());
        trigger.setWorkflowName(stored.getWorkflowName());
        trigger.setType(stored.getType());
        trigger.setState(stored.getState());
        trigger.setDescription(stored.getDescription());
        trigger.setSchedule(stored.getSchedule());
        trigger.setActions(stored.getActions());
        trigger.setPredicate(stored.getPredicate());
        trigger.setEventBatchingCondition(stored.getEventBatchingCondition());
        if (update.getDescription() != null) {
            trigger.setDescription(update.getDescription());
        }
        if (update.getSchedule() != null) {
            trigger.setSchedule(update.getSchedule());
        }
        if (update.getActions() != null) {
            trigger.setActions(update.getActions());
        }
        if (update.getPredicate() != null) {
            trigger.setPredicate(update.getPredicate());
        }
        if (update.getEventBatchingCondition() != null) {
            trigger.setEventBatchingCondition(update.getEventBatchingCondition());
        }
        validateTriggerDefinition(trigger);
        triggerStore.put(name, trigger);
        return trigger;
    }

    /** DeleteTrigger declares no EntityNotFoundException: deleting a missing trigger succeeds. */
    public void deleteTrigger(String name, String region) {
        validateRequired(name, "Name");
        triggerStore.delete(name);
        resourceGroupsTaggingService.deleteResources(List.of(triggerArn(region, name)), region);
    }

    private List<Trigger> triggersStarting(String dependentJobName) {
        List<Trigger> all = allTriggers();
        if (dependentJobName == null) {
            return all;
        }
        getJob(dependentJobName);
        List<Trigger> starting = new ArrayList<>();
        for (Trigger trigger : all) {
            for (TriggerAction action : trigger.getActions()) {
                if (dependentJobName.equals(action.getJobName())) {
                    starting.add(trigger);
                    break;
                }
            }
        }
        return starting.isEmpty() ? all : starting;
    }

    private void validateTriggerDefinition(Trigger trigger) {
        List<TriggerAction> actions = trigger.getActions();
        if (actions == null || actions.isEmpty()) {
            throw new AwsException("InvalidInputException", "Actions must contain at least one action.", 400);
        }
        for (TriggerAction action : actions) {
            boolean hasJob = action.getJobName() != null;
            boolean hasCrawler = action.getCrawlerName() != null;
            if (hasJob == hasCrawler) {
                throw new AwsException("InvalidInputException",
                        "Each action must name exactly one of JobName and CrawlerName.", 400);
            }
            if (hasJob) {
                getJob(action.getJobName());
            } else {
                getCrawler(action.getCrawlerName());
            }
            if (action.getTimeout() != null && action.getTimeout() < 1) {
                throw new AwsException("InvalidInputException", "Timeout must be at least 1 minute.", 400);
            }
        }
        if ("SCHEDULED".equals(trigger.getType())) {
            validateCronExpression(trigger.getSchedule());
        }
        if ("CONDITIONAL".equals(trigger.getType())) {
            validatePredicate(trigger.getPredicate());
        }
    }

    private void validatePredicate(Predicate predicate) {
        if (predicate == null || predicate.getConditions() == null || predicate.getConditions().isEmpty()) {
            throw new AwsException("InvalidInputException", "A CONDITIONAL trigger needs a predicate.", 400);
        }
        if (predicate.getLogical() != null && !Set.of("AND", "ANY").contains(predicate.getLogical())) {
            throw new AwsException("InvalidInputException", "Logical must be AND or ANY.", 400);
        }
        for (TriggerCondition condition : predicate.getConditions()) {
            if (condition.getLogicalOperator() != null && !"EQUALS".equals(condition.getLogicalOperator())) {
                throw new AwsException("InvalidInputException", "LogicalOperator must be EQUALS.", 400);
            }
            if (condition.getJobName() != null) {
                if (condition.getState() == null || !CONDITION_JOB_STATES.contains(condition.getState())) {
                    throw new AwsException("InvalidInputException",
                            "A job condition's State must be one of " + CONDITION_JOB_STATES, 400);
                }
                getJob(condition.getJobName());
            } else if (condition.getCrawlerName() != null) {
                if (condition.getCrawlState() == null || !CONDITION_CRAWL_STATES.contains(condition.getCrawlState())) {
                    throw new AwsException("InvalidInputException",
                            "A crawler condition's CrawlState must be one of " + CONDITION_CRAWL_STATES, 400);
                }
                getCrawler(condition.getCrawlerName());
            } else {
                throw new AwsException("InvalidInputException",
                        "Each condition must name a JobName or a CrawlerName.", 400);
            }
        }
    }

    public void updateCrawler(Crawler update) {
        validateRequired(update.getName(), "Name");
        // Jobs and crawlers skip normalizeName because AWS preserves their case
        String name = update.getName();
        Crawler existing = crawlerStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Crawler " + update.getName() + " not found.", 400));

        Crawler updated = new Crawler();
        updated.setName(existing.getName());
        updated.setCreationTime(existing.getCreationTime());
        updated.setLastUpdated(Instant.now());
        updated.setState(existing.getState());
        updated.setVersion((existing.getVersion() == null ? 1L : existing.getVersion()) + 1L);

        updated.setClassifiers(update.getClassifiers() != null ? update.getClassifiers() : existing.getClassifiers());
        updated.setConfiguration(update.getConfiguration() != null ? update.getConfiguration() : existing.getConfiguration());
        updated.setCrawlerSecurityConfiguration(update.getCrawlerSecurityConfiguration() != null ? update.getCrawlerSecurityConfiguration() : existing.getCrawlerSecurityConfiguration());
        updated.setDatabaseName(update.getDatabaseName() != null ? update.getDatabaseName() : existing.getDatabaseName());
        updated.setDescription(update.getDescription() != null ? update.getDescription() : existing.getDescription());
        updated.setLakeFormationConfiguration(update.getLakeFormationConfiguration() != null ? update.getLakeFormationConfiguration() : existing.getLakeFormationConfiguration());
        updated.setLineageConfiguration(update.getLineageConfiguration() != null ? update.getLineageConfiguration() : existing.getLineageConfiguration());
        updated.setRecrawlPolicy(update.getRecrawlPolicy() != null ? update.getRecrawlPolicy() : existing.getRecrawlPolicy());
        updated.setRole(update.getRole() != null ? update.getRole() : existing.getRole());
        updated.setSchedule(update.getSchedule() != null ? update.getSchedule() : existing.getSchedule());
        updated.setSchemaChangePolicy(update.getSchemaChangePolicy() != null ? update.getSchemaChangePolicy() : existing.getSchemaChangePolicy());
        updated.setTablePrefix(update.getTablePrefix() != null ? update.getTablePrefix() : existing.getTablePrefix());
        updated.setTargets(update.getTargets() != null ? update.getTargets() : existing.getTargets());

        crawlerStore.put(name, updated);
        LOG.infov("Updated Glue Crawler: {0}", name);
    }

    public void deleteCrawler(String name, String region) {
        validateRequired(name, "Name");
        // Jobs and crawlers skip normalizeName because AWS preserves their case
        String normalizedName = name;
        if (crawlerStore.get(normalizedName).isEmpty()) {
            throw new AwsException("EntityNotFoundException", "Crawler " + name + " not found.", 400);
        }
        crawlerStore.delete(normalizedName);
        resourceGroupsTaggingService.deleteResources(List.of(crawlerArn(region, normalizedName)), region);
        LOG.infov("Deleted Glue Crawler: {0}", name);
    }

    // ---- Connections -----------------------------------------------------------------------

    public String createConnection(ConnectionInput input, Map<String, String> tags, String region) {
        validateConnectionInput(input);
        // Connections keep their case, as jobs and crawlers do: AWS matches the name exactly.
        String name = input.getName();
        if (connectionStore.get(name).isPresent()) {
            throw new AwsException("AlreadyExistsException", "Connection " + name + " already exists.", 400);
        }
        Instant now = Instant.now();
        Connection connection = toConnection(input);
        encryptConnectionPasswords(connection.getConnectionProperties(), region);
        connection.setCreationTime(now);
        connection.setLastUpdatedTime(now);
        connectionStore.put(name, connection);
        if (tags != null && !tags.isEmpty()) {
            resourceGroupsTaggingService.tagResources(List.of(connectionArn(region, name)), tags, region);
        }
        LOG.infov("Created Glue Connection: {0}", name);
        // Nothing is validated against the data store, so there is no IN_PROGRESS phase to report.
        return CONNECTION_STATUS_READY;
    }

    public Connection getConnection(String name, boolean hidePassword) {
        validateRequired(name, "Name");
        Connection connection = connectionStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Connection " + name + " not found.", 400));
        return hidePassword ? connection.withoutPassword() : connection;
    }

    /**
     * Lists connections, narrowed by the GetConnections filter: every {@code MatchCriteria}
     * entry must appear on the connection, and {@code ConnectionType} and
     * {@code ConnectionSchemaVersion} must match exactly when given.
     */
    public Page<Connection> getConnections(List<String> matchCriteria, String connectionType,
                                           Integer connectionSchemaVersion, boolean hidePassword,
                                           Integer maxResults, String nextToken) {
        List<Connection> matching = new ArrayList<>();
        for (Connection connection : connectionStore.scan(k -> true)) {
            if (matchCriteria != null && !matchCriteria.isEmpty()
                    && (connection.getMatchCriteria() == null
                        || !connection.getMatchCriteria().containsAll(matchCriteria))) {
                continue;
            }
            if (connectionType != null && !connectionType.equals(connection.getConnectionType())) {
                continue;
            }
            if (connectionSchemaVersion != null
                    && !connectionSchemaVersion.equals(connection.getConnectionSchemaVersion())) {
                continue;
            }
            matching.add(hidePassword ? connection.withoutPassword() : connection);
        }
        matching.sort(Comparator.comparing(Connection::getName));
        return paginate(matching, maxResults, nextToken);
    }

    /**
     * UpdateConnection takes a ConnectionInput that "redefines the connection in question"
     * (API reference), so the stored definition is replaced rather than merged: a member left
     * out of the input is gone afterwards. The connection keeps its name and creation time.
     */
    public void updateConnection(String name, ConnectionInput input, String region) {
        validateRequired(name, "Name");
        validateRequired(input, "ConnectionInput");
        Connection existing = connectionStore.get(name)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Connection " + name + " not found.", 400));
        validateConnectionInput(input);
        Connection updated = toConnection(input);
        encryptConnectionPasswords(updated.getConnectionProperties(), region);
        updated.setName(existing.getName());
        updated.setCreationTime(existing.getCreationTime());
        updated.setLastUpdatedTime(Instant.now());
        connectionStore.put(name, updated);
        LOG.infov("Updated Glue Connection: {0}", name);
    }

    public void deleteConnection(String name, String region) {
        validateRequired(name, "ConnectionName");
        if (connectionStore.get(name).isEmpty()) {
            throw new AwsException("EntityNotFoundException", "Connection " + name + " not found.", 400);
        }
        connectionStore.delete(name);
        resourceGroupsTaggingService.deleteResources(List.of(connectionArn(region, name)), region);
        LOG.infov("Deleted Glue Connection: {0}", name);
    }

    /** Deletes what it can; the names that could not be deleted come back keyed in the errors map. */
    public BatchDeleteConnectionResult batchDeleteConnections(List<String> names, String region) {
        validateRequired(names, "ConnectionNameList");
        if (names.size() > MAX_BATCH_DELETE_CONNECTIONS) {
            throw new AwsException("InvalidInputException",
                    "ConnectionNameList must have at most " + MAX_BATCH_DELETE_CONNECTIONS + " items.", 400);
        }
        List<String> succeeded = new ArrayList<>();
        Map<String, ErrorDetail> errors = new LinkedHashMap<>();
        for (String name : names) {
            try {
                deleteConnection(name, region);
                succeeded.add(name);
            } catch (AwsException e) {
                errors.put(name, new ErrorDetail(e.getErrorCode(), e.getMessage()));
            }
        }
        return new BatchDeleteConnectionResult(succeeded, errors);
    }

    /**
     * TestConnection on AWS is asynchronous and answers with an empty body: the outcome is only
     * visible later, on the connection's status. Floci never reaches the data store (no job can
     * run against it yet), so the request is checked for shape and accepted. Either an existing
     * connection is named, or an inline definition with a type and properties is given.
     */
    public void testConnection(String connectionName, String connectionType,
                               Map<String, String> connectionProperties) {
        if (connectionName != null && !connectionName.isBlank()) {
            getConnection(connectionName, false);
            return;
        }
        validateRequired(connectionType, "TestConnectionInput.ConnectionType");
        validateConnectionType(connectionType);
        validateRequired(connectionProperties, "TestConnectionInput.ConnectionProperties");
        validateConnectionProperties(connectionProperties);
    }

    private Connection toConnection(ConnectionInput input) {
        Connection connection = new Connection();
        connection.setName(input.getName());
        connection.setDescription(input.getDescription());
        connection.setConnectionType(input.getConnectionType());
        connection.setMatchCriteria(input.getMatchCriteria());
        connection.setConnectionProperties(new LinkedHashMap<>(input.getConnectionProperties()));
        connection.setSparkProperties(input.getSparkProperties());
        connection.setAthenaProperties(input.getAthenaProperties());
        connection.setPythonProperties(input.getPythonProperties());
        connection.setPhysicalConnectionRequirements(input.getPhysicalConnectionRequirements());
        if (input.getAuthenticationConfiguration() != null) {
            connection.setAuthenticationConfiguration(input.getAuthenticationConfiguration().toOutput());
        }
        connection.setStatus(CONNECTION_STATUS_READY);
        // Schema version 2 "supports properties for specific compute environments" (Connection
        // structure). A definition that uses any of those members is a version 2 connection;
        // the classic JDBC/Kafka/Network shape is version 1.
        boolean usesComputeEnvironmentMembers = input.getAuthenticationConfiguration() != null
                || input.getSparkProperties() != null
                || input.getAthenaProperties() != null
                || input.getPythonProperties() != null;
        connection.setConnectionSchemaVersion(usesComputeEnvironmentMembers ? 2 : 1);
        return connection;
    }

    private void validateConnectionInput(ConnectionInput input) {
        validateRequired(input, "ConnectionInput");
        validateRequired(input.getName(), "ConnectionInput.Name");
        if (input.getName().length() > MAX_CONNECTION_NAME_LENGTH) {
            throw new AwsException("InvalidInputException",
                    "ConnectionInput.Name must be between 1 and " + MAX_CONNECTION_NAME_LENGTH + " characters.", 400);
        }
        validateRequired(input.getConnectionType(), "ConnectionInput.ConnectionType");
        validateConnectionType(input.getConnectionType());
        validateRequired(input.getConnectionProperties(), "ConnectionInput.ConnectionProperties");
        validateConnectionProperties(input.getConnectionProperties());
        if (input.getDescription() != null && input.getDescription().length() > MAX_CONNECTION_DESCRIPTION_LENGTH) {
            throw new AwsException("InvalidInputException",
                    "ConnectionInput.Description must be at most " + MAX_CONNECTION_DESCRIPTION_LENGTH + " characters.", 400);
        }
        if (input.getMatchCriteria() != null && input.getMatchCriteria().size() > MAX_CONNECTION_MATCH_CRITERIA) {
            throw new AwsException("InvalidInputException",
                    "ConnectionInput.MatchCriteria must have at most " + MAX_CONNECTION_MATCH_CRITERIA + " items.", 400);
        }
    }

    private void validateConnectionType(String connectionType) {
        if (!CONNECTION_TYPES.contains(connectionType)) {
            throw new AwsException("InvalidInputException",
                    "Unsupported connection type: " + connectionType, 400);
        }
    }

    private void validateConnectionProperties(Map<String, String> properties) {
        if (properties.size() > MAX_CONNECTION_PROPERTIES) {
            throw new AwsException("InvalidInputException",
                    "ConnectionProperties must have at most " + MAX_CONNECTION_PROPERTIES + " entries.", 400);
        }
        for (String key : properties.keySet()) {
            if (!CONNECTION_PROPERTY_KEYS.contains(key)) {
                throw new AwsException("InvalidInputException",
                        "Unsupported connection property key: " + key, 400);
            }
        }
    }

    @RegisterForReflection
    public record BatchDeleteConnectionResult(
            @JsonProperty("Succeeded") List<String> succeeded,
            @JsonProperty("Errors") Map<String, ErrorDetail> errors) {}

    // ---- Catalog resource policy and encryption settings -----------------------------------

    /**
     * Sets the catalog's one resource policy. {@code PolicyExistsCondition} and
     * {@code PolicyHashCondition} are checked against the stored policy first and fail with
     * ConditionCheckFailureException, which is how Terraform's create (NOT_EXIST) and update
     * (MUST_EXIST) tell each other apart. Returns the new policy's hash.
     */
    public String putResourcePolicy(String policyInJson, String policyHashCondition,
                                    String policyExistsCondition, String enableHybrid) {
        validateRequired(policyInJson, "PolicyInJson");
        validatePolicyDocument(policyInJson);
        if (policyExistsCondition != null && !POLICY_EXISTS_CONDITIONS.contains(policyExistsCondition)) {
            throw new AwsException("InvalidInputException",
                    "Unsupported PolicyExistsCondition: " + policyExistsCondition, 400);
        }
        if (enableHybrid != null && !ENABLE_HYBRID_VALUES.contains(enableHybrid)) {
            throw new AwsException("InvalidInputException", "Unsupported EnableHybrid value: " + enableHybrid, 400);
        }
        Optional<GluePolicy> existing = resourcePolicyStore.get(CATALOG_KEY);
        if ("NOT_EXIST".equals(policyExistsCondition) && existing.isPresent()) {
            throw new AwsException("ConditionCheckFailureException",
                    "A resource policy already exists and PolicyExistsCondition is NOT_EXIST.", 400);
        }
        if ("MUST_EXIST".equals(policyExistsCondition) && existing.isEmpty()) {
            throw new AwsException("ConditionCheckFailureException",
                    "No resource policy exists and PolicyExistsCondition is MUST_EXIST.", 400);
        }
        checkPolicyHashCondition(policyHashCondition, existing);

        Instant now = Instant.now();
        GluePolicy policy = new GluePolicy();
        policy.setPolicyInJson(policyInJson);
        policy.setPolicyHash(policyHash(policyInJson));
        policy.setCreateTime(existing.map(GluePolicy::getCreateTime).orElse(now));
        policy.setUpdateTime(now);
        resourcePolicyStore.put(CATALOG_KEY, policy);
        LOG.infov("Set Glue catalog resource policy (hash {0})", policy.getPolicyHash());
        return policy.getPolicyHash();
    }

    public GluePolicy getResourcePolicy() {
        return resourcePolicyStore.get(CATALOG_KEY)
                .orElseThrow(() -> new AwsException("EntityNotFoundException", "Policy not found", 400));
    }

    /** The catalog policy is the only entry; RAM-granted per-resource policies are not emulated. */
    public Page<GluePolicy> getResourcePolicies(Integer maxResults, String nextToken) {
        List<GluePolicy> policies = new ArrayList<>(resourcePolicyStore.get(CATALOG_KEY).stream().toList());
        return paginate(policies, maxResults, nextToken);
    }

    public void deleteResourcePolicy(String policyHashCondition) {
        Optional<GluePolicy> existing = resourcePolicyStore.get(CATALOG_KEY);
        if (existing.isEmpty()) {
            throw new AwsException("EntityNotFoundException", "Policy not found", 400);
        }
        checkPolicyHashCondition(policyHashCondition, existing);
        resourcePolicyStore.delete(CATALOG_KEY);
        LOG.info("Deleted Glue catalog resource policy");
    }

    /** Before anything is put, a catalog reports both blocks with encryption off. */
    public DataCatalogEncryptionSettings getDataCatalogEncryptionSettings() {
        return encryptionSettingsStore.get(CATALOG_KEY).orElseGet(DataCatalogEncryptionSettings::defaults);
    }

    /**
     * Replaces the catalog's security configuration. A block left out of the request keeps the
     * default (off), so a later read always carries both blocks, as AWS's does.
     */
    public void putDataCatalogEncryptionSettings(DataCatalogEncryptionSettings settings) {
        validateRequired(settings, "DataCatalogEncryptionSettings");
        DataCatalogEncryptionSettings stored = DataCatalogEncryptionSettings.defaults();
        EncryptionAtRest atRest = settings.getEncryptionAtRest();
        if (atRest != null) {
            validateRequired(atRest.getCatalogEncryptionMode(), "EncryptionAtRest.CatalogEncryptionMode");
            if (!CATALOG_ENCRYPTION_MODES.contains(atRest.getCatalogEncryptionMode())) {
                throw new AwsException("InvalidInputException",
                        "Unsupported CatalogEncryptionMode: " + atRest.getCatalogEncryptionMode(), 400);
            }
            stored.setEncryptionAtRest(atRest);
        }
        ConnectionPasswordEncryption passwords = settings.getConnectionPasswordEncryption();
        if (passwords != null) {
            validateRequired(passwords.getReturnConnectionPasswordEncrypted(),
                    "ConnectionPasswordEncryption.ReturnConnectionPasswordEncrypted");
            stored.setConnectionPasswordEncryption(passwords);
        }
        encryptionSettingsStore.put(CATALOG_KEY, stored);
        LOG.infov("Updated Glue Data Catalog encryption settings: at rest {0}, password encryption {1}",
                stored.getEncryptionAtRest().getCatalogEncryptionMode(),
                stored.getConnectionPasswordEncryption().getReturnConnectionPasswordEncrypted());
    }

    /**
     * Applies the catalog's ConnectionPasswordEncryption to a connection being created or
     * updated: each plaintext password property is encrypted with the configured KMS key and
     * stored under its ENCRYPTED_ name (the Connection structure documents each pair), so every
     * later read returns it encrypted. A KMS failure is the GlueEncryptionException both
     * operations list.
     */
    private void encryptConnectionPasswords(Map<String, String> properties, String region) {
        ConnectionPasswordEncryption setting = getDataCatalogEncryptionSettings().getConnectionPasswordEncryption();
        if (setting == null || !Boolean.TRUE.equals(setting.getReturnConnectionPasswordEncrypted())) {
            return;
        }
        String keyId = setting.getAwsKmsKeyId();
        for (Map.Entry<String, String> pair : ENCRYPTED_CONNECTION_PROPERTY_KEYS.entrySet()) {
            String plaintext = properties.get(pair.getKey());
            if (plaintext == null) {
                continue;
            }
            if (keyId == null || keyId.isBlank()) {
                throw new AwsException("GlueEncryptionException",
                        "Connection password encryption is enabled but the catalog settings name no AwsKmsKeyId.", 400);
            }
            byte[] ciphertext;
            try {
                ciphertext = kmsService.encrypt(keyId, plaintext.getBytes(StandardCharsets.UTF_8), region);
            } catch (AwsException e) {
                throw new AwsException("GlueEncryptionException",
                        "An encryption operation failed: " + e.getMessage(), 400);
            }
            properties.remove(pair.getKey());
            properties.put(pair.getValue(), Base64.getEncoder().encodeToString(ciphertext));
        }
    }

    private static void validatePolicyDocument(String policyInJson) {
        if (policyInJson.length() < 2) {
            throw new AwsException("InvalidInputException", "PolicyInJson must be at least 2 characters.", 400);
        }
        try {
            JsonNode document = POLICY_JSON.readTree(policyInJson);
            if (document == null || !document.isObject()) {
                throw new AwsException("InvalidInputException", "PolicyInJson must be a JSON policy document.", 400);
            }
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidInputException", "PolicyInJson is not valid JSON: " + e.getOriginalMessage(), 400);
        }
    }

    private static void checkPolicyHashCondition(String policyHashCondition, Optional<GluePolicy> existing) {
        if (policyHashCondition == null || policyHashCondition.isBlank()) {
            return;
        }
        String current = existing.map(GluePolicy::getPolicyHash).orElse(null);
        if (!policyHashCondition.equals(current)) {
            throw new AwsException("ConditionCheckFailureException",
                    "PolicyHashCondition does not match the current policy hash.", 400);
        }
    }

    /**
     * AWS documents PolicyHash only as an opaque value to echo back in PolicyHashCondition; this
     * derives it from the document so the same policy always yields the same hash.
     */
    private static String policyHash(String policyInJson) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(policyInJson.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is a required JDK algorithm", e);
        }
    }

    public void tagResource(String arn, Map<String, String> tags, String region) {
        validateArn(arn);
        if (arn.contains(":registry/") || arn.contains(":schema/")) {
            schemaRegistryService.tagResource(arn, tags);
        } else {
            validateResourceExists(arn);
            resourceGroupsTaggingService.tagResources(List.of(arn), tags, region);
        }
    }

    public void untagResource(String arn, List<String> tagKeys, String region) {
        validateArn(arn);
        if (arn.contains(":registry/") || arn.contains(":schema/")) {
            schemaRegistryService.untagResource(arn, tagKeys);
        } else {
            validateResourceExists(arn);
            resourceGroupsTaggingService.untagResources(List.of(arn), tagKeys, region);
        }
    }

    public Map<String, String> getTags(String arn, String region) {
        validateArn(arn);
        if (arn.contains(":registry/") || arn.contains(":schema/")) {
            return schemaRegistryService.getTags(arn);
        } else {
            validateResourceExists(arn);
            return resourceGroupsTaggingService.getTagsForResource(region, arn);
        }
    }

    private void validateArn(String arn) {
        if (arn == null || !arn.startsWith("arn:")) {
            throw new AwsException("InvalidInputException", "Invalid ARN", 400);
        }
    }

    private void validateResourceExists(String arn) {
        String[] parts = arn.split(":");
        if (parts.length >= 6) {
            String resource = parts[5];
            if (resource.startsWith("job/")) {
                getJob(resource.substring(4));
                return;
            } else if (resource.startsWith("crawler/")) {
                getCrawler(resource.substring(8));
                return;
            } else if (resource.startsWith("trigger/")) {
                getTrigger(resource.substring(8));
                return;
            } else if (resource.startsWith("workflow/")) {
                getWorkflow(resource.substring(9));
                return;
            } else if (resource.startsWith("connection/")) {
                getConnection(resource.substring(11), false);
                return;
            } else if (resource.startsWith("database/")) {
                getDatabase(resource.substring(9));
                return;
            } else if (resource.startsWith("table/")) {
                String[] tableParts = resource.substring(6).split("/");
                if (tableParts.length >= 2) {
                    getTable(tableParts[0], tableParts[1]);
                    return;
                }
            } else if (resource.startsWith("userDefinedFunction/")) {
                String[] funcParts = resource.substring(20).split("/");
                if (funcParts.length >= 2) {
                    getUserDefinedFunction(funcParts[0], funcParts[1]);
                    return;
                }
            }
        }
        throw new AwsException("EntityNotFoundException", "Resource " + arn + " not found or not supported.", 400);
    }

    public record Page<T>(List<T> items, String nextToken) {}

    <T> Page<T> paginate(List<T> all, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > 1000)) {
            throw new AwsException("InvalidInputException", "MaxResults must be between 1 and 1000", 400);
        }
        int limit = maxResults == null ? 100 : maxResults;
        int start = 0;
        if (nextToken != null && !nextToken.isBlank()) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
            }
        }
        if (start < 0 || start > all.size()) {
            throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
        }
        int end = Math.min(start + limit, all.size());
        String newToken = end < all.size() ? String.valueOf(end) : null;
        return new Page<>(List.copyOf(all.subList(start, end)), newToken);
    }
}

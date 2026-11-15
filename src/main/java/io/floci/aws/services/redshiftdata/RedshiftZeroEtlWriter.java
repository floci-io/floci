package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@ApplicationScoped
public class RedshiftZeroEtlWriter {

    private static final Pattern TABLE_NAME = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");
    private static final String LANDING_TABLE_DDL = "CREATE TABLE IF NOT EXISTS %s ("
            + "event_id TEXT PRIMARY KEY, "
            + "event_name TEXT NOT NULL, "
            + "event_source TEXT, "
            + "aws_region TEXT, "
            + "sequence_number TEXT NOT NULL, "
            + "approximate_creation_datetime TIMESTAMPTZ, "
            + "keys_json TEXT, "
            + "old_image_json TEXT, "
            + "new_image_json TEXT, "
            + "ingested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP";
    private static final String INSERT_RECORD = "INSERT INTO %s (event_id, event_name, event_source, aws_region, "
            + "sequence_number, approximate_creation_datetime, keys_json, old_image_json, new_image_json) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING";
    private static final String COLUMNS_OF_TABLE = "SELECT column_name, data_type FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ?";
    /** Whether a table exists, and the comment it carries: the mark of the integration that made it. */
    private static final String TABLE_OWNER = "SELECT to_regclass(?) IS NOT NULL, "
            + "obj_description(to_regclass(?), 'pg_class')";
    private static final String OWNER_MARKER_PREFIX = "floci-zetl-integration:";
    private static final Pattern INTEGRATION_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Logger LOG = Logger.getLogger(RedshiftZeroEtlWriter.class);

    private final RedshiftService redshiftService;
    private final RedshiftZeroEtlEndpoints endpoints;
    private final RedshiftDataConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    @Inject
    RedshiftZeroEtlWriter(RedshiftService redshiftService,
                          RedshiftZeroEtlEndpoints endpoints,
                          RedshiftDataConnectionFactory connectionFactory,
                          ObjectMapper objectMapper) {
        this.redshiftService = redshiftService;
        this.endpoints = endpoints;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    /** {@code targetArn} names a Redshift Serverless namespace or a provisioned cluster. */
    public void createLandingTable(String accountId, String targetArn, String tableName) {
        String safeTableName = validateTableName(tableName);
        try (Connection connection = connectionFactory.open(target(accountId, targetArn));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(LANDING_TABLE_DDL.formatted(safeTableName) + ")");
        } catch (SQLException e) {
            throw new AwsException("InternalFailure", "Could not create Redshift zero-ETL landing table.", 500);
        }
    }

    /**
     * Makes the table the integration projects its source's current item state into its own: the table carries a
     * comment naming the integration, so a later run knows whose it is.
     *
     * <ul>
     *   <li>No such table: it is created and marked.</li>
     *   <li>A table this integration marked, with the columns its source's keys give: it is kept.</li>
     *   <li>A table an earlier integration marked, that no longer exists, or this one's with other columns or column
     *       types (the source was made again with other keys): it is dropped and created afresh, since its rows
     *       describe a source that no longer exists.</li>
     *   <li>A table nobody marked, a user's own, or one that a live integration marked (two sources sharing a table
     *       name, such as the same name in two Regions): it is refused with {@code InvalidParameterValue} and left
     *       alone.</li>
     * </ul>
     *
     * @param integrationId the id of the integration that owns the table, the last segment of its ARN
     * @return whether the table was made afresh, so that it holds none of its source's items yet
     */
    public boolean createCurrentStateTable(String accountId, String targetArn, RedshiftZeroEtlCurrentState state,
                                           String integrationId) {
        String marker = ownerMarker(integrationId);
        try (Connection connection = connectionFactory.open(target(accountId, targetArn))) {
            connection.setAutoCommit(false);
            try {
                boolean exists;
                String owner;
                try (PreparedStatement statement = connection.prepareStatement(TABLE_OWNER)) {
                    statement.setString(1, state.qualifiedName());
                    statement.setString(2, state.qualifiedName());
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        exists = rows.getBoolean(1);
                        owner = rows.getString(2);
                    }
                }
                boolean fresh = true;
                if (exists) {
                    if (owner == null || !owner.startsWith(OWNER_MARKER_PREFIX)) {
                        throw new AwsException("InvalidParameterValue", "Table " + state.qualifiedName()
                                + " already exists and was not created by a zero-ETL integration, so integration "
                                + integrationId + " will not write into it.", 400);
                    }
                    String markedBy = owner.substring(OWNER_MARKER_PREFIX.length());
                    if (!markedBy.equals(integrationId) && redshiftService.integrationExists(accountId, markedBy)) {
                        throw new AwsException("InvalidParameterValue", "Table " + state.qualifiedName()
                                + " is in use by zero-ETL integration " + markedBy + ", so integration "
                                + integrationId + " will not write into it.", 400);
                    }
                    fresh = !owner.equals(marker) || !hasColumns(connection, state);
                }
                if (fresh) {
                    try (Statement statement = connection.createStatement()) {
                        if (exists) {
                            statement.executeUpdate("DROP TABLE " + state.qualifiedName());
                        }
                        statement.executeUpdate(state.ddl());
                        statement.executeUpdate("COMMENT ON TABLE " + state.qualifiedName() + " IS '" + marker + "'");
                    }
                }
                connection.commit();
                return fresh;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.warnv(e, "Could not create Redshift zero-ETL table {0}", state.qualifiedName());
            throw new AwsException("InternalFailure", "Could not create Redshift zero-ETL table "
                    + state.qualifiedName() + ": " + e.getMessage(), 500);
        }
    }

    private static String ownerMarker(String integrationId) {
        if (integrationId == null || !INTEGRATION_ID.matcher(integrationId).matches()) {
            throw new AwsException("InvalidParameterValue", "Integration id is invalid.", 400);
        }
        return OWNER_MARKER_PREFIX + integrationId;
    }

    /**
     * Whether the table has exactly the columns the source's keys give, with their types: a source made again with
     * the same key name under another type would otherwise keep a column its keys can no longer be bound to.
     */
    private static boolean hasColumns(Connection connection, RedshiftZeroEtlCurrentState state) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMNS_OF_TABLE)) {
            statement.setString(1, state.tableName());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString(1), rows.getString(2));
                }
            }
        }
        return columns.equals(state.expectedColumnTypes());
    }

    /** Empties the current-state table, for an integration starting its backfill over a table left behind. */
    public void resetCurrentStateTable(String accountId, String targetArn, RedshiftZeroEtlCurrentState state) {
        try (Connection connection = connectionFactory.open(target(accountId, targetArn));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(state.resetSql());
        } catch (SQLException e) {
            LOG.warnv(e, "Could not empty Redshift zero-ETL table {0}", state.qualifiedName());
            throw new AwsException("InternalFailure", "Could not empty Redshift zero-ETL table "
                    + state.qualifiedName() + ": " + e.getMessage(), 500);
        }
    }

    /**
     * Applies the changes to the current-state table in order, in one transaction: an upsert replaces the item
     * under its keys and a delete removes it. Both are idempotent, so a batch written again after a failure
     * leaves the same state.
     */
    public void applyChanges(String accountId, String targetArn, RedshiftZeroEtlCurrentState state,
                             List<RedshiftZeroEtlCurrentState.Change> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }
        try (Connection connection = connectionFactory.open(target(accountId, targetArn))) {
            connection.setAutoCommit(false);
            try (PreparedStatement upsert = connection.prepareStatement(state.upsertSql());
                 PreparedStatement delete = connection.prepareStatement(state.deleteSql())) {
                for (RedshiftZeroEtlCurrentState.Change change : changes) {
                    if (change.isDelete()) {
                        state.bindKeys(delete, change.keys());
                        delete.executeUpdate();
                    } else {
                        state.bindKeys(upsert, change.keys());
                        upsert.setString(state.valueParameterIndex(), objectMapper.writeValueAsString(change.item()));
                        upsert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            LOG.warnv(e, "Could not apply Redshift zero-ETL changes to {0}", state.qualifiedName());
            throw new AwsException("InternalFailure", "Could not apply Redshift zero-ETL changes to "
                    + state.qualifiedName() + ": " + e.getMessage(), 500);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure", "Could not serialize a DynamoDB item.", 500);
        }
    }

    /** Writes AWS DynamoDB Streams {@code Records[]} elements, skipping event ids already landed. */
    public void writeBatch(String accountId, String targetArn, String tableName, List<JsonNode> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        String safeTableName = validateTableName(tableName);
        try (Connection connection = connectionFactory.open(target(accountId, targetArn));
             PreparedStatement statement = connection.prepareStatement(INSERT_RECORD.formatted(safeTableName))) {
            for (JsonNode record : records) {
                bind(statement, record);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new AwsException("InternalFailure", "Could not write Redshift zero-ETL records.", 500);
        }
    }

    private void bind(PreparedStatement statement, JsonNode record) throws SQLException {
        JsonNode dynamodb = record.path("dynamodb");
        statement.setString(1, text(record, "eventID"));
        statement.setString(2, text(record, "eventName"));
        setNullableString(statement, 3, text(record, "eventSource"));
        setNullableString(statement, 4, text(record, "awsRegion"));
        statement.setString(5, text(dynamodb, "SequenceNumber"));
        long approximateCreationDateTime = dynamodb.path("ApproximateCreationDateTime").asLong();
        if (approximateCreationDateTime > 0) {
            // The PostgreSQL driver binds an OffsetDateTime to TIMESTAMPTZ but cannot infer a type for an Instant.
            statement.setObject(6, OffsetDateTime.ofInstant(Instant.ofEpochSecond(approximateCreationDateTime),
                    ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        setJson(statement, 7, dynamodb.get("Keys"));
        setJson(statement, 8, dynamodb.get("OldImage"));
        setJson(statement, 9, dynamodb.get("NewImage"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private void setJson(PreparedStatement statement, int index, JsonNode value) throws SQLException {
        if (value == null || value.isNull()) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        try {
            statement.setString(index, objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalFailure", "Could not serialize a DynamoDB stream record.", 500);
        }
    }

    /**
     * A namespace is written through its workgroup, into the namespace's database as its admin user, the
     * same identity a cluster target is written with.
     */
    private RedshiftDataResourceResolver.DatabaseTarget target(String accountId, String targetArn) {
        if (RedshiftZeroEtlEndpoints.isNamespaceArn(targetArn)) {
            RedshiftServerlessService.WorkgroupTarget workgroup = endpoints.targetWorkgroup(accountId, targetArn);
            return new RedshiftDataResourceResolver.DatabaseTarget(workgroup.arn(), workgroup.host(),
                    workgroup.port(), workgroup.database(), workgroup.masterUsername(), workgroup.masterPassword());
        }
        String clusterIdentifier = AwsArnUtils.parse(targetArn).resource().substring("cluster:".length());
        Cluster cluster;
        try {
            cluster = redshiftService.describeClustersForAccount(accountId, clusterIdentifier).get(0);
        } catch (RuntimeException e) {
            throw new AwsException("ResourceNotFoundException",
                    "Redshift cluster " + clusterIdentifier + " was not found.", 404);
        }
        if (cluster.getContainerHost() == null || cluster.getContainerHost().isBlank()
                || cluster.getContainerPort() <= 0) {
            throw new AwsException("InvalidClusterState", "Redshift cluster runtime is not available.", 400);
        }
        return new RedshiftDataResourceResolver.DatabaseTarget(
                "cluster:" + clusterIdentifier,
                cluster.getContainerHost(),
                cluster.getContainerPort(),
                "dev",
                cluster.getMasterUsername(),
                cluster.getMasterPassword());
    }

    private static String validateTableName(String tableName) {
        if (tableName == null || !TABLE_NAME.matcher(tableName).matches()) {
            throw new AwsException("InvalidParameterValue", "Landing table name is invalid.", 400);
        }
        return tableName;
    }
}

package io.floci.aws.services.redshiftdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PostgresWire;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.RedshiftZeroEtlEndpoints;
import io.floci.aws.services.redshift.model.Cluster;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** The Redshift target could not be resolved or reached, so the caller may retry once it is back. */
    public static final class TargetUnavailableException extends RuntimeException {
        public TargetUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A table an RDS zero-ETL integration replicates: its columns' Redshift types and its primary key. */
    public record ReplicatedTable(String schema, String table, List<Column> columns, List<String> primaryKey) {
        public ReplicatedTable {
            columns = List.copyOf(columns);
            primaryKey = List.copyOf(primaryKey);
        }

        public record Column(String name, String type) {}

        String qualifiedName() {
            return PostgresWire.quoteIdentifier(schema) + "." + PostgresWire.quoteIdentifier(table);
        }

        String ddl() {
            StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(qualifiedName()).append(" (");
            for (Column column : columns) {
                ddl.append(PostgresWire.quoteIdentifier(column.name())).append(' ').append(column.type()).append(", ");
            }
            ddl.append("PRIMARY KEY (");
            for (int i = 0; i < primaryKey.size(); i++) {
                ddl.append(i > 0 ? ", " : "").append(PostgresWire.quoteIdentifier(primaryKey.get(i)));
            }
            return ddl.append("))").toString();
        }
    }

    /**
     * Replaces the table in {@code database} of the target with the rows of {@code csv}, in one transaction:
     * its schema is created when missing, a table of that name is dropped and created again with the given
     * columns, and the rows are copied in. Loading a table again so leaves the same rows.
     *
     * @return the number of rows loaded
     */
    public long loadTable(String accountId, String targetArn, String database, ReplicatedTable table,
                          InputStream csv) {
        return inTransaction(accountId, targetArn, database, "loading " + table.qualifiedName(),
                "load table " + table.qualifiedName() + " into database " + database, connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS "
                                + PostgresWire.quoteIdentifier(table.schema()));
                        statement.executeUpdate("DROP TABLE IF EXISTS " + table.qualifiedName());
                        statement.executeUpdate(table.ddl());
                        return connection.unwrap(PGConnection.class).getCopyAPI()
                                .copyIn("COPY " + table.qualifiedName() + " FROM STDIN WITH (FORMAT csv)", csv);
                    } catch (IOException e) {
                        throw new UncheckedIOException("Could not read the rows of table " + table.qualifiedName(), e);
                    }
                });
    }

    /** One value of a replicated row: {@code expression} reads the value's source text, bound to its one {@code ?}. */
    public record ColumnValue(String column, String expression, String text) {}

    /**
     * A change to a replicated table.
     *
     * <ul>
     *   <li>{@link Kind#UPSERT}: the row of {@code values} replaces the one under the same primary key, or is added.
     *   <li>{@link Kind#UPDATE}: the row under the primary key of {@code keys} takes {@code values}, and the
     *       columns it leaves out keep theirs.
     *   <li>{@link Kind#DELETE}: the row under the primary key of {@code keys} is removed.
     *   <li>{@link Kind#TRUNCATE}: every row is removed.
     * </ul>
     */
    public record RowChange(Kind kind, ReplicatedTable table, List<ColumnValue> values, List<ColumnValue> keys) {
        public enum Kind { UPSERT, UPDATE, DELETE, TRUNCATE }

        public RowChange {
            values = List.copyOf(values);
            keys = List.copyOf(keys);
        }
    }

    /**
     * Applies changes to the tables of {@code database} on the target in order, in one transaction. An upsert and a
     * delete name the whole row or its key, so changes applied again after a failure leave the same rows.
     *
     * @throws TargetUnavailableException when the target cannot be reached, so the caller may try again
     */
    public void applyRowChanges(String accountId, String targetArn, String database, List<RowChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        inTransaction(accountId, targetArn, database, "replicating",
                "replicate changes into database " + database, connection -> {
                    for (RowChange change : changes) {
                        apply(connection, change);
                    }
                    return null;
                });
    }

    private static void apply(Connection connection, RowChange change) throws SQLException {
        String sql = rowChangeSql(change);
        if (sql == null) {
            return;
        }
        if (change.kind() == RowChange.Kind.UPDATE) {
            List<ColumnValue> newKeys = newKeysOf(change);
            if (!newKeys.isEmpty() && !sameKeyValues(change.keys(), newKeys)) {
                applyKeyChangingUpdate(connection, change, sql, newKeys);
                return;
            }
        }
        List<ColumnValue> bound = switch (change.kind()) {
            case UPSERT -> change.values();
            case UPDATE -> valuesThenKeys(change.values(), change.keys());
            case DELETE -> change.keys();
            case TRUNCATE -> List.of();
        };
        executeBound(connection, sql, bound);
    }

    /**
     * Applies a partial update that moves the row's primary key so it is safe to replay over rows already copied or
     * applied: when the old key is present, any existing row under the new key is removed first so moving the old row
     * (and its unchanged TOASTed columns) does not hit a duplicate-key error; when the old key is already gone, the
     * row under the new key is updated in place instead of being deleted.
     */
    private static void applyKeyChangingUpdate(Connection connection, RowChange change, String moveSql,
                                               List<ColumnValue> newKeys) throws SQLException {
        String dropConflictSql = "DELETE FROM " + change.table().qualifiedName() + keyCondition(newKeys)
                + " AND EXISTS (SELECT 1 FROM " + change.table().qualifiedName() + keyCondition(change.keys()) + ")";
        executeBound(connection, dropConflictSql, valuesThenKeys(newKeys, change.keys()));
        int moved = executeBound(connection, moveSql, valuesThenKeys(change.values(), change.keys()));
        if (moved == 0) {
            RowChange inPlace = new RowChange(RowChange.Kind.UPDATE, change.table(), change.values(), newKeys);
            executeBound(connection, rowChangeSql(inPlace), valuesThenKeys(change.values(), newKeys));
        }
    }

    private static List<ColumnValue> valuesThenKeys(List<ColumnValue> values, List<ColumnValue> keys) {
        List<ColumnValue> combined = new ArrayList<>(values.size() + keys.size());
        combined.addAll(values);
        combined.addAll(keys);
        return combined;
    }

    private static List<ColumnValue> newKeysOf(RowChange change) {
        List<String> primaryKey = change.table().primaryKey();
        if (primaryKey.isEmpty()) {
            return List.of();
        }
        List<ColumnValue> newKeys = new ArrayList<>(primaryKey.size());
        for (String key : primaryKey) {
            ColumnValue found = null;
            for (ColumnValue value : change.values()) {
                if (key.equals(value.column())) {
                    found = value;
                    break;
                }
            }
            if (found == null) {
                return List.of();
            }
            newKeys.add(found);
        }
        return newKeys;
    }

    private static boolean sameKeyValues(List<ColumnValue> oldKeys, List<ColumnValue> newKeys) {
        if (oldKeys.size() != newKeys.size()) {
            return false;
        }
        for (int i = 0; i < oldKeys.size(); i++) {
            if (!Objects.equals(oldKeys.get(i).column(), newKeys.get(i).column())
                    || !Objects.equals(oldKeys.get(i).text(), newKeys.get(i).text())) {
                return false;
            }
        }
        return true;
    }

    private static int executeBound(Connection connection, String sql, List<ColumnValue> bound) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < bound.size(); i++) {
                String text = bound.get(i).text();
                if (text == null) {
                    statement.setNull(i + 1, Types.VARCHAR);
                } else {
                    statement.setString(i + 1, text);
                }
            }
            return statement.executeUpdate();
        }
    }

    /**
     * The statement that applies a change, with a parameter for each value and then each key, or null when the change
     * leaves the row as it is.
     */
    static String rowChangeSql(RowChange change) {
        ReplicatedTable table = change.table();
        return switch (change.kind()) {
            case UPSERT -> {
                StringBuilder columns = new StringBuilder();
                StringBuilder values = new StringBuilder();
                StringBuilder updates = new StringBuilder();
                for (ColumnValue value : change.values()) {
                    String column = PostgresWire.quoteIdentifier(value.column());
                    columns.append(columns.isEmpty() ? "" : ", ").append(column);
                    values.append(values.isEmpty() ? "" : ", ").append(value.expression());
                    if (!table.primaryKey().contains(value.column())) {
                        updates.append(updates.isEmpty() ? "" : ", ").append(column).append(" = EXCLUDED.")
                                .append(column);
                    }
                }
                yield "INSERT INTO " + table.qualifiedName() + " (" + columns + ") VALUES (" + values
                        + ") ON CONFLICT (" + keyList(table) + ")"
                        + (updates.isEmpty() ? " DO NOTHING" : " DO UPDATE SET " + updates);
            }
            case UPDATE -> {
                if (change.values().isEmpty()) {
                    yield null;
                }
                StringBuilder sets = new StringBuilder();
                for (ColumnValue value : change.values()) {
                    sets.append(sets.isEmpty() ? "" : ", ").append(PostgresWire.quoteIdentifier(value.column()))
                            .append(" = ").append(value.expression());
                }
                yield "UPDATE " + table.qualifiedName() + " SET " + sets + keyCondition(change.keys());
            }
            case DELETE -> "DELETE FROM " + table.qualifiedName() + keyCondition(change.keys());
            case TRUNCATE -> "TRUNCATE " + table.qualifiedName();
        };
    }

    private static String keyList(ReplicatedTable table) {
        StringBuilder keys = new StringBuilder();
        for (String column : table.primaryKey()) {
            keys.append(keys.isEmpty() ? "" : ", ").append(PostgresWire.quoteIdentifier(column));
        }
        return keys.toString();
    }

    private static String keyCondition(List<ColumnValue> keys) {
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("A replicated update or delete needs the row's primary key");
        }
        StringBuilder condition = new StringBuilder();
        for (ColumnValue key : keys) {
            condition.append(condition.isEmpty() ? " WHERE " : " AND ")
                    .append(PostgresWire.quoteIdentifier(key.column())).append(" = ").append(key.expression());
        }
        return condition.toString();
    }

    /** A connection to {@code database} of the target, or {@link TargetUnavailableException} when there is none. */
    private Connection openDatabase(String accountId, String targetArn, String database) {
        RedshiftDataResourceResolver.DatabaseTarget resolved;
        try {
            resolved = target(accountId, targetArn, database);
        } catch (RuntimeException e) {
            throw new TargetUnavailableException("Could not resolve Redshift target " + targetArn
                    + ": " + e.getMessage(), e);
        }
        try {
            return connectionFactory.open(resolved);
        } catch (SQLException e) {
            throw new TargetUnavailableException("Could not connect to Redshift database " + database
                    + ": " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface TransactionWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} in one transaction on {@code database} of the target, committing when it returns and rolling
     * back when it throws. A lost connection surfaces as {@link TargetUnavailableException}, naming what was
     * {@code during}; any other SQL failure as {@code InternalFailure}, saying what could not be done.
     */
    private <T> T inTransaction(String accountId, String targetArn, String database, String during, String failure,
                                TransactionWork<T> work) {
        try (Connection connection = openDatabase(accountId, targetArn, database)) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        } catch (SQLException e) {
            if (isConnectionFailure(e)) {
                throw new TargetUnavailableException("Connection to Redshift database " + database
                        + " failed while " + during + ": " + e.getMessage(), e);
            }
            LOG.warnv(e, "Zero-ETL could not {0}", failure);
            throw new AwsException("InternalFailure", "Could not " + failure + ": " + e.getMessage(), 500);
        }
    }

    private static boolean isConnectionFailure(SQLException e) {
        String state = e.getSQLState();
        return state != null && (state.startsWith("08") || state.startsWith("57P"));
    }

    private RedshiftDataResourceResolver.DatabaseTarget target(String accountId, String targetArn) {
        return target(accountId, targetArn, null);
    }

    /**
     * A namespace is written through its workgroup, into the namespace's database as its admin user, the
     * same identity a cluster target is written with. A null {@code database} is the target's default one.
     */
    private RedshiftDataResourceResolver.DatabaseTarget target(String accountId, String targetArn, String database) {
        if (RedshiftZeroEtlEndpoints.isNamespaceArn(targetArn)) {
            RedshiftServerlessService.WorkgroupTarget workgroup = endpoints.targetWorkgroup(accountId, targetArn);
            return new RedshiftDataResourceResolver.DatabaseTarget(workgroup.arn(), workgroup.host(),
                    workgroup.port(), database != null ? database : workgroup.database(),
                    workgroup.masterUsername(), workgroup.masterPassword());
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
                database != null ? database : "dev",
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

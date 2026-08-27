package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RdsDataService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsDataService.class);

    /** Statement keywords whose result is a write, even when a {@code RETURNING} clause also reports rows. */
    private static final Set<String> DML_KEYWORDS = Set.of("insert", "update", "delete", "merge", "replace");

    private final RdsDataResourceResolver resourceResolver;
    private final SecretsManagerService secretsManagerService;
    private final ObjectMapper objectMapper;
    private final RdsDataConnectionFactory connectionFactory;
    private final Duration transactionTtl;
    private final ConcurrentMap<String, TransactionContext> transactions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService transactionCleanupExecutor;

    @Inject
    public RdsDataService(RdsDataResourceResolver resourceResolver,
                          SecretsManagerService secretsManagerService,
                          ObjectMapper objectMapper,
                          RdsDataConnectionFactory connectionFactory,
                          EmulatorConfig config) {
        this(resourceResolver, secretsManagerService, objectMapper, connectionFactory,
                Duration.ofSeconds(config.services().rdsData().transactionTtlSeconds()));
    }

    RdsDataService(RdsDataResourceResolver resourceResolver,
                   SecretsManagerService secretsManagerService,
                   ObjectMapper objectMapper,
                   RdsDataConnectionFactory connectionFactory,
                   Duration transactionTtl) {
        this.resourceResolver = resourceResolver;
        this.secretsManagerService = secretsManagerService;
        this.objectMapper = objectMapper;
        this.connectionFactory = connectionFactory;
        this.transactionTtl = transactionTtl;
        this.transactionCleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "rds-data-transaction-cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void startTransactionCleanup() {
        long intervalSeconds = Math.max(1, Math.min(60, transactionTtl.toSeconds()));
        transactionCleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredTransactionsSafely,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        transactionCleanupExecutor.shutdownNow();
        transactions.forEach((id, tx) -> {
            synchronized (tx) {
                if (transactions.remove(id, tx)) {
                    rollbackQuietly(tx.connection);
                    closeQuietly(tx.connection);
                }
            }
        });
    }

    public void clear() {
        transactions.forEach((id, tx) -> {
            synchronized (tx) {
                if (transactions.remove(id, tx)) {
                    rollbackQuietly(tx.connection);
                    closeQuietly(tx.connection);
                }
            }
        });
        transactions.clear();
    }

    public ObjectNode executeStatement(JsonNode request, String region) {
        rejectUnsupportedOptions(request);

        String sql = requiredText(request, "sql");
        Map<String, JsonNode> parameters = parseParameters(request);
        boolean includeMetadata = request.path("includeResultMetadata").asBoolean(false);

        try {
            return onTargetConnection(request, region, (connection, engine) ->
                    executeOnConnection(connection, engine, sql, parameters, includeMetadata));
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    public ObjectNode batchExecuteStatement(JsonNode request, String region) {
        String sql = requiredText(request, "sql");
        List<Map<String, JsonNode>> parameterSets = parseParameterSets(request);

        try {
            return onTargetConnection(request, region, (connection, engine) ->
                    batchOnConnection(connection, engine, sql, parameterSets));
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    public ObjectNode beginTransaction(JsonNode request, String region) {
        cleanupExpiredTransactions();
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        RdsDataResourceResolver.DatabaseTarget target =
                resourceResolver.resolve(resourceArn, region);
        Credentials credentials = credentials(request, target, region);
        String database = databaseName(request, target);

        Connection connection = null;
        try {
            connection = connectionFactory.open(target, credentials.username(), credentials.password(), database);
            connection.setAutoCommit(false);
            String transactionId = UUID.randomUUID().toString();
            transactions.put(transactionId, new TransactionContext(
                    transactionId, connection, target.engine(), target.arn(), database, region, transactionTtl));

            ObjectNode response = objectMapper.createObjectNode();
            response.put("transactionId", transactionId);
            return response;
        } catch (SQLException e) {
            if (connection != null) {
                closeQuietly(connection);
            }
            throw databaseError(e);
        }
    }

    public ObjectNode commitTransaction(JsonNode request, String region) {
        String transactionId = requiredText(request, "transactionId");
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        TransactionContext tx = transaction(transactionId);
        synchronized (tx) {
            requireActiveTransaction(transactionId, tx);
            validateTransactionResource(tx, resourceArn, region);
            if (!transactions.remove(transactionId, tx)) {
                throw transactionNotFound(transactionId);
            }
            try {
                tx.connection.commit();
            } catch (SQLException e) {
                throw databaseError(e);
            } finally {
                closeQuietly(tx.connection);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("transactionStatus", "Transaction Committed");
        return response;
    }

    public ObjectNode rollbackTransaction(JsonNode request, String region) {
        String transactionId = requiredText(request, "transactionId");
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        TransactionContext tx = transaction(transactionId);
        synchronized (tx) {
            requireActiveTransaction(transactionId, tx);
            validateTransactionResource(tx, resourceArn, region);
            if (!transactions.remove(transactionId, tx)) {
                throw transactionNotFound(transactionId);
            }
            try {
                tx.connection.rollback();
            } catch (SQLException e) {
                throw databaseError(e);
            } finally {
                closeQuietly(tx.connection);
            }
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("transactionStatus", "Rollback Complete");
        return response;
    }

    private ObjectNode onTargetConnection(JsonNode request, String region, ConnectionWork work)
            throws SQLException {
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        String transactionId = textOrNull(request, "transactionId");

        if (transactionId != null && !transactionId.isBlank()) {
            TransactionContext tx = transaction(transactionId);
            synchronized (tx) {
                requireActiveTransaction(transactionId, tx);
                validateTransactionIdentity(tx, request, region);
                tx.refresh(transactionTtl);
                return work.execute(tx.connection, tx.engine);
            }
        }

        RdsDataResourceResolver.DatabaseTarget target =
                resourceResolver.resolve(resourceArn, region);
        Credentials credentials = credentials(request, target, region);
        String database = databaseName(request, target);
        try (Connection connection = connectionFactory.open(target, credentials.username(), credentials.password(), database)) {
            return work.execute(connection, target.engine());
        }
    }

    private ObjectNode executeOnConnection(Connection connection, DatabaseEngine engine, String sql,
                                           Map<String, JsonNode> parameters, boolean includeMetadata)
            throws SQLException {
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(sql, usesBackslashEscapes(engine));
        try (PreparedStatement statement = prepare(connection, engine, parsed.sql())) {
            RdsDataSqlParameters.bind(statement, parsed.parameterOrder(), parameters);
            return buildResponse(statement, statement.execute(), includeMetadata, engine);
        }
    }

    /**
     * Runs {@code sql} once per parameter set and reports one
     * {@code updateResults} entry per set.
     *
     * <p>No parameter sets runs nothing. AWS executes the statement "as many
     * times as the number of parameter sets provided" and points a caller who
     * wants a single parameterless execution at one empty set ({@code [[]]}) or
     * at {@code ExecuteStatement}, so an absent or empty {@code parameterSets}
     * must not reach the database.
     *
     * <p>Engines that report generated keys execute a set at a time so each
     * entry carries the keys that set produced: {@code getGeneratedKeys()}
     * after {@code executeBatch()} flattens every row the whole batch generated
     * with nothing tying a row back to the set that made it, so a set inserting
     * more or fewer than one row would shift the keys onto the wrong entries.
     * PostgreSQL reports no generated keys, so it keeps the batched round trip.
     */
    private ObjectNode batchOnConnection(Connection connection, DatabaseEngine engine, String sql,
                                         List<Map<String, JsonNode>> parameterSets)
            throws SQLException {
        ArrayNode updateResults = objectMapper.createArrayNode();
        if (parameterSets.isEmpty()) {
            return batchResponse(updateResults);
        }
        RdsDataSqlParameters.ParsedSql parsed = RdsDataSqlParameters.parse(sql, usesBackslashEscapes(engine));
        try (PreparedStatement statement = prepare(connection, engine, parsed.sql())) {
            rejectStatementReturningRows(statement, parsed.sql());
            if (returnsGeneratedKeys(engine)) {
                for (Map<String, JsonNode> parameters : parameterSets) {
                    RdsDataSqlParameters.bind(statement, parsed.parameterOrder(), parameters);
                    statement.execute();
                    updateResults.add(updateResult(generatedFields(statement, engine)));
                }
            } else {
                for (Map<String, JsonNode> parameters : parameterSets) {
                    RdsDataSqlParameters.bind(statement, parsed.parameterOrder(), parameters);
                    statement.addBatch();
                }
                statement.executeBatch();
                for (int set = 0; set < parameterSets.size(); set++) {
                    updateResults.add(updateResult(objectMapper.createArrayNode()));
                }
            }
        }
        return batchResponse(updateResults);
    }

    private ObjectNode batchResponse(ArrayNode updateResults) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("updateResults", updateResults);
        return response;
    }

    /**
     * Rejects a batched statement whose result is rows rather than an update
     * count. AWS takes "a DML statement" here and answers with one
     * {@code UpdateResult} per set, which has nowhere to carry a result set.
     * Neither engine refuses one on its own: the MySQL branch of
     * {@link #batchOnConnection} executes each set through {@code execute()} to
     * keep generated keys with their set, which bypasses the
     * {@code executeBatch()} that Connector/J would have refused, and the
     * PostgreSQL driver batches a {@code SELECT} happily.
     *
     * <p>The check reads the driver's description of the prepared statement,
     * which both drivers answer without running it. A DML statement that also
     * reports rows through a {@code RETURNING} clause is left alone — AWS points
     * Aurora PostgreSQL callers at {@code RETURNING} because
     * {@code generatedFields} is always empty there — so the leading keyword
     * decides that before the description is asked for.
     */
    private static void rejectStatementReturningRows(PreparedStatement statement, String sql) {
        if (isDataModifying(sql) || !returnsRows(statement)) {
            return;
        }
        throw new AwsException("BadRequestException",
                "BatchExecuteStatement does not support a SQL statement that returns a result set. "
                        + "Use ExecuteStatement instead.", 400);
    }

    private static boolean returnsRows(PreparedStatement statement) {
        try {
            return statement.getMetaData() != null;
        } catch (SQLException e) {
            // A driver that cannot describe the statement leaves the verdict to the execution itself.
            LOG.debugv("Could not describe RDS Data API batch statement: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Whether {@code sql} opens a data-modifying statement, ignoring leading
     * whitespace and comments. Only used to exempt DML from
     * {@link #rejectStatementReturningRows}, so an unrecognized leading keyword
     * counts as not modifying and the driver's description decides.
     */
    private static boolean isDataModifying(String sql) {
        int len = sql.length();
        int i = 0;
        while (i < len) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? len : end + 1;
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? len : end + 2;
            } else {
                break;
            }
        }
        int start = i;
        while (i < len && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        return DML_KEYWORDS.contains(sql.substring(start, i).toLowerCase(Locale.ROOT));
    }

    private ObjectNode updateResult(ArrayNode generatedFields) {
        ObjectNode updateResult = objectMapper.createObjectNode();
        updateResult.set("generatedFields", generatedFields);
        return updateResult;
    }

    private static PreparedStatement prepare(Connection connection, DatabaseEngine engine, String sql)
            throws SQLException {
        if (returnsGeneratedKeys(engine)) {
            return connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        }
        return connection.prepareStatement(sql);
    }

    /**
     * Whether generated keys are requested from {@code engine}. AWS reports no
     * {@code generatedFields} for Aurora PostgreSQL — callers read generated
     * values with a {@code RETURNING} clause instead — so they are only asked
     * for on the engines AWS answers them for. That also avoids the PostgreSQL
     * driver rewriting the statement with {@code RETURNING *} to satisfy
     * {@code RETURN_GENERATED_KEYS}.
     */
    private static boolean returnsGeneratedKeys(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL, MARIADB -> true;
            case POSTGRES -> false;
        };
    }

    /**
     * Whether a backslash escapes quotes inside string literals for {@code engine}.
     * MySQL and MariaDB honor {@code \'} by default (NO_BACKSLASH_ESCAPES disabled);
     * PostgreSQL treats backslash literally with {@code standard_conforming_strings} on.
     */
    private static boolean usesBackslashEscapes(DatabaseEngine engine) {
        return switch (engine) {
            case MYSQL, MARIADB -> true;
            case POSTGRES -> false;
        };
    }

    private ObjectNode buildResponse(Statement statement, boolean hasResultSet, boolean includeMetadata,
                                     DatabaseEngine engine) throws SQLException {
        ObjectNode response = objectMapper.createObjectNode();
        if (hasResultSet) {
            try (ResultSet rs = statement.getResultSet()) {
                ResultSetMetaData meta = rs.getMetaData();
                if (includeMetadata) {
                    response.set("columnMetadata", RdsDataColumnMetadata.toColumnMetadata(objectMapper, meta));
                }
                response.set("records", records(rs, meta));
            }
            response.put("numberOfRecordsUpdated", 0L);
        } else {
            int updateCount = statement.getUpdateCount();
            response.set("records", objectMapper.createArrayNode());
            response.put("numberOfRecordsUpdated", Math.max(updateCount, 0));
            response.set("generatedFields", generatedFields(statement, engine));
        }
        return response;
    }

    /**
     * The keys generated by the last execution of {@code statement}, one
     * {@code Field} per generated column of every row the driver reported.
     */
    private ArrayNode generatedFields(Statement statement, DatabaseEngine engine) {
        if (!returnsGeneratedKeys(engine)) {
            return objectMapper.createArrayNode();
        }
        try (ResultSet keys = statement.getGeneratedKeys()) {
            ResultSetMetaData meta = keys.getMetaData();
            ArrayNode fields = objectMapper.createArrayNode();
            while (keys.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    fields.add(RdsDataFieldMapper.toField(objectMapper, keys.getObject(i), meta.getColumnType(i)));
                }
            }
            return fields;
        } catch (SQLException e) {
            LOG.debugv("Could not read generated keys for RDS Data API statement: {0}", e.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private ArrayNode records(ResultSet rs, ResultSetMetaData meta) throws SQLException {
        ArrayNode records = objectMapper.createArrayNode();
        int columnCount = meta.getColumnCount();
        while (rs.next()) {
            ArrayNode row = objectMapper.createArrayNode();
            for (int i = 1; i <= columnCount; i++) {
                row.add(RdsDataFieldMapper.toField(objectMapper, rs.getObject(i), meta.getColumnType(i)));
            }
            records.add(row);
        }
        return records;
    }

    private Credentials credentials(JsonNode request, RdsDataResourceResolver.DatabaseTarget target, String region) {
        String secretArn = textOrNull(request, "secretArn");
        if (secretArn != null && !secretArn.isBlank()) {
            try {
                SecretVersion secret = secretsManagerService.getSecretValue(secretArn, null, null, region);
                Credentials fromSecret = parseSecretCredentials(secret.getSecretString());
                if (fromSecret != null) {
                    return fromSecret;
                }
            } catch (AwsException e) {
                LOG.debugv("Falling back to RDS master credentials for Data API secret {0}: {1}",
                        secretArn, e.getMessage());
            }
        }
        String username = target.username() != null && !target.username().isBlank() ? target.username() : "root";
        return new Credentials(username, target.password());
    }

    private Credentials parseSecretCredentials(String secretString) {
        if (secretString == null || secretString.isBlank()) {
            return null;
        }
        try {
            JsonNode secret = objectMapper.readTree(secretString);
            String username = textOrNull(secret, "username");
            if (username == null) {
                username = textOrNull(secret, "user");
            }
            String password = textOrNull(secret, "password");
            if (username != null && password != null) {
                return new Credentials(username, password);
            }
        } catch (Exception e) {
            LOG.debugv("Could not parse RDS Data API secret credentials: {0}", e.getMessage());
        }
        return null;
    }

    private String databaseName(JsonNode request, RdsDataResourceResolver.DatabaseTarget target) {
        String database = textOrNull(request, "database");
        if (database != null && !database.isBlank()) {
            return database;
        }
        if (target.databaseName() != null && !target.databaseName().isBlank()) {
            return target.databaseName();
        }
        throw new AwsException("BadRequestException", "database is required.", 400);
    }

    private TransactionContext transaction(String transactionId) {
        cleanupExpiredTransactions();
        TransactionContext tx = transactions.get(transactionId);
        if (tx == null) {
            throw transactionNotFound(transactionId);
        }
        return tx;
    }

    private static AwsException transactionNotFound(String transactionId) {
        return new AwsException("TransactionNotFoundException",
                "Transaction " + transactionId + " was not found.", 404);
    }

    private void requireActiveTransaction(String transactionId, TransactionContext tx) {
        if (transactions.get(transactionId) != tx) {
            throw transactionNotFound(transactionId);
        }
    }

    private void cleanupExpiredTransactions() {
        Instant now = Instant.now();
        transactions.forEach((id, tx) -> {
            if (tx.expiresAt.isBefore(now)) {
                synchronized (tx) {
                    if (tx.expiresAt.isBefore(now) && transactions.remove(id, tx)) {
                        rollbackQuietly(tx.connection);
                        closeQuietly(tx.connection);
                    }
                }
            }
        });
    }

    private void cleanupExpiredTransactionsSafely() {
        try {
            cleanupExpiredTransactions();
        } catch (Exception e) {
            LOG.warn("Failed to clean up expired RDS Data API transactions", e);
        }
    }

    private void validateTransactionIdentity(
            TransactionContext tx, JsonNode request, String region) {
        validateTransactionResource(tx, requiredText(request, "resourceArn"), region);
        String database = textOrNull(request, "database");
        if (database != null && !database.isBlank() && !database.equals(tx.database)) {
            throw transactionNotFound(tx.id);
        }
    }

    private void validateTransactionResource(
            TransactionContext tx, String resourceArn, String region) {
        if (region == null || region.isBlank() || !region.equals(tx.region)) {
            throw transactionNotFound(tx.id);
        }
        if (resourceArn.equals(tx.resourceArn)) {
            return;
        }
        RdsDataResourceResolver.DatabaseTarget target;
        try {
            target = resourceResolver.resolve(resourceArn, region);
        } catch (AwsException e) {
            throw transactionNotFound(tx.id);
        }
        if (target == null || !target.arn().equals(tx.resourceArn)) {
            throw transactionNotFound(tx.id);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    private static void rejectUnsupportedOptions(JsonNode request) {
        rejectFormattedRecords(request);
        rejectResultSetOptions(request);
    }

    private static Map<String, JsonNode> parseParameters(JsonNode request) {
        return parseParameterList(request.get("parameters"), "parameters");
    }

    private static List<Map<String, JsonNode>> parseParameterSets(JsonNode request) {
        JsonNode parameterSets = request.get("parameterSets");
        if (parameterSets == null || parameterSets.isNull()) {
            return List.of();
        }
        if (!parameterSets.isArray()) {
            throw new AwsException("BadRequestException",
                    "parameterSets must be an array of SqlParameter arrays.", 400);
        }
        List<Map<String, JsonNode>> sets = new ArrayList<>();
        for (JsonNode parameters : parameterSets) {
            sets.add(parseParameterList(parameters, "each entry in parameterSets"));
        }
        return sets;
    }

    private static Map<String, JsonNode> parseParameterList(JsonNode parameters, String field) {
        if (parameters == null || parameters.isNull()) {
            return Map.of();
        }
        if (!parameters.isArray()) {
            throw new AwsException("BadRequestException",
                    field + " must be an array of SqlParameter values.", 400);
        }
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode parameter : parameters) {
            if (parameter == null || !parameter.isObject()) {
                throw new AwsException("BadRequestException",
                        "Each parameter must be a SqlParameter object.", 400);
            }
            String name = textOrNull(parameter, "name");
            if (name == null || name.isBlank()) {
                throw new AwsException("BadRequestException",
                        "Each SqlParameter requires a name.", 400);
            }
            if (byName.putIfAbsent(name, parameter) != null) {
                throw new AwsException("BadRequestException",
                        "Duplicate parameter name :" + name + " in the parameter set.", 400);
            }
        }
        return byName;
    }

    private static void rejectFormattedRecords(JsonNode request) {
        String formatRecordsAs = textOrNull(request, "formatRecordsAs");
        if (formatRecordsAs != null && !formatRecordsAs.isBlank()
                && !"NONE".equalsIgnoreCase(formatRecordsAs)) {
            throw new AwsException("BadRequestException",
                    "formattedRecords is not supported by this local RDS Data API implementation.", 400);
        }
    }

    private static void rejectResultSetOptions(JsonNode request) {
        JsonNode resultSetOptions = request.get("resultSetOptions");
        if (resultSetOptions != null && !resultSetOptions.isNull()
                && (!resultSetOptions.isObject() || !resultSetOptions.isEmpty())) {
            throw new AwsException("BadRequestException",
                    "resultSetOptions is not supported by this local RDS Data API implementation.", 400);
        }
    }

    private static String requiredText(JsonNode request, String name) {
        String value = textOrNull(request, name);
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException", name + " is required.", 400);
        }
        return value;
    }

    private static String textOrNull(JsonNode request, String name) {
        JsonNode node = request.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.asText();
    }

    private static AwsException databaseError(SQLException e) {
        return new AwsException("DatabaseErrorException", e.getMessage(), 400);
    }

    private static final class TransactionContext {
        private final String id;
        private final Connection connection;
        private final DatabaseEngine engine;
        private final String resourceArn;
        private final String database;
        private final String region;
        private volatile Instant expiresAt;

        private TransactionContext(
                String id, Connection connection, DatabaseEngine engine, String resourceArn,
                String database, String region, Duration ttl) {
            this.id = id;
            this.connection = connection;
            this.engine = engine;
            this.resourceArn = resourceArn;
            this.database = database;
            this.region = region;
            refresh(ttl);
        }

        private void refresh(Duration ttl) {
            this.expiresAt = Instant.now().plus(ttl);
        }
    }

    private record Credentials(String username, String password) {
    }

    @FunctionalInterface
    private interface ConnectionWork {
        ObjectNode execute(Connection connection, DatabaseEngine engine) throws SQLException;
    }
}

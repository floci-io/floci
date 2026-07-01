package io.github.hectorvent.floci.services.rdsdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.secretsmanager.model.SecretVersion;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class RdsDataService implements Resettable {

    private static final Logger LOG = Logger.getLogger(RdsDataService.class);

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
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        String transactionId = textOrNull(request, "transactionId");
        boolean includeMetadata = request.path("includeResultMetadata").asBoolean(false);

        try {
            if (transactionId != null && !transactionId.isBlank()) {
                TransactionContext tx = transaction(transactionId);
                synchronized (tx) {
                    requireActiveTransaction(transactionId, tx);
                    validateTransactionIdentity(tx, request);
                    tx.refresh(transactionTtl);
                    return executeOnConnection(tx.connection, sql, includeMetadata);
                }
            }

            RdsDataResourceResolver.DatabaseTarget target = resourceResolver.resolve(resourceArn);
            Credentials credentials = credentials(request, target, region);
            String database = databaseName(request, target);
            try (Connection connection = connectionFactory.open(target, credentials.username(), credentials.password(), database)) {
                return executeOnConnection(connection, sql, includeMetadata);
            }
        } catch (SQLException e) {
            throw databaseError(e);
        }
    }

    public ObjectNode beginTransaction(JsonNode request, String region) {
        cleanupExpiredTransactions();
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        RdsDataResourceResolver.DatabaseTarget target = resourceResolver.resolve(resourceArn);
        Credentials credentials = credentials(request, target, region);
        String database = databaseName(request, target);

        Connection connection = null;
        try {
            connection = connectionFactory.open(target, credentials.username(), credentials.password(), database);
            connection.setAutoCommit(false);
            String transactionId = UUID.randomUUID().toString();
            transactions.put(transactionId, new TransactionContext(transactionId, connection, target.arn(), database, transactionTtl));

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

    public ObjectNode commitTransaction(JsonNode request) {
        String transactionId = requiredText(request, "transactionId");
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        TransactionContext tx = transaction(transactionId);
        synchronized (tx) {
            requireActiveTransaction(transactionId, tx);
            validateTransactionResource(tx, resourceArn);
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

    public ObjectNode rollbackTransaction(JsonNode request) {
        String transactionId = requiredText(request, "transactionId");
        String resourceArn = requiredText(request, "resourceArn");
        requiredText(request, "secretArn");
        TransactionContext tx = transaction(transactionId);
        synchronized (tx) {
            requireActiveTransaction(transactionId, tx);
            validateTransactionResource(tx, resourceArn);
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

    private ObjectNode executeOnConnection(Connection connection, String sql, boolean includeMetadata) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            ObjectNode response = objectMapper.createObjectNode();
            if (hasResultSet) {
                try (ResultSet rs = statement.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    if (includeMetadata) {
                        response.set("columnMetadata", columnMetadata(meta));
                    }
                    response.set("records", records(rs, meta));
                }
                response.put("numberOfRecordsUpdated", 0L);
            } else {
                int updateCount = statement.getUpdateCount();
                response.set("records", objectMapper.createArrayNode());
                response.put("numberOfRecordsUpdated", Math.max(updateCount, 0));
            }
            return response;
        }
    }

    private ArrayNode columnMetadata(ResultSetMetaData meta) throws SQLException {
        ArrayNode columns = objectMapper.createArrayNode();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            ObjectNode column = objectMapper.createObjectNode();
            String name = meta.getColumnLabel(i);
            if (name == null || name.isBlank()) {
                name = meta.getColumnName(i);
            }
            column.put("name", name);
            columns.add(column);
        }
        return columns;
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

    private void validateTransactionIdentity(TransactionContext tx, JsonNode request) {
        validateTransactionResource(tx, requiredText(request, "resourceArn"));
        String database = textOrNull(request, "database");
        if (database != null && !database.isBlank() && !database.equals(tx.database)) {
            throw transactionNotFound(tx.id);
        }
    }

    private void validateTransactionResource(TransactionContext tx, String resourceArn) {
        if (resourceArn.equals(tx.resourceArn)) {
            return;
        }
        RdsDataResourceResolver.DatabaseTarget target;
        try {
            target = resourceResolver.resolve(resourceArn);
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
        rejectSqlParameters(request);
        rejectFormattedRecords(request);
        rejectResultSetOptions(request);
    }

    private static void rejectSqlParameters(JsonNode request) {
        JsonNode parameters = request.get("parameters");
        if (parameters != null && (!parameters.isArray() || !parameters.isEmpty())) {
            throw new AwsException("BadRequestException",
                    "SqlParameter binding is not supported by this local RDS Data API implementation.", 400);
        }
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
        private final String resourceArn;
        private final String database;
        private volatile Instant expiresAt;

        private TransactionContext(String id, Connection connection, String resourceArn, String database, Duration ttl) {
            this.id = id;
            this.connection = connection;
            this.resourceArn = resourceArn;
            this.database = database;
            refresh(ttl);
        }

        private void refresh(Duration ttl) {
            this.expiresAt = Instant.now().plus(ttl);
        }
    }

    private record Credentials(String username, String password) {
    }
}

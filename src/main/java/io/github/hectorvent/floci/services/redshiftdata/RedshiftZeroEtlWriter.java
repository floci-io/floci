package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
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

    private final RedshiftService redshiftService;
    private final RedshiftDataConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    @Inject
    RedshiftZeroEtlWriter(RedshiftService redshiftService,
                          RedshiftDataConnectionFactory connectionFactory,
                          ObjectMapper objectMapper) {
        this.redshiftService = redshiftService;
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    public void createLandingTable(String accountId, String clusterIdentifier, String tableName) {
        String safeTableName = validateTableName(tableName);
        try (Connection connection = connectionFactory.open(target(accountId, clusterIdentifier));
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(LANDING_TABLE_DDL.formatted(safeTableName) + ")");
        } catch (SQLException e) {
            throw new AwsException("InternalFailure", "Could not create Redshift zero-ETL landing table.", 500);
        }
    }

    public String writeBatch(String accountId, String clusterIdentifier, String tableName,
                             List<DynamoDbStreamRecord> records) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        String safeTableName = validateTableName(tableName);
        String newestSequence = null;
        try (Connection connection = connectionFactory.open(target(accountId, clusterIdentifier));
             PreparedStatement statement = connection.prepareStatement(INSERT_RECORD.formatted(safeTableName))) {
            for (DynamoDbStreamRecord record : records) {
                bind(statement, record);
                statement.executeUpdate();
                newestSequence = record.getSequenceNumber();
            }
        } catch (SQLException e) {
            throw new AwsException("InternalFailure", "Could not write Redshift zero-ETL records.", 500);
        }
        return newestSequence;
    }

    private void bind(PreparedStatement statement, DynamoDbStreamRecord record) throws SQLException {
        statement.setString(1, record.getEventId());
        statement.setString(2, record.getEventName());
        setNullableString(statement, 3, record.getEventSource());
        setNullableString(statement, 4, record.getAwsRegion());
        statement.setString(5, record.getSequenceNumber());
        if (record.getApproximateCreationDateTime() > 0) {
            statement.setObject(6, Instant.ofEpochSecond(record.getApproximateCreationDateTime()));
        } else {
            statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
        }
        setJson(statement, 7, record.getKeys());
        setJson(statement, 8, record.getOldImage());
        setJson(statement, 9, record.getNewImage());
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

    private RedshiftDataResourceResolver.DatabaseTarget target(String accountId, String clusterIdentifier) {
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

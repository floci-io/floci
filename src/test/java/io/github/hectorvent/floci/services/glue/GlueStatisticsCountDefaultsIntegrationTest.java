package io.github.hectorvent.floci.services.glue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.BooleanColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.ColumnStatistics;
import software.amazon.awssdk.services.glue.model.ColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.DatabaseInput;
import software.amazon.awssdk.services.glue.model.DateColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.DecimalColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.DoubleColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.LongColumnStatisticsData;
import software.amazon.awssdk.services.glue.model.PartitionInput;
import software.amazon.awssdk.services.glue.model.StorageDescriptor;
import software.amazon.awssdk.services.glue.model.TableInput;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class GlueStatisticsCountDefaultsIntegrationTest {

    @ParameterizedTest
    @CsvSource({"LONG,false,false", "LONG,true,false", "DOUBLE,false,false", "DOUBLE,true,false",
            "DECIMAL,false,false", "DECIMAL,true,false", "DATE,false,false", "DATE,true,false",
            "BOOLEAN,false,false", "BOOLEAN,true,false", "LONG,false,true", "LONG,true,true",
            "DOUBLE,false,true", "DOUBLE,true,true", "DECIMAL,false,true", "DECIMAL,true,true",
            "DATE,false,true", "DATE,true,true", "BOOLEAN,false,true", "BOOLEAN,true,true"})
    void sdkReadsMissingCountsAsZeroAndPreservesSuppliedCounts(String type, boolean partition, boolean supplied) {
        String database = "counts_" + UUID.randomUUID().toString().replace("-", "");
        String columnType = switch (type) {
            case "LONG" -> "bigint";
            case "DOUBLE" -> "double";
            case "DECIMAL" -> "decimal(10,2)";
            case "DATE" -> "date";
            default -> "boolean";
        };
        try (GlueClient client = GlueClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("741579900001", "test")))
                .build()) {
            client.createDatabase(request -> request.databaseInput(DatabaseInput.builder().name(database).build()));
            try {
                StorageDescriptor descriptor = StorageDescriptor.builder()
                        .columns(Column.builder().name("value").type(columnType).build()).build();
                client.createTable(request -> request.databaseName(database).tableInput(TableInput.builder().name("stats")
                        .storageDescriptor(descriptor).partitionKeys(Column.builder().name("day").type("string").build())
                        .build()));
                if (partition) {
                    client.createPartition(request -> request.databaseName(database).tableName("stats")
                            .partitionInput(PartitionInput.builder().values("one").storageDescriptor(descriptor).build()));
                }
                Long nulls = supplied ? 7L : null;
                ColumnStatisticsData.Builder data = ColumnStatisticsData.builder().type(type);
                switch (type) {
                    case "LONG" -> data.longColumnStatisticsData(LongColumnStatisticsData.builder().numberOfNulls(nulls).build());
                    case "DOUBLE" -> data.doubleColumnStatisticsData(DoubleColumnStatisticsData.builder().numberOfNulls(nulls).build());
                    case "DECIMAL" -> data.decimalColumnStatisticsData(DecimalColumnStatisticsData.builder().numberOfNulls(nulls).build());
                    case "DATE" -> data.dateColumnStatisticsData(DateColumnStatisticsData.builder().numberOfNulls(nulls).build());
                    default -> data.booleanColumnStatisticsData(BooleanColumnStatisticsData.builder().numberOfNulls(nulls).build());
                }
                ColumnStatistics statistics = ColumnStatistics.builder().columnName("value").columnType(columnType)
                        .analyzedTime(Instant.now()).statisticsData(data.build()).build();
                if (partition) {
                    client.updateColumnStatisticsForPartition(request -> request.databaseName(database).tableName("stats")
                            .partitionValues("one").columnStatisticsList(statistics));
                } else {
                    client.updateColumnStatisticsForTable(request -> request.databaseName(database).tableName("stats")
                            .columnStatisticsList(statistics));
                }
                List<ColumnStatistics> values = partition
                        ? client.getColumnStatisticsForPartition(request -> request.databaseName(database).tableName("stats")
                                .partitionValues("one").columnNames("value")).columnStatisticsList()
                        : client.getColumnStatisticsForTable(request -> request.databaseName(database).tableName("stats")
                                .columnNames("value")).columnStatisticsList();
                assertEquals(1, values.size());
                ColumnStatisticsData result = values.get(0).statisticsData();
                List<Long> counts = switch (type) {
                    case "LONG" -> Arrays.asList(result.longColumnStatisticsData().numberOfNulls(),
                            result.longColumnStatisticsData().numberOfDistinctValues());
                    case "DOUBLE" -> Arrays.asList(result.doubleColumnStatisticsData().numberOfNulls(),
                            result.doubleColumnStatisticsData().numberOfDistinctValues());
                    case "DECIMAL" -> Arrays.asList(result.decimalColumnStatisticsData().numberOfNulls(),
                            result.decimalColumnStatisticsData().numberOfDistinctValues());
                    case "DATE" -> Arrays.asList(result.dateColumnStatisticsData().numberOfNulls(),
                            result.dateColumnStatisticsData().numberOfDistinctValues());
                    default -> Arrays.asList(result.booleanColumnStatisticsData().numberOfNulls(),
                            result.booleanColumnStatisticsData().numberOfTrues(), result.booleanColumnStatisticsData().numberOfFalses());
                };
                long expectedNulls = supplied ? 7L : 0L;
                assertEquals(type.equals("BOOLEAN") ? List.of(expectedNulls, 0L, 0L) : List.of(expectedNulls, 0L), counts);
                if (!type.equals("BOOLEAN")) {
                    List<?> bounds = switch (type) {
                        case "LONG" -> Arrays.asList(result.longColumnStatisticsData().minimumValue(), result.longColumnStatisticsData().maximumValue());
                        case "DOUBLE" -> Arrays.asList(result.doubleColumnStatisticsData().minimumValue(), result.doubleColumnStatisticsData().maximumValue());
                        case "DECIMAL" -> Arrays.asList(result.decimalColumnStatisticsData().minimumValue(), result.decimalColumnStatisticsData().maximumValue());
                        default -> Arrays.asList(result.dateColumnStatisticsData().minimumValue(), result.dateColumnStatisticsData().maximumValue());
                    };
                    assertEquals(Arrays.asList(null, null), bounds);
                }
            } finally {
                client.deleteDatabase(request -> request.name(database));
            }
        }
    }
}

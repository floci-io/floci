package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.cloudwatch.logs.model.LogDestination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudWatchLogsCrossAccountServiceTest {
    private static final String REGION = "us-east-1";
    private static final String DOCUMENT = "{}";

    private final CloudWatchLogsCrossAccountService service = new CloudWatchLogsCrossAccountService(
            new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"), new ObjectMapper());

    @Test
    void destinationTagsSurvivePersistentStorageReload(@TempDir Path tempDir) {
        Path file = tempDir.resolve("destinations.json");
        TypeReference<Map<String, LogDestination>> type = new TypeReference<>() {};
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        PersistentStorage<String, LogDestination> firstStore = new PersistentStorage<>(file, type);
        CloudWatchLogsCrossAccountService first = new CloudWatchLogsCrossAccountService(
                firstStore, new InMemoryStorage<>(), regionResolver, new ObjectMapper());
        LogDestination destination = first.putDestination("persisted-tags",
                "arn:aws:kinesis:us-east-1:000000000000:stream/logs",
                "arn:aws:iam::000000000000:role/logs", Map.of("team", "red"), REGION);
        first.tagDestination(destination.getArn(), Map.of("env", "dev"), REGION);

        PersistentStorage<String, LogDestination> secondStore = new PersistentStorage<>(file, type);
        secondStore.load();
        CloudWatchLogsCrossAccountService second = new CloudWatchLogsCrossAccountService(
                secondStore, new InMemoryStorage<>(), regionResolver, new ObjectMapper());
        assertEquals(Map.of("team", "red", "env", "dev"), second.listDestinationTags(destination.getArn(), REGION));
        second.untagDestination(destination.getArn(), List.of("team"), REGION);

        PersistentStorage<String, LogDestination> thirdStore = new PersistentStorage<>(file, type);
        thirdStore.load();
        CloudWatchLogsCrossAccountService third = new CloudWatchLogsCrossAccountService(
                thirdStore, new InMemoryStorage<>(), regionResolver, new ObjectMapper());
        assertEquals(Map.of("env", "dev"), third.listDestinationTags(destination.getArn(), REGION));
    }

    @Test
    void transformerGlobalAndScopedPoliciesCannotCoexist() {
        service.putAccountPolicy("global", "[{\"parseJSON\":{}}]", "TRANSFORMER_POLICY", null, "ALL", REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("scoped", "[{\"parseJSON\":{}}]", "TRANSFORMER_POLICY",
                        "LogGroupNamePrefix = 'aws/'", "ALL", REGION));

        assertEquals("LimitExceededException", error.getErrorCode());
    }

    @Test
    void updatingScopedTransformerToGlobalRevalidatesQuota() {
        service.putAccountPolicy("first", "[{\"parseJSON\":{}}]", "TRANSFORMER_POLICY",
                "LogGroupNamePrefix = 'aws/'", "ALL", REGION);
        service.putAccountPolicy("second", "[{\"parseJSON\":{}}]", "TRANSFORMER_POLICY",
                "LogGroupNamePrefix = 'app/'", "ALL", REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("first", "[{\"parseJSON\":{}}]", "TRANSFORMER_POLICY",
                        null, "ALL", REGION));

        assertEquals("LimitExceededException", error.getErrorCode());
    }

    @Test
    void metricExtractionRejectsMalformedSelectionCriteria() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("metrics", DOCUMENT, "METRIC_EXTRACTION_POLICY",
                        "not a criterion", "ALL", REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void metricExtractionAcceptsDocumentedSelectionCriteria() {
        service.putAccountPolicy("metrics", DOCUMENT, "METRIC_EXTRACTION_POLICY",
                "LogGroupNamePrefix IN [\"/aws/lambda/\",\"/app/\"]", "ALL", REGION);

        assertEquals(1, service.describeAccountPolicies("METRIC_EXTRACTION_POLICY", null, REGION).size());
    }

    @Test
    void fieldIndexRejectsMixedScopeForms() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("mixed-index", DOCUMENT, "FIELD_INDEX_POLICY",
                        "LogGroupNamePrefix = 'aws/' AND DataSourceName = 'amazon_vpc' AND DataSourceType = 'flow'",
                        "ALL", REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void fieldIndexRequiresCompleteDataSourcePair() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("partial-index", DOCUMENT, "FIELD_INDEX_POLICY",
                        "DataSourceName = 'amazon_vpc'", "ALL", REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    @Test
    void fieldIndexIgnoresReservedFieldNamesInsidePrefixValue() {
        service.putAccountPolicy("prefix-index", DOCUMENT, "FIELD_INDEX_POLICY",
                "LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"", "ALL", REGION);

        assertEquals(1, service.describeAccountPolicies("FIELD_INDEX_POLICY", null, REGION).size());
    }

    @Test
    void fieldIndexIgnoresReservedFieldNamesInsideDataSourceValues() {
        service.putAccountPolicy("data-source-index", DOCUMENT, "FIELD_INDEX_POLICY",
                "DataSourceName = \"LogGroupNamePrefix\" AND DataSourceType = \"flow\"", "ALL", REGION);

        assertEquals(1, service.describeAccountPolicies("FIELD_INDEX_POLICY", null, REGION).size());
    }

    @Test
    void quotedPrefixStillConflictsWithGlobalFieldIndexPolicy() {
        service.putAccountPolicy("global-index", DOCUMENT, "FIELD_INDEX_POLICY", null, "ALL", REGION);

        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("prefix-index", DOCUMENT, "FIELD_INDEX_POLICY",
                        "LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"", "ALL", REGION));

        assertEquals("LimitExceededException", error.getErrorCode());
    }

    @Test
    void metricExtractionRejectsMoreThanFiftySelectionValues() {
        StringBuilder criteria = new StringBuilder("LogGroupName IN [");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                criteria.append(',');
            }
            criteria.append('"').append("group-").append(i).append('"');
        }
        criteria.append(']');

        AwsException error = assertThrows(AwsException.class,
                () -> service.putAccountPolicy("metrics", DOCUMENT, "METRIC_EXTRACTION_POLICY",
                        criteria.toString(), "ALL", REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
    }
}

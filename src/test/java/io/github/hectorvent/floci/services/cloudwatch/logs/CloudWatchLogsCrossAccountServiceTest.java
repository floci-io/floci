package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudWatchLogsCrossAccountServiceTest {
    private static final String REGION = "us-east-1";
    private static final String DOCUMENT = "{}";

    private final CloudWatchLogsCrossAccountService service = new CloudWatchLogsCrossAccountService(
            new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"), new ObjectMapper());

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

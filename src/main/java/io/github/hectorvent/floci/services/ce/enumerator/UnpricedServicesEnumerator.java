package io.github.hectorvent.floci.services.ce.enumerator;

import io.github.hectorvent.floci.core.common.ResourceUsageEnumerator;
import io.github.hectorvent.floci.core.common.UsageLine;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Emits zero-quantity placeholder lines for Floci services that don't have
 * unit-price data in the bundled snapshot yet. Keeps these services discoverable
 * via {@code GetDimensionValues SERVICE} without contributing billed cost.
 *
 * <p>Each service ships its own AWS-shaped {@code ServiceCode} per the AWS Price
 * List naming convention.
 */
@ApplicationScoped
public class UnpricedServicesEnumerator implements ResourceUsageEnumerator {

    /**
     * AWS Price List service codes for the Floci services Floci's pricing snapshot
     * does not yet cover. Update when a new {@code *UsageEnumerator} bean lands
     * with rate data.
     */
    private static final List<String> SERVICE_CODES = List.of(
            "AmazonDynamoDB",
            "AmazonSQS",
            "AmazonSNS",
            "AmazonSES",
            "AmazonKinesis",
            "AmazonKinesisFirehose",
            "AWSSecretsManager",
            "AmazonCloudWatch",
            "AWSCloudFormation",
            "AmazonAPIGateway",
            "AWSStepFunctions",
            "AmazonECR",
            "AmazonECS",
            "AmazonEKS",
            "AmazonRDS",
            "AmazonElastiCache",
            "AmazonOpenSearchService",
            "AmazonAthena",
            "AWSGlue",
            "AWSEvents",
            "AmazonCognito",
            "AWSCodeBuild",
            "AWSCodeDeploy",
            "AmazonRoute53",
            "AWSCertificateManager",
            "AWSKeyManagementService",
            "AWSBackup",
            "AWSTransfer",
            "AmazonMSK",
            "AWSSystemsManager",
            "AWSAppConfig",
            "AmazonTextract",
            "AmazonBedrock");

    @Override
    public Stream<UsageLine> enumerate(Instant periodStart, Instant periodEnd, String region) {
        if (Duration.between(periodStart, periodEnd).toSeconds() <= 0) {
            return Stream.empty();
        }
        return SERVICE_CODES.stream().map(code -> new UsageLine(
                periodStart, periodEnd,
                code, region,
                "Unknown", "Unknown",
                UsageLine.RECORD_TYPE_USAGE,
                "000000000000",
                null,
                Map.of(),
                0.0,
                "Unknown"));
    }
}

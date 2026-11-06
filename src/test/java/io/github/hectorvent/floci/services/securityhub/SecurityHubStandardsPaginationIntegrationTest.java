package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.securityhub.SecurityHubClient;
import software.amazon.awssdk.services.securityhub.model.DescribeStandardsRequest;
import software.amazon.awssdk.services.securityhub.model.DescribeStandardsResponse;
import software.amazon.awssdk.services.securityhub.model.InvalidInputException;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SecurityHubStandardsPaginationIntegrationTest {
    @Test
    void sdkPagesTheCompleteCatalogAndPreservesStandardMetadata() {
        try (SecurityHubClient client = AwsTestClients.create(SecurityHubClient.builder(), "test", Region.US_EAST_1)) {
            Set<String> found = new HashSet<>();
            String token = null;
            int pages = 0;
            do {
                String cursor = token;
                DescribeStandardsResponse page = client.describeStandards(r -> r.maxResults(1).nextToken(cursor));
                assertEquals(1, page.standards().size());
                assertTrue(found.add(page.standards().getFirst().standardsArn()));
                assertNotNull(page.standards().getFirst().name());
                assertEquals("AWS", page.standards().getFirst().standardsManagedBy().company());
                token = page.nextToken();
                assertTrue(++pages <= 100, "pagination must terminate");
            } while (token != null);
            assertEquals(client.describeStandards(DescribeStandardsRequest.builder().build()).standards().size(), found.size());
            assertEquals(found.size(), client.describeStandardsPaginator(r -> r.maxResults(2))
                    .standards().stream().count());
            assertNull(client.describeStandards(r -> r.maxResults(100)).nextToken());
        }
    }

    @Test
    void sdkRejectsInvalidSizesAndTokens() {
        try (SecurityHubClient client = AwsTestClients.create(SecurityHubClient.builder(), "test", Region.US_EAST_1)) {
            for (int size : List.of(0, -1, 101)) {
                assertThrows(InvalidInputException.class, () -> client.describeStandards(r -> r.maxResults(size)));
            }
            assertThrows(InvalidInputException.class, () -> client.describeStandards(r -> r.nextToken("invalid-token")));
        }
    }
}

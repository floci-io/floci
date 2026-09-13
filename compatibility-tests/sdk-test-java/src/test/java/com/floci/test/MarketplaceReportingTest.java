package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.marketplacereporting.MarketplaceReportingClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarketplaceReportingTest {

    @Test
    void usesAwsSdkWireContract() {
        try (MarketplaceReportingClient client = TestFixtures.marketplaceReportingClient()) {
            String arn = "arn:aws:aws-marketplace::000000000000:AWSMarketplace/ReportingData/Agreement_V1/Dashboard/AgreementSummary_V1";
            var response = client.getBuyerDashboard(r -> r.dashboardIdentifier(arn).embeddingDomains("https://example.com"));
            assertEquals(arn, response.dashboardIdentifier());
            assertFalse(response.embedUrl().isBlank());
        }
    }
}

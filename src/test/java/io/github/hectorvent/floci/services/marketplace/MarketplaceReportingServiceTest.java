package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketplaceReportingServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MarketplaceReportingService service = new MarketplaceReportingService(mapper);

    @Test
    void getBuyerDashboardBuildsAccountScopedEmbedUrl() throws Exception {
        var response = service.getBuyerDashboard(mapper.readTree("""
                {"dashboardIdentifier":"arn:aws:aws-marketplace::123456789012:AWSMarketplace/ReportingData/Agreement_V1/Dashboard/AgreementSummary_V1",
                "embeddingDomains":["https://example.com"]}
                """), "123456789012");
        assertTrue(response.path("embedUrl").asText().contains("accounts/123456789012/dashboards/AgreementSummary_V1"));
        assertEquals("https://example.com", response.path("embeddingDomains").get(0).asText());
    }

    @Test
    void invalidDomainsAreRejected() throws Exception {
        AwsException error = assertThrows(AwsException.class, () -> service.getBuyerDashboard(mapper.readTree("""
                {"dashboardIdentifier":"arn:aws:aws-marketplace::123456789012:AWSMarketplace/ReportingData/Agreement_V1/Dashboard/AgreementSummary_V1",
                "embeddingDomains":["javascript:alert(1)"]}
                """), "123456789012"));
        assertEquals("BadRequestException", error.getErrorCode());
    }
}

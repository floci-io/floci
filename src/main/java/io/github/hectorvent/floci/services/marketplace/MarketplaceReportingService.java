package io.github.hectorvent.floci.services.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.marketplace.model.MarketplaceBuyerDashboard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class MarketplaceReportingService {
    private static final Pattern DASHBOARD = Pattern.compile(
            "arn:aws:aws-marketplace::[0-9]{12}:AWSMarketplace/ReportingData/(Agreement_V1/Dashboard/AgreementSummary_V1|BillingEvent_V1/Dashboard/CostAnalysis_V1)");
    private static final Pattern DOMAIN = Pattern.compile(
            "(https://[a-zA-Z.*0-9_-]+[.][a-zA-Z]+[a-zA-Z0-9&?/_=-]*[a-zA-Z*0-9/]+|https?://localhost(:[0-9]{1,5})?)");
    private final ObjectMapper mapper;

    @Inject
    public MarketplaceReportingService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode getBuyerDashboard(JsonNode request, String accountId) {
        String dashboard = text(request, "dashboardIdentifier");
        if (!DASHBOARD.matcher(dashboard).matches()) {
            throw badRequest("dashboardIdentifier must be a supported AWS Marketplace procurement insights dashboard ARN.");
        }
        JsonNode domains = request.get("embeddingDomains");
        if (domains == null || !domains.isArray() || domains.size() < 1 || domains.size() > 2) {
            throw badRequest("embeddingDomains must contain one or two domains.");
        }
        List<String> copiedDomains = new ArrayList<>(domains.size());
        for (JsonNode domain : domains) {
            if (!domain.isTextual() || domain.asText().length() > 2000 || !DOMAIN.matcher(domain.asText()).matches()) {
                throw badRequest("embeddingDomains contains an invalid domain.");
            }
            copiedDomains.add(domain.asText());
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        String dashboardName = dashboard.substring(dashboard.lastIndexOf('/') + 1);
        String embedUrl = "https://us-east-1.quicksight.aws.amazon.com/sn/embed/share/accounts/"
                + accountId + "/dashboards/" + URLEncoder.encode(dashboardName, StandardCharsets.UTF_8)
                + "?code=" + token;
        MarketplaceBuyerDashboard result = new MarketplaceBuyerDashboard(
                dashboard, embedUrl, copiedDomains);
        return mapper.valueToTree(result);
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request == null ? null : request.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw badRequest(field + " is required.");
        }
        return value.asText();
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}

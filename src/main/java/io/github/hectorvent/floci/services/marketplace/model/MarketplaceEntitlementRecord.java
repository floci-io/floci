package io.github.hectorvent.floci.services.marketplace.model;

import com.fasterxml.jackson.databind.JsonNode;

/** Read-only entitlement record returned by the AWS Marketplace Entitlement Service. */
public record MarketplaceEntitlementRecord(
        String productCode,
        String dimension,
        String customerIdentifier,
        String customerAwsAccountId,
        String licenseArn,
        JsonNode value,
        Double expirationDate) {

    public MarketplaceEntitlementRecord {
        value = value == null ? null : value.deepCopy();
    }

    public static MarketplaceEntitlementRecord fromJson(JsonNode node) {
        return new MarketplaceEntitlementRecord(
                text(node, "ProductCode"),
                text(node, "Dimension"),
                text(node, "CustomerIdentifier"),
                text(node, "CustomerAWSAccountId"),
                text(node, "LicenseArn"),
                node.get("Value"),
                node.hasNonNull("ExpirationDate") ? node.path("ExpirationDate").asDouble() : null);
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
}

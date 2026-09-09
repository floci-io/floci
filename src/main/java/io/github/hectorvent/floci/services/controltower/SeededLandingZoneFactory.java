package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.controltower.model.LandingZone;

/**
 * Builds the single pre-seeded active landing zone {@link ControlTowerService} lazily creates on
 * first read. Every manifest key below is deliberate — see
 * {@code issues/controltower/02-architecture.md} §3.4 for the landmine each one defuses,
 * most notably {@code securityRoles}, which LZA's {@code makeManifestDocument} UPDATE branch
 * dereferences unguarded ({@code setup-landing-zone/functions.ts:91}).
 */
final class SeededLandingZoneFactory {

    private SeededLandingZoneFactory() {
    }

    static LandingZone create(String accountId, String region) {
        String arn = "arn:aws:controltower:" + region + ":" + accountId
                + ":landingzone/" + ControlTowerService.LANDING_ZONE_ID;
        return new LandingZone(
                arn,
                ControlTowerService.LANDING_ZONE_VERSION,
                ControlTowerService.LANDING_ZONE_VERSION,
                ControlTowerService.STATUS_ACTIVE,
                ControlTowerService.DRIFT_IN_SYNC,
                manifest(accountId, region),
                null);
    }

    private static ObjectNode manifest(String accountId, String region) {
        ObjectNode manifest = JsonNodeFactory.instance.objectNode();
        manifest.putArray("governedRegions").add(region);

        ObjectNode security = JsonNodeFactory.instance.objectNode();
        security.put("name", "Security");
        ObjectNode organizationStructure = JsonNodeFactory.instance.objectNode();
        organizationStructure.set("security", security);
        manifest.set("organizationStructure", organizationStructure);

        manifest.set("centralizedLogging", loggingSection(accountId, region));
        manifest.set("config", configSection(accountId));

        ObjectNode securityRoles = JsonNodeFactory.instance.objectNode();
        securityRoles.put("enabled", true);
        securityRoles.put("accountId", accountId);
        manifest.set("securityRoles", securityRoles);

        ObjectNode accessManagement = JsonNodeFactory.instance.objectNode();
        accessManagement.put("enabled", true);
        manifest.set("accessManagement", accessManagement);

        return manifest;
    }

    private static ObjectNode loggingSection(String accountId, String region) {
        ObjectNode loggingBucket = JsonNodeFactory.instance.objectNode();
        loggingBucket.put("retentionDays", 365);
        ObjectNode accessLoggingBucket = JsonNodeFactory.instance.objectNode();
        accessLoggingBucket.put("retentionDays", 3650);

        ObjectNode configurations = JsonNodeFactory.instance.objectNode();
        configurations.set("loggingBucket", loggingBucket);
        configurations.set("accessLoggingBucket", accessLoggingBucket);
        configurations.put("kmsKeyArn",
                "arn:aws:kms:" + region + ":" + accountId + ":key/floci-seeded-ct-key");

        ObjectNode centralizedLogging = JsonNodeFactory.instance.objectNode();
        centralizedLogging.put("accountId", accountId);
        centralizedLogging.set("configurations", configurations);
        centralizedLogging.put("enabled", true);
        return centralizedLogging;
    }

    private static ObjectNode configSection(String accountId) {
        ObjectNode loggingBucket = JsonNodeFactory.instance.objectNode();
        loggingBucket.put("retentionDays", 365);
        ObjectNode accessLoggingBucket = JsonNodeFactory.instance.objectNode();
        accessLoggingBucket.put("retentionDays", 3650);

        ObjectNode configurations = JsonNodeFactory.instance.objectNode();
        configurations.set("loggingBucket", loggingBucket);
        configurations.set("accessLoggingBucket", accessLoggingBucket);

        ObjectNode config = JsonNodeFactory.instance.objectNode();
        config.put("accountId", accountId);
        config.set("configurations", configurations);
        config.put("enabled", true);
        return config;
    }
}

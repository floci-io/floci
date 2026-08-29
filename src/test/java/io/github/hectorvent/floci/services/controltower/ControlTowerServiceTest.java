package io.github.hectorvent.floci.services.controltower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.controltower.model.EnabledBaseline;
import io.github.hectorvent.floci.services.controltower.model.LandingZone;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlTowerServiceTest {

    private static final String ACCOUNT = "000000000101";
    private static final String REGION = "us-east-1";
    private static final String SEEDED_ARN =
            "arn:aws:controltower:us-east-1:000000000101:landingzone/FLOCISEEDEDLZ1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ControlTowerService service =
            new ControlTowerService(new InMemoryStorage<>(), new InMemoryStorage<>());

    @Test
    void listLandingZonesAlwaysReturnsExactlyOneSeededLandingZone() {
        List<LandingZone> first = service.listLandingZones(ACCOUNT, REGION);
        assertEquals(1, first.size());
        assertEquals(SEEDED_ARN, first.get(0).getArn());

        List<LandingZone> second = service.listLandingZones(ACCOUNT, REGION);
        assertEquals(1, second.size());
        assertEquals(SEEDED_ARN, second.get(0).getArn());
    }

    @Test
    void createLandingZoneStoresManifestAndReturnsCreateOperation() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0","tags":{"Environment":"test"},
                 "manifest":{"governedRegions":["us-east-1"],"accessManagement":{"enabled":true}}}
                """);

        ControlTowerService.CreateLandingZoneResult result =
                service.createLandingZone(ACCOUNT, REGION, request);

        assertTrue(result.arn().startsWith("arn:aws:controltower:" + REGION + ":" + ACCOUNT + ":landingzone/"));
        assertTrue(result.operationIdentifier().matches("^[a-f0-9-]{36}$"));
        assertEquals("CREATE", service.getOperationType(ACCOUNT, REGION, result.operationIdentifier()));
        assertEquals(request.get("manifest"), service.getOrSeedLandingZone(ACCOUNT, REGION).getManifest());
    }

    @Test
    void createLandingZoneRejectsSecondLandingZoneAndInvalidManifest() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0","manifest":{"accessManagement":{"enabled":true}}}
                """);
        service.createLandingZone(ACCOUNT, REGION, request);

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createLandingZone(ACCOUNT, REGION, request));
        assertEquals("ConflictException", conflict.getErrorCode());

        AwsException invalid = assertThrows(AwsException.class,
                () -> service.createLandingZone("000000000102", REGION,
                        objectMapper.readTree("{\"version\":\"4.0\",\"manifest\":[]}")));
        assertEquals("ValidationException", invalid.getErrorCode());
    }

    @Test
    void seededLandingZoneCarriesPinnedVersionAndInSyncDrift() {
        LandingZone seeded = service.getOrSeedLandingZone(ACCOUNT, REGION);
        assertEquals("4.0", seeded.getVersion());
        assertEquals("4.0", seeded.getLatestAvailableVersion());
        assertEquals("ACTIVE", seeded.getStatus());
        assertEquals("IN_SYNC", seeded.getDriftStatus());
        assertNull(seeded.getRemediationTypes());
    }

    @Test
    void seededManifestContainsEveryKeyLzaDereferences() {
        JsonNode manifest = service.getOrSeedLandingZone(ACCOUNT, REGION).getManifest();
        assertTrue(manifest.path("securityRoles").path("enabled").asBoolean(false));
        assertTrue(manifest.path("accessManagement").path("enabled").asBoolean(false));
        assertEquals(REGION, manifest.path("governedRegions").get(0).asText());
        assertTrue(manifest.has("centralizedLogging"));
        assertTrue(manifest.has("config"));
        assertEquals("Security", manifest.path("organizationStructure").path("security").path("name").asText());
    }

    @Test
    void updateLandingZoneStoresManifestVersionAndRemediationTypesAndReturnsOperationId() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {
                  "version": "4.0",
                  "landingZoneIdentifier": "%s",
                  "remediationTypes": ["INHERITANCE_DRIFT"],
                  "manifest": {
                    "governedRegions": ["us-east-1"],
                    "centralizedLogging": {"configurations": {"loggingBucket": {"retentionDays": 90}}},
                    "securityRoles": {"enabled": true, "accountId": "000000000101"},
                    "accessManagement": {"enabled": true}
                  }
                }
                """.formatted(SEEDED_ARN));

        String opId = service.updateLandingZone(ACCOUNT, REGION, request);
        assertNotNull(opId);
        assertFalse(opId.isBlank());

        LandingZone updated = service.getOrSeedLandingZone(ACCOUNT, REGION);
        assertEquals("4.0", updated.getVersion());
        assertEquals("4.0", updated.getLatestAvailableVersion());
        assertEquals(90, updated.getManifest().path("centralizedLogging")
                .path("configurations").path("loggingBucket").path("retentionDays").asInt());
        assertEquals(List.of("INHERITANCE_DRIFT"), updated.getRemediationTypes());
    }

    @Test
    void updateLandingZoneWithoutRemediationTypesClearsThem() throws Exception {
        JsonNode withRemediation = objectMapper.readTree("""
                {"version":"4.0","landingZoneIdentifier":"%s","remediationTypes":["INHERITANCE_DRIFT"],
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """.formatted(SEEDED_ARN));
        service.updateLandingZone(ACCOUNT, REGION, withRemediation);

        JsonNode withoutRemediation = objectMapper.readTree("""
                {"version":"4.0","landingZoneIdentifier":"%s",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """.formatted(SEEDED_ARN));
        service.updateLandingZone(ACCOUNT, REGION, withoutRemediation);

        assertNull(service.getOrSeedLandingZone(ACCOUNT, REGION).getRemediationTypes());
    }

    @Test
    void getLandingZoneRequiresLandingZoneIdentifier() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.getLandingZone(ACCOUNT, REGION, objectMapper.createObjectNode()));
        assertEquals("ValidationException", missing.getErrorCode());
        assertEquals(400, missing.getHttpStatus());
    }

    @Test
    void getLandingZoneRejectsUnknownIdentifier() throws Exception {
        AwsException unknown = assertThrows(AwsException.class,
                () -> service.getLandingZone(ACCOUNT, REGION, objectMapper.readTree(
                        "{\"landingZoneIdentifier\":\"arn:aws:controltower:us-east-1:000000000101:landingzone/other\"}")));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());
        assertEquals(404, unknown.getHttpStatus());
    }

    @Test
    void getLandingZoneReturnsSeededLandingZoneForMatchingIdentifier() throws Exception {
        LandingZone landingZone = service.getLandingZone(ACCOUNT, REGION,
                objectMapper.readTree("{\"landingZoneIdentifier\":\"" + SEEDED_ARN + "\"}"));

        assertEquals(SEEDED_ARN, landingZone.getArn());
        assertEquals("ACTIVE", landingZone.getStatus());
    }

    @Test
    void updateLandingZoneRequiresLandingZoneIdentifier() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """);

        AwsException missing = assertThrows(AwsException.class,
                () -> service.updateLandingZone(ACCOUNT, REGION, request));
        assertEquals("ValidationException", missing.getErrorCode());
        assertEquals(400, missing.getHttpStatus());
    }

    @Test
    void updateLandingZoneRejectsUnknownIdentifier() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0",
                 "landingZoneIdentifier":"arn:aws:controltower:us-east-1:000000000101:landingzone/other",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """);

        AwsException unknown = assertThrows(AwsException.class,
                () -> service.updateLandingZone(ACCOUNT, REGION, request));
        assertEquals("ResourceNotFoundException", unknown.getErrorCode());
        assertEquals(404, unknown.getHttpStatus());
    }

    @Test
    void operationLedgerIsScopedPerAccountAndRegion() throws Exception {
        JsonNode createRequest = objectMapper.readTree("""
                {"version":"4.0","manifest":{"accessManagement":{"enabled":true}}}
                """);
        String opId = service.createLandingZone(ACCOUNT, REGION, createRequest).operationIdentifier();

        assertEquals(List.of(opId), operationIdentifiers(ACCOUNT, REGION));
        assertEquals("CREATE", service.getOperationType(ACCOUNT, REGION, opId));

        // Another account, and another region of the same account, must not see it at all.
        assertTrue(operationIdentifiers("000000000102", REGION).isEmpty());
        assertTrue(operationIdentifiers(ACCOUNT, "us-west-2").isEmpty());
        assertEquals("UPDATE", service.getOperationType("000000000102", REGION, opId));
    }

    @Test
    void operationLedgerEvictsOldestBeyondCap() throws Exception {
        JsonNode createRequest = objectMapper.readTree("""
                {"version":"4.0","manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """);
        String eldest = service.createLandingZone(ACCOUNT, REGION, createRequest).operationIdentifier();
        JsonNode updateRequest = objectMapper.readTree("""
                {"version":"4.0","landingZoneIdentifier":"%s",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """.formatted(service.getOrSeedLandingZone(ACCOUNT, REGION).getArn()));
        for (int i = 0; i < 250; i++) {
            service.updateLandingZone(ACCOUNT, REGION, updateRequest);
        }

        List<String> retained = operationIdentifiers(ACCOUNT, REGION);
        assertEquals(250, retained.size());
        assertFalse(retained.contains(eldest));
    }

    /** Pages {@code ListLandingZoneOperations} to completion for one account+region scope. */
    private List<String> operationIdentifiers(String accountId, String region) throws Exception {
        List<String> identifiers = new ArrayList<>();
        String nextToken = null;
        do {
            String body = nextToken == null
                    ? "{\"maxResults\":100}"
                    : "{\"maxResults\":100,\"nextToken\":\"" + nextToken + "\"}";
            ControlTowerService.ListLandingZoneOperationsResult page =
                    service.listLandingZoneOperations(accountId, region, objectMapper.readTree(body));
            page.landingZoneOperations().stream()
                    .map(ControlTowerService.LandingZoneOperationSummary::operationIdentifier)
                    .forEach(identifiers::add);
            nextToken = page.nextToken();
        } while (nextToken != null);
        return identifiers;
    }

    @Test
    void deleteLandingZoneRemovesStoredLandingZoneAndReturnsDeleteOperation() throws Exception {
        service.getOrSeedLandingZone(ACCOUNT, REGION);

        String opId = service.deleteLandingZone(ACCOUNT, REGION,
                objectMapper.readTree("{\"landingZoneIdentifier\":\"" + SEEDED_ARN + "\"}"));

        assertTrue(opId.matches("^[a-f0-9-]{36}$"));
        assertEquals("DELETE", service.getOperationType(ACCOUNT, REGION, opId));
        assertEquals(SEEDED_ARN, service.getOrSeedLandingZone(ACCOUNT, REGION).getArn());
    }

    @Test
    void deleteLandingZoneRequiresLandingZoneIdentifier() {
        AwsException invalid = assertThrows(AwsException.class,
                () -> service.deleteLandingZone(ACCOUNT, REGION, objectMapper.createObjectNode()));
        assertEquals("ValidationException", invalid.getErrorCode());
    }

    @Test
    void deleteLandingZoneRejectsMismatchedIdentifier() throws Exception {
        service.getOrSeedLandingZone(ACCOUNT, REGION);

        AwsException invalid = assertThrows(AwsException.class,
                () -> service.deleteLandingZone(ACCOUNT, REGION, objectMapper.readTree(
                        "{\"landingZoneIdentifier\":\"arn:aws:controltower:us-east-1:000000000101:landingzone/other\"}")));
        assertEquals("ResourceNotFoundException", invalid.getErrorCode());
        assertEquals(404, invalid.getHttpStatus());
    }

    @Test
    void deleteLandingZoneRejectsMissingStoredLandingZone() throws Exception {
        AwsException invalid = assertThrows(AwsException.class,
                () -> service.deleteLandingZone(ACCOUNT, REGION, objectMapper.readTree(
                        "{\"landingZoneIdentifier\":\"" + SEEDED_ARN + "\"}")));
        assertEquals("ResourceNotFoundException", invalid.getErrorCode());
        assertEquals(404, invalid.getHttpStatus());
    }

    @Test
    void resetLandingZoneReturnsResetOperationForValidIdentifier() throws Exception {
        service.getOrSeedLandingZone(ACCOUNT, REGION);

        String opId = service.resetLandingZone(ACCOUNT, REGION,
                objectMapper.readTree("{\"landingZoneIdentifier\":\"" + SEEDED_ARN + "\"}"));

        assertTrue(opId.matches("^[a-f0-9-]{36}$"));
        assertEquals("RESET", service.getOperationType(ACCOUNT, REGION, opId));
        assertEquals(SEEDED_ARN, service.getOrSeedLandingZone(ACCOUNT, REGION).getArn());
    }

    @Test
    void resetLandingZoneRejectsMissingOrMismatchedIdentifier() throws Exception {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.resetLandingZone(ACCOUNT, REGION, objectMapper.createObjectNode()));
        assertEquals("ValidationException", missing.getErrorCode());

        service.getOrSeedLandingZone(ACCOUNT, REGION);
        AwsException mismatched = assertThrows(AwsException.class,
                () -> service.resetLandingZone(ACCOUNT, REGION, objectMapper.readTree(
                        "{\"landingZoneIdentifier\":\"arn:aws:controltower:us-east-1:000000000101:landingzone/other\"}")));
        assertEquals("ResourceNotFoundException", mismatched.getErrorCode());
        assertEquals(404, mismatched.getHttpStatus());
    }

    @Test
    void getOperationTypeReportsUpdateForIssuedAndUnknownIds() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0","landingZoneIdentifier":"%s",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """.formatted(SEEDED_ARN));
        String opId = service.updateLandingZone(ACCOUNT, REGION, request);

        assertEquals("UPDATE", service.getOperationType(ACCOUNT, REGION, opId));
        assertNotNull(service.getOperationType(ACCOUNT, REGION, "never-issued"));
    }

    @Test
    void listLandingZoneOperationsReturnsNewestFirstWithFilteringAndPagination() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0","landingZoneIdentifier":"%s",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":true}}}
                """.formatted(SEEDED_ARN));
        String firstId = service.updateLandingZone(ACCOUNT, REGION, request);
        String secondId = service.updateLandingZone(ACCOUNT, REGION, request);

        ControlTowerService.ListLandingZoneOperationsResult first = service.listLandingZoneOperations(
                ACCOUNT, REGION,
                objectMapper.readTree("{\"maxResults\":1,\"filter\":{\"statuses\":[\"SUCCEEDED\"]}}"));
        assertEquals(1, first.landingZoneOperations().size());
        assertEquals(secondId, first.landingZoneOperations().get(0).operationIdentifier());
        assertEquals("UPDATE", first.landingZoneOperations().get(0).operationType());
        assertEquals("SUCCEEDED", first.landingZoneOperations().get(0).status());
        assertNotNull(first.nextToken());

        ControlTowerService.ListLandingZoneOperationsResult second = service.listLandingZoneOperations(
                ACCOUNT, REGION,
                objectMapper.readTree("{\"maxResults\":1,\"nextToken\":\"%s\",\"filter\":{\"types\":[\"UPDATE\"]}}"
                        .formatted(first.nextToken())));
        assertEquals(List.of(firstId), second.landingZoneOperations().stream()
                .map(ControlTowerService.LandingZoneOperationSummary::operationIdentifier).toList());
        assertNull(second.nextToken());
    }

    @Test
    void listBaselinesContainsControlTowerAndIdentityCenterBaselinesWithRegionStampedArns() {
        List<ObjectNode> baselines = service.listBaselines(REGION);
        List<String> names = baselines.stream().map(b -> b.get("name").asText()).toList();
        assertTrue(names.contains("AWSControlTowerBaseline"));
        assertTrue(names.contains("IdentityCenterBaseline"));

        for (ObjectNode baseline : baselines) {
            assertTrue(baseline.get("arn").asText().startsWith("arn:aws:controltower:us-east-1::baseline/"));
        }
    }

    @Test
    void identityCenterBaselineAutoEnabledWhenManifestAccessManagementEnabled() {
        // Fresh service — seed manifest has accessManagement.enabled=true.
        String identityCenterArn = service.listBaselines(REGION).stream()
                .filter(b -> "IdentityCenterBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();

        List<EnabledBaseline> enabled = service.listEnabledBaselines(ACCOUNT, REGION);
        List<EnabledBaseline> icEntries = enabled.stream()
                .filter(e -> identityCenterArn.equals(e.getBaselineIdentifier()))
                .toList();

        assertEquals(1, icEntries.size());
        EnabledBaseline entry = icEntries.get(0);
        assertNotNull(entry.getArn());
        assertNotNull(entry.getTargetIdentifier());
        assertNotNull(entry.getBaselineVersion());
        assertNotNull(entry.getStatus());
    }

    @Test
    void identityCenterBaselineAbsentWhenAccessManagementDisabled() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"version":"4.0","landingZoneIdentifier":"%s",
                 "manifest":{"securityRoles":{"enabled":true},"accessManagement":{"enabled":false}}}
                """.formatted(SEEDED_ARN));
        service.updateLandingZone(ACCOUNT, REGION, request);

        List<EnabledBaseline> enabled = service.listEnabledBaselines(ACCOUNT, REGION);
        boolean hasIdentityCenter = enabled.stream()
                .anyMatch(e -> e.getBaselineIdentifier() != null
                        && e.getBaselineIdentifier().contains("LN25R72TTG6IGPTQ"));
        assertFalse(hasIdentityCenter);
    }

    @Test
    void enableBaselineStoresEnabledBaselineByTargetAndEchoesVersion() throws Exception {
        String ctBaselineArn = service.listBaselines(REGION).stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();
        String ouArn = "arn:aws:organizations::000000000101:ou/o-floci0001/ou-abcd-11111111";

        JsonNode request = objectMapper.readTree("""
                {"baselineIdentifier":"%s","baselineVersion":"5.0","targetIdentifier":"%s",
                 "parameters":[{"key":"IdentityCenterEnabledBaselineArn","value":"placeholder"}]}
                """.formatted(ctBaselineArn, ouArn));

        ControlTowerService.EnableBaselineResult result = service.enableBaseline(ACCOUNT, REGION, request);
        assertNotNull(result.operationIdentifier());
        assertFalse(result.operationIdentifier().isBlank());
        assertNotNull(result.arn());

        List<EnabledBaseline> enabled = service.listEnabledBaselines(ACCOUNT, REGION);
        EnabledBaseline stored = enabled.stream()
                .filter(e -> ouArn.equals(e.getTargetIdentifier()))
                .findFirst().orElseThrow();
        assertEquals("5.0", stored.getBaselineVersion());
        assertEquals("SUCCEEDED", stored.getStatus());

        assertEquals("BASELINE_ENABLED", service.getBaselineOperationType(ACCOUNT, REGION, result.operationIdentifier()));
    }

    @Test
    void enableBaselineTwiceForSameTargetReplacesNotDuplicates() throws Exception {
        String ctBaselineArn = service.listBaselines(REGION).stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();
        String ouArn = "arn:aws:organizations::000000000101:ou/o-floci0001/ou-abcd-22222222";

        JsonNode request = objectMapper.readTree("""
                {"baselineIdentifier":"%s","baselineVersion":"5.0","targetIdentifier":"%s"}
                """.formatted(ctBaselineArn, ouArn));
        service.enableBaseline(ACCOUNT, REGION, request);
        service.enableBaseline(ACCOUNT, REGION, request);

        long matching = service.listEnabledBaselines(ACCOUNT, REGION).stream()
                .filter(e -> ouArn.equals(e.getTargetIdentifier()))
                .count();
        assertEquals(1, matching);
    }

    @Test
    void enableBaselineWithoutRequiredFieldsThrowsValidationException() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"baselineIdentifier":"arn:aws:controltower:us-east-1::baseline/17BSJV3IGJ2QSGA2","baselineVersion":"5.0"}
                """);

        AwsException error = assertThrows(
                AwsException.class, () -> service.enableBaseline(ACCOUNT, REGION, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void enableBaselineRejectsNonArnTargetIdentifier() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"baselineIdentifier":"arn:aws:controltower:us-east-1::baseline/17BSJV3IGJ2QSGA2",
                 "baselineVersion":"5.0","targetIdentifier":"ou-abcd-11111111"}
                """);

        AwsException error = assertThrows(
                AwsException.class, () -> service.enableBaseline(ACCOUNT, REGION, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.listEnabledBaselines(ACCOUNT, REGION).stream()
                .noneMatch(e -> "ou-abcd-11111111".equals(e.getTargetIdentifier())));
    }

    @Test
    void enableBaselineRejectsNonArnBaselineIdentifier() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"baselineIdentifier":"17BSJV3IGJ2QSGA2","baselineVersion":"5.0",
                 "targetIdentifier":"arn:aws:organizations::000000000101:ou/o-floci0001/ou-abcd-33333333"}
                """);

        AwsException error = assertThrows(
                AwsException.class, () -> service.enableBaseline(ACCOUNT, REGION, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void enableBaselineRejectsMalformedBaselineVersion() throws Exception {
        JsonNode request = objectMapper.readTree("""
                {"baselineIdentifier":"arn:aws:controltower:us-east-1::baseline/17BSJV3IGJ2QSGA2",
                 "baselineVersion":"five-point-oh",
                 "targetIdentifier":"arn:aws:organizations::000000000101:ou/o-floci0001/ou-abcd-44444444"}
                """);

        AwsException error = assertThrows(
                AwsException.class, () -> service.enableBaseline(ACCOUNT, REGION, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void getEnabledBaselineFindsSyntheticIdentityCenterAndReportsMissingResource() {
        String identityCenterArn = service.listBaselines(REGION).stream()
                .filter(b -> "IdentityCenterBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();
        EnabledBaseline synthetic = service.listEnabledBaselines(ACCOUNT, REGION).stream()
                .filter(e -> identityCenterArn.equals(e.getBaselineIdentifier()))
                .findFirst().orElseThrow();

        assertEquals(synthetic.getArn(), service.getEnabledBaseline(ACCOUNT, REGION, synthetic.getArn()).getArn());
        AwsException error = assertThrows(AwsException.class,
                () -> service.getEnabledBaseline(ACCOUNT, REGION, "arn:aws:controltower:us-east-1:000000000101:enabledbaseline/missing"));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void listEnabledBaselinesAppliesFilterAndPagination() throws Exception {
        String baselineArn = service.listBaselines(REGION).stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();
        for (int i = 0; i < 6; i++) {
            JsonNode request = objectMapper.readTree("""
                    {"baselineIdentifier":"%s","baselineVersion":"5.0",
                     "targetIdentifier":"arn:aws:organizations::000000000101:ou/o-floci0001/ou-page-%08d"}
                    """.formatted(baselineArn, i));
            service.enableBaseline(ACCOUNT, REGION, request);
        }

        JsonNode listRequest = objectMapper.readTree("""
                {"filter":{"baselineIdentifiers":["%s"]},"maxResults":5}
                """.formatted(baselineArn));
        ControlTowerService.ListEnabledBaselinesResult first =
                service.listEnabledBaselines(ACCOUNT, REGION, listRequest);
        assertEquals(5, first.enabledBaselines().size());
        assertNotNull(first.nextToken());

        JsonNode secondRequest = objectMapper.readTree("""
                {"filter":{"baselineIdentifiers":["%s"]},"maxResults":5,"nextToken":"%s"}
                """.formatted(baselineArn, first.nextToken()));
        ControlTowerService.ListEnabledBaselinesResult second =
                service.listEnabledBaselines(ACCOUNT, REGION, secondRequest);
        assertEquals(1, second.enabledBaselines().size());
        assertNull(second.nextToken());
    }

    @Test
    void resetEnabledBaselineReturnsOperationIdentifierForValidBaseline() throws Exception {
        String baselineArn = service.listBaselines(REGION).stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();
        String ouArn = "arn:aws:organizations::000000000101:ou/o-floci0001/ou-reset-00000001";

        JsonNode enableRequest = objectMapper.readTree("""
                {"baselineIdentifier":"%s","baselineVersion":"5.0","targetIdentifier":"%s"}
                """.formatted(baselineArn, ouArn));
        service.enableBaseline(ACCOUNT, REGION, enableRequest);

        EnabledBaseline enabled = service.listEnabledBaselines(ACCOUNT, REGION).stream()
                .filter(e -> ouArn.equals(e.getTargetIdentifier()))
                .findFirst().orElseThrow();

        String opId = service.resetEnabledBaseline(ACCOUNT, REGION, enabled.getArn());
        assertNotNull(opId);
        assertFalse(opId.isBlank());
        assertEquals("BASELINE_RESET", service.getBaselineOperationType(ACCOUNT, REGION, opId));
    }

    @Test
    void resetEnabledBaselineThrowsResourceNotFoundForMissingBaseline() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.resetEnabledBaseline(ACCOUNT, REGION,
                        "arn:aws:controltower:us-east-1:000000000101:enabledbaseline/missing"));
        assertEquals("ResourceNotFoundException", error.getErrorCode());
        assertEquals(404, error.getHttpStatus());
    }

    @Test
    void resetEnabledBaselineThrowsValidationExceptionForInvalidArn() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.resetEnabledBaseline(ACCOUNT, REGION, "not-an-arn"));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void resetEnabledBaselineThrowsValidationExceptionForNullIdentifier() {
        AwsException error = assertThrows(
                AwsException.class,
                () -> service.resetEnabledBaseline(ACCOUNT, REGION, null));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void updateEnabledBaselineChangesVersionAndParametersAndReturnsOperationIdentifier() throws Exception {
        String baselineArn = service.listBaselines(REGION).stream()
                .filter(b -> "AWSControlTowerBaseline".equals(b.get("name").asText()))
                .findFirst().orElseThrow().get("arn").asText();
        String ouArn = "arn:aws:organizations::000000000101:ou/o-floci0001/ou-update-00000001";
        JsonNode enableRequest = objectMapper.readTree("""
                {"baselineIdentifier":"%s","baselineVersion":"5.0","targetIdentifier":"%s"}
                """.formatted(baselineArn, ouArn));
        service.enableBaseline(ACCOUNT, REGION, enableRequest);
        EnabledBaseline enabled = service.listEnabledBaselines(ACCOUNT, REGION).stream()
                .filter(e -> ouArn.equals(e.getTargetIdentifier()))
                .findFirst().orElseThrow();

        JsonNode updateRequest = objectMapper.readTree("""
                {"enabledBaselineIdentifier":"%s","baselineVersion":"6.0",
                 "parameters":[{"key":"Example","value":{"enabled":true}}]}
                """.formatted(enabled.getArn()));
        String opId = service.updateEnabledBaseline(ACCOUNT, REGION, updateRequest);

        EnabledBaseline updated = service.getEnabledBaseline(ACCOUNT, REGION, enabled.getArn());
        assertEquals("6.0", updated.getBaselineVersion());
        assertEquals(true, updated.getParameters().path(0).path("value").path("enabled").asBoolean());
        assertEquals("UPDATE_ENABLED_BASELINE", service.getBaselineOperationType(ACCOUNT, REGION, opId));
    }

    @Test
    void updateEnabledBaselineRejectsMissingRequiredFields() throws Exception {
        JsonNode request = objectMapper.readTree("{\"enabledBaselineIdentifier\":\"not-an-arn\"}");
        AwsException error = assertThrows(
                AwsException.class, () -> service.updateEnabledBaseline(ACCOUNT, REGION, request));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }
}

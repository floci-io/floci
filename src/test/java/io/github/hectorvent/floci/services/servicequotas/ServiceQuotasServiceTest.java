package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceQuotasServiceTest {

    private static final String ACCOUNT_ID = "000000000000";

    private final ServiceQuotasService service =
            new ServiceQuotasService(new InMemoryStorageFactory(ACCOUNT_ID), new ObjectMapper());

    @Test
    void syntheticQuotaCodesAreStableAndWellFormed() {
        String code = ServiceQuotasService.syntheticQuotaCode("widgetfactory", "Resources per Region");
        assertEquals(code, ServiceQuotasService.syntheticQuotaCode("widgetfactory", "Resources per Region"));
        assertTrue(code.matches("L-[0-9A-F]{8}"), code);
    }

    @Test
    void everyListedQuotaResolvesThroughGetServiceQuota() {
        for (String serviceCode : List.of("codebuild", "lambda", "widgetfactory")) {
            for (ServiceQuotasService.QuotaDefinition quota : service.quotasFor(serviceCode)) {
                ObjectNode response = service.getServiceQuota(
                        serviceCode, quota.quotaCode(), "us-east-1", "000000000000");
                assertEquals(quota.quotaCode(), response.path("Quota").path("QuotaCode").asText());
                assertTrue(response.path("Quota").path("Value").asDouble() >= 60.0);
            }
        }
    }

    @Test
    void ec2StandardQuotaRetainsCanonicalMetadataWhenListingAndReading() {
        String code = "L-1216C47A";
        String name = "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances";
        JsonNode quota = assertDoesNotThrow(() ->
                service.getServiceQuota("ec2", code, "us-east-1", ACCOUNT_ID)).path("Quota");
        assertEquals(code, quota.path("QuotaCode").asText());
        assertEquals(name, quota.path("QuotaName").asText());
        assertEquals(5.0, quota.path("Value").asDouble());
        assertTrue(quota.path("Adjustable").asBoolean());
        assertTrue(quota.path("GlobalQuota").isBoolean());
        assertFalse(quota.path("GlobalQuota").asBoolean());
        assertEquals("ACCOUNT", quota.path("QuotaAppliedAtLevel").asText());
        assertEquals("arn:aws:servicequotas:us-east-1:" + ACCOUNT_ID + ":ec2/" + code,
                quota.path("QuotaArn").asText());
        JsonNode listed = service.listServiceQuotas("ec2", null, null, 100, "us-east-1", ACCOUNT_ID).path("Quotas");
        assertEquals(4, listed.size());
        int matching = 0;
        for (JsonNode entry : listed) {
            if (code.equals(entry.path("QuotaCode").asText())) {
                assertEquals(quota, entry);
                matching++;
            }
        }
        assertEquals(1, matching);
    }

    @Test
    void codebuildIncludesConcurrentlyRunningBuilds() {
        List<String> codes = service.quotasFor("codebuild").stream()
                .map(ServiceQuotasService.QuotaDefinition::quotaCode)
                .toList();
        assertTrue(codes.contains("L-2DC20C30"), codes.toString());
    }

    @Test
    void vpcResolvesRealQuotaCodesWithNamesThroughGetAndList() {
        Map<String, String> want = Map.of(
                "L-FE5A380F", "NAT gateways per Availability Zone",
                "L-2AEEBF1A", "Rules per network ACL");
        Map<String, Double> defaults = Map.of("L-FE5A380F", 5.0, "L-2AEEBF1A", 20.0);
        for (Map.Entry<String, String> e : want.entrySet()) {
            JsonNode quota = service.getServiceQuota("vpc", e.getKey(), "us-east-1", "000000000000").path("Quota");
            assertEquals(e.getKey(), quota.path("QuotaCode").asText());
            assertEquals(e.getValue(), quota.path("QuotaName").asText());
            assertEquals(defaults.get(e.getKey()), quota.path("Value").asDouble());
            assertEquals("Amazon Virtual Private Cloud (Amazon VPC)", quota.path("ServiceName").asText());
        }
        Map<String, String> listed = new HashMap<>();
        service.listServiceQuotas("vpc", null, null, 100, "us-east-1", "000000000000").withArray("Quotas")
                .forEach(q -> listed.put(q.path("QuotaCode").asText(), q.path("QuotaName").asText()));
        want.forEach((code, name) -> assertEquals(name, listed.get(code), listed.toString()));
        assertEquals(want.size() + 3, listed.size(), "synthetic quotas still appended: " + listed);
    }

    @Test
    void vpcUnknownQuotaCodeStillThrowsNoSuchResource() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getServiceQuota("vpc", "L-DOESNOTEX", "us-east-1", "000000000000"));
        assertEquals("NoSuchResourceException", e.getErrorCode());
    }

    @Test
    void getServiceQuota_unknownCode_throwsNoSuchResource() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getServiceQuota("codebuild", "L-DOESNOTEX", "us-east-1", "000000000000"));
        assertEquals("NoSuchResourceException", e.getErrorCode());
    }

    /**
     * Unknown-but-well-formed service codes deliberately generate a quota catalog, so a code that
     * cannot name a service at all has to be rejected up front — otherwise "!!!" comes back with
     * three invented quotas instead of an error. The model constrains ServiceCode to at most 63
     * characters matching {@code [a-zA-Z][a-zA-Z0-9-]{1,63}}.
     */
    @Test
    void malformedServiceCodeIsRejected() {
        for (String bad : List.of("!!!", "1codebuild", "-codebuild", "code build", "c", "a".repeat(64))) {
            AwsException listing = assertThrows(AwsException.class,
                    () -> service.listServiceQuotas(bad, null, null, null, "us-east-1", "000000000000"),
                    "expected ListServiceQuotas to reject " + bad);
            assertEquals("IllegalArgumentException", listing.getErrorCode(), bad);
            assertEquals(400, listing.getHttpStatus(), bad);

            AwsException get = assertThrows(AwsException.class,
                    () -> service.getServiceQuota(bad, "L-2DC20C30", "us-east-1", "000000000000"),
                    "expected GetServiceQuota to reject " + bad);
            assertEquals("IllegalArgumentException", get.getErrorCode(), bad);
        }
    }

    @Test
    void wellFormedServiceCodesAreStillAccepted() {
        // Including an unknown-but-valid code: generating a catalog for those is deliberate.
        for (String good : List.of("codebuild", "widgetfactory", "elasticloadbalancing", "AWS-Thing", "ec")) {
            assertTrue(service.listServiceQuotas(good, null, null, null, "us-east-1", ACCOUNT_ID)
                    .withArray("Quotas").size() > 0, good);
        }
    }

    @Test
    void requestedIncreaseIsReadableBackById() {
        ObjectNode created = service.requestServiceQuotaIncrease(
                "codebuild", "L-2DC20C30", 9000.0, null, "us-east-1", ACCOUNT_ID)
                .withObject("RequestedQuota");
        String id = created.path("Id").asText();

        ObjectNode read = service.getRequestedServiceQuotaChange(id, "us-east-1").withObject("RequestedQuota");
        assertEquals(id, read.path("Id").asText());
        assertEquals("codebuild", read.path("ServiceCode").asText());
        assertEquals("L-2DC20C30", read.path("QuotaCode").asText());
        assertEquals("Concurrently running builds", read.path("QuotaName").asText());
        assertEquals(9000.0, read.path("DesiredValue").asDouble());
        assertEquals("PENDING", read.path("Status").asText());
    }

    @Test
    void repeatedRequestsForTheSameQuotaReturnResourceAlreadyExists() {
        service.requestServiceQuotaIncrease(
                "codebuild", "L-2DC20C30", 9000.0, null, "us-east-1", ACCOUNT_ID);
        AwsException e = assertThrows(AwsException.class, () -> service.requestServiceQuotaIncrease(
                "codebuild", "L-2DC20C30", 9500.0, null, "us-east-1", ACCOUNT_ID));
        assertEquals("ResourceAlreadyExistsException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }

    @Test
    void requestStoredInOneRegionIsNotVisibleInAnother() {
        String id = service.requestServiceQuotaIncrease(
                "codebuild", "L-2DC20C30", 9000.0, null, "us-east-1", ACCOUNT_ID)
                .path("RequestedQuota").path("Id").asText();

        AwsException e = assertThrows(AwsException.class,
                () -> service.getRequestedServiceQuotaChange(id, "eu-west-1"));
        assertEquals("NoSuchResourceException", e.getErrorCode());
    }

    @Test
    void historyListsOnlyTheRequestedQuota() {
        service.requestServiceQuotaIncrease(
                "codebuild", "L-2DC20C30", 9000.0, null, "us-east-1", ACCOUNT_ID);
        service.requestServiceQuotaIncrease(
                "lambda", "L-B99A9384", 8000.0, null, "us-east-1", ACCOUNT_ID);

        JsonNode codebuild = service.listRequestedServiceQuotaChangeHistoryByQuota(
                "codebuild", "L-2DC20C30", null, null, "us-east-1");
        assertEquals(1, codebuild.path("RequestedQuotas").size());
        assertEquals("codebuild", codebuild.path("RequestedQuotas").get(0).path("ServiceCode").asText());

        JsonNode lambda = service.listRequestedServiceQuotaChangeHistoryByQuota(
                "lambda", "L-B99A9384", null, null, "us-east-1");
        assertEquals(1, lambda.path("RequestedQuotas").size());
        assertEquals("lambda", lambda.path("RequestedQuotas").get(0).path("ServiceCode").asText());
    }

    @Test
    void missingRequestIdThrowsIllegalArgument() {
        AwsException empty = assertThrows(AwsException.class,
                () -> service.getRequestedServiceQuotaChange("", "us-east-1"));
        assertEquals("IllegalArgumentException", empty.getErrorCode());
        assertEquals(400, empty.getHttpStatus());
        AwsException nullId = assertThrows(AwsException.class,
                () -> service.getRequestedServiceQuotaChange(null, "us-east-1"));
        assertEquals("IllegalArgumentException", nullId.getErrorCode());
    }

    @Test
    void unknownRequestIdThrowsNoSuchResource() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.getRequestedServiceQuotaChange("DEADBEEF", "us-east-1"));
        assertEquals("NoSuchResourceException", e.getErrorCode());
        assertEquals(400, e.getHttpStatus());
    }
}

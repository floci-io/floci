package io.github.hectorvent.floci.services.servicequotas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceQuotasServiceTest {

    private final ServiceQuotasService service = new ServiceQuotasService(new ObjectMapper());

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
    void codebuildIncludesConcurrentlyRunningBuilds() {
        List<String> codes = service.quotasFor("codebuild").stream()
                .map(ServiceQuotasService.QuotaDefinition::quotaCode)
                .toList();
        assertTrue(codes.contains("L-2DC20C30"), codes.toString());
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
            assertTrue(service.listServiceQuotas(good, null, null, null, "us-east-1", "000000000000")
                    .withArray("Quotas").size() > 0, good);
        }
    }
}

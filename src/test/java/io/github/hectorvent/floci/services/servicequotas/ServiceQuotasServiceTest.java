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
}

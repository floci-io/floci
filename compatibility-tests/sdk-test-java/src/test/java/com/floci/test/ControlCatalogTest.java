package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.controlcatalog.ControlCatalogClient;
import software.amazon.awssdk.services.controlcatalog.model.ControlFilter;
import software.amazon.awssdk.services.controlcatalog.model.ImplementationFilter;
import software.amazon.awssdk.services.controlcatalog.model.ResourceNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlCatalogTest {
    private static final String RCP = "AWS::Organizations::Policy::RESOURCE_CONTROL_POLICY";
    private static final String CONTROL_ARN = "arn:aws:controlcatalog:::control/7mo7a2h2ebsq71l8k6uzr96ou";

    @Test
    void controlLookupAndFilteringUseAwsSdkContracts() {
        try (ControlCatalogClient client = TestFixtures.controlCatalogClient()) {
            var control = client.getControl(request -> request.controlArn(CONTROL_ARN));
            assertEquals(CONTROL_ARN, control.arn());
            assertEquals(RCP, control.implementation().type());
            assertEquals("CT.S3.PV.5", control.implementation().identifier());

            var page = client.listControls(request -> request
                    .maxResults(2)
                    .filter(ControlFilter.builder()
                            .implementations(ImplementationFilter.builder().types(RCP).build())
                            .governedProviders("AWS")
                            .build()));
            assertEquals(2, page.controls().size());
            assertTrue(page.controls().stream().allMatch(item -> RCP.equals(item.implementation().type())));
            assertTrue(page.controls().stream().allMatch(item -> item.governedProviders().contains("AWS")));
            assertFalse(page.nextToken().isBlank());

            var secondPage = client.listControls(request -> request
                    .maxResults(2)
                    .nextToken(page.nextToken())
                    .filter(ControlFilter.builder()
                            .implementations(ImplementationFilter.builder().types(RCP).build())
                            .governedProviders("AWS")
                            .build()));
            assertEquals(2, secondPage.controls().size());
            assertFalse(secondPage.nextToken().isBlank());

            var finalPage = client.listControls(request -> request
                    .maxResults(2)
                    .nextToken(secondPage.nextToken())
                    .filter(ControlFilter.builder()
                            .implementations(ImplementationFilter.builder().types(RCP).build())
                            .governedProviders("AWS")
                            .build()));
            assertEquals(1, finalPage.controls().size());
            assertNull(finalPage.nextToken());
            var arns = new HashSet<String>();
            page.controls().forEach(item -> arns.add(item.arn()));
            secondPage.controls().forEach(item -> arns.add(item.arn()));
            finalPage.controls().forEach(item -> arns.add(item.arn()));
            assertEquals(5, arns.size());

            assertThrows(ResourceNotFoundException.class, () -> client.getControl(request -> request
                    .controlArn("arn:aws:controlcatalog:::control/aaaaaaaaaaaaaaaaaaaaaaaaa")));
        }
    }
}

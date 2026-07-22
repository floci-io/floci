package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.ContinuousDeploymentPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontControllerTest {

    @Test
    void listDistributionsStopsAtTheExactEndOfASecondPage() {
        CloudFrontService service = mock(CloudFrontService.class);
        Distribution first = distribution("A");
        Distribution second = distribution("B");
        Distribution third = distribution("C");
        Distribution fourth = distribution("D");
        when(service.listDistributions(null, 3))
                .thenReturn(List.of(first, second, third));
        when(service.listDistributions("B", 3))
                .thenReturn(List.of(third, fourth));

        CloudFrontController controller = new CloudFrontController(service);

        try (Response firstPage = controller.listDistributions(null, 2);
             Response secondPage = controller.listDistributions("B", 2)) {
            String firstXml = (String) firstPage.getEntity();
            String secondXml = (String) secondPage.getEntity();

            assertTrue(firstXml.startsWith("<DistributionList "));
            assertEquals("true", XmlParser.extractFirst(firstXml, "IsTruncated", null));
            assertEquals("B", XmlParser.extractFirst(firstXml, "NextMarker", null));

            assertTrue(secondXml.startsWith("<DistributionList "));
            assertEquals("false", XmlParser.extractFirst(secondXml, "IsTruncated", null));
            assertTrue(XmlParser.extractAll(secondXml, "NextMarker").isEmpty());
            assertEquals(List.of("C", "D"), XmlParser.extractAll(secondXml, "Id"));
        }
    }

    @Test
    void listFunctionsHonorsMaxItemsAndMarker() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontFunction first = function("alpha");
        CloudFrontFunction second = function("beta");
        CloudFrontFunction third = function("gamma");
        CloudFrontFunction fourth = function("omega");
        when(service.getAccountId()).thenReturn("000000000000");
        when(service.listFunctions(null, null, 3))
                .thenReturn(List.of(first, second, third));
        when(service.listFunctions(null, "beta", 3))
                .thenReturn(List.of(third, fourth));

        CloudFrontController controller = new CloudFrontController(service);

        try (Response firstPage = controller.listFunctions(null, null, 2);
             Response secondPage = controller.listFunctions(null, "beta", 2)) {
            String firstXml = (String) firstPage.getEntity();
            String secondXml = (String) secondPage.getEntity();

            assertEquals("beta", XmlParser.extractFirst(firstXml, "NextMarker", null));
            assertEquals(List.of("alpha", "beta"), XmlParser.extractAll(firstXml, "Name"));

            assertTrue(XmlParser.extractAll(secondXml, "NextMarker").isEmpty());
            assertEquals(List.of("gamma", "omega"), XmlParser.extractAll(secondXml, "Name"));
        }
    }

    @Test
    void continuousDeploymentPolicyQuantityReportsTheAccountTotal() {
        CloudFrontService service = mock(CloudFrontService.class);
        ContinuousDeploymentPolicy first = continuousDeploymentPolicy("A");
        ContinuousDeploymentPolicy second = continuousDeploymentPolicy("B");
        when(service.listContinuousDeploymentPolicies(null, 2))
                .thenReturn(List.of(first, second));
        when(service.listContinuousDeploymentPolicies(null, Integer.MAX_VALUE))
                .thenReturn(List.of(first, second));
        when(service.listContinuousDeploymentPolicies("A", 2))
                .thenReturn(List.of(second));

        CloudFrontController controller = new CloudFrontController(service);

        try (Response firstPage = controller.listContinuousDeploymentPolicies(null, 1);
             Response secondPage = controller.listContinuousDeploymentPolicies("A", 1)) {
            String firstXml = (String) firstPage.getEntity();
            String secondXml = (String) secondPage.getEntity();

            assertEquals("2", XmlParser.extractFirst(firstXml, "Quantity", null));
            assertEquals("A", XmlParser.extractFirst(firstXml, "NextMarker", null));
            assertEquals("2", XmlParser.extractFirst(secondXml, "Quantity", null));
            assertTrue(XmlParser.extractAll(secondXml, "NextMarker").isEmpty());
        }
    }

    private static Distribution distribution(String id) {
        Distribution distribution = new Distribution();
        distribution.setId(id);
        return distribution;
    }

    private static CloudFrontFunction function(String name) {
        CloudFrontFunction function = new CloudFrontFunction();
        function.setName(name);
        return function;
    }

    private static ContinuousDeploymentPolicy continuousDeploymentPolicy(String id) {
        ContinuousDeploymentPolicy policy = new ContinuousDeploymentPolicy();
        policy.setId(id);
        return policy;
    }
}

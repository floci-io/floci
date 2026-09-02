package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.cloudfront.model.CloudFrontFunction;
import io.github.hectorvent.floci.services.cloudfront.model.ContinuousDeploymentPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        when(service.listDistributions(null, Integer.MAX_VALUE))
                .thenReturn(List.of(first, second, third, fourth));

        CloudFrontController controller = new CloudFrontController(service);

        try (Response firstPage = controller.listDistributions(null, 2);
             Response secondPage = controller.listDistributions("B", 2)) {
            String firstXml = (String) firstPage.getEntity();
            String secondXml = (String) secondPage.getEntity();

            assertTrue(firstXml.startsWith("<DistributionList "));
            assertEquals("true", XmlParser.extractFirst(firstXml, "IsTruncated", null));
            assertEquals("B", XmlParser.extractFirst(firstXml, "NextMarker", null));
            assertEquals("4", XmlParser.extractFirst(firstXml, "Quantity", null));

            assertTrue(secondXml.startsWith("<DistributionList "));
            assertEquals("false", XmlParser.extractFirst(secondXml, "IsTruncated", null));
            assertTrue(XmlParser.extractAll(secondXml, "NextMarker").isEmpty());
            assertEquals("4", XmlParser.extractFirst(secondXml, "Quantity", null));
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

    @Test
    void distributionConfigRoundTripsLambdaAndCloudFrontFunctionAssociations() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody("""
                <LambdaFunctionAssociations><Quantity>1</Quantity><Items><LambdaFunctionAssociation>
                  <LambdaFunctionARN>arn:aws:lambda:us-east-1:000000000000:function:edge-fn:1</LambdaFunctionARN>
                  <EventType>viewer-request</EventType>
                  <IncludeBody>false</IncludeBody>
                </LambdaFunctionAssociation></Items></LambdaFunctionAssociations>
                <FunctionAssociations><Quantity>1</Quantity><Items><FunctionAssociation>
                  <FunctionARN>arn:aws:cloudfront::000000000000:function/cf-fn</FunctionARN>
                  <EventType>viewer-response</EventType>
                </FunctionAssociation></Items></FunctionAssociations>
                """);

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-1");
            d.setEtag("etag-1");
            return d;
        });

        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }

        when(service.getDistribution("dist-1")).thenReturn(captor.getValue());

        try (Response cfg = controller.getDistributionConfig("dist-1")) {
            String xml = (String) cfg.getEntity();

            List<Map<String, String>> lambda =
                    XmlParser.extractGroups(xml, "LambdaFunctionAssociation");
            assertEquals(1, lambda.size());
            assertEquals("arn:aws:lambda:us-east-1:000000000000:function:edge-fn:1",
                    lambda.get(0).get("LambdaFunctionARN"));
            assertEquals("viewer-request", lambda.get(0).get("EventType"));
            assertEquals("false", lambda.get(0).get("IncludeBody"));

            List<Map<String, String>> fn =
                    XmlParser.extractGroups(xml, "FunctionAssociation");
            assertEquals(1, fn.size());
            assertEquals("arn:aws:cloudfront::000000000000:function/cf-fn",
                    fn.get(0).get("FunctionARN"));
            assertEquals("viewer-response", fn.get(0).get("EventType"));
        }
    }

    @Test
    void orderedCacheBehaviorRoundTripsLambdaFunctionAssociations() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody("").replace("</DefaultCacheBehavior>", """
                </DefaultCacheBehavior>
                <CacheBehaviors><Quantity>1</Quantity><Items><CacheBehavior>
                  <PathPattern>/api/*</PathPattern>
                  <TargetOriginId>o1</TargetOriginId>
                  <ViewerProtocolPolicy>https-only</ViewerProtocolPolicy>
                  <AllowedMethods><Quantity>2</Quantity><Items>
                    <Method>GET</Method><Method>HEAD</Method></Items></AllowedMethods>
                  <LambdaFunctionAssociations><Quantity>1</Quantity><Items><LambdaFunctionAssociation>
                    <LambdaFunctionARN>arn:aws:lambda:us-east-1:000000000000:function:api-fn:7</LambdaFunctionARN>
                    <EventType>origin-request</EventType>
                    <IncludeBody>true</IncludeBody>
                  </LambdaFunctionAssociation></Items></LambdaFunctionAssociations>
                </CacheBehavior></Items></CacheBehaviors>
                """);

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-2");
            d.setEtag("etag-2");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-2")).thenReturn(captor.getValue());

        try (Response cfg = controller.getDistributionConfig("dist-2")) {
            String xml = (String) cfg.getEntity();
            List<Map<String, String>> lambda =
                    XmlParser.extractGroups(xml, "LambdaFunctionAssociation");
            assertEquals(1, lambda.size());
            assertEquals("origin-request", lambda.get(0).get("EventType"));
            assertEquals("true", lambda.get(0).get("IncludeBody"));
        }
    }

    @Test
    void invalidLambdaFunctionAssociationEventTypeIsRejected() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody("""
                <LambdaFunctionAssociations><Quantity>1</Quantity><Items><LambdaFunctionAssociation>
                  <LambdaFunctionARN>arn:aws:lambda:us-east-1:000000000000:function:edge-fn:1</LambdaFunctionARN>
                  <EventType>not-an-event</EventType>
                </LambdaFunctionAssociation></Items></LambdaFunctionAssociations>
                """);

        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(400, created.getStatus());
            assertTrue(((String) created.getEntity()).contains("InvalidArgument"));
        }
    }

    @Test
    void lambdaFunctionAssociationsQuantityMismatchIsRejected() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody("""
                <LambdaFunctionAssociations><Quantity>2</Quantity><Items><LambdaFunctionAssociation>
                  <LambdaFunctionARN>arn:aws:lambda:us-east-1:000000000000:function:edge-fn:1</LambdaFunctionARN>
                  <EventType>viewer-request</EventType>
                </LambdaFunctionAssociation></Items></LambdaFunctionAssociations>
                """);

        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(400, created.getStatus());
            assertTrue(((String) created.getEntity()).contains("InconsistentQuantities"));
        }
    }

    private static String distributionConfigBody(String defaultCacheBehaviorExtra) {
        return """
                <DistributionConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <CallerReference>ref-1</CallerReference>
                  <Enabled>true</Enabled>
                  <Comment>probe</Comment>
                  <Origins><Quantity>1</Quantity><Items><Origin>
                    <Id>o1</Id><DomainName>example.com</DomainName>
                    <CustomOriginConfig><HTTPPort>80</HTTPPort><HTTPSPort>443</HTTPSPort>
                      <OriginProtocolPolicy>https-only</OriginProtocolPolicy></CustomOriginConfig>
                  </Origin></Items></Origins>
                  <DefaultCacheBehavior>
                    <TargetOriginId>o1</TargetOriginId>
                    <ViewerProtocolPolicy>redirect-to-https</ViewerProtocolPolicy>
                    <AllowedMethods><Quantity>2</Quantity><Items>
                      <Method>GET</Method><Method>HEAD</Method></Items></AllowedMethods>
                    %s
                  </DefaultCacheBehavior>
                </DistributionConfig>
                """.formatted(defaultCacheBehaviorExtra);
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

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void defaultCacheBehaviorEchoesTrustedSignersSoTheAwsProviderDoesNotSegfault() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody("");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-ts");
            d.setEtag("etag-ts");
            return d;
        });

        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }

        when(service.getDistribution("dist-ts")).thenReturn(captor.getValue());

        // The Terraform AWS provider reads DefaultCacheBehavior.TrustedSigners.Items with no nil
        // guard, so an omitted object segfaults it on read-back. AWS always echoes the disabled form.
        try (Response dist = controller.getDistribution("dist-ts")) {
            String xml = (String) dist.getEntity();
            int ts = xml.indexOf("<TrustedSigners>");
            assertTrue(ts >= 0, "DefaultCacheBehavior must echo a TrustedSigners object");
            String block = xml.substring(ts, xml.indexOf("</TrustedSigners>", ts));
            assertTrue(block.contains("<Enabled>false</Enabled>"), "TrustedSigners.Enabled=false");
            assertTrue(block.contains("<Quantity>0</Quantity>"), "TrustedSigners.Quantity=0");
        }
    }

    @Test
    void defaultCacheBehaviorEchoesCachedMethodsWithoutDuplicatingAllowedMethods() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        // AWS nests CachedMethods inside AllowedMethods. A parser that doesn't scope Method
        // elements to the right block folds the CachedMethods items into AllowedMethods too,
        // producing duplicates (["GET","HEAD","GET","HEAD","OPTIONS"]) and dropping CachedMethods.
        String body = distributionConfigBody("").replace(
                """
                <AllowedMethods><Quantity>2</Quantity><Items>
                      <Method>GET</Method><Method>HEAD</Method></Items></AllowedMethods>""",
                """
                <AllowedMethods><Quantity>3</Quantity><Items>
                      <Method>GET</Method><Method>HEAD</Method><Method>OPTIONS</Method></Items>
                      <CachedMethods><Quantity>2</Quantity><Items>
                        <Method>GET</Method><Method>HEAD</Method></Items></CachedMethods>
                    </AllowedMethods>""");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-cm");
            d.setEtag("etag-cm");
            return d;
        });

        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }

        when(service.getDistribution("dist-cm")).thenReturn(captor.getValue());

        try (Response dist = controller.getDistribution("dist-cm")) {
            String xml = (String) dist.getEntity();
            int start = xml.indexOf("<AllowedMethods>");
            int end = xml.indexOf("</AllowedMethods>") + "</AllowedMethods>".length();
            String block = xml.substring(start, end);

            int cachedStart = block.indexOf("<CachedMethods>");
            assertTrue(cachedStart >= 0, "AllowedMethods must echo a nested CachedMethods object");
            String outer = block.substring(0, cachedStart);
            String cached = block.substring(cachedStart);

            assertEquals(List.of("GET", "HEAD", "OPTIONS"), XmlParser.extractAll(outer, "Method"),
                    "AllowedMethods.Items must not include CachedMethods entries");
            assertEquals(List.of("GET", "HEAD"), XmlParser.extractAll(cached, "Method"));
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
    @Test
    void customOriginConfigEchoesOriginSslProtocolsSoTheAwsProviderDoesNotSegfault() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        // A custom origin whose request omits OriginSslProtocols, as the aws provider sends for a
        // minimal distribution. The provider flattens CustomOriginConfig.OriginSslProtocols.Items with
        // no nil guard, so an omitted object segfaults it on read-back. AWS always echoes the object.
        String body = distributionConfigBody("");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-ssl");
            d.setEtag("etag-ssl");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-ssl")).thenReturn(captor.getValue());

        try (Response dist = controller.getDistribution("dist-ssl")) {
            String xml = (String) dist.getEntity();
            int start = xml.indexOf("<OriginSslProtocols>");
            assertTrue(start >= 0, "CustomOriginConfig must echo an OriginSslProtocols object");
            String block = xml.substring(start, xml.indexOf("</OriginSslProtocols>", start));
            assertTrue(block.contains("<SslProtocol>"),
                    "OriginSslProtocols must list at least one protocol");
        }
    }

    @Test
    void customOriginConfigRoundTripsSubmittedOriginSslProtocols() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody("").replace(
                "<OriginProtocolPolicy>https-only</OriginProtocolPolicy></CustomOriginConfig>",
                "<OriginProtocolPolicy>https-only</OriginProtocolPolicy>"
                        + "<OriginSslProtocols><Quantity>2</Quantity><Items>"
                        + "<SslProtocol>TLSv1.1</SslProtocol><SslProtocol>TLSv1.2</SslProtocol>"
                        + "</Items></OriginSslProtocols></CustomOriginConfig>");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-ssl2");
            d.setEtag("etag-ssl2");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-ssl2")).thenReturn(captor.getValue());

        try (Response cfg = controller.getDistributionConfig("dist-ssl2")) {
            String xml = (String) cfg.getEntity();
            int start = xml.indexOf("<OriginSslProtocols>");
            assertTrue(start >= 0);
            String block = xml.substring(start, xml.indexOf("</OriginSslProtocols>", start));
            assertTrue(block.contains("<SslProtocol>TLSv1.1</SslProtocol>"), "echoes TLSv1.1");
            assertTrue(block.contains("<SslProtocol>TLSv1.2</SslProtocol>"), "echoes TLSv1.2");
        }
    }

    @Test
    void defaultCacheBehaviorRoundTripsForwardedValues() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody(
                "<ForwardedValues><QueryString>true</QueryString>"
                        + "<Cookies><Forward>all</Forward></Cookies></ForwardedValues>");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-fv");
            d.setEtag("etag-fv");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-fv")).thenReturn(captor.getValue());

        try (Response dist = controller.getDistribution("dist-fv")) {
            String xml = (String) dist.getEntity();
            int start = xml.indexOf("<ForwardedValues>");
            assertTrue(start >= 0, "DefaultCacheBehavior must echo a ForwardedValues object");
            String block = xml.substring(start, xml.indexOf("</ForwardedValues>", start));
            assertTrue(block.contains("<QueryString>true</QueryString>"), "echoes QueryString");
            assertTrue(block.contains("<Forward>all</Forward>"), "echoes Cookies.Forward");
        }
    }

    @Test
    void defaultCacheBehaviorRoundTripsAnExplicitZeroDefaultTtl() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        // default_ttl = 0 with forwarded_values is the usual "do not cache". Treating 0 as "unset"
        // drops it from the read-back, so the provider sees the attribute disappear and diffs forever.
        String body = distributionConfigBody(
                "<MinTTL>0</MinTTL><DefaultTTL>0</DefaultTTL><MaxTTL>0</MaxTTL>"
                        + "<ForwardedValues><QueryString>false</QueryString>"
                        + "<Cookies><Forward>none</Forward></Cookies></ForwardedValues>");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-ttl0");
            d.setEtag("etag-ttl0");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-ttl0")).thenReturn(captor.getValue());

        try (Response dist = controller.getDistribution("dist-ttl0")) {
            String xml = (String) dist.getEntity();
            assertTrue(xml.contains("<MinTTL>0</MinTTL>"), "echoes an explicit MinTTL of 0");
            assertTrue(xml.contains("<DefaultTTL>0</DefaultTTL>"), "echoes an explicit DefaultTTL of 0");
            assertTrue(xml.contains("<MaxTTL>0</MaxTTL>"), "echoes an explicit MaxTTL of 0");
        }
    }

    @Test
    void defaultCacheBehaviorOmitsTtlsTheClientNeverSubmitted() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        String body = distributionConfigBody(
                "<ForwardedValues><QueryString>false</QueryString>"
                        + "<Cookies><Forward>none</Forward></Cookies></ForwardedValues>");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-ttlabsent");
            d.setEtag("etag-ttlabsent");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-ttlabsent")).thenReturn(captor.getValue());

        try (Response dist = controller.getDistribution("dist-ttlabsent")) {
            String xml = (String) dist.getEntity();
            assertFalse(xml.contains("<DefaultTTL>"), "an unsubmitted DefaultTTL stays absent");
            assertFalse(xml.contains("<MaxTTL>"), "an unsubmitted MaxTTL stays absent");
        }
    }

    @Test
    void forwardedValuesRoundTripsHeadersQueryStringCacheKeysAndWhitelistedCookies() {
        CloudFrontService service = mock(CloudFrontService.class);
        CloudFrontController controller = new CloudFrontController(service);

        // Headers, QueryStringCacheKeys and Cookies.WhitelistedNames are Quantity/Items pairs. Echoing
        // a bare Quantity 0 loses them, so the provider reads the lists back empty and diffs each plan.
        String body = distributionConfigBody(
                "<ForwardedValues><QueryString>true</QueryString>"
                        + "<Cookies><Forward>whitelist</Forward>"
                        + "<WhitelistedNames><Quantity>2</Quantity><Items>"
                        + "<Name>session</Name><Name>tracking</Name></Items></WhitelistedNames>"
                        + "</Cookies>"
                        + "<Headers><Quantity>2</Quantity><Items>"
                        + "<Name>Origin</Name><Name>Accept</Name></Items></Headers>"
                        + "<QueryStringCacheKeys><Quantity>1</Quantity><Items>"
                        + "<Name>page</Name></Items></QueryStringCacheKeys>"
                        + "</ForwardedValues>");

        ArgumentCaptor<Distribution> captor = ArgumentCaptor.forClass(Distribution.class);
        when(service.createDistribution(captor.capture(), any())).thenAnswer(inv -> {
            Distribution d = inv.getArgument(0);
            d.setId("dist-fvnames");
            d.setEtag("etag-fvnames");
            return d;
        });
        try (Response created = controller.createDistribution(null, body)) {
            assertEquals(201, created.getStatus());
        }
        when(service.getDistribution("dist-fvnames")).thenReturn(captor.getValue());

        try (Response dist = controller.getDistribution("dist-fvnames")) {
            String xml = (String) dist.getEntity();
            int start = xml.indexOf("<ForwardedValues>");
            assertTrue(start >= 0);
            String block = xml.substring(start, xml.indexOf("</ForwardedValues>", start));

            String cookies = block.substring(
                    block.indexOf("<Cookies>"), block.indexOf("</Cookies>"));
            assertEquals(List.of("session", "tracking"), XmlParser.extractAll(cookies, "Name"),
                    "whitelisted cookie names round-trip inside Cookies");

            String headers = block.substring(
                    block.indexOf("<Headers>"), block.indexOf("</Headers>"));
            assertEquals(List.of("Origin", "Accept"), XmlParser.extractAll(headers, "Name"),
                    "forwarded headers round-trip");
            assertTrue(headers.contains("<Quantity>2</Quantity>"), "Headers.Quantity matches");

            String keys = block.substring(
                    block.indexOf("<QueryStringCacheKeys>"), block.indexOf("</QueryStringCacheKeys>"));
            assertEquals(List.of("page"), XmlParser.extractAll(keys, "Name"),
                    "query string cache keys round-trip");
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

package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontPolicyConfigCodecTest {

    @Test
    void cachePolicyConfigRoundTripsAllTtlsAndCacheKeyMembers() {
        String body = """
                <CachePolicyConfig>
                  <Name>complete-cache-policy</Name>
                  <Comment>all fields</Comment>
                  <MinTTL>10</MinTTL>
                  <DefaultTTL>3600</DefaultTTL>
                  <MaxTTL>86400</MaxTTL>
                  <ParametersInCacheKeyAndForwardedToOrigin>
                    <EnableAcceptEncodingGzip>true</EnableAcceptEncodingGzip>
                    <EnableAcceptEncodingBrotli>true</EnableAcceptEncodingBrotli>
                    <HeadersConfig>
                      <HeaderBehavior>whitelist</HeaderBehavior>
                      <Headers><Quantity>2</Quantity><Items>
                        <Name>Accept-Language</Name><Name>CloudFront-Viewer-Country</Name>
                      </Items></Headers>
                    </HeadersConfig>
                    <CookiesConfig>
                      <CookieBehavior>whitelist</CookieBehavior>
                      <Cookies><Quantity>2</Quantity><Items>
                        <Name>session</Name><Name>locale</Name>
                      </Items></Cookies>
                    </CookiesConfig>
                    <QueryStringsConfig>
                      <QueryStringBehavior>whitelist</QueryStringBehavior>
                      <QueryStrings><Quantity>2</Quantity><Items>
                        <Name>page</Name><Name>sort</Name>
                      </Items></QueryStrings>
                    </QueryStringsConfig>
                  </ParametersInCacheKeyAndForwardedToOrigin>
                </CachePolicyConfig>
                """;

        Map<String, Object> config = CloudFrontPolicyConfigCodec.parseCachePolicy(body);
        assertEquals(Map.of(
                "MinTTL", "10",
                "DefaultTTL", "3600",
                "MaxTTL", "86400",
                "ParametersInCacheKeyAndForwardedToOrigin", Map.of(
                        "EnableAcceptEncodingGzip", "true",
                        "EnableAcceptEncodingBrotli", "true",
                        "HeadersConfig", Map.of(
                                "HeaderBehavior", "whitelist",
                                "Headers", List.of("Accept-Language", "CloudFront-Viewer-Country")),
                        "CookiesConfig", Map.of(
                                "CookieBehavior", "whitelist",
                                "Cookies", List.of("session", "locale")),
                        "QueryStringsConfig", Map.of(
                                "QueryStringBehavior", "whitelist",
                                "QueryStrings", List.of("page", "sort")))),
                config);

        XmlBuilder xml = new XmlBuilder().start("CachePolicyConfig");
        CloudFrontPolicyConfigCodec.serializeCachePolicy(xml, config);
        Map<String, Object> reparsed = CloudFrontPolicyConfigCodec.parseCachePolicy(
                xml.end("CachePolicyConfig").build());
        assertEquals(config, reparsed);
    }

    @Test
    void originRequestPolicyConfigRoundTripsAllForwardedMembers() {
        String body = """
                <OriginRequestPolicyConfig>
                  <Name>complete-origin-policy</Name>
                  <HeadersConfig>
                    <HeaderBehavior>whitelist</HeaderBehavior>
                    <Headers><Quantity>1</Quantity><Items><Name>Origin</Name></Items></Headers>
                  </HeadersConfig>
                  <CookiesConfig>
                    <CookieBehavior>whitelist</CookieBehavior>
                    <Cookies><Quantity>1</Quantity><Items><Name>session</Name></Items></Cookies>
                  </CookiesConfig>
                  <QueryStringsConfig>
                    <QueryStringBehavior>whitelist</QueryStringBehavior>
                    <QueryStrings><Quantity>1</Quantity><Items><Name>page</Name></Items></QueryStrings>
                  </QueryStringsConfig>
                </OriginRequestPolicyConfig>
                """;

        Map<String, Object> config = CloudFrontPolicyConfigCodec.parseOriginRequestPolicy(body);
        assertEquals(Map.of(
                "HeadersConfig", Map.of(
                        "HeaderBehavior", "whitelist", "Headers", List.of("Origin")),
                "CookiesConfig", Map.of(
                        "CookieBehavior", "whitelist", "Cookies", List.of("session")),
                "QueryStringsConfig", Map.of(
                        "QueryStringBehavior", "whitelist", "QueryStrings", List.of("page"))),
                config);

        XmlBuilder xml = new XmlBuilder().start("OriginRequestPolicyConfig");
        CloudFrontPolicyConfigCodec.serializeOriginRequestPolicy(xml, config);
        Map<String, Object> reparsed = CloudFrontPolicyConfigCodec.parseOriginRequestPolicy(
                xml.end("OriginRequestPolicyConfig").build());
        assertEquals(config, reparsed);
    }

    @Test
    void cachePolicyConfigAppliesAwsTtlDefaults() {
        Map<String, Object> standard = CloudFrontPolicyConfigCodec.parseCachePolicy("""
                <CachePolicyConfig><MinTTL>10</MinTTL></CachePolicyConfig>
                """);
        assertEquals("86400", standard.get("DefaultTTL"));
        assertEquals("31536000", standard.get("MaxTTL"));

        Map<String, Object> raisedMinimum = CloudFrontPolicyConfigCodec.parseCachePolicy("""
                <CachePolicyConfig><MinTTL>40000000</MinTTL></CachePolicyConfig>
                """);
        assertEquals("40000000", raisedMinimum.get("DefaultTTL"));
        assertEquals("40000000", raisedMinimum.get("MaxTTL"));

        Map<String, Object> raisedDefault = CloudFrontPolicyConfigCodec.parseCachePolicy("""
                <CachePolicyConfig><MinTTL>0</MinTTL><DefaultTTL>40000000</DefaultTTL></CachePolicyConfig>
                """);
        assertEquals("40000000", raisedDefault.get("MaxTTL"));
    }

    @Test
    void selectionParsingAcceptsOnlyNamesAndPreservesEmptyShapes() {
        String body = """
                <OriginRequestPolicyConfig>
                  <HeadersConfig><HeaderBehavior>none</HeaderBehavior></HeadersConfig>
                  <CookiesConfig><CookieBehavior>whitelist</CookieBehavior>
                    <Cookies><Quantity>0</Quantity></Cookies>
                  </CookiesConfig>
                  <QueryStringsConfig><QueryStringBehavior>whitelist</QueryStringBehavior>
                    <QueryStrings><Quantity>1</Quantity><Items>
                      <Ignored>not-a-name</Ignored><Name>page</Name>
                    </Items></QueryStrings>
                  </QueryStringsConfig>
                </OriginRequestPolicyConfig>
                """;

        Map<String, Object> config = CloudFrontPolicyConfigCodec.parseOriginRequestPolicy(body);
        assertEquals(Map.of("HeaderBehavior", "none"), config.get("HeadersConfig"));
        assertEquals(Map.of("CookieBehavior", "whitelist", "Cookies", List.of()),
                config.get("CookiesConfig"));
        assertEquals(Map.of("QueryStringBehavior", "whitelist", "QueryStrings", List.of("page")),
                config.get("QueryStringsConfig"));

        XmlBuilder xml = new XmlBuilder().start("OriginRequestPolicyConfig");
        CloudFrontPolicyConfigCodec.serializeOriginRequestPolicy(xml, config);
        String serialized = xml.end("OriginRequestPolicyConfig").build();
        assertTrue(serialized.contains("<HeaderBehavior>none</HeaderBehavior>"));
        assertFalse(serialized.contains("<Headers>"));
        assertTrue(serialized.contains("<Cookies><Quantity>0</Quantity></Cookies>"));
        assertFalse(serialized.contains("<Cookies><Quantity>0</Quantity><Items>"));
    }
}

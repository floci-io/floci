package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link ResponseHeadersPolicyConfigCodec}: a full response-headers policy config
 * (security headers, CORS, custom headers, header removal) is parsed into the header directives the
 * serving path applies, and survives a serialize round-trip.
 */
class ResponseHeadersPolicyConfigCodecTest {

    private static final String XML = """
            <ResponseHeadersPolicyConfig>
              <Name>sec</Name>
              <Comment>full config</Comment>
              <SecurityHeadersConfig>
                <XSSProtection><Override>true</Override><Protection>true</Protection><ModeBlock>true</ModeBlock></XSSProtection>
                <FrameOptions><Override>true</Override><FrameOption>DENY</FrameOption></FrameOptions>
                <ReferrerPolicy><Override>true</Override><ReferrerPolicy>same-origin</ReferrerPolicy></ReferrerPolicy>
                <ContentSecurityPolicy><Override>false</Override><ContentSecurityPolicy>default-src 'self'</ContentSecurityPolicy></ContentSecurityPolicy>
                <ContentTypeOptions><Override>true</Override></ContentTypeOptions>
                <StrictTransportSecurity><Override>true</Override><IncludeSubdomains>true</IncludeSubdomains><Preload>false</Preload><AccessControlMaxAgeSec>31536000</AccessControlMaxAgeSec></StrictTransportSecurity>
              </SecurityHeadersConfig>
              <CorsConfig>
                <AccessControlAllowOrigins><Quantity>1</Quantity><Items><Origin>*</Origin></Items></AccessControlAllowOrigins>
                <AccessControlAllowMethods><Quantity>2</Quantity><Items><Method>GET</Method><Method>HEAD</Method></Items></AccessControlAllowMethods>
                <AccessControlExposeHeaders><Quantity>1</Quantity><Items><Header>X-App</Header></Items></AccessControlExposeHeaders>
                <AccessControlAllowCredentials>false</AccessControlAllowCredentials>
                <AccessControlMaxAgeSec>600</AccessControlMaxAgeSec>
                <OriginOverride>true</OriginOverride>
              </CorsConfig>
              <CustomHeadersConfig>
                <Quantity>1</Quantity>
                <Items>
                  <ResponseHeadersPolicyCustomHeader><Header>X-App</Header><Value>floci</Value><Override>true</Override></ResponseHeadersPolicyCustomHeader>
                </Items>
              </CustomHeadersConfig>
              <RemoveHeadersConfig>
                <Quantity>1</Quantity>
                <Items><ResponseHeadersPolicyRemoveHeader><Header>Server</Header></ResponseHeadersPolicyRemoveHeader></Items>
              </RemoveHeadersConfig>
            </ResponseHeadersPolicyConfig>
            """;

    @Test
    void parsesConfigIntoResponseHeaderDirectives() {
        Map<String, String> headers = headers(ResponseHeadersPolicyConfigCodec.parse(XML));

        assertEquals("max-age=31536000; includeSubDomains", headers.get("Strict-Transport-Security"));
        assertEquals("nosniff", headers.get("X-Content-Type-Options"));
        assertEquals("DENY", headers.get("X-Frame-Options"));
        assertEquals("same-origin", headers.get("Referrer-Policy"));
        assertEquals("default-src 'self'", headers.get("Content-Security-Policy"));
        assertEquals("1; mode=block", headers.get("X-XSS-Protection"));
        assertEquals("floci", headers.get("X-App"));
        assertEquals("*", headers.get("Access-Control-Allow-Origin"));
        assertEquals("X-App", headers.get("Access-Control-Expose-Headers"));
        assertFalse(headers.containsKey("Access-Control-Allow-Methods"));
        assertFalse(headers.containsKey("Access-Control-Max-Age"));
        // AllowCredentials=false is omitted, matching CloudFront (the header is only sent when true).
        assertFalse(headers.containsKey("Access-Control-Allow-Credentials"));
    }

    @Test
    void collidingMaxAgeFieldsStayInTheirOwnBlocks() {
        // AccessControlMaxAgeSec appears under both StrictTransportSecurity and CorsConfig; a flat
        // parse would conflate them. Structure-aware parsing keeps HSTS at 31536000 and CORS at 600.
        Map<String, String> headers = headers(
                ResponseHeadersPolicyConfigCodec.parse(XML), "https://a.example", true);
        assertTrue(headers.get("Strict-Transport-Security").contains("31536000"));
        assertEquals("600", headers.get("Access-Control-Max-Age"));
    }

    @Test
    void removeHeadersConfigIsCaptured() {
        var directives = ResponseHeadersPolicyConfigCodec.directives(
                ResponseHeadersPolicyConfigCodec.parse(XML));
        assertEquals(List.of("Server"), directives.remove());
    }

    @Test
    void allowOriginIsASingleValueNotACommaJoinedList() {
        // Access-Control-Allow-Origin must be a single origin (or "*"); joining multiple configured
        // origins with a comma would be an invalid header value.
        String xml = """
                <ResponseHeadersPolicyConfig><Name>multi</Name><CorsConfig>
                  <AccessControlAllowOrigins><Quantity>2</Quantity><Items>
                    <Origin>https://a.example</Origin><Origin>https://b.example</Origin>
                  </Items></AccessControlAllowOrigins>
                  <OriginOverride>true</OriginOverride>
                </CorsConfig></ResponseHeadersPolicyConfig>
                """;
        String value = headers(ResponseHeadersPolicyConfigCodec.parse(xml)).get("Access-Control-Allow-Origin");
        assertEquals("https://a.example", value);
        assertFalse(value.contains(","), value);
    }

    @Test
    void corsHeadersEchoTheMatchingViewerOriginAndOmitUnmatchedRequests() {
        Map<String, Object> config = ResponseHeadersPolicyConfigCodec.parse("""
                <ResponseHeadersPolicyConfig><CorsConfig>
                  <AccessControlAllowOrigins><Quantity>2</Quantity><Items>
                    <Origin>https://*.example.org</Origin><Origin>https://exact.test:8443</Origin>
                  </Items></AccessControlAllowOrigins>
                  <AccessControlAllowMethods><Quantity>1</Quantity><Items><Method>GET</Method></Items></AccessControlAllowMethods>
                  <OriginOverride>true</OriginOverride>
                </CorsConfig></ResponseHeadersPolicyConfig>
                """);

        Map<String, String> matching = headers(config, "https://api.example.org", true);
        assertEquals("https://api.example.org", matching.get("Access-Control-Allow-Origin"));
        assertEquals("GET", matching.get("Access-Control-Allow-Methods"));

        Map<String, String> unmatched = headers(config, "https://attacker.example.com");
        assertFalse(unmatched.containsKey("Access-Control-Allow-Origin"));
        assertFalse(unmatched.containsKey("Access-Control-Allow-Methods"));
        assertTrue(headers(config, null).isEmpty());
    }

    @Test
    void preflightResponsesIncludeAllowListsAndMaxAge() {
        Map<String, String> headers = headers(
                ResponseHeadersPolicyConfigCodec.parse(XML), "https://a.example", true);

        assertEquals("GET, HEAD", headers.get("Access-Control-Allow-Methods"));
        assertEquals("600", headers.get("Access-Control-Max-Age"));
    }

    @Test
    void preflightExpandsAllMethodsAndPreservesAuthorizationAlongsideWildcard() {
        Map<String, Object> cors = new LinkedHashMap<>();
        cors.put("AccessControlAllowOrigins", List.of("*"));
        cors.put("AccessControlAllowMethods", List.of("ALL"));
        cors.put("AccessControlAllowHeaders", List.of("*", "Authorization"));
        cors.put("AccessControlAllowCredentials", "false");
        cors.put("OriginOverride", "true");

        Map<String, String> result = headers(
                Map.of("CorsConfig", cors), "https://viewer.example", true);

        assertEquals("DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT",
                result.get("Access-Control-Allow-Methods"));
        assertEquals("*, Authorization", result.get("Access-Control-Allow-Headers"));
    }

    @Test
    void malformedOrMissingPolicyConfigIsRejected() {
        AwsException malformed = assertThrows(AwsException.class,
                () -> ResponseHeadersPolicyConfigCodec.parse(
                        "<ResponseHeadersPolicyConfig><Name>broken</ResponseHeadersPolicyConfig>"));
        assertEquals("InvalidArgument", malformed.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> ResponseHeadersPolicyConfigCodec.parse("<OtherConfig/>"));
        assertEquals("InvalidArgument", missing.getErrorCode());
    }

    @Test
    void rejectsMissingAndInconsistentListQuantities() {
        AwsException inconsistent = assertThrows(AwsException.class,
                () -> ResponseHeadersPolicyConfigCodec.parse("""
                        <ResponseHeadersPolicyConfig><CorsConfig>
                          <AccessControlAllowOrigins><Quantity>2</Quantity><Items>
                            <Origin>*</Origin>
                          </Items></AccessControlAllowOrigins>
                        </CorsConfig></ResponseHeadersPolicyConfig>
                        """));
        assertEquals("InconsistentQuantities", inconsistent.getErrorCode());

        AwsException missing = assertThrows(AwsException.class,
                () -> ResponseHeadersPolicyConfigCodec.parse("""
                        <ResponseHeadersPolicyConfig><RemoveHeadersConfig>
                          <Items><ResponseHeadersPolicyRemoveHeader>
                            <Header>Server</Header>
                          </ResponseHeadersPolicyRemoveHeader></Items>
                        </RemoveHeadersConfig></ResponseHeadersPolicyConfig>
                        """));
        assertEquals("InvalidArgument", missing.getErrorCode());
    }

    @Test
    void originWildcardMatchingFollowsCloudFrontSchemeHostAndPortRules() {
        assertTrue(ResponseHeadersPolicyConfigCodec.originMatches(
                "http://*.example.org", "http://www.example.org"));
        assertFalse(ResponseHeadersPolicyConfigCodec.originMatches(
                "http://*.example.org", "https://www.example.org"));
        assertFalse(ResponseHeadersPolicyConfigCodec.originMatches(
                "http://*.example.org", "http://www.example.org:123"));
        assertTrue(ResponseHeadersPolicyConfigCodec.originMatches(
                "example.org", "https://example.org"));
        assertTrue(ResponseHeadersPolicyConfigCodec.originMatches(
                "http://example.org:*", "http://example.org"));
        assertTrue(ResponseHeadersPolicyConfigCodec.originMatches(
                "http://example.org:1*3", "http://example.org:1893"));
    }

    @Test
    void serializedConfigRoundTripsToTheSameHeaders() {
        Map<String, Object> config = ResponseHeadersPolicyConfigCodec.parse(XML);
        XmlBuilder xml = new XmlBuilder().start("ResponseHeadersPolicyConfig");
        ResponseHeadersPolicyConfigCodec.serialize(xml, config);
        String out = xml.end("ResponseHeadersPolicyConfig").build();

        assertTrue(out.contains("<FrameOption>DENY</FrameOption>"), out);
        assertTrue(out.contains("<ResponseHeadersPolicyCustomHeader>"), out);
        assertTrue(out.contains("<Origin>*</Origin>"), out);

        assertEquals(headers(config), headers(ResponseHeadersPolicyConfigCodec.parse(out)));
    }

    private static Map<String, String> headers(Map<String, Object> config) {
        return headers(config, "https://a.example");
    }

    private static Map<String, String> headers(Map<String, Object> config, String viewerOrigin) {
        return headers(config, viewerOrigin, false);
    }

    private static Map<String, String> headers(Map<String, Object> config, String viewerOrigin,
                                               boolean preflightRequest) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ResponseHeadersPolicyConfigCodec.PolicyHeader h
                : ResponseHeadersPolicyConfigCodec.directives(
                        config, viewerOrigin, preflightRequest).add()) {
            map.put(h.name(), h.value());
        }
        return map;
    }
}

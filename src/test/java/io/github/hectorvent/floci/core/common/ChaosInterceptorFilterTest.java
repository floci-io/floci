package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.core.common.ChaosInterceptorFilter.ErrorShape;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChaosInterceptorFilter}'s protocol-shape resolution and fault response
 * builders. These are the behaviours PR review flagged: a presigned or s3express S3 request must
 * be shaped as S3 XML, and each injected fault must carry the code, status and content type the
 * caller's SDK can parse. No Quarkus context is needed: the methods are static and the request
 * context is mocked.
 */
class ChaosInterceptorFilterTest {

    // ---- resolveShape (finding #3) ----

    @Test
    void queryClaimResolvesToQueryXml() {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.claim(new ProtocolClaim(WireProtocol.AWS_QUERY, null, null, null));
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.QUERY_XML));
    }

    @Test
    void jsonClaimResolvesToJson() {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.claim(new ProtocolClaim(WireProtocol.AWS_JSON_1_1, descriptor("dynamodb"), "PutItem", null));
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.JSON));
    }

    @Test
    void cborClaimResolvesToCbor() {
        // Maintainer #2: CloudWatch (and other smithy-rpc-v2-cbor services) cannot parse a JSON
        // fault body, so a CBOR-protocol claim must resolve to the CBOR shape.
        ContainerRequestContextStub rpcv2 = new ContainerRequestContextStub();
        rpcv2.claim(new ProtocolClaim(WireProtocol.RPCV2_CBOR, descriptor("cloudwatch"), "PutMetricData", null));
        assertThat(ChaosInterceptorFilter.resolveShape(rpcv2.ctx()), equalTo(ErrorShape.CBOR));

        ContainerRequestContextStub target = new ContainerRequestContextStub();
        target.claim(new ProtocolClaim(WireProtocol.AWS_CBOR_TARGET, descriptor("cloudwatch"), "PutMetricData", null));
        assertThat(ChaosInterceptorFilter.resolveShape(target.ctx()), equalTo(ErrorShape.CBOR));
    }

    @Test
    void route53ClaimResolvesToRestXml() {
        // Maintainer #2: Route 53 speaks rest-xml; a JSON fault body is unparseable by its SDK.
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.claim(new ProtocolClaim(WireProtocol.AWS_JSON_1_1, descriptor("route53"), "ListHostedZones", null));
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.REST_XML));
    }

    @Test
    void s3AuthorizationHeaderResolvesToS3Xml() {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.authorization("AWS4-HMAC-SHA256 Credential=AKID/20260928/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host, Signature=abc");
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.S3_XML));
    }

    @Test
    void presignedS3ResolvesToS3Xml() {
        // A presigned request carries no Authorization header; its credential scope is in the
        // X-Amz-Credential query parameter. Finding #3: this must still be recognised as S3.
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.query("X-Amz-Credential", "AKID/20260928/us-east-1/s3/aws4_request");
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.S3_XML));
    }

    @Test
    void s3expressScopeResolvesToS3Xml() {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.authorization("AWS4-HMAC-SHA256 Credential=AKID/20260928/us-east-1/s3express/aws4_request, "
                + "SignedHeaders=host, Signature=abc");
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.S3_XML));
    }

    @Test
    void formEncodedNoClaimFallsBackToQueryXml() {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.mediaType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.QUERY_XML));
    }

    @Test
    void noSignalsFallBackToJson() {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        assertThat(ChaosInterceptorFilter.resolveShape(ctx.ctx()), equalTo(ErrorShape.JSON));
    }

    // ---- fault response builders (findings #3 shape + #4 no-response) ----

    @Test
    void throttlingResponsesCarryCorrectCodeAndStatus() {
        Response query = ChaosInterceptorFilter.throttlingResponse(ErrorShape.QUERY_XML);
        assertThat(query.getStatus(), equalTo(400));
        assertThat(query.getMediaType().toString(), containsString("xml"));
        assertThat(entityString(query), containsString("Throttling"));

        Response json = ChaosInterceptorFilter.throttlingResponse(ErrorShape.JSON);
        assertThat(json.getStatus(), equalTo(400));
        assertThat(json.getMediaType().toString(), containsString("json"));
        assertThat(json.getHeaderString("X-Amzn-Errortype"), containsString("ThrottlingException"));

        Response s3 = ChaosInterceptorFilter.throttlingResponse(ErrorShape.S3_XML);
        assertThat(s3.getStatus(), equalTo(503));
        assertThat(s3.getMediaType().toString(), containsString("xml"));
        assertThat(entityString(s3), containsString("SlowDown"));
    }

    @Test
    void accessDeniedResponsesCarryCorrectCodeAndStatus() {
        assertThat(ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.QUERY_XML).getStatus(), equalTo(403));
        assertThat(entityString(ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.QUERY_XML)),
                containsString("AccessDenied"));
        assertThat(ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.JSON).getStatus(), equalTo(403));
        assertThat(ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.S3_XML).getStatus(), equalTo(403));
        assertThat(entityString(ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.S3_XML)),
                containsString("AccessDenied"));
    }

    @Test
    void timeoutResponsesForNoResponseCarryCorrectStatus() {
        // Finding #4: no-response aborts with a terminal timeout rather than letting the resource run.
        assertThat(ChaosInterceptorFilter.timeoutResponse(ErrorShape.QUERY_XML).getStatus(), equalTo(504));
        assertThat(ChaosInterceptorFilter.timeoutResponse(ErrorShape.JSON).getStatus(), equalTo(504));
        Response s3 = ChaosInterceptorFilter.timeoutResponse(ErrorShape.S3_XML);
        assertThat(s3.getStatus(), equalTo(400));
        assertThat(entityString(s3), containsString("RequestTimeout"));
    }

    @Test
    void restXmlFaultIsNamespacedErrorResponse() {
        // Maintainer #2: a Route 53 fault must be a rest-xml ErrorResponse its SDK can parse,
        // carrying the Route 53 document namespace and the error code.
        Response throttle = ChaosInterceptorFilter.throttlingResponse(ErrorShape.REST_XML);
        assertThat(throttle.getStatus(), equalTo(400));
        assertThat(throttle.getMediaType().toString(), containsString("xml"));
        String body = entityString(throttle);
        assertThat(body, containsString("<ErrorResponse"));
        assertThat(body, containsString("route53.amazonaws.com/doc/2013-04-01"));
        assertThat(body, containsString("Throttling"));

        Response denied = ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.REST_XML);
        assertThat(denied.getStatus(), equalTo(403));
        assertThat(entityString(denied), containsString("AccessDenied"));
    }

    @Test
    void cborFaultCarriesCborMediaTypeAndProtocolHeader() {
        // Maintainer #2: a CloudWatch (rpc-v2-cbor) fault must be a CBOR response, not JSON, so the
        // SDK can deserialise it. CborErrorResponses stamps the rpc-v2-cbor smithy-protocol header.
        Response throttle = ChaosInterceptorFilter.throttlingResponse(ErrorShape.CBOR);
        assertThat(throttle.getStatus(), equalTo(400));
        assertThat(throttle.getMediaType().toString(), containsString("cbor"));
        assertThat(throttle.getHeaderString("smithy-protocol"), containsString("rpc-v2-cbor"));

        assertThat(ChaosInterceptorFilter.accessDeniedResponse(ErrorShape.CBOR).getStatus(), equalTo(403));
        assertThat(ChaosInterceptorFilter.timeoutResponse(ErrorShape.CBOR).getStatus(), equalTo(504));
    }

    // ---- internal-route exclusion (maintainer #2) ----

    @Test
    void flociInternalRoutesAreExcludedFromChaos() {
        // Maintainer #2: Floci's own control-plane and health routes must never receive injected
        // faults, or chaos would break the emulator's own management surface.
        assertThat(isInternal("/_floci/state"), equalTo(true));
        assertThat(isInternal("_floci/state"), equalTo(true));
        assertThat(isInternal("/_aws/lambda/invoke"), equalTo(true));
        assertThat(isInternal("/health"), equalTo(true));
    }

    @Test
    void awsDataPlaneRoutesAreNotExcluded() {
        // The exclusion must be tight: real AWS routes, including Route 53's /healthcheck and
        // data-plane paths, must still be eligible for chaos. Only exact /health is internal.
        assertThat(isInternal("/healthcheck"), equalTo(false));
        assertThat(isInternal("/2013-04-01/hostedzone"), equalTo(false));
        assertThat(isInternal("/mybucket/mykey"), equalTo(false));
        assertThat(isInternal("/"), equalTo(false));
    }

    private static boolean isInternal(String path) {
        ContainerRequestContextStub ctx = new ContainerRequestContextStub();
        ctx.path(path);
        return ChaosInterceptorFilter.isFlociInternalRoute(ctx.ctx());
    }

    // ---- deterministic (seeded) rolls ----

    @Test
    void seededRollIsReproducibleForSameInputs() {
        // DST property: the same seed, request key and decision point always yield the same draw,
        // so a replay reproduces the same faults regardless of thread interleaving.
        double first = ChaosInterceptorFilter.seededRoll(42L, "req-1", ChaosInterceptorFilter.RollPoint.THROTTLE);
        double again = ChaosInterceptorFilter.seededRoll(42L, "req-1", ChaosInterceptorFilter.RollPoint.THROTTLE);
        assertThat(again, equalTo(first));
    }

    @Test
    void seededRollIsInRange() {
        for (String key : new String[] {"a", "b", "c", "req-xyz"}) {
            double roll = ChaosInterceptorFilter.seededRoll(7L, key, ChaosInterceptorFilter.RollPoint.FAULT);
            assertThat("roll >= 0", roll >= 0.0, equalTo(true));
            assertThat("roll < 1", roll < 1.0, equalTo(true));
        }
    }

    @Test
    void differentSeedsProduceDifferentDrawSequences() {
        // Not every single point need differ, but across many keys the two seeds must diverge,
        // otherwise the seed would not actually control the outcome.
        int differences = 0;
        for (int i = 0; i < 200; i++) {
            String key = "req-" + i;
            double a = ChaosInterceptorFilter.seededRoll(1L, key, ChaosInterceptorFilter.RollPoint.THROTTLE);
            double b = ChaosInterceptorFilter.seededRoll(2L, key, ChaosInterceptorFilter.RollPoint.THROTTLE);
            if (a != b) {
                differences++;
            }
        }
        assertThat("seeds diverge across keys", differences > 190, equalTo(true));
    }

    @Test
    void differentDecisionPointsDoNotAliasUnderOneSeedAndKey() {
        // The per-decision salt keeps a request's latency roll independent of its throttle roll,
        // so one probability cannot silently drive another.
        double latency = ChaosInterceptorFilter.seededRoll(99L, "req-1", ChaosInterceptorFilter.RollPoint.LATENCY);
        double throttle = ChaosInterceptorFilter.seededRoll(99L, "req-1", ChaosInterceptorFilter.RollPoint.THROTTLE);
        assertThat(latency, not(equalTo(throttle)));
    }

    @Test
    void seededFaultSelectionIsStableAcrossRuns() {
        // End to end through filter(): with a fixed seed and a 0.5 throttle probability, the set of
        // request keys that get throttled must be identical on a second pass over the same keys.
        java.util.List<String> throttledRunA = seededThrottleDecisions(2024L);
        java.util.List<String> throttledRunB = seededThrottleDecisions(2024L);
        assertThat(throttledRunB, equalTo(throttledRunA));
        // And a different seed changes which requests are throttled (sanity: not all-or-nothing).
        java.util.List<String> throttledOtherSeed = seededThrottleDecisions(777L);
        assertThat(throttledOtherSeed, not(equalTo(throttledRunA)));
    }

    /** Runs filter() over 100 distinct signed requests and returns which ones were throttled. */
    private static java.util.List<String> seededThrottleDecisions(long seed) {
        ChaosInterceptorFilter filter = new ChaosInterceptorFilter(
                () -> configWithSeed(seed, 0.5));
        java.util.List<String> throttled = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            String invocationId = "invocation-" + i;
            ContainerRequestContextStub ctx = new ContainerRequestContextStub();
            ctx.path("/some/aws/path");
            ctx.header("amz-sdk-invocation-id", invocationId);
            filter.filter(ctx.ctx());
            if (ctx.aborted()) {
                throttled.add(invocationId);
            }
        }
        return throttled;
    }

    /** A minimal EmulatorConfig whose chaos block is enabled, seeded, with a throttle probability. */
    private static EmulatorConfig configWithSeed(long seed, double throttleProbability) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ChaosConfig chaos = mock(EmulatorConfig.ChaosConfig.class);
        EmulatorConfig.ChaosConfig.ChaosFaultConfig fault = mock(EmulatorConfig.ChaosConfig.ChaosFaultConfig.class);
        EmulatorConfig.ChaosConfig.ChaosNetworkConfig network = mock(EmulatorConfig.ChaosConfig.ChaosNetworkConfig.class);
        when(config.chaos()).thenReturn(chaos);
        when(chaos.enabled()).thenReturn(true);
        when(chaos.seed()).thenReturn(java.util.Optional.of(seed));
        when(chaos.fault()).thenReturn(fault);
        when(chaos.network()).thenReturn(network);
        when(network.latencyProbability()).thenReturn(0.0);
        when(network.noResponseProbability()).thenReturn(0.0);
        when(network.latencyMs()).thenReturn(0L);
        when(fault.throttleProbability()).thenReturn(throttleProbability);
        when(fault.accessDeniedProbability()).thenReturn(0.0);
        when(fault.faultProbability()).thenReturn(0.0);
        return config;
    }

    private static String entityString(Response response) {
        return String.valueOf(response.getEntity());
    }

    private static ServiceDescriptor descriptor(String service) {
        return new ServiceDescriptor(service, service, true, true, service, "memory", 0L,
                null, null, Set.of(), Set.of(), Set.of(service), Set.of(), Set.of());
    }

    /** Minimal mocked {@link ContainerRequestContext} exposing only what resolveShape reads. */
    private static final class ContainerRequestContextStub {
        private final ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        private final UriInfo uriInfo = mock(UriInfo.class);
        private final MultivaluedMap<String, String> queryParams = new MultivaluedHashMap<>();
        private boolean aborted;

        ContainerRequestContextStub() {
            when(ctx.getUriInfo()).thenReturn(uriInfo);
            when(uriInfo.getQueryParameters()).thenReturn(queryParams);
            when(ctx.getMethod()).thenReturn("POST");
            org.mockito.Mockito.doAnswer(invocation -> {
                aborted = true;
                return null;
            }).when(ctx).abortWith(org.mockito.ArgumentMatchers.any());
        }

        void authorization(String value) {
            when(ctx.getHeaderString("Authorization")).thenReturn(value);
        }

        void query(String name, String value) {
            queryParams.add(name, value);
        }

        void mediaType(MediaType mediaType) {
            when(ctx.getMediaType()).thenReturn(mediaType);
        }

        void path(String path) {
            when(uriInfo.getPath()).thenReturn(path);
        }

        void header(String name, String value) {
            when(ctx.getHeaderString(name)).thenReturn(value);
        }

        boolean aborted() {
            return aborted;
        }

        void claim(ProtocolClaim claim) {
            when(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY)).thenReturn(claim);
        }

        ContainerRequestContext ctx() {
            return ctx;
        }
    }
}

package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.Distribution;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import io.github.hectorvent.floci.services.cloudfront.model.OriginAccessControl;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.cloudfront.model.ResponseHeadersPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The CloudFront CFN provisioner in isolation: one mocked service. Every case asserts the exact
 * physical id and the exact {@code Fn::GetAtt} attribute keys, since an unmapped type still
 * reports CREATE_COMPLETE through the dispatcher's stub arm (issue #2441).
 */
class CloudFrontCfnProvisionerTest {

    private static final String RESPONSE_HEADERS_POLICY = "AWS::CloudFront::ResponseHeadersPolicy";
    private static final String CACHE_POLICY = "AWS::CloudFront::CachePolicy";
    private static final String ORIGIN_REQUEST_POLICY = "AWS::CloudFront::OriginRequestPolicy";
    private static final String ORIGIN_ACCESS_CONTROL = "AWS::CloudFront::OriginAccessControl";
    private static final String DISTRIBUTION = "AWS::CloudFront::Distribution";
    private static final String REGION = "us-east-1";
    private static final String ID = "5cc3b908-e619-4b99-88e5-2cf7f45965bd";
    private static final String ETAG = "E2QWRUHAPOMQZL";
    private static final String NEW_ETAG = "E3ZQ1XZ4M2ZTKH";
    private static final Instant MODIFIED = Instant.parse("2026-09-07T10:15:30Z");

    private final CloudFrontService cloudFront = mock(CloudFrontService.class);
    private final CloudFrontCfnProvisioner provisioner = new CloudFrontCfnProvisioner(cloudFront);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        return ctx(null);
    }

    private ProvisionContext ctx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static StackResource resource(String type) {
        StackResource r = new StackResource();
        r.setLogicalId("Policy");
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private static StackResource resource(String type, String physicalId, Map<String, String> attributes) {
        StackResource r = resource(type);
        r.setPhysicalId(physicalId);
        r.setAttributes(new HashMap<>(attributes));
        return r;
    }

    private JsonNode json(String json) throws Exception {
        return mapper.readTree(json);
    }

    private static ResponseHeadersPolicy responseHeadersPolicy(String id, String etag) {
        ResponseHeadersPolicy p = new ResponseHeadersPolicy();
        p.setId(id);
        p.setEtag(etag);
        p.setLastModifiedTime(MODIFIED);
        return p;
    }

    private static <T> T withIdentity(T model, java.util.function.Consumer<T> set) {
        set.accept(model);
        return model;
    }

    @Test
    void servesTheDistributionAndItsFourConfigTypes() {
        assertEquals(Set.of(DISTRIBUTION, RESPONSE_HEADERS_POLICY, CACHE_POLICY, ORIGIN_REQUEST_POLICY,
                ORIGIN_ACCESS_CONTROL), provisioner.resourceTypes());
    }

    /**
     * The distribution helpers read every scalar through {@code engine.resolve}, which the shared
     * {@link #ctx()} leaves unstubbed because the config types only need {@code resolveNode}.
     */
    private ProvisionContext distributionCtx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText();
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    private static Distribution distribution(String id, String domainName) {
        Distribution d = new Distribution();
        d.setId(id);
        d.setDomainName(domainName);
        d.setArn("arn:aws:cloudfront::000000000000:distribution/" + id);
        d.setEtag(ETAG);
        return d;
    }

    @Test
    void createsDistributionAndExposesTheSchemaAttributes() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, json("""
                {"DistributionConfig": {
                   "Enabled": true,
                   "Comment": "site",
                   "Origins": [{"Id": "o1", "DomainName": "b.s3.amazonaws.com"}],
                   "DefaultCacheBehavior": {"TargetOriginId": "o1", "ViewerProtocolPolicy": "redirect-to-https"}
                }}
                """), distributionCtx(null));

        // Ref is the distribution id; Id and DomainName are the schema's read-only properties.
        assertEquals("E1DIST", r.getPhysicalId());
        assertEquals("E1DIST", r.getAttributes().get("Id"));
        assertEquals("d111.cloudfront.net", r.getAttributes().get("DomainName"));
        assertEquals("arn:aws:cloudfront::000000000000:distribution/E1DIST", r.getAttributes().get("Arn"));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        DistributionConfig config = sent.getValue().getConfig();
        assertTrue(config.isEnabled());
        assertEquals("site", config.getComment());
        // Defaults the monolith applied and that must survive the move.
        assertEquals("http2", config.getHttpVersion());
        assertEquals("PriceClass_All", config.getPriceClass());
        Origin origin = config.getOrigins().getFirst();
        assertEquals("o1", origin.getId());
        assertEquals("b.s3.amazonaws.com", origin.getDomainName());
        // No CustomOriginConfig means an S3 origin.
        assertNull(origin.getCustomOriginConfig());
        assertEquals("redirect-to-https", config.getDefaultCacheBehavior().getViewerProtocolPolicy());
    }

    @Test
    void updatesTheDistributionInPlaceUnderThePriorId() throws Exception {
        // provision() re-runs on every UpdateStack. The prior id comes from the context, not from
        // the resource, because provision assigns the new id as it runs.
        when(cloudFront.getDistribution("E1DIST")).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        when(cloudFront.updateDistribution(eq("E1DIST"), eq(ETAG), any()))
                .thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION, "E1DIST", Map.of());

        provisioner.provision(r, json("""
                {"DistributionConfig": {"Enabled": false, "Comment": "paused"}}
                """), distributionCtx("E1DIST"));

        verify(cloudFront).updateDistribution(eq("E1DIST"), eq(ETAG), any());
        verify(cloudFront, never()).createDistribution(any(), any());
        assertEquals("E1DIST", r.getPhysicalId());
    }

    @Test
    void deletesTheDistributionThroughTheStackLevelRemove() {
        provisioner.delete(DISTRIBUTION, "E1DIST", REGION);
        // removeDistribution skips the disable and If-Match guards deleteDistribution enforces,
        // because the stack owns the lifecycle.
        verify(cloudFront).removeDistribution("E1DIST");
    }

    @Test
    void createsResponseHeadersPolicyFromTheConfigInTheCodecShape() throws Exception {
        when(cloudFront.createResponseHeadersPolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<ResponseHeadersPolicy>getArgument(0), p -> {
                    p.setId(ID);
                    p.setEtag(ETAG);
                    p.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(RESPONSE_HEADERS_POLICY);

        provisioner.provision(r, json("""
                {"ResponseHeadersPolicyConfig": {
                  "Name": "floci-response-policy-ref-repro",
                  "Comment": "Minimal response headers policy reference reproduction",
                  "CustomHeadersConfig": {"Items": [{"Header": "X-Floci-Repro", "Value": "enabled", "Override": true}]},
                  "SecurityHeadersConfig": {"FrameOptions": {"FrameOption": "DENY", "Override": false}},
                  "CorsConfig": {
                    "AccessControlAllowOrigins": {"Items": ["https://app.example.test"]},
                    "AccessControlMaxAgeSec": 600,
                    "OriginOverride": true
                  },
                  "RemoveHeadersConfig": {"Items": [{"Header": "Server"}]}
                }}
                """), ctx());

        ArgumentCaptor<ResponseHeadersPolicy> captor = ArgumentCaptor.forClass(ResponseHeadersPolicy.class);
        verify(cloudFront).createResponseHeadersPolicy(captor.capture());
        ResponseHeadersPolicy sent = captor.getValue();
        assertEquals("floci-response-policy-ref-repro", sent.getName());
        assertEquals("Minimal response headers policy reference reproduction", sent.getComment());
        assertEquals(Map.of(
                "CustomHeadersConfig", List.of(
                        Map.of("Header", "X-Floci-Repro", "Value", "enabled", "Override", "true")),
                "SecurityHeadersConfig", Map.of("FrameOptions", Map.of("FrameOption", "DENY", "Override", "false")),
                "CorsConfig", Map.of(
                        "AccessControlAllowOrigins", List.of("https://app.example.test"),
                        "AccessControlMaxAgeSec", "600",
                        "OriginOverride", "true"),
                "RemoveHeadersConfig", List.of("Server")),
                sent.getConfig());
        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", "2026-09-07T10:15:30Z"), r.getAttributes());
    }

    @Test
    void updatesResponseHeadersPolicyInPlaceWithTheCurrentEtag() throws Exception {
        when(cloudFront.getResponseHeadersPolicy(ID)).thenReturn(responseHeadersPolicy(ID, ETAG));
        when(cloudFront.updateResponseHeadersPolicy(eq(ID), eq(ETAG), any()))
                .thenAnswer(inv -> withIdentity(inv.<ResponseHeadersPolicy>getArgument(2), p -> {
                    p.setId(ID);
                    p.setEtag(NEW_ETAG);
                    p.setLastModifiedTime(MODIFIED.plusSeconds(60));
                }));
        StackResource r = resource(RESPONSE_HEADERS_POLICY, ID,
                Map.of("Id", ID, "LastModifiedTime", MODIFIED.toString()));

        provisioner.provision(r, json("""
                {"ResponseHeadersPolicyConfig": {"Name": "renamed", "Comment": "second revision"}}
                """), ctx(ID));

        ArgumentCaptor<ResponseHeadersPolicy> captor = ArgumentCaptor.forClass(ResponseHeadersPolicy.class);
        verify(cloudFront).updateResponseHeadersPolicy(eq(ID), eq(ETAG), captor.capture());
        assertEquals("renamed", captor.getValue().getName());
        assertEquals(Map.of(), captor.getValue().getConfig());
        verify(cloudFront, never()).createResponseHeadersPolicy(any());
        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", "2026-09-07T10:16:30Z"), r.getAttributes());
    }

    @Test
    void recreatesResponseHeadersPolicyWhenThePriorRecordIsGone() throws Exception {
        when(cloudFront.getResponseHeadersPolicy(ID)).thenThrow(
                new AwsException("NoSuchResponseHeadersPolicy", "The specified response headers policy does not exist.", 404));
        when(cloudFront.createResponseHeadersPolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<ResponseHeadersPolicy>getArgument(0), p -> {
                    p.setId("new-id");
                    p.setEtag(ETAG);
                    p.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(RESPONSE_HEADERS_POLICY, ID, Map.of("Id", ID));

        provisioner.provision(r, json("""
                {"ResponseHeadersPolicyConfig": {"Name": "policy"}}
                """), ctx(ID));

        verify(cloudFront, never()).updateResponseHeadersPolicy(any(), any(), any());
        assertEquals("new-id", r.getPhysicalId());
        assertEquals("new-id", r.getAttributes().get("Id"));
    }

    @Test
    void rollingBackARecreationDeletesTheReplacementAndRestoresThePriorId() throws Exception {
        // The engine's update rollback only deletes resources the update ADDED; a resource that
        // already existed and merely acquired a new physical id reached the "Rollback is not
        // implemented" arm, which deletes nothing, so the recreated policy stayed behind.
        when(cloudFront.getResponseHeadersPolicy(ID)).thenThrow(new AwsException(
                "NoSuchResponseHeadersPolicy", "The specified response headers policy does not exist.", 404));
        when(cloudFront.createResponseHeadersPolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<ResponseHeadersPolicy>getArgument(0), p -> {
                    p.setId("new-id");
                    p.setEtag(ETAG);
                    p.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(RESPONSE_HEADERS_POLICY, ID, Map.of("Id", ID, "LastModifiedTime", "old-time"));
        provisioner.provision(r, json("""
                {"ResponseHeadersPolicyConfig": {"Name": "policy"}}
                """), ctx(ID));
        assertEquals("new-id", r.getPhysicalId());
        when(cloudFront.getResponseHeadersPolicy("new-id")).thenReturn(responseHeadersPolicy("new-id", ETAG));

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cloudFront).deleteResponseHeadersPolicy("new-id", ETAG);
        assertEquals(ID, r.getPhysicalId(), "the prior id is named again");
        assertEquals(ID, r.getAttributes().get("Id"));
        assertEquals("old-time", r.getAttributes().get("LastModifiedTime"));
    }

    @Test
    void rollingBackAnInPlaceUpdateReportsNotRolledBack() {
        // Deliberate and unchanged: an in-place update keeps the physical id, so nothing is
        // recorded to undo, and putting the committed change back would need a configuration
        // snapshot this provisioner does not keep. Same limitation as AWS::Cognito::UserPool.
        StackResource r = resource(CACHE_POLICY, ID, Map.of("Id", ID));

        assertFalse(provisioner.rollbackUpdate(r));

        verify(cloudFront, never()).deleteCachePolicy(any(), any());
    }

    @Test
    void aRecreationOwesCleanupForThePriorEntityAndToleratesItBeingGone() throws Exception {
        // The recorded displaced entity is always one the service already forgot, since prior()
        // returns the object whenever it exists. The cleanup still has to run so the record is
        // cleared rather than left owed forever.
        when(cloudFront.getCachePolicy(ID)).thenThrow(
                new AwsException("NoSuchCachePolicy", "The specified cache policy does not exist.", 404));
        when(cloudFront.createCachePolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<CachePolicy>getArgument(0), p -> {
                    p.setId("new-id");
                    p.setEtag(ETAG);
                    p.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(CACHE_POLICY, ID, Map.of("Id", ID));

        provisioner.provision(r, json("""
                {"CachePolicyConfig": {"Name": "policy"}}
                """), ctx(ID));

        assertTrue(provisioner.hasReplacementUpdate(r), "the prior entity is owed a cleanup");
        assertEquals(ID, provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        assertFalse(provisioner.hasReplacementUpdate(r), "the record is cleared once the cleanup ran");
    }

    @Test
    void otherLookupFailuresOnUpdatePropagate() throws Exception {
        when(cloudFront.getResponseHeadersPolicy(ID)).thenThrow(new AwsException("AccessDenied", "denied", 403));
        StackResource r = resource(RESPONSE_HEADERS_POLICY, ID, Map.of("Id", ID));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"ResponseHeadersPolicyConfig": {"Name": "policy"}}
                """), ctx(ID)));

        assertEquals("AccessDenied", e.getErrorCode());
        verify(cloudFront, never()).createResponseHeadersPolicy(any());
    }

    @Test
    void missingConfigIsAValidationError() throws Exception {
        for (String[] type : new String[][] {
                {RESPONSE_HEADERS_POLICY, "ResponseHeadersPolicyConfig"},
                {CACHE_POLICY, "CachePolicyConfig"},
                {ORIGIN_REQUEST_POLICY, "OriginRequestPolicyConfig"},
                {ORIGIN_ACCESS_CONTROL, "OriginAccessControlConfig"}}) {
            StackResource r = resource(type[0]);
            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(r, json("{\"Tags\": []}"), ctx()));
            assertEquals("ValidationError", e.getErrorCode());
            assertEquals(type[0] + " requires " + type[1], e.getMessage());
            assertNull(r.getPhysicalId());
        }
        verify(cloudFront, never()).createResponseHeadersPolicy(any());
        verify(cloudFront, never()).createCachePolicy(any());
        verify(cloudFront, never()).createOriginRequestPolicy(any());
        verify(cloudFront, never()).createOriginAccessControl(any());
    }

    @Test
    void deletesResponseHeadersPolicyWithItsCurrentEtag() {
        when(cloudFront.getResponseHeadersPolicy(ID)).thenReturn(responseHeadersPolicy(ID, ETAG));

        provisioner.delete(RESPONSE_HEADERS_POLICY, ID, REGION);

        verify(cloudFront).deleteResponseHeadersPolicy(ID, ETAG);
    }

    @Test
    void deleteToleratesAPolicyAlreadyGone() {
        when(cloudFront.getResponseHeadersPolicy(ID)).thenThrow(
                new AwsException("NoSuchResponseHeadersPolicy", "The specified response headers policy does not exist.", 404));

        provisioner.delete(RESPONSE_HEADERS_POLICY, ID, REGION);
        provisioner.delete(RESPONSE_HEADERS_POLICY, "", REGION);
        provisioner.delete(RESPONSE_HEADERS_POLICY, null, REGION);

        verify(cloudFront, never()).deleteResponseHeadersPolicy(any(), any());
    }

    @Test
    void deleteOfAPolicyStillInUseFailsTheStack() {
        when(cloudFront.getResponseHeadersPolicy(ID)).thenReturn(responseHeadersPolicy(ID, ETAG));
        AwsException inUse = new AwsException("ResponseHeadersPolicyInUse",
                "The response headers policy is attached to one or more distributions.", 409);
        org.mockito.Mockito.doThrow(inUse).when(cloudFront).deleteResponseHeadersPolicy(ID, ETAG);

        AwsException e = assertThrows(AwsException.class,
                () -> provisioner.delete(RESPONSE_HEADERS_POLICY, ID, REGION));

        assertSame(inUse, e);
    }

    @Test
    void createsCachePolicyWithTtlsAsText() throws Exception {
        when(cloudFront.createCachePolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<CachePolicy>getArgument(0), p -> {
                    p.setId(ID);
                    p.setEtag(ETAG);
                    p.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(CACHE_POLICY);

        provisioner.provision(r, json("""
                {"CachePolicyConfig": {
                  "Name": "cfn-cache",
                  "DefaultTTL": 86400, "MaxTTL": 31536000, "MinTTL": 1,
                  "ParametersInCacheKeyAndForwardedToOrigin": {
                    "EnableAcceptEncodingGzip": true,
                    "CookiesConfig": {"CookieBehavior": "none"},
                    "HeadersConfig": {"HeaderBehavior": "whitelist", "Headers": ["Origin"]},
                    "QueryStringsConfig": {"QueryStringBehavior": "all"}
                  }
                }}
                """), ctx());

        ArgumentCaptor<CachePolicy> captor = ArgumentCaptor.forClass(CachePolicy.class);
        verify(cloudFront).createCachePolicy(captor.capture());
        assertEquals("cfn-cache", captor.getValue().getName());
        assertNull(captor.getValue().getComment());
        assertEquals(Map.of(
                "DefaultTTL", "86400", "MaxTTL", "31536000", "MinTTL", "1",
                "ParametersInCacheKeyAndForwardedToOrigin", Map.of(
                        "EnableAcceptEncodingGzip", "true",
                        "CookiesConfig", Map.of("CookieBehavior", "none"),
                        "HeadersConfig", Map.of("HeaderBehavior", "whitelist", "Headers", List.of("Origin")),
                        "QueryStringsConfig", Map.of("QueryStringBehavior", "all"))),
                captor.getValue().getConfig());
        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", "2026-09-07T10:15:30Z"), r.getAttributes());
    }

    @Test
    void updatesAndDeletesCachePolicyThroughTheEtag() throws Exception {
        CachePolicy existing = new CachePolicy();
        existing.setId(ID);
        existing.setEtag(ETAG);
        when(cloudFront.getCachePolicy(ID)).thenReturn(existing);
        when(cloudFront.updateCachePolicy(eq(ID), eq(ETAG), any()))
                .thenAnswer(inv -> withIdentity(inv.<CachePolicy>getArgument(2), p -> {
                    p.setId(ID);
                    p.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(CACHE_POLICY, ID, Map.of("Id", ID, "LastModifiedTime", ""));

        provisioner.provision(r, json("{\"CachePolicyConfig\": {\"Name\": \"cfn-cache-2\"}}"), ctx(ID));
        provisioner.delete(CACHE_POLICY, ID, REGION);

        verify(cloudFront, never()).createCachePolicy(any());
        verify(cloudFront).deleteCachePolicy(ID, ETAG);
        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", "2026-09-07T10:15:30Z"), r.getAttributes());
    }

    @Test
    void createsAndDeletesOriginRequestPolicy() throws Exception {
        when(cloudFront.createOriginRequestPolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<OriginRequestPolicy>getArgument(0), p -> {
                    p.setId(ID);
                    p.setEtag(ETAG);
                    p.setLastModifiedTime(MODIFIED);
                }));
        OriginRequestPolicy existing = new OriginRequestPolicy();
        existing.setId(ID);
        existing.setEtag(ETAG);
        when(cloudFront.getOriginRequestPolicy(ID)).thenReturn(existing);
        StackResource r = resource(ORIGIN_REQUEST_POLICY);

        provisioner.provision(r, json("""
                {"OriginRequestPolicyConfig": {
                  "Name": "cfn-origin-request", "Comment": "forward all",
                  "CookiesConfig": {"CookieBehavior": "all"},
                  "HeadersConfig": {"HeaderBehavior": "allViewer"},
                  "QueryStringsConfig": {"QueryStringBehavior": "all"}
                }}
                """), ctx());
        provisioner.delete(ORIGIN_REQUEST_POLICY, ID, REGION);

        ArgumentCaptor<OriginRequestPolicy> captor = ArgumentCaptor.forClass(OriginRequestPolicy.class);
        verify(cloudFront).createOriginRequestPolicy(captor.capture());
        assertEquals("cfn-origin-request", captor.getValue().getName());
        assertEquals("forward all", captor.getValue().getComment());
        assertEquals(Map.of(
                "CookiesConfig", Map.of("CookieBehavior", "all"),
                "HeadersConfig", Map.of("HeaderBehavior", "allViewer"),
                "QueryStringsConfig", Map.of("QueryStringBehavior", "all")),
                captor.getValue().getConfig());
        verify(cloudFront).deleteOriginRequestPolicy(ID, ETAG);
        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", "2026-09-07T10:15:30Z"), r.getAttributes());
    }

    @Test
    void createsOriginAccessControlWithAllFiveFieldsAndOnlyIdAsAttribute() throws Exception {
        when(cloudFront.createOriginAccessControl(any()))
                .thenAnswer(inv -> withIdentity(inv.<OriginAccessControl>getArgument(0), o -> {
                    o.setId("E2ABCDEFGHIJKL");
                    o.setEtag(ETAG);
                    o.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(ORIGIN_ACCESS_CONTROL);

        provisioner.provision(r, json("""
                {"OriginAccessControlConfig": {
                  "Name": "cfn-oac", "Description": "bucket access",
                  "SigningBehavior": "always", "SigningProtocol": "sigv4",
                  "OriginAccessControlOriginType": "s3"
                }}
                """), ctx());

        ArgumentCaptor<OriginAccessControl> captor = ArgumentCaptor.forClass(OriginAccessControl.class);
        verify(cloudFront).createOriginAccessControl(captor.capture());
        OriginAccessControl sent = captor.getValue();
        assertEquals("cfn-oac", sent.getName());
        assertEquals("bucket access", sent.getDescription());
        assertEquals("always", sent.getSigningBehavior());
        assertEquals("sigv4", sent.getSigningProtocol());
        assertEquals("s3", sent.getOriginAccessControlOriginType());
        assertEquals("E2ABCDEFGHIJKL", r.getPhysicalId());
        assertEquals(Map.of("Id", "E2ABCDEFGHIJKL"), r.getAttributes());
        assertFalse(r.getAttributes().containsKey("LastModifiedTime"));
    }

    @Test
    void updatesAndDeletesOriginAccessControlThroughTheEtag() throws Exception {
        OriginAccessControl existing = new OriginAccessControl();
        existing.setId("E2ABCDEFGHIJKL");
        existing.setEtag(ETAG);
        when(cloudFront.getOriginAccessControl("E2ABCDEFGHIJKL")).thenReturn(existing);
        when(cloudFront.updateOriginAccessControl(eq("E2ABCDEFGHIJKL"), eq(ETAG), any()))
                .thenAnswer(inv -> withIdentity(inv.<OriginAccessControl>getArgument(2),
                        o -> o.setId("E2ABCDEFGHIJKL")));
        StackResource r = resource(ORIGIN_ACCESS_CONTROL, "E2ABCDEFGHIJKL", Map.of("Id", "E2ABCDEFGHIJKL"));

        provisioner.provision(r, json("""
                {"OriginAccessControlConfig": {"Name": "cfn-oac", "SigningBehavior": "never",
                  "SigningProtocol": "sigv4", "OriginAccessControlOriginType": "s3"}}
                """), ctx("E2ABCDEFGHIJKL"));
        provisioner.delete(ORIGIN_ACCESS_CONTROL, "E2ABCDEFGHIJKL", REGION);

        ArgumentCaptor<OriginAccessControl> captor = ArgumentCaptor.forClass(OriginAccessControl.class);
        verify(cloudFront).updateOriginAccessControl(eq("E2ABCDEFGHIJKL"), eq(ETAG), captor.capture());
        assertEquals("never", captor.getValue().getSigningBehavior());
        verify(cloudFront, never()).createOriginAccessControl(any());
        verify(cloudFront).deleteOriginAccessControl("E2ABCDEFGHIJKL", ETAG);
        assertEquals(Map.of("Id", "E2ABCDEFGHIJKL"), r.getAttributes());
    }

    @Test
    void configIntrinsicsAreResolvedThroughTheEngine() throws Exception {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> json("""
                {"Name": "resolved-name", "CustomHeadersConfig": {"Items": [{"Header": "X-A", "Value": "1", "Override": false}]}}
                """));
        ProvisionContext ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack", null);
        when(cloudFront.createResponseHeadersPolicy(any()))
                .thenAnswer(inv -> withIdentity(inv.<ResponseHeadersPolicy>getArgument(0), p -> p.setId(ID)));
        StackResource r = resource(RESPONSE_HEADERS_POLICY);

        provisioner.provision(r, json("""
                {"ResponseHeadersPolicyConfig": {"Name": {"Fn::Sub": "${AWS::StackName}-policy"}}}
                """), ctx);

        ArgumentCaptor<ResponseHeadersPolicy> captor = ArgumentCaptor.forClass(ResponseHeadersPolicy.class);
        verify(cloudFront).createResponseHeadersPolicy(captor.capture());
        assertEquals("resolved-name", captor.getValue().getName());
        assertEquals(Map.of("CustomHeadersConfig",
                List.of(Map.of("Header", "X-A", "Value", "1", "Override", "false"))),
                captor.getValue().getConfig());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", ""), r.getAttributes());
    }
}

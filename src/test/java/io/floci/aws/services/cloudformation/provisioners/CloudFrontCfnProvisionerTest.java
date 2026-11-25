package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudfront.CloudFrontService;
import io.floci.aws.services.cloudfront.model.CachePolicy;
import io.floci.aws.services.cloudfront.model.CloudFrontFunction;
import io.floci.aws.services.cloudfront.model.ConnectionGroup;
import io.floci.aws.services.cloudfront.model.Distribution;
import io.floci.aws.services.cloudfront.model.DistributionConfig;
import io.floci.aws.services.cloudfront.model.DistributionTenant;
import io.floci.aws.services.cloudfront.model.KeyGroup;
import io.floci.aws.services.cloudfront.model.Origin;
import io.floci.aws.services.cloudfront.model.OriginAccessControl;
import io.floci.aws.services.cloudfront.model.OriginRequestPolicy;
import io.floci.aws.services.cloudfront.model.PublicKey;
import io.floci.aws.services.cloudfront.model.ResponseHeadersPolicy;
import io.floci.aws.services.cloudfront.model.TenantCustomizations;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private static final String PUBLIC_KEY = "AWS::CloudFront::PublicKey";
    private static final String KEY_GROUP = "AWS::CloudFront::KeyGroup";
    private static final String FUNCTION = "AWS::CloudFront::Function";
    private static final String CONNECTION_GROUP_TYPE = "AWS::CloudFront::ConnectionGroup";
    private static final String TENANT_TYPE = "AWS::CloudFront::DistributionTenant";
    private static final String FUNCTION_ARN = "arn:aws:cloudfront::000000000000:function/my-function";
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

    private static String wireCode(String code) {
        return Base64.getEncoder().encodeToString(code.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseHeadersPolicy responseHeadersPolicy(String id, String etag) {
        ResponseHeadersPolicy p = new ResponseHeadersPolicy();
        p.setId(id);
        p.setEtag(etag);
        p.setLastModifiedTime(MODIFIED);
        return p;
    }

    private static <T> T withIdentity(T model, Consumer<T> set) {
        set.accept(model);
        return model;
    }

    @Test
    void servesTheDistributionAndTheTypesItReferences() {
        assertEquals(Set.of(DISTRIBUTION, RESPONSE_HEADERS_POLICY, CACHE_POLICY, ORIGIN_REQUEST_POLICY,
                ORIGIN_ACCESS_CONTROL, PUBLIC_KEY, KEY_GROUP, FUNCTION, "AWS::CloudFront::ConnectionGroup",
                "AWS::CloudFront::DistributionTenant"),
                provisioner.resourceTypes());
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
        // Settings the template never mentions stay on the model's own AWS defaults.
        assertEquals(3, origin.getConnectionAttempts());
        assertEquals(10, origin.getConnectionTimeout());
        assertEquals("redirect-to-https", config.getDefaultCacheBehavior().getViewerProtocolPolicy());
    }

    @Test
    void copiesTheOriginConnectionSettingsTheTemplateDeclares() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, json("""
                {"DistributionConfig": {
                   "Origins": [{"Id": "o1", "DomainName": "origin.example.com",
                                "ConnectionAttempts": 2, "ConnectionTimeout": 5,
                                "ResponseCompletionTimeout": 90,
                                "CustomOriginConfig": {"OriginProtocolPolicy": "https-only",
                                                       "OriginReadTimeout": 45,
                                                       "OriginKeepaliveTimeout": 120}}],
                   "DefaultCacheBehavior": {"TargetOriginId": "o1", "ViewerProtocolPolicy": "redirect-to-https"}
                }}
                """), distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        Origin origin = sent.getValue().getConfig().getOrigins().getFirst();
        assertEquals(2, origin.getConnectionAttempts());
        assertEquals(5, origin.getConnectionTimeout());
        // ResponseCompletionTimeout lives on the origin, where the serving layer reads it.
        assertEquals(Integer.valueOf(90), origin.getResponseCompletionTimeout());
        // OriginReadTimeout lives on CustomOriginConfig, where the serving layer reads it.
        assertEquals(Integer.valueOf(45), origin.getCustomOriginConfig().get("OriginReadTimeout"));
        assertEquals(Integer.valueOf(120), origin.getCustomOriginConfig().get("OriginKeepaliveTimeout"));
    }

    @Test
    void copiesTheConnectionModeTheTemplateDeclares() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, json("""
                {"DistributionConfig": {
                   "ConnectionMode": "tenant-only",
                   "Origins": [{"Id": "o1", "DomainName": "origin.example.com",
                                "CustomOriginConfig": {"OriginProtocolPolicy": "https-only"}}],
                   "DefaultCacheBehavior": {"TargetOriginId": "o1", "ViewerProtocolPolicy": "redirect-to-https"}
                }}
                """), distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        assertEquals("tenant-only", sent.getValue().getConfig().getConnectionMode());
    }

    @Test
    void leavesTheConnectionModeUnsetWhenTheTemplateOmitsIt() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, json("""
                {"DistributionConfig": {
                   "Origins": [{"Id": "o1", "DomainName": "origin.example.com",
                                "CustomOriginConfig": {"OriginProtocolPolicy": "https-only"}}],
                   "DefaultCacheBehavior": {"TargetOriginId": "o1", "ViewerProtocolPolicy": "redirect-to-https"}
                }}
                """), distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        assertNull(sent.getValue().getConfig().getConnectionMode());
    }

    /** A minimal DistributionConfig template carrying the given ViewerCertificate object, or none when null. */
    private JsonNode withViewerCertificate(String viewerCertificate) throws Exception {
        String fragment = viewerCertificate == null ? "" : "\"ViewerCertificate\": " + viewerCertificate + ",";
        return json("{\"DistributionConfig\": {" + fragment
                + "\"Origins\": [{\"Id\": \"o1\", \"DomainName\": \"origin.example.com\","
                + " \"CustomOriginConfig\": {\"OriginProtocolPolicy\": \"https-only\"}}],"
                + " \"DefaultCacheBehavior\": {\"TargetOriginId\": \"o1\","
                + " \"ViewerProtocolPolicy\": \"redirect-to-https\"}}}");
    }

    @Test
    void copiesTheAcmViewerCertificateTheTemplateDeclares() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, withViewerCertificate("{\"AcmCertificateArn\": \"arn:aws:acm:us-east-1:000000000000:certificate/abc\", \"SslSupportMethod\": \"sni-only\", \"MinimumProtocolVersion\": \"TLSv1.2_2021\"}"),
                distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ACMCertificateArn", "arn:aws:acm:us-east-1:000000000000:certificate/abc");
        expected.put("SSLSupportMethod", "sni-only");
        expected.put("MinimumProtocolVersion", "TLSv1.2_2021");
        assertEquals(expected, sent.getValue().getConfig().getViewerCertificate());
    }

    @Test
    void mapsTheIamCertificateIdOntoItsApiName() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, withViewerCertificate("{\"IamCertificateId\": \"ASCAEXAMPLE\", \"SslSupportMethod\": \"vip\", \"MinimumProtocolVersion\": \"TLSv1\"}"),
                distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("IAMCertificateId", "ASCAEXAMPLE");
        expected.put("SSLSupportMethod", "vip");
        expected.put("MinimumProtocolVersion", "TLSv1");
        assertEquals(expected, sent.getValue().getConfig().getViewerCertificate());
    }

    @Test
    void rendersTheDefaultCertificateFlagAsText() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, withViewerCertificate("{\"CloudFrontDefaultCertificate\": true}"),
                distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        assertEquals(Map.of("CloudFrontDefaultCertificate", "true"),
                sent.getValue().getConfig().getViewerCertificate());
    }

    @Test
    void leavesTheViewerCertificateUnsetWhenTheTemplateOmitsIt() throws Exception {
        when(cloudFront.createDistribution(any(), any())).thenReturn(distribution("E1DIST", "d111.cloudfront.net"));
        StackResource r = resource(DISTRIBUTION);

        provisioner.provision(r, withViewerCertificate(null), distributionCtx(null));

        ArgumentCaptor<Distribution> sent = ArgumentCaptor.forClass(Distribution.class);
        verify(cloudFront).createDistribution(sent.capture(), any());
        assertNull(sent.getValue().getConfig().getViewerCertificate());
    }

    @Test
    void aNonNumericOriginConnectionSettingFailsTheStackInsteadOfAServerError() {
        StackResource r = resource(DISTRIBUTION);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"DistributionConfig": {
                   "Origins": [{"Id": "o1", "DomainName": "origin.example.com", "ConnectionTimeout": "lots"}],
                   "DefaultCacheBehavior": {"TargetOriginId": "o1", "ViewerProtocolPolicy": "redirect-to-https"}
                }}
                """), distributionCtx(null)));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals("AWS::CloudFront::Distribution origin ConnectionTimeout must be an integer, but was 'lots'",
                e.getMessage());
        verify(cloudFront, never()).createDistribution(any(), any());
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
        Mockito.doThrow(inUse).when(cloudFront).deleteResponseHeadersPolicy(ID, ETAG);

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

    private static PublicKey publicKey(String id, String etag) {
        PublicKey key = new PublicKey();
        key.setId(id);
        key.setEtag(etag);
        key.setCreatedTime(MODIFIED);
        return key;
    }

    private static KeyGroup keyGroup(String id, String etag) {
        KeyGroup group = new KeyGroup();
        group.setId(id);
        group.setEtag(etag);
        group.setLastModifiedTime(MODIFIED);
        return group;
    }

    private static CloudFrontFunction function(String name, String etag) {
        CloudFrontFunction fn = new CloudFrontFunction();
        fn.setName(name);
        fn.setEtag(etag);
        fn.setStage("DEVELOPMENT");
        return fn;
    }

    private void stubFunctionArns() {
        when(cloudFront.arn(any())).thenAnswer(inv -> "arn:aws:cloudfront::000000000000:" + inv.getArgument(0));
    }

    @Test
    void createsPublicKeyAndExposesIdAndCreatedTime() throws Exception {
        when(cloudFront.createPublicKey(any()))
                .thenAnswer(inv -> withIdentity(inv.<PublicKey>getArgument(0), k -> {
                    k.setId("K36X4X2EO997HM");
                    k.setEtag(ETAG);
                    k.setCreatedTime(MODIFIED);
                }));
        StackResource r = resource(PUBLIC_KEY);

        provisioner.provision(r, json("""
                {"PublicKeyConfig": {"Name": "example-key", "CallerReference": "example-key-1",
                  "EncodedKey": "-----BEGIN PUBLIC KEY-----", "Comment": "first"}}
                """), ctx());

        ArgumentCaptor<PublicKey> captor = ArgumentCaptor.forClass(PublicKey.class);
        verify(cloudFront).createPublicKey(captor.capture());
        assertEquals("example-key", captor.getValue().getName());
        assertEquals("example-key-1", captor.getValue().getCallerReference());
        assertEquals("-----BEGIN PUBLIC KEY-----", captor.getValue().getEncodedKey());
        assertEquals("first", captor.getValue().getComment());
        assertEquals("K36X4X2EO997HM", r.getPhysicalId());
        assertEquals(Map.of("Id", "K36X4X2EO997HM", "CreatedTime", MODIFIED.toString()), r.getAttributes());
    }

    @Test
    void updatesPublicKeyInPlaceWithTheCurrentEtag() throws Exception {
        when(cloudFront.getPublicKey("K36X4X2EO997HM")).thenReturn(publicKey("K36X4X2EO997HM", ETAG));
        when(cloudFront.updatePublicKey(eq("K36X4X2EO997HM"), eq(ETAG), any()))
                .thenAnswer(inv -> withIdentity(inv.<PublicKey>getArgument(2), k -> {
                    k.setId("K36X4X2EO997HM");
                    k.setCreatedTime(MODIFIED);
                }));
        StackResource r = resource(PUBLIC_KEY, "K36X4X2EO997HM", Map.of("Id", "K36X4X2EO997HM"));

        provisioner.provision(r, json("""
                {"PublicKeyConfig": {"Name": "example-key", "CallerReference": "example-key-1",
                  "EncodedKey": "-----BEGIN PUBLIC KEY-----", "Comment": "second"}}
                """), ctx("K36X4X2EO997HM"));

        ArgumentCaptor<PublicKey> captor = ArgumentCaptor.forClass(PublicKey.class);
        verify(cloudFront).updatePublicKey(eq("K36X4X2EO997HM"), eq(ETAG), captor.capture());
        assertEquals("second", captor.getValue().getComment());
        verify(cloudFront, never()).createPublicKey(any());
        assertEquals("K36X4X2EO997HM", r.getPhysicalId());
    }

    @Test
    void publicKeyFieldTheServiceCannotChangeFailsTheUpdate() throws Exception {
        when(cloudFront.getPublicKey("K36X4X2EO997HM")).thenReturn(publicKey("K36X4X2EO997HM", ETAG));
        AwsException immutable = new AwsException("CannotChangeImmutablePublicKeyFields",
                "The caller reference, name, and encoded public key cannot be changed.", 400);
        when(cloudFront.updatePublicKey(eq("K36X4X2EO997HM"), eq(ETAG), any())).thenThrow(immutable);
        StackResource r = resource(PUBLIC_KEY, "K36X4X2EO997HM", Map.of("Id", "K36X4X2EO997HM"));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"PublicKeyConfig": {"Name": "renamed-key", "CallerReference": "example-key-1",
                  "EncodedKey": "-----BEGIN PUBLIC KEY-----"}}
                """), ctx("K36X4X2EO997HM")));

        assertSame(immutable, e);
        verify(cloudFront, never()).createPublicKey(any());
    }

    @Test
    void deletesPublicKeyWithItsEtagAndToleratesItBeingGone() {
        when(cloudFront.getPublicKey("K36X4X2EO997HM")).thenReturn(publicKey("K36X4X2EO997HM", ETAG));
        when(cloudFront.getPublicKey("gone")).thenThrow(
                new AwsException("NoSuchPublicKey", "The specified public key does not exist.", 404));

        provisioner.delete(PUBLIC_KEY, "K36X4X2EO997HM", REGION);
        provisioner.delete(PUBLIC_KEY, "gone", REGION);

        verify(cloudFront).deletePublicKey("K36X4X2EO997HM", ETAG);
        verify(cloudFront, never()).deletePublicKey(eq("gone"), any());
    }

    @Test
    void createsKeyGroupWithItsPublicKeyIdsAndExposesIdAndLastModifiedTime() throws Exception {
        when(cloudFront.createKeyGroup(any()))
                .thenAnswer(inv -> withIdentity(inv.<KeyGroup>getArgument(0), g -> {
                    g.setId(ID);
                    g.setEtag(ETAG);
                    g.setLastModifiedTime(MODIFIED);
                }));
        StackResource r = resource(KEY_GROUP);

        provisioner.provision(r, json("""
                {"KeyGroupConfig": {"Name": "example-key-group", "Comment": "group",
                  "Items": ["K36X4X2EO997HM", "K2JCJMDEHXQW5F"]}}
                """), ctx());

        ArgumentCaptor<KeyGroup> captor = ArgumentCaptor.forClass(KeyGroup.class);
        verify(cloudFront).createKeyGroup(captor.capture());
        assertEquals("example-key-group", captor.getValue().getName());
        assertEquals("group", captor.getValue().getComment());
        assertEquals(List.of("K36X4X2EO997HM", "K2JCJMDEHXQW5F"), captor.getValue().getItems());
        assertEquals(ID, r.getPhysicalId());
        assertEquals(Map.of("Id", ID, "LastModifiedTime", MODIFIED.toString()), r.getAttributes());
    }

    @Test
    void updatesAndDeletesKeyGroupThroughTheEtag() throws Exception {
        when(cloudFront.getKeyGroup(ID)).thenReturn(keyGroup(ID, ETAG));
        when(cloudFront.updateKeyGroup(eq(ID), eq(ETAG), any()))
                .thenAnswer(inv -> withIdentity(inv.<KeyGroup>getArgument(2), g -> {
                    g.setId(ID);
                    g.setLastModifiedTime(MODIFIED.plusSeconds(60));
                }));
        StackResource r = resource(KEY_GROUP, ID, Map.of("Id", ID));

        provisioner.provision(r, json("""
                {"KeyGroupConfig": {"Name": "example-key-group", "Items": ["K2JCJMDEHXQW5F"]}}
                """), ctx(ID));
        provisioner.delete(KEY_GROUP, ID, REGION);

        ArgumentCaptor<KeyGroup> captor = ArgumentCaptor.forClass(KeyGroup.class);
        verify(cloudFront).updateKeyGroup(eq(ID), eq(ETAG), captor.capture());
        assertEquals(List.of("K2JCJMDEHXQW5F"), captor.getValue().getItems());
        verify(cloudFront, never()).createKeyGroup(any());
        verify(cloudFront).deleteKeyGroup(ID, ETAG);
        assertEquals(Map.of("Id", ID, "LastModifiedTime", "2026-09-07T10:16:30Z"), r.getAttributes());
    }

    @Test
    void deleteOfAKeyGroupStillInUseFailsTheStack() {
        when(cloudFront.getKeyGroup(ID)).thenReturn(keyGroup(ID, ETAG));
        AwsException inUse = new AwsException("ResourceInUse",
                "Cannot delete this resource because it is in use.", 409);
        doThrow(inUse).when(cloudFront).deleteKeyGroup(ID, ETAG);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.delete(KEY_GROUP, ID, REGION));

        assertSame(inUse, e);
    }

    @Test
    void createsFunctionPublishesItAndTagsItsArn() throws Exception {
        stubFunctionArns();
        when(cloudFront.createFunction(any()))
                .thenAnswer(inv -> withIdentity(inv.<CloudFrontFunction>getArgument(0), f -> f.setEtag(ETAG)));
        StackResource r = resource(FUNCTION);

        provisioner.provision(r, json("""
                {"Name": "my-function", "AutoPublish": true,
                 "FunctionCode": "function handler(event) { return event.request; }",
                 "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"},
                 "Tags": [{"Key": "Env", "Value": "test"}]}
                """), distributionCtx(null));

        ArgumentCaptor<CloudFrontFunction> captor = ArgumentCaptor.forClass(CloudFrontFunction.class);
        verify(cloudFront).createFunction(captor.capture());
        assertEquals("my-function", captor.getValue().getName());
        assertEquals(wireCode("function handler(event) { return event.request; }"),
                captor.getValue().getFunctionCode());
        assertEquals("example", captor.getValue().getComment());
        assertEquals("cloudfront-js-2.0", captor.getValue().getRuntime());
        verify(cloudFront).publishFunction("my-function", ETAG);
        verify(cloudFront).tagResource(FUNCTION_ARN, Map.of("Env", "test"));
        assertEquals(FUNCTION_ARN, r.getPhysicalId());
        assertEquals(Map.of("FunctionARN", FUNCTION_ARN, "FunctionMetadata.FunctionARN", FUNCTION_ARN,
                "Stage", "LIVE"), r.getAttributes());
    }

    @Test
    void functionWithoutAutoPublishStaysInDevelopment() throws Exception {
        stubFunctionArns();
        when(cloudFront.createFunction(any()))
                .thenAnswer(inv -> withIdentity(inv.<CloudFrontFunction>getArgument(0), f -> f.setEtag(ETAG)));
        StackResource r = resource(FUNCTION);

        provisioner.provision(r, json("""
                {"Name": "my-function", "FunctionCode": "code",
                 "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(null));

        verify(cloudFront, never()).publishFunction(any(), any());
        verify(cloudFront, never()).tagResource(any(), any());
        assertEquals("DEVELOPMENT", r.getAttributes().get("Stage"));
    }

    @Test
    void updatesFunctionInPlaceWithTheDevelopmentEtagAndDropsStaleTags() throws Exception {
        stubFunctionArns();
        when(cloudFront.describeFunction("my-function", null)).thenReturn(function("my-function", ETAG));
        when(cloudFront.updateFunction(eq("my-function"), eq(ETAG), any()))
                .thenAnswer(inv -> withIdentity(inv.<CloudFrontFunction>getArgument(2), f -> f.setEtag(NEW_ETAG)));
        when(cloudFront.listTagsForResource(FUNCTION_ARN)).thenReturn(Map.of("Env", "test", "Old", "x"));
        StackResource r = resource(FUNCTION, FUNCTION_ARN, Map.of("FunctionARN", FUNCTION_ARN));

        provisioner.provision(r, json("""
                {"Name": "my-function", "AutoPublish": true, "FunctionCode": "new code",
                 "FunctionConfig": {"Comment": "second", "Runtime": "cloudfront-js-2.0"},
                 "Tags": [{"Key": "Env", "Value": "test"}]}
                """), distributionCtx(FUNCTION_ARN));

        ArgumentCaptor<CloudFrontFunction> captor = ArgumentCaptor.forClass(CloudFrontFunction.class);
        verify(cloudFront).updateFunction(eq("my-function"), eq(ETAG), captor.capture());
        assertEquals(wireCode("new code"), captor.getValue().getFunctionCode());
        verify(cloudFront, never()).createFunction(any());
        verify(cloudFront).publishFunction("my-function", NEW_ETAG);
        verify(cloudFront).untagResource(FUNCTION_ARN, List.of("Old"));
        assertEquals(FUNCTION_ARN, r.getPhysicalId());
        assertFalse(provisioner.hasReplacementUpdate(r));
    }

    @Test
    void functionNameChangeCreatesANewFunctionAndDeletesTheOldOneAfterTheUpdate() throws Exception {
        stubFunctionArns();
        when(cloudFront.createFunction(any()))
                .thenAnswer(inv -> withIdentity(inv.<CloudFrontFunction>getArgument(0), f -> f.setEtag(NEW_ETAG)));
        when(cloudFront.describeFunction("my-function", null)).thenReturn(function("my-function", ETAG));
        StackResource r = resource(FUNCTION, FUNCTION_ARN, Map.of("FunctionARN", FUNCTION_ARN));

        provisioner.provision(r, json("""
                {"Name": "renamed-function", "FunctionCode": "code",
                 "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(FUNCTION_ARN));

        verify(cloudFront, never()).updateFunction(any(), any(), any());
        String newArn = "arn:aws:cloudfront::000000000000:function/renamed-function";
        assertEquals(newArn, r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r), "the old function is owed a cleanup");
        assertEquals(FUNCTION_ARN, provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(cloudFront).deleteFunction("my-function", ETAG);
    }

    @Test
    void functionCreatedByARenameIsDeletedWhenPublishingFails() throws Exception {
        stubFunctionArns();
        CloudFrontFunction created = function("renamed-function", NEW_ETAG);
        when(cloudFront.createFunction(any())).thenReturn(created);
        when(cloudFront.describeFunction("renamed-function", null)).thenReturn(created);
        AwsException publishFailure = new AwsException("InvalidIfMatchVersion",
                "The If-Match version is missing or not valid for the resource.", 400);
        when(cloudFront.publishFunction("renamed-function", NEW_ETAG)).thenThrow(publishFailure);
        StackResource r = resource(FUNCTION, FUNCTION_ARN, Map.of("FunctionARN", FUNCTION_ARN));

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"Name": "renamed-function", "AutoPublish": true, "FunctionCode": "code",
                 "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(FUNCTION_ARN)));

        assertSame(publishFailure, e);
        verify(cloudFront).deleteFunction("renamed-function", NEW_ETAG);
        verify(cloudFront, never()).deleteFunction(eq("my-function"), any());
        assertEquals(FUNCTION_ARN, r.getPhysicalId(), "the resource still names the old function");
    }

    @Test
    void functionWhoseNameIsTakenFailsWithoutTouchingTheOtherFunction() throws Exception {
        stubFunctionArns();
        AwsException taken = new AwsException("FunctionAlreadyExists",
                "A function with the same name already exists in this AWS account.", 409);
        when(cloudFront.createFunction(any())).thenThrow(taken);
        StackResource r = resource(FUNCTION);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"Name": "my-function", "AutoPublish": true, "FunctionCode": "code",
                 "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(null)));

        assertSame(taken, e);
        assertNull(r.getPhysicalId(), "a failed create names nothing the rollback would delete");
        verify(cloudFront, never()).deleteFunction(any(), any());
        verify(cloudFront, never()).publishFunction(any(), any());
    }

    @Test
    void functionWithoutRuntimeIsAValidationError() throws Exception {
        StackResource r = resource(FUNCTION);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"Name": "my-function", "FunctionCode": "code", "FunctionConfig": {"Comment": "example"}}
                """), distributionCtx(null)));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals(FUNCTION + " requires FunctionConfig.Runtime", e.getMessage());
        verify(cloudFront, never()).createFunction(any());
    }

    @Test
    void functionWithoutCommentIsAValidationError() throws Exception {
        StackResource r = resource(FUNCTION);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"Name": "my-function", "FunctionCode": "code", "FunctionConfig": {"Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(null)));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals(FUNCTION + " requires FunctionConfig.Comment", e.getMessage());
        verify(cloudFront, never()).createFunction(any());
    }

    @Test
    void functionWithAnEmptyCommentIsCreated() throws Exception {
        when(cloudFront.arn("function/my-function")).thenReturn(FUNCTION_ARN);
        when(cloudFront.createFunction(any())).thenReturn(function("my-function", ETAG));
        StackResource r = resource(FUNCTION);

        provisioner.provision(r, json("""
                {"Name": "my-function", "FunctionCode": "code",
                 "FunctionConfig": {"Comment": "", "Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(null));

        assertEquals(FUNCTION_ARN, r.getPhysicalId());
    }

    @Test
    void functionWithAnAutoPublishThatIsNotABooleanIsAValidationError() throws Exception {
        StackResource r = resource(FUNCTION);

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, json("""
                {"Name": "my-function", "AutoPublish": "yes", "FunctionCode": "code",
                 "FunctionConfig": {"Comment": "example", "Runtime": "cloudfront-js-2.0"}}
                """), distributionCtx(null)));

        assertEquals("ValidationError", e.getErrorCode());
        assertEquals(FUNCTION + " AutoPublish must be a boolean", e.getMessage());
        verify(cloudFront, never()).createFunction(any());
    }

    @Test
    void deleteOfAFunctionAlreadyGoneStillDropsItsTags() {
        when(cloudFront.describeFunction("my-function", null)).thenThrow(
                new AwsException("NoSuchFunctionExists", "The specified function does not exist.", 404));
        when(cloudFront.listTagsForResource(FUNCTION_ARN)).thenReturn(Map.of("Env", "test"));

        provisioner.delete(FUNCTION, FUNCTION_ARN, REGION);

        verify(cloudFront, never()).deleteFunction(any(), any());
        verify(cloudFront).untagResource(FUNCTION_ARN, List.of("Env"));
    }

    @Test
    void deletesFunctionWithItsEtagAndRemovesItsTags() {
        when(cloudFront.describeFunction("my-function", null)).thenReturn(function("my-function", ETAG));
        when(cloudFront.listTagsForResource(FUNCTION_ARN)).thenReturn(Map.of("Env", "test"));
        when(cloudFront.describeFunction("gone", null)).thenThrow(
                new AwsException("NoSuchFunctionExists", "The specified function does not exist.", 404));

        provisioner.delete(FUNCTION, FUNCTION_ARN, REGION);
        provisioner.delete(FUNCTION, "arn:aws:cloudfront::000000000000:function/gone", REGION);

        verify(cloudFront).deleteFunction("my-function", ETAG);
        verify(cloudFront).untagResource(FUNCTION_ARN, List.of("Env"));
        verify(cloudFront, never()).deleteFunction(eq("gone"), any());
    }

    @Test
    void functionWithoutNameOrCodeIsAValidationError() throws Exception {
        for (String[] missing : new String[][] {
                {"Name", """
                        {"FunctionCode": "code", "FunctionConfig": {"Comment": "c", "Runtime": "cloudfront-js-2.0"}}
                        """},
                {"FunctionCode", """
                        {"Name": "my-function", "FunctionConfig": {"Comment": "c", "Runtime": "cloudfront-js-2.0"}}
                        """},
                {"FunctionConfig", """
                        {"Name": "my-function", "FunctionCode": "code"}
                        """}}) {
            StackResource r = resource(FUNCTION);
            AwsException e = assertThrows(AwsException.class,
                    () -> provisioner.provision(r, json(missing[1]), distributionCtx(null)));
            assertEquals("ValidationError", e.getErrorCode());
            assertEquals(FUNCTION + " requires " + missing[0], e.getMessage());
            assertNull(r.getPhysicalId());
        }
        verify(cloudFront, never()).createFunction(any());
    }

    @Test
    void provisionsConnectionGroupWithEveryReadOnlyGetAttTheSchemaDeclares() throws Exception {
        when(cloudFront.createConnectionGroup(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ConnectionGroup g = new ConnectionGroup();
                    g.setName(inv.getArgument(0, String.class));
                    g.setAnycastIpListId(inv.getArgument(1, String.class));
                    g.setEnabled(inv.getArgument(2, Boolean.class));
                    g.setIpv6Enabled(inv.getArgument(3, Boolean.class));
                    g.setViewerFallbackDistributionId(inv.getArgument(4, String.class));
                    g.setTags(inv.getArgument(5));
                    g.setId("cg_2yb6uj74B4PCbfhT31WFdiS");
                    g.setArn("arn:aws:cloudfront::000000000000:connection-group/cg_2yb6uj74B4PCbfhT31WFdiS");
                    g.setRoutingEndpoint("d111111abcdef8.cloudfront.net");
                    g.setStatus("Deployed");
                    g.setIsDefault(false);
                    g.setEtag(ETAG);
                    g.setCreatedTime(MODIFIED);
                    g.setLastModifiedTime(MODIFIED);
                    return g;
                });

        StackResource r = resource(CONNECTION_GROUP_TYPE);
        provisioner.provision(r, json("""
                {"Name": "edge-group", "Enabled": true, "Ipv6Enabled": false}
                """), connectionCtx(null));

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> enabled = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> ipv6 = ArgumentCaptor.forClass(Boolean.class);
        verify(cloudFront).createConnectionGroup(name.capture(), any(), enabled.capture(),
                ipv6.capture(), any(), any());
        assertEquals("edge-group", name.getValue());
        assertEquals(Boolean.TRUE, enabled.getValue());
        assertEquals(Boolean.FALSE, ipv6.getValue());
        verify(cloudFront, never()).updateConnectionGroup(any(), any(), any(), any(), any(), any());
        assertEquals("cg_2yb6uj74B4PCbfhT31WFdiS", r.getPhysicalId());
        assertEquals(Map.of("Id", "cg_2yb6uj74B4PCbfhT31WFdiS",
                "Arn", "arn:aws:cloudfront::000000000000:connection-group/cg_2yb6uj74B4PCbfhT31WFdiS",
                "RoutingEndpoint", "d111111abcdef8.cloudfront.net",
                "Status", "Deployed",
                "IsDefault", "false",
                "ETag", ETAG,
                "CreatedTime", MODIFIED.toString(),
                "LastModifiedTime", MODIFIED.toString()), r.getAttributes());
    }

    /**
     * The connection-group members are scalars the template may hand an intrinsic, so the context's
     * {@code resolveOptional} answers them: an object node reads as its resolved value, as
     * {@code Ref} and {@code Fn::Sub} names and booleans do.
     */
    private ProvisionContext connectionCtx(String priorPhysicalId) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return "";
            }
            return node.isValueNode() ? node.asText() : "resolved-name";
        });
        return new ProvisionContext(engine, REGION, "000000000000", "my-stack", priorPhysicalId);
    }

    @Test
    void aRefResolvedNameAndBooleansCreateTheGroup() throws Exception {
        when(cloudFront.createConnectionGroup(eq("resolved-name"), any(), eq(Boolean.TRUE), any(),
                any(), any())).thenReturn(connectionGroup("cg_new", ETAG));
        StackResource r = resource(CONNECTION_GROUP_TYPE);

        provisioner.provision(r, json("""
                {"Name": {"Fn::Sub": "${AWS::StackName}-group"}, "Enabled": true, "Ipv6Enabled": false}
                """), connectionCtx(null));

        assertEquals("cg_new", r.getPhysicalId());
        verify(cloudFront, never()).updateConnectionGroup(any(), any(), any(), any(), any(), any(),
                any(), any());
    }

    private static ConnectionGroup connectionGroup(String id, String etag) {
        ConnectionGroup g = new ConnectionGroup();
        g.setId(id);
        g.setEtag(etag);
        return g;
    }

    @Test
    void updatesConnectionGroupInPlaceWithThePriorEtagAndCarriesItsViewerFallback() throws Exception {
        ConnectionGroup prior = connectionGroup("cg_prior", ETAG);
        prior.setName("edge-group");
        prior.setAnycastIpListId("aip_prior");
        prior.setEnabled(true);
        prior.setIpv6Enabled(true);
        prior.setLastModifiedTime(MODIFIED);
        when(cloudFront.getConnectionGroup("cg_prior")).thenReturn(prior);
        when(cloudFront.updateConnectionGroup(eq("cg_prior"), eq(ETAG), eq("aip_prior"), eq(Boolean.FALSE),
                eq(Boolean.TRUE), eq("E12X3MPWexample"), ArgumentMatchers.isNull(), any()))
                .thenAnswer(inv -> withIdentity(prior, g -> g.setEtag(NEW_ETAG)));

        StackResource r = resource(CONNECTION_GROUP_TYPE, "cg_prior", Map.of("Id", "cg_prior"));
        provisioner.provision(r, json("""
                {"Name": "edge-group", "Enabled": false, "ViewerFallback": {"DistributionId": "E12X3MPWexample"}}
                """), connectionCtx("cg_prior"));

        verify(cloudFront, never()).createConnectionGroup(any(), any(), any(), any(), any(), any());
        assertEquals("cg_prior", r.getPhysicalId());
        assertEquals(NEW_ETAG, r.getAttributes().get("ETag"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUpdateReconcilesTheGroupsTags() throws Exception {
        ConnectionGroup prior = connectionGroup("cg_prior", ETAG);
        prior.setName("edge-group");
        prior.setArn("arn:aws:cloudfront::000000000000:connection-group/cg_prior");
        when(cloudFront.getConnectionGroup("cg_prior")).thenReturn(prior);
        when(cloudFront.updateConnectionGroup(eq("cg_prior"), eq(ETAG), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), any())).thenReturn(prior);

        StackResource r = resource(CONNECTION_GROUP_TYPE, "cg_prior", Map.of("Id", "cg_prior"));
        provisioner.provision(r, json("""
                {"Name": "edge-group", "Tags": [{"Key": "Env", "Value": "test"}]}
                """), connectionCtx("cg_prior"));

        ArgumentCaptor<Map<String, String>> tags =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(cloudFront).updateConnectionGroup(eq("cg_prior"), eq(ETAG), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), tags.capture());
        assertEquals(Map.of("Env", "test"), tags.getValue());
    }

    @Test
    void anUpdateWithAnExplicitlyNullViewerFallbackClearsIt() throws Exception {
        ConnectionGroup prior = connectionGroup("cg_prior", ETAG);
        prior.setName("edge-group");
        prior.setViewerFallbackDistributionId("E12X3MPWexample");
        when(cloudFront.getConnectionGroup("cg_prior")).thenReturn(prior);
        when(cloudFront.updateConnectionGroup(eq("cg_prior"), eq(ETAG), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                eq(Boolean.TRUE), any())).thenReturn(prior);

        StackResource r = resource(CONNECTION_GROUP_TYPE, "cg_prior", Map.of("Id", "cg_prior"));
        provisioner.provision(r, json("""
                {"Name": "edge-group", "ViewerFallback": null}
                """), connectionCtx("cg_prior"));

        verify(cloudFront).updateConnectionGroup(eq("cg_prior"), eq(ETAG), ArgumentMatchers.isNull(),
                ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(),
                eq(Boolean.TRUE), any());
    }

    @Test
    void aChangedNameCreatesANewGroupAndOwesTheOldOneCleanup() throws Exception {
        ConnectionGroup prior = connectionGroup("cg_prior", ETAG);
        prior.setName("edge-group");
        prior.setEnabled(false);
        when(cloudFront.getConnectionGroup("cg_prior")).thenReturn(prior);
        ConnectionGroup created = connectionGroup("cg_new", NEW_ETAG);
        created.setEnabled(false);
        when(cloudFront.createConnectionGroup(eq("renamed-group"), any(), any(), any(), any(), any()))
                .thenReturn(created);
        when(cloudFront.getConnectionGroup("cg_new")).thenReturn(created);
        StackResource r = resource(CONNECTION_GROUP_TYPE, "cg_prior", Map.of("Id", "cg_prior"));

        provisioner.provision(r, json("""
                {"Name": "renamed-group", "Enabled": false}
                """), connectionCtx("cg_prior"));

        verify(cloudFront, never()).updateConnectionGroup(any(), any(), any(), any(), any(), any(),
                any(), any());
        assertEquals("cg_new", r.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(r), "the old group is owed a cleanup");
        assertEquals("cg_prior", provisioner.updateCleanupPhysicalId(r));
        provisioner.completeUpdate(r);
        verify(cloudFront).deleteConnectionGroup("cg_prior", ETAG);
    }

    @Test
    void rollingBackANameReplacementDeletesTheNewGroupAndRestoresThePriorId() throws Exception {
        ConnectionGroup prior = connectionGroup("cg_prior", ETAG);
        prior.setName("edge-group");
        prior.setEnabled(false);
        when(cloudFront.getConnectionGroup("cg_prior")).thenReturn(prior);
        ConnectionGroup created = connectionGroup("cg_new", NEW_ETAG);
        created.setEnabled(false);
        when(cloudFront.createConnectionGroup(eq("renamed-group"), any(), any(), any(), any(), any()))
                .thenReturn(created);
        when(cloudFront.getConnectionGroup("cg_new")).thenReturn(created);
        StackResource r = resource(CONNECTION_GROUP_TYPE, "cg_prior", Map.of("Id", "cg_prior"));

        provisioner.provision(r, json("""
                {"Name": "renamed-group", "Enabled": false}
                """), connectionCtx("cg_prior"));
        assertEquals("cg_new", r.getPhysicalId());

        assertTrue(provisioner.rollbackUpdate(r));

        verify(cloudFront).deleteConnectionGroup("cg_new", NEW_ETAG);
        assertEquals("cg_prior", r.getPhysicalId(), "the prior id is named again");
        assertEquals("cg_prior", r.getAttributes().get("Id"));
    }

    @Test
    void deleteOfAnEnabledGroupDisablesItFirstAndDeletesWithTheNewEtag() {
        ConnectionGroup group = connectionGroup(ID, ETAG);
        group.setEnabled(true);
        when(cloudFront.getConnectionGroup(ID)).thenReturn(group);
        ConnectionGroup disabled = connectionGroup(ID, NEW_ETAG);
        disabled.setEnabled(false);
        when(cloudFront.updateConnectionGroup(ID, ETAG, null, false, null, null)).thenReturn(disabled);

        provisioner.delete(CONNECTION_GROUP_TYPE, ID, REGION);

        verify(cloudFront).updateConnectionGroup(ID, ETAG, null, false, null, null);
        verify(cloudFront).deleteConnectionGroup(ID, NEW_ETAG);
    }

    @Test
    void deleteOfADisabledGroupGoesStraightToTheService() {
        ConnectionGroup group = connectionGroup(ID, ETAG);
        group.setEnabled(false);
        when(cloudFront.getConnectionGroup(ID)).thenReturn(group);

        provisioner.delete(CONNECTION_GROUP_TYPE, ID, REGION);

        verify(cloudFront, never()).updateConnectionGroup(any(), any(), any(), any(), any(), any());
        verify(cloudFront).deleteConnectionGroup(ID, ETAG);
    }

    @Test
    void deletesConnectionGroupWithItsOwnNotFoundCodeTolerated() throws Exception {
        when(cloudFront.getConnectionGroup(ID)).thenReturn(connectionGroup(ID, ETAG));

        provisioner.delete(CONNECTION_GROUP_TYPE, ID, REGION);
        verify(cloudFront).deleteConnectionGroup(ID, ETAG);

        when(cloudFront.getConnectionGroup(ID)).thenThrow(
                new AwsException("EntityNotFound", "The connection group " + ID + " does not exist.", 404));
        provisioner.delete(CONNECTION_GROUP_TYPE, ID, REGION);
        verify(cloudFront, Mockito.times(1)).deleteConnectionGroup(any(), any());
    }

    private static DistributionTenant tenant(String id, String etag, String name) {
        DistributionTenant t = new DistributionTenant();
        t.setId(id);
        t.setEtag(etag);
        t.setName(name);
        t.setArn("arn:aws:cloudfront::000000000000:distribution-tenant/" + id);
        t.setStatus("Deployed");
        t.setCreatedTime(MODIFIED);
        t.setLastModifiedTime(MODIFIED);
        return t;
    }

    @Test
    void provisionsDistributionTenantWithEveryReadOnlyGetAttTheSchemaDeclares() throws Exception {
        when(cloudFront.createDistributionTenant(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(tenant("dt_abc", ETAG, "acme"));
        StackResource r = resource(TENANT_TYPE);

        provisioner.provision(r, json("""
                {"DistributionId": "EDIST", "Name": "acme", "Domains": ["acme.example.com"],
                 "Enabled": true, "ConnectionGroupId": "cg_x",
                 "Tags": [{"Key": "env", "Value": "test"}],
                 "Parameters": [{"Name": "origin_path", "Value": "/acme"}],
                 "Customizations": {"WebAcl": {"Action": "override", "Arn": "arn:acl"},
                                    "Certificate": {"Arn": "arn:cert"},
                                    "GeoRestrictions": {"RestrictionType": "whitelist", "Locations": ["US"]}},
                 "ManagedCertificateRequest": {"ValidationTokenHost": "cloudfront"}}
                """), connectionCtx(null));

        ArgumentCaptor<TenantCustomizations> customizations = ArgumentCaptor.forClass(TenantCustomizations.class);
        verify(cloudFront).createDistributionTenant(eq("EDIST"), eq("acme"), eq(List.of("acme.example.com")),
                eq(Map.of("env", "test")), customizations.capture(), eq(Map.of("origin_path", "/acme")),
                eq("cg_x"), eq(Boolean.TRUE));
        assertEquals("override", customizations.getValue().getWebAclAction());
        assertEquals("arn:acl", customizations.getValue().getWebAclArn());
        assertEquals("arn:cert", customizations.getValue().getCertificateArn());
        assertEquals("whitelist", customizations.getValue().getGeoRestrictionType());
        assertEquals(List.of("US"), customizations.getValue().getGeoLocations());
        assertEquals("dt_abc", r.getPhysicalId());
        assertEquals(Map.of("Id", "dt_abc",
                "Arn", "arn:aws:cloudfront::000000000000:distribution-tenant/dt_abc",
                "ETag", ETAG,
                "Status", "Deployed",
                "CreatedTime", MODIFIED.toString(),
                "LastModifiedTime", MODIFIED.toString()), r.getAttributes());
    }

    @Test
    void aTenantNeedsItsRequiredProperties() throws Exception {
        assertThrows(AwsException.class, () -> provisioner.provision(resource(TENANT_TYPE), json("""
                {"Name": "acme", "Domains": ["a.example.com"]}
                """), connectionCtx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(TENANT_TYPE), json("""
                {"DistributionId": "EDIST", "Domains": ["a.example.com"]}
                """), connectionCtx(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(TENANT_TYPE), json("""
                {"DistributionId": "EDIST", "Name": "acme"}
                """), connectionCtx(null)));
    }

    @Test
    void updatesATenantInPlaceDeclarativelyWithThePriorEtag() throws Exception {
        DistributionTenant prior = tenant("dt_prior", ETAG, "acme");
        when(cloudFront.getDistributionTenant("dt_prior")).thenReturn(prior);
        ConnectionGroup defaultGroup = new ConnectionGroup();
        defaultGroup.setId("cg_default");
        when(cloudFront.ensureDefaultConnectionGroup()).thenReturn(defaultGroup);
        when(cloudFront.listTagsForResource(prior.getArn())).thenReturn(Map.of("stale", "1"));
        when(cloudFront.updateDistributionTenant(eq("dt_prior"), eq(ETAG), eq("EDIST"),
                eq(List.of("b.example.com")), any(), eq(Map.of()), eq("cg_default"),
                eq(Boolean.TRUE))).thenReturn(tenant("dt_prior", NEW_ETAG, "acme"));
        StackResource r = resource(TENANT_TYPE, "dt_prior", Map.of("Id", "dt_prior"));

        provisioner.provision(r, json("""
                {"DistributionId": "EDIST", "Name": "acme", "Domains": ["b.example.com"],
                 "Tags": [{"Key": "env", "Value": "test"}]}
                """), connectionCtx("dt_prior"));

        assertEquals("dt_prior", r.getPhysicalId());
        assertEquals(NEW_ETAG, r.getAttributes().get("ETag"));
        verify(cloudFront, never()).createDistributionTenant(any(), any(), any(), any(), any(), any(), any(),
                any());
        ArgumentCaptor<TenantCustomizations> cleared = ArgumentCaptor.forClass(TenantCustomizations.class);
        verify(cloudFront).updateDistributionTenant(eq("dt_prior"), eq(ETAG), eq("EDIST"), any(),
                cleared.capture(), any(), any(), any());
        assertTrue(cleared.getValue().isEmpty(), "a template that drops Customizations clears them");
        verify(cloudFront).untagResource(prior.getArn(), List.of("stale"));
        verify(cloudFront).tagResource(prior.getArn(), Map.of("env", "test"));
    }

    @Test
    void anUpdateThatDropsTheConnectionGroupMovesTheTenantBackToTheDefaultGroup() throws Exception {
        DistributionTenant prior = tenant("dt_prior", ETAG, "acme");
        when(cloudFront.getDistributionTenant("dt_prior")).thenReturn(prior);
        ConnectionGroup defaultGroup = new ConnectionGroup();
        defaultGroup.setId("cg_default");
        when(cloudFront.ensureDefaultConnectionGroup()).thenReturn(defaultGroup);
        when(cloudFront.updateDistributionTenant(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(tenant("dt_prior", NEW_ETAG, "acme"));
        StackResource r = resource(TENANT_TYPE, "dt_prior", Map.of("Id", "dt_prior"));

        provisioner.provision(r, json("""
                {"DistributionId": "EDIST", "Name": "acme", "Domains": ["b.example.com"]}
                """), connectionCtx("dt_prior"));

        verify(cloudFront).updateDistributionTenant(eq("dt_prior"), eq(ETAG), eq("EDIST"), any(), any(), any(),
                eq("cg_default"), any());
    }

    @Test
    void aChangedNameReplacesTheTenantAndTheCleanupDeletesTheOldOne() throws Exception {
        DistributionTenant prior = tenant("dt_prior", ETAG, "acme");
        prior.setEnabled(false);
        when(cloudFront.getDistributionTenant("dt_prior")).thenReturn(prior);
        DistributionTenant created = tenant("dt_new", NEW_ETAG, "renamed");
        when(cloudFront.createDistributionTenant(eq("EDIST"), eq("renamed"), any(), any(), any(), any(), any(),
                any())).thenReturn(created);
        StackResource r = resource(TENANT_TYPE, "dt_prior", Map.of("Id", "dt_prior"));

        provisioner.provision(r, json("""
                {"DistributionId": "EDIST", "Name": "renamed", "Domains": ["a.example.com"]}
                """), connectionCtx("dt_prior"));

        assertEquals("dt_new", r.getPhysicalId());
        verify(cloudFront, never()).updateDistributionTenant(any(), any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void deleteOfAnEnabledTenantDisablesItFirstAndDeletesWithTheNewEtag() {
        DistributionTenant enabled = tenant(ID, ETAG, "acme");
        enabled.setEnabled(true);
        when(cloudFront.getDistributionTenant(ID)).thenReturn(enabled);
        DistributionTenant disabled = tenant(ID, NEW_ETAG, "acme");
        when(cloudFront.updateDistributionTenant(ID, ETAG, null, null, null, null, null, false))
                .thenReturn(disabled);

        provisioner.delete(TENANT_TYPE, ID, REGION);

        verify(cloudFront).updateDistributionTenant(ID, ETAG, null, null, null, null, null, false);
        verify(cloudFront).deleteDistributionTenant(ID, NEW_ETAG);
    }

    @Test
    void deleteOfADisabledTenantGoesStraightToTheServiceAndToleratesOnlyItsOwnNotFoundCode() {
        DistributionTenant disabled = tenant(ID, ETAG, "acme");
        disabled.setEnabled(false);
        when(cloudFront.getDistributionTenant(ID)).thenReturn(disabled);

        provisioner.delete(TENANT_TYPE, ID, REGION);
        verify(cloudFront, never()).updateDistributionTenant(any(), any(), any(), any(), any(), any(), any(),
                any());
        verify(cloudFront).deleteDistributionTenant(ID, ETAG);

        when(cloudFront.getDistributionTenant(ID)).thenThrow(
                new AwsException("EntityNotFound", "The specified distribution tenant doesn't exist.", 404));
        provisioner.delete(TENANT_TYPE, ID, REGION);
        verify(cloudFront, Mockito.times(1)).deleteDistributionTenant(any(), any());

        doThrow(new AwsException("AccessDenied", "no", 403)).when(cloudFront).getDistributionTenant(ID);
        assertThrows(AwsException.class, () -> provisioner.delete(TENANT_TYPE, ID, REGION));
    }
}

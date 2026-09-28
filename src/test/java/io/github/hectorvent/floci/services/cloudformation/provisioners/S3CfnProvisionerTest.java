package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::S3::Bucket} and {@code AWS::S3::BucketPolicy}. */
class S3CfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String NO_DUAL_STACK_REGION = "eu-isoe-west-1";

    private S3Service s3;
    private S3CfnProvisioner provisioner;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Service.class);
        provisioner = new S3CfnProvisioner(s3);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static StackResource resource(String logicalId, String type) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        return r;
    }

    private StackResource provisionBucket(String json) {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        provisioner.provision(r, props(json), ctx);
        return r;
    }

    @Test
    void refIsTheBucketNameAndGetAttExposesTheDocumentedAttributes() {
        StackResource r = provisionBucket("""
                {"BucketName": "my-bucket"}
                """);

        verify(s3).createBucket("my-bucket", REGION);
        assertEquals("my-bucket", r.getPhysicalId(), "Ref is the bucket name");
        // Every readOnlyProperty in aws-s3-bucket.json, plus BucketName which the template engine
        // resolves for Fn::GetAtt.
        assertEquals(Map.of(
                "Arn", "arn:aws:s3:::my-bucket",
                "DomainName", "my-bucket.s3.amazonaws.com",
                "RegionalDomainName", "my-bucket.s3.us-east-1.amazonaws.com",
                "DualStackDomainName", "my-bucket.s3.dualstack.us-east-1.amazonaws.com",
                // us-east-1 is one of the nine regions that keep the legacy dash form, and AWS's
                // WebsiteURL ends with a slash (the AWS::S3::Bucket schema example).
                "WebsiteURL", "http://my-bucket.s3-website-us-east-1.amazonaws.com/",
                "BucketName", "my-bucket"), r.getAttributes());
    }

    /**
     * The defect Greptile flagged on the migration PR, pre-existing rather than introduced there:
     * an unnamed bucket was renamed on every update, creating a second bucket and orphaning the
     * first with its objects. BucketName is createOnly in aws-s3-bucket.json, so an unchanged
     * template must keep its physical id.
     */
    @Test
    void anUnnamedBucketKeepsItsNameAcrossUpdates() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("my-stack-bucket-abc123def456");
        provisioner.provision(r, props("{}"),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack",
                        "my-stack-bucket-abc123def456"));

        assertEquals("my-stack-bucket-abc123def456", r.getPhysicalId());
        // The bucket already exists under this name. Outside us-east-1 CreateBucket would answer
        // BucketAlreadyOwnedByYou, so the update must not create; it still reconciles configuration.
        verify(s3, never()).createBucket(anyString(), anyString());
        verify(s3).deleteBucketCors("my-stack-bucket-abc123def456");
    }

    @Test
    void anUpdateThatRenamesTheBucketStillCreatesIt() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("my-stack-bucket-abc123def456");
        provisioner.provision(r, props("{\"BucketName\": \"renamed\"}"),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack",
                        "my-stack-bucket-abc123def456"));

        // A replacing update: the derived name differs from the prior id, so the new bucket must
        // be created. This is why the guard asks reusesPriorEntity and not isUpdate.
        assertEquals("renamed", r.getPhysicalId());
        verify(s3).createBucket("renamed", REGION);
    }

    /** ISO-E publishes no S3 dual-stack endpoint, so the attribute is absent rather than invented. */
    @Test
    void aBucketInARegionWithoutS3DualStackHasNoDualStackDomainName() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        provisioner.provision(r, props("{\"BucketName\": \"my-bucket\"}"),
                new ProvisionContext(ctx.engine(), NO_DUAL_STACK_REGION, "000000000000", "my-stack"));

        assertEquals("my-bucket.s3.eu-isoe-west-1.cloud.adc-e.uk", r.getAttributes().get("RegionalDomainName"));
        assertFalse(r.getAttributes().containsKey("DualStackDomainName"));
    }

    /**
     * An update arrives with the prior attributes already on the resource, so a dual-stack host
     * recorded before the region's availability was known has to be removed, not just not written.
     */
    @Test
    void anUpdateInARegionWithoutS3DualStackDropsAStaleDualStackDomainName() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("my-bucket");
        r.getAttributes().put("DualStackDomainName", "my-bucket.s3.dualstack.eu-isoe-west-1.cloud.adc-e.uk");
        provisioner.provision(r, props("{\"BucketName\": \"my-bucket\"}"),
                new ProvisionContext(ctx.engine(), NO_DUAL_STACK_REGION, "000000000000", "my-stack", "my-bucket"));

        assertFalse(r.getAttributes().containsKey("DualStackDomainName"), "attributes: " + r.getAttributes());
    }

    @Test
    void anUnnamedBucketGetsALowerCasedStackScopedName() {
        StackResource r = provisionBucket("{}");

        String name = r.getPhysicalId();
        assertTrue(name.startsWith("my-stack-bucket-"), name);
        assertEquals(name.toLowerCase(), name, "S3 bucket names must be lower case");
        assertTrue(name.length() <= 63, "bucket names cap at 63 characters: " + name);
    }

    /**
     * A bucket with no CORS block has its configuration cleared rather than left alone, so an
     * update that removes CorsConfiguration actually removes it from the bucket.
     */
    @Test
    void absentCorsConfigurationClearsAnyExistingRules() {
        provisionBucket("""
                {"BucketName": "b"}
                """);

        verify(s3).deleteBucketCors("b");
        verify(s3, never()).putBucketCors(anyString(), anyString());
    }

    @Test
    void corsRulesBecomeS3CorsConfigurationXml() {
        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);

        provisionBucket("""
                {
                  "BucketName": "b",
                  "CorsConfiguration": {
                    "CorsRules": [
                      {
                        "Id": "rule-1",
                        "AllowedHeaders": ["*"],
                        "AllowedMethods": ["GET", "PUT"],
                        "AllowedOrigins": ["https://example.com"],
                        "ExposedHeaders": ["ETag"],
                        "MaxAge": "3600"
                      }
                    ]
                  }
                }
                """);

        verify(s3).putBucketCors(anyString(), xml.capture());
        String body = xml.getValue();
        assertTrue(body.contains("<ID>rule-1</ID>"), body);
        assertTrue(body.contains("<AllowedMethod>GET</AllowedMethod>"), body);
        assertTrue(body.contains("<AllowedMethod>PUT</AllowedMethod>"), body);
        assertTrue(body.contains("<AllowedOrigin>https://example.com</AllowedOrigin>"), body);
        // The CFN property is ExposedHeaders but the S3 XML element is ExposeHeader.
        assertTrue(body.contains("<ExposeHeader>ETag</ExposeHeader>"), body);
        assertTrue(body.contains("<MaxAgeSeconds>3600</MaxAgeSeconds>"), body);
    }

    @Test
    void anEmptyCorsRuleListIsTreatedAsNoCors() {
        provisionBucket("""
                {"BucketName": "b", "CorsConfiguration": {"CorsRules": []}}
                """);

        verify(s3).deleteBucketCors("b");
        verify(s3, never()).putBucketCors(anyString(), anyString());
    }

    @Test
    void versioningIsAppliedOnlyWhenAStatusIsGiven() {
        provisionBucket("""
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Enabled"}}
                """);
        verify(s3).putBucketVersioning("b", "Enabled");

        provisionBucket("""
                {"BucketName": "c"}
                """);
        verify(s3, never()).putBucketVersioning("c", null);
    }

    /**
     * primaryIdentifier in the registry schema is /properties/Bucket, so the bucket name is the
     * physical id. The type has no readOnlyProperties, so it has no Fn::GetAtt and the attribute
     * map stays empty.
     */
    @Test
    void aBucketPolicyIsIdentifiedByItsBucketAndCarriesNoAttributes() {
        StackResource r = resource("Policy", "AWS::S3::BucketPolicy");
        provisioner.provision(r, props("""
                {"Bucket": "b", "PolicyDocument": {"Version": "2012-10-17"}}
                """), ctx);

        assertEquals("b", r.getPhysicalId());
        assertTrue(r.getAttributes().isEmpty());
    }

    /**
     * provision runs again on every UpdateStack. The id follows the bucket, which is create-only,
     * so an unchanged policy keeps its id and does not look like a replaced resource.
     */
    @Test
    void aBucketPolicyKeepsItsIdAcrossUpdates() {
        StackResource r = resource("Policy", "AWS::S3::BucketPolicy");
        r.setPhysicalId("b");
        provisioner.provision(r, props("""
                {"Bucket": "b", "PolicyDocument": {"Version": "2012-10-17"}}
                """),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", "b"));

        assertEquals("b", r.getPhysicalId());
    }

    @Test
    void deletingABucketReachesTheService() {
        provisioner.delete("AWS::S3::Bucket", "b", REGION);

        verify(s3).deleteBucket("b");
        verify(s3, never()).deleteBucketPolicy(anyString());
    }

    @Test
    void deletingABucketPolicyClearsItFromTheBucketAndLeavesTheBucket() {
        provisioner.delete("AWS::S3::BucketPolicy", "b", REGION);

        verify(s3).deleteBucketPolicy("b");
        verify(s3, never()).deleteBucket(anyString());
    }

    /** The bucket in the same stack may be deleted first, leaving no policy to clear. */
    @Test
    void deletingTheBucketPolicyOfAVanishedBucketIsTolerated() {
        doThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404))
                .when(s3).deleteBucketPolicy("gone");

        provisioner.delete("AWS::S3::BucketPolicy", "gone", REGION);

        verify(s3).deleteBucketPolicy("gone");
    }

    @Test
    void aBucketPolicyDeleteThatFailsForAnyOtherReasonPropagates() {
        doThrow(new AwsException("AccessDenied", "nope", 403)).when(s3).deleteBucketPolicy("b");

        assertThrows(AwsException.class, () -> provisioner.delete("AWS::S3::BucketPolicy", "b", REGION));
    }
}

package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::S3::Bucket} and {@code AWS::S3::BucketPolicy}. */
class S3CfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";

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
                "WebsiteURL", "http://my-bucket.s3-website.us-east-1.amazonaws.com",
                "BucketName", "my-bucket"), r.getAttributes());
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

    @Test
    void bucketPolicyIsAcceptedWithAPhysicalIdAndNoAttributes() {
        StackResource r = resource("Policy", "AWS::S3::BucketPolicy");
        provisioner.provision(r, props("""
                {"Bucket": "b", "PolicyDocument": {"Version": "2012-10-17"}}
                """), ctx);

        assertTrue(r.getPhysicalId().startsWith("bucket-policy-"), r.getPhysicalId());
        assertTrue(r.getAttributes().isEmpty());
    }

    @Test
    void deletingABucketReachesTheServiceButAPolicyHasNothingToDelete() {
        provisioner.delete("AWS::S3::Bucket", "b", REGION);
        verify(s3).deleteBucket("b");

        provisioner.delete("AWS::S3::BucketPolicy", "bucket-policy-abc", REGION);
        verify(s3, never()).deleteBucket("bucket-policy-abc");
    }
}

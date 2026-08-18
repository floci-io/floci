package io.github.hectorvent.floci.services.cloudcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationResourceProvisioner;
import io.github.hectorvent.floci.services.cloudfront.CloudFrontService;
import io.github.hectorvent.floci.services.cloudfront.model.CachePolicy;
import io.github.hectorvent.floci.services.cloudfront.model.OriginRequestPolicy;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudControlServiceTest {

    @Test
    void emitsOnlyAwsShapedTagsForMalformedPersistedData() throws Exception {
        Ec2Service ec2Service = mock(Ec2Service.class);
        Vpc vpc = new Vpc();
        vpc.setVpcId("vpc-test");
        vpc.setTags(List.of(
                new Tag(null, "ignored-null"),
                new Tag("", "ignored-empty"),
                new Tag("  ", "ignored-blank"),
                new Tag("Name", null)));
        when(ec2Service.describeVpcs("us-east-1", List.of(), Map.of())).thenReturn(List.of(vpc));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), ec2Service, mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class),
                mock(io.github.hectorvent.floci.services.amp.AmpService.class),
                mock(io.github.hectorvent.floci.services.ivs.IvsService.class),
                mock(io.github.hectorvent.floci.services.ivschat.IvschatService.class),
                mock(io.github.hectorvent.floci.services.medialive.MediaLiveService.class),
                mock(CloudFrontService.class),
                new CloudControlStoreLister(null, mapper),
                mapper);

        String properties = service.listResources("us-east-1", "AWS::EC2::VPC").getFirst().properties();
        JsonNode tags = mapper.readTree(properties).path("Tags");

        assertTrue(tags.isArray());
        assertEquals(1, tags.size());
        assertTrue(tags.get(0).path("Key").isTextual());
        assertEquals("Name", tags.get(0).path("Key").asText());
        assertTrue(tags.get(0).path("Value").isTextual());
        assertEquals("", tags.get(0).path("Value").asText());
        assertFalse(properties.contains("ignored-null"));
        assertFalse(properties.contains("ignored-empty"));
        assertFalse(properties.contains("ignored-blank"));
    }

    /**
     * choudoufu#287 item 2: the real CFN registry schema for
     * {@code AWS::CloudFront::CachePolicy} requires {@code CachePolicyConfig} and nests
     * {@code Name} under it. A flat {@code {"Id":..., "Name":...}} response (what the generic
     * store lister would have produced by reflecting the persisted model's own field names) reads
     * as "no readable name" to a client that reads the documented, nested path.
     */
    @Test
    void cachePolicyPropertiesNestUnderCachePolicyConfig() throws Exception {
        CachePolicy policy = new CachePolicy();
        policy.setId("policy-1");
        policy.setName("my-cache-policy");
        policy.setComment("a comment");
        policy.setLastModifiedTime(Instant.parse("2026-01-01T00:00:00Z"));
        CloudFrontService cloudFrontService = mock(CloudFrontService.class);
        when(cloudFrontService.listCachePolicies(null, 0)).thenReturn(List.of(policy));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class),
                mock(io.github.hectorvent.floci.services.amp.AmpService.class),
                mock(io.github.hectorvent.floci.services.ivs.IvsService.class),
                mock(io.github.hectorvent.floci.services.ivschat.IvschatService.class),
                mock(io.github.hectorvent.floci.services.medialive.MediaLiveService.class),
                cloudFrontService,
                new CloudControlStoreLister(null, mapper),
                mapper);

        String properties = service.listResources("us-east-1", "AWS::CloudFront::CachePolicy")
                .getFirst().properties();
        JsonNode node = mapper.readTree(properties);

        assertEquals("policy-1", node.path("Id").asText());
        assertTrue(node.path("Name").isMissingNode(), "Name must not be flattened to the top level");
        assertEquals("my-cache-policy", node.path("CachePolicyConfig").path("Name").asText());
        assertEquals("a comment", node.path("CachePolicyConfig").path("Comment").asText());
    }

    /** As {@link #cachePolicyPropertiesNestUnderCachePolicyConfig}, for OriginRequestPolicy. */
    @Test
    void originRequestPolicyPropertiesNestUnderOriginRequestPolicyConfig() throws Exception {
        OriginRequestPolicy policy = new OriginRequestPolicy();
        policy.setId("orp-1");
        policy.setName("my-origin-request-policy");
        policy.setLastModifiedTime(Instant.parse("2026-01-01T00:00:00Z"));
        CloudFrontService cloudFrontService = mock(CloudFrontService.class);
        when(cloudFrontService.listOriginRequestPolicies(null, 0)).thenReturn(List.of(policy));
        ObjectMapper mapper = new ObjectMapper();
        CloudControlService service = new CloudControlService(
                mock(S3Service.class), mock(Ec2Service.class), mock(IamService.class),
                mock(CloudFormationResourceProvisioner.class),
                mock(io.github.hectorvent.floci.services.amp.AmpService.class),
                mock(io.github.hectorvent.floci.services.ivs.IvsService.class),
                mock(io.github.hectorvent.floci.services.ivschat.IvschatService.class),
                mock(io.github.hectorvent.floci.services.medialive.MediaLiveService.class),
                cloudFrontService,
                new CloudControlStoreLister(null, mapper),
                mapper);

        String properties = service.listResources("us-east-1", "AWS::CloudFront::OriginRequestPolicy")
                .getFirst().properties();
        JsonNode node = mapper.readTree(properties);

        assertEquals("orp-1", node.path("Id").asText());
        assertTrue(node.path("Name").isMissingNode(), "Name must not be flattened to the top level");
        assertEquals("my-origin-request-policy", node.path("OriginRequestPolicyConfig").path("Name").asText());
    }
}

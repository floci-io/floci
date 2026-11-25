package io.floci.aws.services.ec2;

import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.VpcEndpointType;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Amazon OpenSearch Service's service-linked role policy lets the service delete and modify only the
 * VPC endpoints it manages: AmazonOpenSearchServiceRolePolicy grants {@code ec2:DeleteVpcEndpoints}
 * and {@code ec2:ModifyVpcEndpoint} on {@code vpc-endpoint/*} with
 * {@code StringEquals aws:ResourceTag/OpenSearchManaged = "true"}. Each endpoint is checked with its
 * own stored tags, so the grant reaches a tagged endpoint and no other.
 *
 * <p>The principal holds the AWS managed policy itself, attached by ARN, under signed IAM enforcement.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class Ec2VpcEndpointResourceTagIamIntegrationTest {

    private static final String ADMIN = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}""";
    private static final String SLR_POLICY =
            "arn:aws:iam::aws:policy/aws-service-role/AmazonOpenSearchServiceRolePolicy";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Test
    void theServiceLinkedRoleDeletesOnlyTheEndpointsTaggedOpenSearchManaged() throws Exception {
        Ec2Client admin = client(SignedIamUsers.createUser(endpoint, "vpce-admin-", "admin", ADMIN));
        Ec2Client service = serviceLinkedRoleClient();
        String vpc = admin.createVpc(request -> request.cidrBlock("10.57.0.0/16")).vpc().vpcId();
        cleanup.register(() -> admin.deleteVpc(request -> request.vpcId(vpc)));

        String managed = gatewayEndpoint(admin, vpc, Map.of("OpenSearchManaged", "true"));
        String untagged = gatewayEndpoint(admin, vpc, Map.of());
        String otherValue = gatewayEndpoint(admin, vpc, Map.of("OpenSearchManaged", "false"));

        denied(() -> service.deleteVpcEndpoints(request -> request.vpcEndpointIds(untagged)));
        denied(() -> service.deleteVpcEndpoints(request -> request.vpcEndpointIds(otherValue)));
        // One endpoint the grant does not reach refuses the whole batch.
        denied(() -> service.deleteVpcEndpoints(request -> request.vpcEndpointIds(managed, untagged)));
        assertTrue(exists(admin, managed) && exists(admin, untagged) && exists(admin, otherValue));

        service.deleteVpcEndpoints(request -> request.vpcEndpointIds(managed));
        assertTrue(!exists(admin, managed), "the tagged endpoint was deleted");
        assertTrue(exists(admin, untagged) && exists(admin, otherValue));
    }

    @Test
    void theSameTagScopesModifyVpcEndpoint() throws Exception {
        Ec2Client admin = client(SignedIamUsers.createUser(endpoint, "vpce-admin-", "admin", ADMIN));
        Ec2Client service = serviceLinkedRoleClient();
        String vpc = admin.createVpc(request -> request.cidrBlock("10.58.0.0/16")).vpc().vpcId();
        cleanup.register(() -> admin.deleteVpc(request -> request.vpcId(vpc)));
        String managed = gatewayEndpoint(admin, vpc, Map.of("OpenSearchManaged", "true"));
        String untagged = gatewayEndpoint(admin, vpc, Map.of());

        service.modifyVpcEndpoint(request -> request.vpcEndpointId(managed).resetPolicy(true));
        denied(() -> service.modifyVpcEndpoint(request -> request.vpcEndpointId(untagged).resetPolicy(true)));
    }

    /**
     * A client for a principal holding exactly AmazonOpenSearchServiceRolePolicy, the AWS managed
     * document attached by ARN. The detach is registered last so it runs before the user is deleted.
     */
    private Ec2Client serviceLinkedRoleClient() throws Exception {
        SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "vpce-slr-");
        Ec2Client client = client(user);
        SignedIamUsers.iam(endpoint, "AttachUserPolicy", Map.of("UserName", user.name(), "PolicyArn", SLR_POLICY))
                .then().statusCode(200);
        cleanup.register(() -> {
            try {
                SignedIamUsers.iam(endpoint, "DetachUserPolicy",
                        Map.of("UserName", user.name(), "PolicyArn", SLR_POLICY)).then().statusCode(200);
            } catch (Exception error) {
                throw new IllegalStateException("Cannot detach the test user's policy", error);
            }
        });
        return client;
    }

    private Ec2Client client(SignedIamUsers.User user) {
        // Registered first, so it runs after everything this user's client made is cleaned up.
        cleanup.register(() -> {
            try {
                user.close();
            } catch (Exception error) {
                throw new IllegalStateException("Cannot retire the owned IAM test user", error);
            }
        });
        Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(user.accessKeyId(), user.secretAccessKey()))).build();
        cleanup.register(client::close);
        return client;
    }

    private String gatewayEndpoint(Ec2Client admin, String vpc, Map<String, String> tags) {
        String id = admin.createVpcEndpoint(request -> {
            request.vpcId(vpc).serviceName("com.amazonaws.us-east-1.s3").vpcEndpointType(VpcEndpointType.GATEWAY);
            if (!tags.isEmpty()) {
                request.tagSpecifications(spec -> spec.resourceType(ResourceType.VPC_ENDPOINT)
                        .tags(tags.entrySet().stream()
                                .map(tag -> Tag.builder()
                                        .key(tag.getKey()).value(tag.getValue()).build())
                                .toList()));
            }
        }).vpcEndpoint().vpcEndpointId();
        cleanup.register(() -> {
            if (exists(admin, id)) {
                admin.deleteVpcEndpoints(request -> request.vpcEndpointIds(id));
            }
        });
        return id;
    }

    private static boolean exists(Ec2Client admin, String endpointId) {
        return admin.describeVpcEndpoints(request -> request.filters(filter -> filter.name("vpc-endpoint-id")
                        .values(endpointId))).vpcEndpoints().stream()
                .anyMatch(found -> endpointId.equals(found.vpcEndpointId())
                        && !"deleted".equalsIgnoreCase(found.stateAsString()));
    }

    private void denied(Runnable action) {
        assertEquals(403, assertThrows(Ec2Exception.class, action::run).statusCode());
    }
}

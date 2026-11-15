package io.floci.aws.core.common;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.AssociateAddressRequest;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.AwsSdkErrors.assertAwsError;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2AddressIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"instance", "network-interface"})
    void associationRequiresEveryRequestedTargetWithItsOwnStoredTags(String kind) {
        String user = "address-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String vpc = admin.createVpc(r -> r.cidrBlock("10.211.0.0/16")).vpc().vpcId();
            String subnet = admin.createSubnet(r -> r.vpcId(vpc).cidrBlock("10.211.1.0/24")).subnet().subnetId();
            String target;
            if ("instance".equals(kind)) {
                target = admin.runInstances(r -> r.imageId("ami-amazonlinux2023").instanceType("t3.micro")
                        .minCount(1).maxCount(1).subnetId(subnet).tagSpecifications(tags(kind, "foreign")))
                        .instances().getFirst().instanceId();
            } else {
                target = admin.createNetworkInterface(r -> r.subnetId(subnet).tagSpecifications(tags(kind, "foreign")))
                        .networkInterface().networkInterfaceId();
            }
            String allocation = admin.allocateAddress(r -> r.domain("vpc")
                    .tagSpecifications(tags("elastic-ip", "address"))).allocationId();
            String addressGrant = grant("AssociateAddress", "elastic-ip/" + allocation, "address");
            String targetGrant = grant("AssociateAddress", kind + "/" + target, "target");
            AssociateAddressRequest.Builder builder = AssociateAddressRequest.builder().allocationId(allocation);
            if ("instance".equals(kind)) {
                builder.instanceId(target);
            } else {
                builder.networkInterfaceId(target);
            }
            AssociateAddressRequest request = builder.build();
            try {
                policy(user, addressGrant + "," + targetGrant);
                assertAwsError("UnauthorizedOperation", 403, () -> client.associateAddress(request));
                assertNull(admin.describeAddresses(r -> r.allocationIds(allocation)).addresses().getFirst().associationId());
                admin.createTags(r -> r.resources(target).tags(t -> t.key("Owner").value("target")));
                policy(user, addressGrant);
                assertAwsError("UnauthorizedOperation", 403, () -> client.associateAddress(request));
                policy(user, targetGrant);
                assertAwsError("UnauthorizedOperation", 403, () -> client.associateAddress(request));
                policy(user, addressGrant + "," + targetGrant + "," + targetGrant.replace("Allow", "Deny"));
                assertAwsError("UnauthorizedOperation", 403, () -> client.associateAddress(request));
                policy(user, addressGrant + "," + targetGrant + "," + addressGrant.replace("Allow", "Deny"));
                assertAwsError("UnauthorizedOperation", 403, () -> client.associateAddress(request));
                policy(user, addressGrant + "," + targetGrant);
                String association = client.associateAddress(request).associationId();
                Address result = admin.describeAddresses(r -> r.allocationIds(allocation)).addresses().getFirst();
                assertEquals(association, result.associationId());
                assertEquals(target, "instance".equals(kind) ? result.instanceId() : result.networkInterfaceId());
            } finally {
                Address address = admin.describeAddresses(r -> r.allocationIds(allocation)).addresses().getFirst();
                if (address.associationId() != null) {
                    admin.disassociateAddress(r -> r.associationId(address.associationId()));
                }
                admin.releaseAddress(r -> r.allocationId(allocation));
                if ("instance".equals(kind)) {
                    admin.terminateInstances(r -> r.instanceIds(target));
                }
                admin.describeNetworkInterfaces().networkInterfaces().stream().filter(n -> vpc.equals(n.vpcId()))
                        .forEach(n -> admin.deleteNetworkInterface(r -> r.networkInterfaceId(n.networkInterfaceId())));
                admin.deleteSubnet(r -> r.subnetId(subnet));
                admin.deleteVpc(r -> r.vpcId(vpc));
            }
        }
    }

    @Test
    void releaseRequiresTheNamedAllocationsStoredOwnershipAndHonorsExplicitDeny() {
        String user = "release-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(),
                auth("000000000000", "iam"), user);
        try (Ec2Client admin = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint);
             Ec2Client client = AwsTestClients.create(Ec2Client.builder(), credentials.accessKeyId(),
                     credentials.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            String allocation = admin.allocateAddress(r -> r.domain("vpc")
                    .tagSpecifications(tags("elastic-ip", "foreign"))).allocationId();
            String grant = grant("ReleaseAddress", "elastic-ip/" + allocation, "address");
            try {
                policy(user, grant);
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.releaseAddress(r -> r.allocationId(allocation)));
                admin.createTags(r -> r.resources(allocation).tags(t -> t.key("Owner").value("address")));
                policy(user, grant + "," + grant.replace("Allow", "Deny"));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.releaseAddress(r -> r.allocationId(allocation)));
                policy(user, grant.replace(allocation, "eipalloc-other"));
                assertAwsError("UnauthorizedOperation", 403,
                        () -> client.releaseAddress(r -> r.allocationId(allocation)));
                policy(user, grant);
                client.releaseAddress(r -> r.allocationId(allocation));
            } finally {
                if (admin.describeAddresses().addresses().stream().anyMatch(a -> allocation.equals(a.allocationId()))) {
                    admin.releaseAddress(r -> r.allocationId(allocation));
                }
            }
        }
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), user,
                "address", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String grant(String action, String resource, String owner) {
        return """
                {"Effect":"Allow","Action":"ec2:%s","Resource":"arn:aws:ec2:us-east-1:000000000000:%s",
                 "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"%s"}}}
                """.formatted(action, resource, owner);
    }

    private static TagSpecification tags(String type, String owner) {
        return TagSpecification.builder().resourceType(type).tags(t -> t.key("Owner").value(owner)).build();
    }
}

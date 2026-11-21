package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.model.InternetGateway;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2SecurityGroupDeletionIntegrationTest {
    private static final String REGION = "us-west-2";
    private static final String ACCOUNT = "555566667788";

    @Inject
    Ec2Service service;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "invalid", "", " "})
    void invalidDryRunCannotDeleteGroupOrRules(String dryRun) {
        RequestScopes.runAs(ACCOUNT, () -> {
            String vpc = service.createVpc(REGION, "10.232.0.0/24", false).getVpcId();
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, () -> service.deleteVpc(REGION, vpc)));
            SecurityGroup group = service.createSecurityGroup(
                    REGION, "invalid-dry-run-" + UUID.randomUUID(), "owned test group", vpc);
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, () -> {
                if (!service.describeSecurityGroups(
                        REGION, List.of(group.getGroupId()), List.of(), Map.of()).isEmpty()) {
                    service.deleteSecurityGroup(REGION, group.getGroupId());
                }
            }));
            int rules = service.describeSecurityGroupRules(
                    REGION, List.of(group.getGroupId()), List.of()).size();
            assertTrue(rules > 0);
            given().contentType("application/x-www-form-urlencoded")
                    .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                            + "/20261110/" + REGION + "/ec2/aws4_request, SignedHeaders=host, Signature=test")
                    .formParam("Action", "DeleteSecurityGroup")
                    .formParam("Version", "2016-11-15")
                    .formParam("GroupId", group.getGroupId()).formParam("DryRun", dryRun)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            assertEquals(group.getGroupId(), service.describeSecurityGroups(
                    REGION, List.of(group.getGroupId()), List.of(), Map.of()).getFirst().getGroupId());
            assertEquals(rules, service.describeSecurityGroupRules(
                    REGION, List.of(group.getGroupId()), List.of()).size());
        });
    }

    @Test
    void defaultGroupCannotBeDeleted() {
        RequestScopes.runAs(ACCOUNT, () -> {
            String vpc = service.createDefaultVpc(REGION).getVpcId();
            String group = service.describeSecurityGroups(
                    REGION, List.of(), List.of("default"),
                    Map.of("vpc-id", List.of(vpc))).getFirst().getGroupId();
            AwsException error = assertThrows(AwsException.class, () ->
                    service.deleteSecurityGroup(REGION, group, null, false));
            assertEquals("CannotDelete", error.getErrorCode());
            assertEquals(group, service.describeSecurityGroups(
                    REGION, List.of(group), List.of(), Map.of()).getFirst().getGroupId());
        });
    }

    @Test
    void nameWithoutDefaultVpcCannotSelectACustomVpcGroup() {
        RequestScopes.runAs(ACCOUNT, () -> {
            String defaultVpc = service.createDefaultVpc(REGION).getVpcId();
            for (Subnet subnet : service.describeSubnets(
                    REGION, List.of(), Map.of("vpc-id", List.of(defaultVpc)))) {
                service.deleteSubnet(REGION, subnet.getSubnetId());
            }
            for (InternetGateway gateway : service.describeInternetGateways(
                    REGION, List.of(), Map.of("attachment.vpc-id", List.of(defaultVpc)))) {
                service.detachInternetGateway(REGION, gateway.getInternetGatewayId(), defaultVpc);
                service.deleteInternetGateway(REGION, gateway.getInternetGatewayId());
            }
            service.deleteVpc(REGION, defaultVpc);
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, () -> service.createDefaultVpc(REGION)));
            String customVpc = service.createVpc(REGION, "10.229.0.0/24", false).getVpcId();
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, () -> service.deleteVpc(REGION, customVpc)));
            SecurityGroup group = service.createSecurityGroup(
                    REGION, "without-default-" + UUID.randomUUID(), "owned test group", customVpc);
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT,
                    () -> service.deleteSecurityGroup(REGION, group.getGroupId())));
            AwsException error = assertThrows(AwsException.class, () ->
                    service.deleteSecurityGroup(REGION, null, group.getGroupName(), false));
            assertEquals("VPCIdNotSpecified", error.getErrorCode());
            assertEquals(group.getGroupId(), service.describeSecurityGroups(
                    REGION, List.of(group.getGroupId()), List.of(), Map.of()).getFirst().getGroupId());
        });
    }

    @Test
    void selfReferenceAllowsDeletionAndOwnRulesAreRemoved() {
        RequestScopes.runAs(ACCOUNT, () -> {
            String vpc = service.createVpc(REGION, "10.230.0.0/24", false).getVpcId();
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, () -> service.deleteVpc(REGION, vpc)));
            SecurityGroup group = service.createSecurityGroup(
                    REGION, "self-reference-" + UUID.randomUUID(), "owned test group", vpc);
            cleanup.register(() -> RequestScopes.runAs(ACCOUNT, () -> {
                if (!service.describeSecurityGroups(
                        REGION, List.of(group.getGroupId()), List.of(), Map.of()).isEmpty()) {
                    service.deleteSecurityGroup(REGION, group.getGroupId());
                }
            }));
            UserIdGroupPair pair = new UserIdGroupPair();
            pair.setGroupId(group.getGroupId());
            IpPermission permission = new IpPermission();
            permission.setIpProtocol("-1");
            permission.getUserIdGroupPairs().add(pair);
            service.authorizeSecurityGroupIngress(REGION, group.getGroupId(), List.of(permission));
            assertFalse(service.describeSecurityGroupRules(
                    REGION, List.of(group.getGroupId()), List.of()).isEmpty());
            service.deleteSecurityGroup(REGION, group.getGroupId());
            assertTrue(service.describeSecurityGroupRules(
                    REGION, List.of(group.getGroupId()), List.of()).isEmpty());
        });
    }
}

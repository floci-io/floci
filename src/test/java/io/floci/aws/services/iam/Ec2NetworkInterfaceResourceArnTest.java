package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The resources network interface and VPC endpoint requests are checked on: the types AWS's Service
 * Authorization Reference gives each action, for the resources the request names. And what that
 * buys: Amazon OpenSearch Service's service-linked role policy, which scopes its EC2 grants to
 * resource ARNs, authorizes the calls the service makes in the customer's account with it, and still
 * refuses what lies outside its patterns.
 */
class Ec2NetworkInterfaceResourceArnTest {

    private static final String CUSTOMER = "111111111111";
    private static final String PREFIX = "arn:aws:ec2:eu-west-1:" + CUSTOMER + ":";

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void createNetworkInterfaceNamesTheInterfaceItsSubnetAndEachGroup() {
        assertResources(List.of(PREFIX + "network-interface/*", PREFIX + "subnet/subnet-11111111",
                        PREFIX + "security-group/sg-11111111", PREFIX + "security-group/sg-22222222"),
                resources("CreateNetworkInterface", "SubnetId=subnet-11111111&SecurityGroupId.1=sg-11111111"
                        + "&SecurityGroupId.2=sg-22222222&Description=web"));
    }

    @Test
    void interfaceActionsNameTheInterface() {
        for (String operation : List.of("DeleteNetworkInterface", "CreateNetworkInterfacePermission")) {
            assertResources(List.of(PREFIX + "network-interface/eni-11111111"),
                    resources(operation, "NetworkInterfaceId=eni-11111111&AwsAccountId=222222222222"
                            + "&Permission=INSTANCE-ATTACH"), operation);
        }
    }

    @Test
    void attachNamesTheInstanceAndTheInterface() {
        assertResources(List.of(PREFIX + "instance/i-11111111", PREFIX + "network-interface/eni-11111111"),
                resources("AttachNetworkInterface", "NetworkInterfaceId=eni-11111111&InstanceId=i-11111111"
                        + "&DeviceIndex=1"));
    }

    @Test
    void anAttachmentNoServiceResolvesIsCheckedOnTheAccountsInstancesAndInterfaces() {
        assertResources(List.of(PREFIX + "instance/*", PREFIX + "network-interface/*"),
                resources("DetachNetworkInterface", "AttachmentId=eni-attach-11111111&Force=true"));
    }

    @Test
    void vpcEndpointActionsNameTheEndpointAndWhatItIsPlacedIn() {
        assertResources(List.of(PREFIX + "vpc-endpoint/*", PREFIX + "vpc/vpc-11111111", PREFIX + "subnet/subnet-11111111",
                        PREFIX + "security-group/sg-11111111", PREFIX + "route-table/rtb-11111111"),
                resources("CreateVpcEndpoint", "VpcId=vpc-11111111&ServiceName=com.amazonaws.vpce.x"
                        + "&SubnetId.1=subnet-11111111&SecurityGroupId.1=sg-11111111&RouteTableId.1=rtb-11111111"));
        assertResources(List.of(PREFIX + "vpc-endpoint/vpce-11111111", PREFIX + "subnet/subnet-22222222",
                        PREFIX + "security-group/sg-33333333"),
                resources("ModifyVpcEndpoint", "VpcEndpointId=vpce-11111111&AddSubnetId.1=subnet-22222222"
                        + "&RemoveSecurityGroupId.1=sg-33333333"));
        assertResources(List.of(PREFIX + "vpc-endpoint/vpce-11111111", PREFIX + "vpc-endpoint/vpce-22222222"),
                resources("DeleteVpcEndpoints", "VpcEndpointId.1=vpce-11111111&VpcEndpointId.2=vpce-22222222"));
    }

    @Test
    void tagRequestsNameKnownInterfacesAndLeaveUncoveredEndpointIdsOnTheWildcard() {
        assertResources(List.of(PREFIX + "network-interface/eni-11111111", "*"),
                resources("CreateTags", "ResourceId.1=eni-11111111&ResourceId.2=vpce-11111111"
                        + "&Tag.1.Key=Name&Tag.1.Value=web"));
    }

    @Test
    void actionsAwsChecksOnNoResourceTypeStayOnTheWildcard() {
        for (String operation : List.of("DescribeNetworkInterfaces", "DescribeNetworkInterfacePermissions")) {
            assertResources(List.of("*"), resources(operation, "NetworkInterfaceId.1=eni-11111111"
                    + "&NetworkInterfaceId=eni-11111111"), operation);
        }
    }

    @Test
    void theOpenSearchServiceLinkedRolePolicyAuthorizesTheServicesInterfaceCalls() {
        List<String> policy = List.of(openSearchRolePolicy());
        List<String> create = resources("CreateNetworkInterface",
                "SubnetId=subnet-11111111&SecurityGroupId.1=sg-11111111");
        for (String resource : create) {
            assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                    evaluator.evaluate(policy, "ec2:CreateNetworkInterface", resource), resource);
        }
        for (String operation : List.of("DeleteNetworkInterface", "CreateTags")) {
            for (String resource : resources(operation, "NetworkInterfaceId=eni-11111111&ResourceId.1=eni-11111111")) {
                assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                        evaluator.evaluate(policy, "ec2:" + operation, resource), operation + " " + resource);
            }
        }
        // Checked on the wildcard, as before, the same grant matches none of its patterns.
        assertEquals(IamPolicyEvaluator.Decision.DENY, evaluator.evaluate(policy, "ec2:CreateNetworkInterface", "*"));
    }

    @Test
    void theSamePolicyStillRefusesWhatLiesOutsideItsPatterns() {
        List<String> policy = List.of(openSearchRolePolicy());
        // CreateNetworkInterface is granted on interfaces, subnets and groups only.
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(policy, "ec2:CreateNetworkInterface", PREFIX + "route-table/rtb-11111111"));
        // The policy grants no AttachNetworkInterface.
        for (String resource : resources("AttachNetworkInterface", "NetworkInterfaceId=eni-11111111&InstanceId=i-1")) {
            assertEquals(IamPolicyEvaluator.Decision.DENY,
                    evaluator.evaluate(policy, "ec2:AttachNetworkInterface", resource), resource);
        }
        // Its CreateTags grant covers interfaces, and endpoints only while they are created.
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(policy, "ec2:CreateTags", PREFIX + "instance/i-11111111"));
    }

    @Test
    void theEndpointItselfNeedsTheRequestTagThePolicyNames() {
        List<String> policy = List.of(openSearchRolePolicy());
        String endpoint = PREFIX + "vpc-endpoint/*";
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.simulateCustomPolicy(policy, "ec2:CreateVpcEndpoint", endpoint, Map.of()));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.simulateCustomPolicy(policy, "ec2:CreateVpcEndpoint", endpoint,
                        Map.of("aws:RequestTag/OpenSearchManaged", List.of("true"))));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(policy, "ec2:CreateVpcEndpoint", PREFIX + "subnet/subnet-11111111"));
    }

    private static String openSearchRolePolicy() {
        return AwsManagedPolicies.POLICIES.stream()
                .filter(policy -> "AmazonOpenSearchServiceRolePolicy".equals(policy.name()))
                .findFirst()
                .orElseThrow()
                .document();
    }

    private static void assertResources(List<String> expected, List<String> actual) {
        assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList());
    }

    private static void assertResources(List<String> expected, List<String> actual, String message) {
        assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList(), message);
    }

    private List<String> resources(String operation, String body) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        Map<String, Object> properties = new HashMap<>();
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("/");
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(context.getEntityStream()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> {
            when(context.getEntityStream()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(context).setEntityStream(any());
        when(context.getProperty(any())).thenAnswer(invocation -> properties.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            properties.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(context).setProperty(any(), any());
        return new ResourceArnBuilder().buildResources("ec2", "ec2:" + operation, context,
                "eu-west-1", CUSTOMER, "aws");
    }
}

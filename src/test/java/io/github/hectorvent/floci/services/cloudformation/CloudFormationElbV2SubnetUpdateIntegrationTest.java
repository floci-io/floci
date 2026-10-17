package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.reset;

/**
 * Stack updates that move an ELBv2 load balancer between subnets, through the whole engine. EC2
 * refuses to delete a subnet a load balancer still uses, so an update has to move the balancer off
 * a subnet the template dropped or replaced before its cleanup deletes that subnet, and a rolled-back
 * update has to move the balancer back before it deletes a subnet the update added.
 */
@QuarkusTest
class CloudFormationElbV2SubnetUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261013/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261013/us-east-1/ec2/aws4_request";
    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261013/us-east-1/elasticloadbalancing/aws4_request";
    private static final String CIDR_A = "10.93.1.0/24";
    private static final String CIDR_B = "10.93.2.0/24";
    private static final String CIDR_C = "10.93.3.0/24";

    @InjectSpy
    ElbV2Service elbService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anUpdateThatDropsASubnetMovesTheLoadBalancerOffItSoTheCleanupDeletesIt() {
        String stack = "alb-drop-subnet-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack,
                    template(CIDR_B, true, List.of("SubnetA", "SubnetB", "SubnetC"), ""));

            Map<String, String> updated = updateStack(stack,
                    template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""), "UPDATE_COMPLETE");

            assertEquals(Set.of(updated.get("SubnetA"), updated.get("SubnetB")),
                    Set.copyOf(loadBalancerSubnets(updated.get("Alb"))));
            ec2("DescribeSubnets", "SubnetId.1", created.get("SubnetC")).statusCode(400)
                    .body(containsString("InvalidSubnetID.NotFound"));
        } finally {
            deleteStack(stack);
        }
    }

    @Test
    void anUpdateThatReplacesASubnetMovesTheLoadBalancerOntoTheReplacement() {
        String stack = "alb-replace-subnet-" + Long.toString(System.nanoTime(), 36);
        try {
            String oldSubnetB = createStack(stack,
                    template(CIDR_B, false, List.of("SubnetA", "SubnetB"), "")).get("SubnetB");

            // A new CidrBlock is create-only, so SubnetB is replaced and the old one is deleted on commit.
            Map<String, String> updated = updateStack(stack,
                    template("10.93.4.0/24", false, List.of("SubnetA", "SubnetB"), ""), "UPDATE_COMPLETE");

            String newSubnetB = updated.get("SubnetB");
            assertNotEquals(oldSubnetB, newSubnetB);
            assertEquals(Set.of(updated.get("SubnetA"), newSubnetB),
                    Set.copyOf(loadBalancerSubnets(updated.get("Alb"))));
            ec2("DescribeSubnets", "SubnetId.1", oldSubnetB).statusCode(400)
                    .body(containsString("InvalidSubnetID.NotFound"));
        } finally {
            deleteStack(stack);
        }
    }

    @Test
    void aFailedUpdateMovesTheLoadBalancerBackToItsSubnets() {
        String stack = "alb-subnets-rollback-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack,
                    template(CIDR_B, true, List.of("SubnetA", "SubnetB", "SubnetC"), ""));
            Set<String> before = Set.copyOf(loadBalancerSubnets(created.get("Alb")));

            Map<String, String> rolledBack = updateStack(stack, template(CIDR_B, true,
                    List.of("SubnetA", "SubnetB"), failingSecret(stack, "Alb")), "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(3, before.size());
            assertEquals(before, Set.copyOf(loadBalancerSubnets(rolledBack.get("Alb"))));
        } finally {
            deleteStack(stack);
        }
    }

    /**
     * The update adds SubnetC and moves the load balancer onto it before a later resource fails.
     * The rollback has to move the load balancer back before it deletes the subnet it added, or EC2
     * refuses the delete and the rollback fails.
     */
    @Test
    void aFailedUpdateMovesTheLoadBalancerOffASubnetItAddedBeforeDeletingIt() {
        String stack = "alb-added-subnet-rollback-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack,
                    template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""));
            Set<String> before = Set.of(created.get("SubnetA"), created.get("SubnetB"));

            Map<String, String> rolledBack = updateStack(stack, template(CIDR_B, true,
                    List.of("SubnetA", "SubnetC"), failingSecret(stack, "Alb")), "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(before, Set.copyOf(loadBalancerSubnets(rolledBack.get("Alb"))));
            assertEquals(before, Set.copyOf(vpcSubnets(created.get("Vpc"))));
        } finally {
            deleteStack(stack);
        }
    }

    /**
     * The first rollback's move back fails, which leaves the load balancer on the subnet the update
     * added, so that subnet cannot be deleted either and the stack ends UPDATE_ROLLBACK_FAILED with
     * both owed. ContinueUpdateRollback walks them again in dependency order: the load balancer moves
     * back first, from the subnets it kept for the retry, and then the subnet goes.
     */
    @Test
    void continueUpdateRollbackMovesTheLoadBalancerBackBeforeDeletingTheSubnetItAdded() {
        String stack = "alb-continue-rollback-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack,
                    template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""));
            Set<String> before = Set.of(created.get("SubnetA"), created.get("SubnetB"));
            failTheFirstMoveBack();

            failUpdateOntoAnAddedSubnet(stack);

            continueUpdateRollback(stack);
            CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(before, Set.copyOf(loadBalancerSubnets(created.get("Alb"))));
            assertEquals(before, Set.copyOf(vpcSubnets(created.get("Vpc"))));
        } finally {
            reset(elbService);
            deleteStack(stack);
        }
    }

    /**
     * ResourcesToSkip leaves a skipped resource as the failed update left it. Skipping only the load
     * balancer keeps it on the added subnet, so that subnet still cannot be deleted. Skipping the
     * subnet as well completes the rollback with both left in place, and the stack still deletes
     * cleanly, load balancer first.
     */
    @Test
    void continueUpdateRollbackWithResourcesToSkipLeavesTheSkippedLoadBalancerOnTheAddedSubnet() {
        String stack = "alb-continue-skip-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack,
                    template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""));
            failTheFirstMoveBack();
            failUpdateOntoAnAddedSubnet(stack);
            Set<String> addedSubnet = new HashSet<>(vpcSubnets(created.get("Vpc")));
            addedSubnet.removeAll(Set.of(created.get("SubnetA"), created.get("SubnetB")));
            assertEquals(1, addedSubnet.size());

            continueUpdateRollback(stack, "Alb");
            CfnStackWaits.StackState stillFailed = CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_FAILED");
            assertTrue(stillFailed.reason().contains("SubnetC"), stillFailed.reason());

            continueUpdateRollback(stack, "SubnetC");
            CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(Set.of(created.get("SubnetA"), addedSubnet.iterator().next()),
                    Set.copyOf(loadBalancerSubnets(created.get("Alb"))));
            assertEquals(3, vpcSubnets(created.get("Vpc")).size());
        } finally {
            reset(elbService);
            deleteStack(stack);
        }
    }

    /**
     * The update's own move onto the new subnets goes through, the first move back fails as if its
     * subnet had been deleted meanwhile, and any later call goes through again.
     */
    private void failTheFirstMoveBack() {
        doCallRealMethod()
                .doThrow(new AwsException("SubnetNotFound", "The subnet ID was not found", 400))
                .doCallRealMethod()
                .when(elbService).setSubnets(anyString(), anyString(), anyList());
    }

    /** Moves the load balancer onto SubnetA and an added SubnetC in an update a later resource fails. */
    private static void failUpdateOntoAnAddedSubnet(String stack) {
        updateStack(stack, template(CIDR_B, true, List.of("SubnetA", "SubnetC"), failingSecret(stack, "Alb")),
                "UPDATE_ROLLBACK_FAILED");
        String reason = CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_FAILED").reason();
        assertTrue(reason.contains("Alb") && reason.contains("SubnetC"), reason);
    }

    private static void continueUpdateRollback(String stack, String... resourcesToSkip) {
        String[] values = new String[2 + resourcesToSkip.length * 2];
        values[0] = "StackName";
        values[1] = stack;
        for (int i = 0; i < resourcesToSkip.length; i++) {
            values[2 + i * 2] = "ResourcesToSkip.member." + (i + 1);
            values[3 + i * 2] = resourcesToSkip[i];
        }
        request(CFN_AUTH, "ContinueUpdateRollback", values).statusCode(200);
    }

    /**
     * A failed rollback restores the parameters but can leave a resource on the branch the failed
     * update chose: here the load balancer stays on the subnets an Fn::If picked for the new
     * parameter value. Deleting the stack has to delete it before those subnets too, whichever
     * branch the restored parameters now select.
     */
    @Test
    void deleteStackAfterAFailedRollbackDeletesTheLoadBalancerBeforeTheSubnetsOfEitherBranch() {
        String stack = "alb-fnif-branch-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack, branchTemplate(""), "first");
            failTheFirstMoveBack();
            request(CFN_AUTH, "UpdateStack", "StackName", stack, "TemplateBody",
                    branchTemplate(failingSecret(stack, "Alb")),
                    "Parameters.member.1.ParameterKey", "Placement",
                    "Parameters.member.1.ParameterValue", "second").statusCode(200);
            String reason = CfnStackWaits.awaitStatus(stack, "UPDATE_ROLLBACK_FAILED").reason();
            assertTrue(reason.contains("Alb"), reason);
            assertEquals(Set.of(created.get("SubnetC"), created.get("SubnetD")),
                    Set.copyOf(loadBalancerSubnets(created.get("Alb"))), "the move back failed");
            reset(elbService);

            deleteStack(stack);

            ec2("DescribeVpcs", "VpcId.1", created.get("Vpc")).statusCode(400)
                    .body(containsString("InvalidVpcID.NotFound"));
        } finally {
            reset(elbService);
            if (request(CFN_AUTH, "DescribeStacks", "StackName", stack).extract().statusCode() == 200) {
                request(CFN_AUTH, "DeleteStack", "StackName", stack);
            }
        }
    }

    /** CloudFormation deletes a VPC too, so the load balancer has to go before its subnets and they before the VPC. */
    @Test
    void deleteStackRemovesTheLoadBalancerBeforeTheSubnetsAndTheVpcUnderIt() {
        String stack = "alb-vpc-delete-" + Long.toString(System.nanoTime(), 36);
        Map<String, String> created = createStack(stack, template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""));

        deleteStack(stack);

        ec2("DescribeVpcs", "VpcId.1", created.get("Vpc")).statusCode(400)
                .body(containsString("InvalidVpcID.NotFound"));
        request(ELB_AUTH, "DescribeLoadBalancers", "LoadBalancerArns.member.1", created.get("Alb")).statusCode(400)
                .body(containsString("LoadBalancerNotFound"));
    }

    /**
     * A load balancer outside the stack on the stack's subnets pins them, and through them the VPC,
     * so DeleteStack fails until that load balancer is gone, and then deletes everything.
     */
    @Test
    void aLoadBalancerOutsideTheStackKeepsItsSubnetsAndVpcUntilItIsDeleted() {
        String stack = "alb-vpc-outside-" + Long.toString(System.nanoTime(), 36);
        Map<String, String> created = createStack(stack, template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""));
        try {
            String outside = request(ELB_AUTH, "CreateLoadBalancer",
                    "Name", "outside-" + stack.substring(stack.length() - 8), "Type", "application",
                    "Scheme", "internal",
                    "Subnets.member.1", created.get("SubnetA"), "Subnets.member.2", created.get("SubnetB"))
                    .statusCode(200).extract()
                    .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
            try {
                request(CFN_AUTH, "DeleteStack", "StackName", stack).statusCode(200);
                CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
                assertEquals("DELETE_FAILED", failed.status());
                String reason = failed.reason();
                assertEquals(Set.of("SubnetA", "SubnetB", "Vpc"),
                        Set.of(reason.substring(reason.indexOf('[') + 1, reason.indexOf(']')).split(", ")), reason);
            } finally {
                request(ELB_AUTH, "DeleteLoadBalancer", "LoadBalancerArn", outside).statusCode(200);
            }
        } finally {
            deleteStack(stack);
        }

        ec2("DescribeVpcs", "VpcId.1", created.get("Vpc")).statusCode(400)
                .body(containsString("InvalidVpcID.NotFound"));
    }

    /**
     * An update that adds a VPC, subnets in it and a load balancer on them, then fails, rolls them
     * back dependents first: the load balancer before its subnets, and the subnets before the VPC,
     * so nothing the update added is left behind.
     */
    @Test
    void aFailedUpdateDeletesTheLoadBalancerItAddedBeforeTheSubnetsAndVpcUnderIt() {
        String stack = "alb-vpc-rollback-" + Long.toString(System.nanoTime(), 36);
        try {
            Map<String, String> created = createStack(stack,
                    template(CIDR_B, false, List.of("SubnetA", "SubnetB"), ""));
            String added = """
                ,
                    "Vpc2": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.95.0.0/16",
                             "Tags": [{"Key": "floci-it", "Value": "%1$s"}]}},
                    "SubnetX": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc2"}, "CidrBlock": "10.95.1.0/24", "AvailabilityZone": "us-east-1a"}
                    },
                    "SubnetY": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc2"}, "CidrBlock": "10.95.2.0/24", "AvailabilityZone": "us-east-1b"}
                    },
                    "Alb2": {
                      "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                      "Properties": {"Type": "application", "Scheme": "internal",
                                     "Subnets": [{"Ref": "SubnetX"}, {"Ref": "SubnetY"}]}
                    }""".formatted(stack);

            updateStack(stack, template(CIDR_B, false, List.of("SubnetA", "SubnetB"),
                    added + failingSecret(stack, "Alb2")), "UPDATE_ROLLBACK_COMPLETE");

            String vpcs = ec2("DescribeVpcs", "Filter.1.Name", "tag:floci-it", "Filter.1.Value.1", stack)
                    .statusCode(200).extract().asString();
            assertEquals(List.of(), XmlParser.extractAll(vpcs, "vpcId"));
            assertEquals(Set.of(created.get("SubnetA"), created.get("SubnetB")),
                    Set.copyOf(loadBalancerSubnets(created.get("Alb"))));
        } finally {
            deleteStack(stack);
        }
    }

    /**
     * The rollback orders resources by the template's conditions, evaluated with the SSM-resolved
     * parameter value the update itself used. With the raw value, the parameter's name, the load
     * balancer's condition reads false and the subnet it uses would be deleted first.
     */
    @Test
    void aRollbackOrdersAResourceUnderAnSsmResolvedConditionBeforeTheSubnetItUses() {
        String stack = "alb-ssm-condition-rollback-" + Long.toString(System.nanoTime(), 36);
        String parameterName = "/" + stack + "/env";
        ssm("PutParameter", "{\"Name\": \"" + parameterName + "\", \"Value\": \"prod\", \"Type\": \"String\"}");
        try {
            Map<String, String> created = createStack(stack, ssmConditionTemplate(parameterName, ""));
            Set<String> before = Set.of(created.get("SubnetA"), created.get("SubnetB"));

            String added = """
                ,
                    "SubnetC": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "%s", "AvailabilityZone": "us-east-1c"}
                    },
                    "ProdAlb": {
                      "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                      "Condition": "IsProd",
                      "Properties": {"Type": "application", "Scheme": "internal",
                                     "Subnets": [{"Ref": "SubnetA"}, {"Ref": "SubnetC"}]}
                    },
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Condition": "IsProd",
                      "DependsOn": "ProdAlb",
                      "Properties": {
                        "Name": "%s-secret",
                        "SecretString": "explicit",
                        "GenerateSecretString": {"PasswordLength": 32}
                      }
                    }""".formatted(CIDR_C, stack);
            updateStack(stack, ssmConditionTemplate(parameterName, added), "UPDATE_ROLLBACK_COMPLETE");

            assertEquals(before, Set.copyOf(vpcSubnets(created.get("Vpc"))));
        } finally {
            deleteStack(stack);
            ssm("DeleteParameter", "{\"Name\": \"" + parameterName + "\"}");
        }
    }

    /** A secret that sets both secret properties, which fails the update once what it depends on is done. */
    private static String failingSecret(String stack, String dependsOn) {
        return """
            ,
                "BadSecret": {
                  "Type": "AWS::SecretsManager::Secret",
                  "DependsOn": "%s",
                  "Properties": {
                    "Name": "%s-secret",
                    "SecretString": "explicit",
                    "GenerateSecretString": {"PasswordLength": 32}
                  }
                }""".formatted(dependsOn, stack);
    }

    /**
     * A VPC with subnets in three zones, and an internal ALB on the named ones. SubnetC, its
     * output and SubnetB's CIDR vary between the versions a test applies.
     */
    private static String template(String cidrB, boolean withSubnetC, List<String> albSubnets, String extraResources) {
        StringBuilder refs = new StringBuilder();
        for (String subnet : albSubnets) {
            refs.append(refs.isEmpty() ? "" : ", ").append("{\"Ref\": \"").append(subnet).append("\"}");
        }
        String subnetC = withSubnetC ? """
                "SubnetC": {
                  "Type": "AWS::EC2::Subnet",
                  "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "%s", "AvailabilityZone": "us-east-1c"}
                },""".formatted(CIDR_C) : "";
        String subnetCOutput = withSubnetC ? """
                "SubnetC": {"Value": {"Ref": "SubnetC"}},""" : "";
        return """
            {
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.93.0.0/16"}},
                "SubnetA": {
                  "Type": "AWS::EC2::Subnet",
                  "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "%s", "AvailabilityZone": "us-east-1a"}
                },
                "SubnetB": {
                  "Type": "AWS::EC2::Subnet",
                  "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "%s", "AvailabilityZone": "us-east-1b"}
                },%s
                "Alb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Type": "application", "Scheme": "internal", "Subnets": [%s]}
                }%s
              },
              "Outputs": {%s
                "SubnetA": {"Value": {"Ref": "SubnetA"}},
                "SubnetB": {"Value": {"Ref": "SubnetB"}},
                "Vpc": {"Value": {"Ref": "Vpc"}},
                "Alb": {"Value": {"Ref": "Alb"}}
              }
            }
            """.formatted(CIDR_A, cidrB, subnetC, refs, extraResources, subnetCOutput);
    }

    /**
     * A VPC with four subnets and an ALB whose subnets an Fn::If picks by the Placement parameter:
     * SubnetA and SubnetB for "first", SubnetC and SubnetD for "second". The ALB is declared before
     * the subnets, and SubnetC is created after SubnetD after SubnetB, so whichever branch the
     * ordering reads, SubnetC is not created before the ALB unless the ALB depends on it.
     */
    private static String branchTemplate(String extraResources) {
        return """
            {
              "Parameters": {"Placement": {"Type": "String", "Default": "first"}},
              "Conditions": {"Second": {"Fn::Equals": [{"Ref": "Placement"}, "second"]}},
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.93.0.0/16"}},
                "Alb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Type": "application", "Scheme": "internal",
                                 "Subnets": {"Fn::If": ["Second", [{"Ref": "SubnetC"}, {"Ref": "SubnetD"}],
                                                        [{"Ref": "SubnetA"}, {"Ref": "SubnetB"}]]}}
                },
                "SubnetA": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.93.1.0/24", "AvailabilityZone": "us-east-1a"}},
                "SubnetB": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.93.2.0/24", "AvailabilityZone": "us-east-1b"}},
                "SubnetC": {"Type": "AWS::EC2::Subnet", "DependsOn": "SubnetD", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.93.3.0/24", "AvailabilityZone": "us-east-1a"}},
                "SubnetD": {"Type": "AWS::EC2::Subnet", "DependsOn": "SubnetB", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.93.4.0/24", "AvailabilityZone": "us-east-1b"}}%s
              },
              "Outputs": {
                "Vpc": {"Value": {"Ref": "Vpc"}},
                "SubnetC": {"Value": {"Ref": "SubnetC"}},
                "SubnetD": {"Value": {"Ref": "SubnetD"}},
                "Alb": {"Value": {"Ref": "Alb"}}
              }
            }
            """.formatted(extraResources);
    }

    /** A VPC and two subnets, with an IsProd condition on an SSM-typed parameter. */
    private static String ssmConditionTemplate(String parameterName, String extraResources) {
        return """
            {
              "Parameters": {
                "Env": {"Type": "AWS::SSM::Parameter::Value<String>", "Default": "%s"}
              },
              "Conditions": {
                "IsProd": {"Fn::Equals": [{"Ref": "Env"}, "prod"]}
              },
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.93.0.0/16"}},
                "SubnetA": {
                  "Type": "AWS::EC2::Subnet",
                  "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "%s", "AvailabilityZone": "us-east-1a"}
                },
                "SubnetB": {
                  "Type": "AWS::EC2::Subnet",
                  "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "%s", "AvailabilityZone": "us-east-1b"}
                }%s
              },
              "Outputs": {
                "SubnetA": {"Value": {"Ref": "SubnetA"}},
                "SubnetB": {"Value": {"Ref": "SubnetB"}},
                "Vpc": {"Value": {"Ref": "Vpc"}}
              }
            }
            """.formatted(parameterName, CIDR_A, CIDR_B, extraResources);
    }

    private static List<String> loadBalancerSubnets(String arn) {
        assertNotNull(arn, "the stack outputs the load balancer");
        String xml = request(ELB_AUTH, "DescribeLoadBalancers", "LoadBalancerArns.member.1", arn)
                .statusCode(200).extract().asString();
        return XmlParser.extractAll(xml, "SubnetId");
    }

    private static List<String> vpcSubnets(String vpcId) {
        String xml = ec2("DescribeSubnets", "Filter.1.Name", "vpc-id", "Filter.1.Value.1", vpcId)
                .statusCode(200).extract().asString();
        return XmlParser.extractAll(xml, "subnetId");
    }

    private static Map<String, String> createStack(String stack, String templateBody, String placement) {
        request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", templateBody,
                "Parameters.member.1.ParameterKey", "Placement",
                "Parameters.member.1.ParameterValue", placement).statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE");
        return outputs(stack);
    }

    private static Map<String, String> createStack(String stack, String templateBody) {
        request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", templateBody).statusCode(200);
        CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE");
        return outputs(stack);
    }

    private static Map<String, String> updateStack(String stack, String templateBody, String expectedStatus) {
        request(CFN_AUTH, "UpdateStack", "StackName", stack, "TemplateBody", templateBody).statusCode(200);
        CfnStackWaits.awaitStatus(stack, expectedStatus);
        return outputs(stack);
    }

    private static Map<String, String> outputs(String stack) {
        String xml = request(CFN_AUTH, "DescribeStacks", "StackName", stack).statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "member", "OutputKey", "OutputValue");
    }

    /** DeleteStack answers before the stack is gone, so this waits and fails on DELETE_FAILED. */
    private static void deleteStack(String stack) {
        request(CFN_AUTH, "DeleteStack", "StackName", stack).statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
    }

    private static void ssm(String action, String body) {
        given().header("X-Amz-Target", "AmazonSSM." + action).contentType("application/x-amz-json-1.1")
                .body(body).when().post("/").then().statusCode(200);
    }

    private static ValidatableResponse ec2(String action, String... values) {
        return request(EC2_AUTH, action, values);
    }

    private static ValidatableResponse request(String auth, String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().header("Authorization", auth).formParams(parameters).when().post("/").then()
                .log().ifValidationFails();
    }
}

package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

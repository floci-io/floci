package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code SubnetId} is required in a load balancer's {@code SubnetMappings}. A mapping that names no
 * subnet fails its stack, as on AWS, rather than the balancer being created with no subnets, while a
 * mapping an {@code Fn::If} drops with {@code AWS::NoValue} is left out. {@code Subnets} and
 * {@code SubnetMappings} together fail it too, as ELBv2 takes one or the other, and so does a
 * balancer with neither, or an Application Load Balancer on one subnet.
 */
@QuarkusTest
class CloudFormationElbV2SubnetMappingsIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/cloudformation/aws4_request";
    private static final String ELB_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261018/us-east-1/elasticloadbalancing/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * A mapping with no {@code SubnetId}, one whose {@code SubnetId} an {@code Fn::If} drops, and one
     * whose {@code SubnetId} is blank, each with the reason the resource fails with.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"AllocationId\": \"eipalloc-12345678\"}|required key [SubnetId] not found",
        "{\"SubnetId\": {\"Fn::If\": [\"Never\", {\"Ref\": \"SubnetA\"}, {\"Ref\": \"AWS::NoValue\"}]}}|required key [SubnetId] not found",
        "{\"SubnetId\": \"\"}|The subnet ID '' does not exist"
    })
    void aSubnetMappingThatNamesNoSubnetFailsTheStackWithoutALoadBalancer(String mapping, String reason) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "nlb-mappings-" + suffix;
        String name = "nlb-" + suffix;
        try {
            request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", template(name, mapping))
                    .statusCode(200);
            CfnStackWaits.awaitStatus(stack, "ROLLBACK_COMPLETE");
            String events = request(CFN_AUTH, "DescribeStackEvents", "StackName", stack).statusCode(200)
                    .extract().asString();
            List<String> reasons = XmlParser.extractAll(events, "ResourceStatusReason");
            assertTrue(reasons.stream().anyMatch(r -> r.contains(reason)), String.join(" | ", reasons));
            request(ELB_AUTH, "DescribeLoadBalancers", "Names.member.1", name).statusCode(400)
                    .body(containsString("LoadBalancerNotFound"));
        } finally {
            request(CFN_AUTH, "DeleteStack", "StackName", stack);
        }
    }

    /** {@code Subnets} and {@code SubnetMappings} together fail the stack, as ELBv2 refuses both. */
    @Test
    void subnetsAndSubnetMappingsTogetherFailTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "nlb-both-" + suffix;
        String name = "nlb-both-" + suffix;
        String template = template(name, "{\"SubnetId\": {\"Ref\": \"SubnetA\"}}")
                .replace("\"SubnetMappings\":", "\"Subnets\": [{\"Ref\": \"SubnetB\"}], \"SubnetMappings\":");
        try {
            request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", template).statusCode(200);
            CfnStackWaits.awaitStatus(stack, "ROLLBACK_COMPLETE");
            String events = request(CFN_AUTH, "DescribeStackEvents", "StackName", stack).statusCode(200)
                    .extract().asString();
            List<String> reasons = XmlParser.extractAll(events, "ResourceStatusReason");
            assertTrue(reasons.stream().anyMatch(r -> r.contains("either subnets or subnet mappings, not both")),
                    String.join(" | ", reasons));
            request(ELB_AUTH, "DescribeLoadBalancers", "Names.member.1", name).statusCode(400)
                    .body(containsString("LoadBalancerNotFound"));
        } finally {
            request(CFN_AUTH, "DeleteStack", "StackName", stack);
        }
    }

    /**
     * A balancer with neither {@code Subnets} nor {@code SubnetMappings}, left out or with the only
     * mapping an {@code Fn::If} drops, fails the stack with CreateLoadBalancer's error for its type.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
        "network|NONE|At least one subnet must be specified",
        "network|{\"Fn::If\": [\"Never\", {\"SubnetId\": {\"Ref\": \"SubnetA\"}}, {\"Ref\": \"AWS::NoValue\"}]}|At least one subnet must be specified",
        "application|NONE|At least two subnets in two different Availability Zones must be specified"
    })
    void aLoadBalancerWithNeitherSubnetsNorSubnetMappingsFailsTheStack(String type, String mappings, String reason) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "lb-no-subnets-" + suffix;
        String name = "lb-none-" + suffix;
        String template = (mappings == null
                ? template(name, "").replace(", \"SubnetMappings\": []", "")
                : template(name, mappings))
                .replace("\"Type\": \"network\"", "\"Type\": \"" + type + "\"");
        assertEquals(mappings != null, template.contains("SubnetMappings"), template);
        assertTrue(template.contains("\"Type\": \"" + type + "\""), template);
        try {
            request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", template).statusCode(200);
            CfnStackWaits.awaitStatus(stack, "ROLLBACK_COMPLETE");
            String events = request(CFN_AUTH, "DescribeStackEvents", "StackName", stack).statusCode(200)
                    .extract().asString();
            List<String> reasons = XmlParser.extractAll(events, "ResourceStatusReason");
            assertTrue(reasons.stream().anyMatch(r -> r.contains(reason)), String.join(" | ", reasons));
            request(ELB_AUTH, "DescribeLoadBalancers", "Names.member.1", name).statusCode(400)
                    .body(containsString("LoadBalancerNotFound"));
        } finally {
            request(CFN_AUTH, "DeleteStack", "StackName", stack);
        }
    }

    /**
     * An Application Load Balancer on one subnet, in {@code Subnets} or in {@code SubnetMappings},
     * fails the stack with CreateLoadBalancer's error and leaves no balancer.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Subnets", "SubnetMappings"})
    void anApplicationLoadBalancerOnOneSubnetFailsTheStack(String property) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "alb-one-subnet-" + suffix;
        String name = "alb-one-" + suffix;
        String template = template(name, "{\"SubnetId\": {\"Ref\": \"SubnetA\"}}")
                .replace("\"Type\": \"network\"", "\"Type\": \"application\"");
        if ("Subnets".equals(property)) {
            template = template.replace("\"SubnetMappings\": [{\"SubnetId\": {\"Ref\": \"SubnetA\"}}]",
                    "\"Subnets\": [{\"Ref\": \"SubnetA\"}]");
        }
        assertTrue(template.contains("\"" + property + "\": [{"), template);
        assertTrue(template.contains("\"Type\": \"application\""), template);
        try {
            request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", template).statusCode(200);
            CfnStackWaits.awaitStatus(stack, "ROLLBACK_COMPLETE");
            String events = request(CFN_AUTH, "DescribeStackEvents", "StackName", stack).statusCode(200)
                    .extract().asString();
            List<String> reasons = XmlParser.extractAll(events, "ResourceStatusReason");
            assertTrue(reasons.stream().anyMatch(
                    r -> r.contains("At least two subnets in two different Availability Zones must be specified")),
                    String.join(" | ", reasons));
            request(ELB_AUTH, "DescribeLoadBalancers", "Names.member.1", name).statusCode(400)
                    .body(containsString("LoadBalancerNotFound"));
        } finally {
            request(CFN_AUTH, "DeleteStack", "StackName", stack);
        }
    }

    /** A second mapping an {@code Fn::If} drops leaves the balancer on the first mapping's subnet. */
    @Test
    void aMappingAnFnIfDropsIsLeftOut() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stack = "nlb-mappings-if-" + suffix;
        String name = "nlb-if-" + suffix;
        String mappings = "{\"SubnetId\": {\"Ref\": \"SubnetA\"}}, "
                + "{\"Fn::If\": [\"Never\", {\"SubnetId\": {\"Ref\": \"SubnetB\"}}, {\"Ref\": \"AWS::NoValue\"}]}";
        try {
            request(CFN_AUTH, "CreateStack", "StackName", stack, "TemplateBody", template(name, mappings))
                    .statusCode(200);
            CfnStackWaits.awaitStatus(stack, "CREATE_COMPLETE");
            String outputs = request(CFN_AUTH, "DescribeStacks", "StackName", stack).statusCode(200)
                    .extract().asString();
            String subnetA = XmlParser.extractPairs(outputs, "member", "OutputKey", "OutputValue").get("SubnetA");
            String described = request(ELB_AUTH, "DescribeLoadBalancers", "Names.member.1", name).statusCode(200)
                    .extract().asString();
            assertEquals(List.of(subnetA), XmlParser.extractAll(described, "SubnetId"));
        } finally {
            request(CFN_AUTH, "DeleteStack", "StackName", stack);
        }
    }

    /** A VPC with two subnets and a network load balancer with the given mappings. */
    private static String template(String name, String mappings) {
        return """
            {
              "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
              "Resources": {
                "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.97.0.0/16"}},
                "SubnetA": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.97.1.0/24", "AvailabilityZone": "us-east-1a"}},
                "SubnetB": {"Type": "AWS::EC2::Subnet", "Properties": {"VpcId": {"Ref": "Vpc"},
                            "CidrBlock": "10.97.2.0/24", "AvailabilityZone": "us-east-1b"}},
                "Nlb": {
                  "Type": "AWS::ElasticLoadBalancingV2::LoadBalancer",
                  "Properties": {"Name": "%s", "Type": "network", "Scheme": "internal", "SubnetMappings": [%s]}
                }
              },
              "Outputs": {"SubnetA": {"Value": {"Ref": "SubnetA"}}}
            }
            """.formatted(name, mappings);
    }

    private static ValidatableResponse request(String auth, String action, String... values) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", action);
        for (int i = 0; i < values.length; i += 2) {
            parameters.put(values[i], values[i + 1]);
        }
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", auth)
                .formParams(parameters).when().post("/").then().log().ifValidationFails();
    }
}

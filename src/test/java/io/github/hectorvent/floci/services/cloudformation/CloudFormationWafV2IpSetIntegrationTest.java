package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnRequests;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.github.hectorvent.floci.testing.WafV2Requests.waf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AWS::WAFv2::IPSet through a stack: Ref is {@code name|id|scope}, {@code Fn::GetAtt} resolves the
 * real {@code Arn} and {@code Id} rather than literal placeholders, an {@code Addresses} change
 * updates in place keeping the id, a rename replaces the set, an omitted {@code Name} still gets a
 * generated one, and deleting the stack deletes it. Every assertion reads WAFv2 back, not only the
 * stack.
 */
@QuarkusTest
class CloudFormationWafV2IpSetIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void ipSetFollowsTheTemplateAcrossCreateUpdateAndStackDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-ipset-" + suffix;
        String nameA = "cfn-ipset-a-" + suffix;
        String nameB = "cfn-ipset-b-" + suffix;

        cfn("CreateStack", stackName, template(nameA, "\"192.0.2.1/32\""));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        String created = describeStack(stackName)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();
        String id = output(created, "IpSetId");
        String arn = output(created, "IpSetArn");

        assertFalse(id.contains("IpSet."), "Fn::GetAtt Id resolved to a literal placeholder");
        assertFalse(arn.contains("IpSet."), "Fn::GetAtt Arn resolved to a literal placeholder");
        assertEquals(nameA + "|" + id + "|REGIONAL", output(created, "IpSetRef"));
        ipSet(nameA, id)
            .body("IPSet.ARN", equalTo(arn))
            .body("IPSet.Addresses", contains("192.0.2.1/32"));

        cfn("UpdateStack", stackName, template(nameA, "\"198.51.100.0/24\""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        String addressesUpdated = describeStack(stackName)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .extract().asString();

        assertEquals(id, output(addressesUpdated, "IpSetId"));
        assertEquals(arn, output(addressesUpdated, "IpSetArn"));
        ipSet(nameA, id).body("IPSet.Addresses", contains("198.51.100.0/24"));

        cfn("UpdateStack", stackName, template(nameB, "\"198.51.100.0/24\""));
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE");
        String replaced = describeStack(stackName)
            .body(containsString("<StackStatus>UPDATE_COMPLETE</StackStatus>"))
            .body(not(containsString("ROLLBACK")))
            .extract().asString();

        String replacementId = output(replaced, "IpSetId");
        String replacementArn = output(replaced, "IpSetArn");
        assertFalse(replacementId.equals(id), "a renamed IP set must be replaced, not updated in place");
        assertEquals(nameB + "|" + replacementId + "|REGIONAL", output(replaced, "IpSetRef"));
        ipSet(nameB, replacementId).body("IPSet.ARN", equalTo(replacementArn));
        listIpSets()
            .body("IPSets.ARN", not(hasItem(arn)))
            .body("IPSets.ARN", hasItem(replacementArn));

        cfn("DeleteStack", stackName, null);

        listIpSets().body("IPSets.ARN", not(hasItem(replacementArn)));
    }

    @Test
    void createStackWithoutIpSetAddressesFailsWithRequiredPropertyReason() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-ipset-required-" + suffix;
        String template = """
                {
                  "Resources": {
                    "IpSet": {
                      "Type": "AWS::WAFv2::IPSet",
                      "Properties": {
                        "Name": "cfn-ipset-required-%s",
                        "Scope": "REGIONAL",
                        "IPAddressVersion": "IPV4"
                      }
                    }
                  }
                }
                """.formatted(suffix);

        cfn("CreateStack", stackName, template);

        CfnStackWaits.awaitStatus(stackName, "ROLLBACK_COMPLETE");
        describeStackEvents(stackName)
            .body(containsString("<ResourceStatus>CREATE_FAILED</ResourceStatus>"))
            .body(containsString(
                    "<ResourceStatusReason>AWS::WAFv2::IPSet requires Addresses</ResourceStatusReason>"));
    }

    @Test
    void createStackWithoutIpSetNameStillCreatesTheSet() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-wafv2-ipset-anon-" + suffix;
        String template = """
                {
                  "Resources": {
                    "IpSet": {
                      "Type": "AWS::WAFv2::IPSet",
                      "Properties": {
                        "Scope": "REGIONAL",
                        "IPAddressVersion": "IPV4",
                        "Addresses": ["203.0.113.10/32"]
                      }
                    }
                  },
                  "Outputs": {
                    "IpSetRef": {"Value": {"Ref": "IpSet"}},
                    "IpSetArn": {"Value": {"Fn::GetAtt": ["IpSet", "Arn"]}},
                    "IpSetId": {"Value": {"Fn::GetAtt": ["IpSet", "Id"]}}
                  }
                }
                """;

        cfn("CreateStack", stackName, template);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE");
        String created = describeStack(stackName)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        String ref = output(created, "IpSetRef");
        String generatedName = ref.substring(0, ref.indexOf('|'));
        assertFalse(generatedName.isBlank(), "an IP set without Name must still get a generated one");
        ipSet(generatedName, output(created, "IpSetId"))
            .body("IPSet.ARN", equalTo(output(created, "IpSetArn")));

        cfn("DeleteStack", stackName, null);

        listIpSets().body("IPSets.ARN", not(hasItem(output(created, "IpSetArn"))));
    }

    private static String template(String name, String addresses) {
        return """
                {
                  "Resources": {
                    "IpSet": {
                      "Type": "AWS::WAFv2::IPSet",
                      "Properties": {
                        "Name": "%1$s",
                        "Scope": "REGIONAL",
                        "IPAddressVersion": "IPV4",
                        "Addresses": [%2$s],
                        "Description": "cfn ip set"
                      }
                    }
                  },
                  "Outputs": {
                    "IpSetRef": {"Value": {"Ref": "IpSet"}},
                    "IpSetArn": {"Value": {"Fn::GetAtt": ["IpSet", "Arn"]}},
                    "IpSetId": {"Value": {"Fn::GetAtt": ["IpSet", "Id"]}}
                  }
                }
                """.formatted(name, addresses);
    }

    private static String output(String describeXml, String key) {
        return XmlPath.from(describeXml).getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member"
                + ".Outputs.member.find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private void cfn(String action, String stackName, String template) {
        RequestSpecification request = CfnRequests.cfn(action, stackName).header("Authorization", CFN_AUTH);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private ValidatableResponse describeStack(String stackName) {
        return CfnRequests.cfn("DescribeStacks", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private ValidatableResponse describeStackEvents(String stackName) {
        return CfnRequests.cfn("DescribeStackEvents", stackName)
            .header("Authorization", CFN_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private ValidatableResponse ipSet(String name, String id) {
        return waf("GetIPSet", "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\"}")
            .statusCode(200);
    }

    private ValidatableResponse listIpSets() {
        return waf("ListIPSets", "{\"Scope\":\"REGIONAL\"}").statusCode(200);
    }
}

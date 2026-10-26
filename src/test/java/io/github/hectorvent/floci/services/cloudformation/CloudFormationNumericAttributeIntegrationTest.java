package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationNumericAttributeIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/ec2/aws4_request";

    @Test
    void aNumericGetAttCanConfigureAnotherResourcesNumericProperty() {
        String stack = "numeric-attribute-" + UUID.randomUUID();
        String template = """
                {"Parameters":{"Number":{"Type":"Number","Default":1}},
                "Resources":{"First":{"Type":"AWS::EC2::PrefixList","Properties":{
                "PrefixListName":"%1$s-first","AddressFamily":"IPv4","MaxEntries":1}},
                "Second":{"Type":"AWS::EC2::PrefixList","Properties":{
                "PrefixListName":"%1$s-second","AddressFamily":"IPv4",
                "MaxEntries":{"Fn::GetAtt":["First","Version"]}}}},
                "Outputs":{"Id":{"Value":{"Fn::GetAtt":["Second","PrefixListId"]}},
                "Arn":{"Value":{"Fn::Sub":"${Second.Arn}"}},
                "Literal":{"Value":1},"Parameter":{"Value":{"Ref":"Number"}},
                "Join":{"Value":{"Fn::Join":["",[1]]}},
                "SubMap":{"Value":{"Fn::Sub":["${Value}",{"Value":1}]}}}}
                """.formatted(stack);
        try {
            cloudFormation(stack, "CreateStack", template);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
            String prefixes = describePrefixLists();
            assertTrue(prefixes.contains(stack + "-second") && prefixes.contains("<maxEntries>1</maxEntries>"),
                    prefixes);
            String described = given().contentType("application/x-www-form-urlencoded")
                    .header("Authorization", CFN_AUTH).formParam("Action", "DescribeStacks")
                    .formParam("StackName", stack).post("/").then().statusCode(200).extract().asString();
            Map<String, String> outputs = XmlParser.extractPairs(
                    described, "Outputs", "OutputKey", "OutputValue");
            for (String key : new String[]{"Literal", "Parameter", "Join", "SubMap"}) {
                assertEquals("1", outputs.get(key), key);
            }
        } finally {
            deleteStack(stack);
        }
        assertFalse(describePrefixLists().contains(stack));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"Fn::GetAtt\":[\"First\",\"Version\"]}",
        "{\"Fn::Join\":[\"\",[{\"Fn::GetAtt\":[\"First\",\"Version\"]}]]}",
        "{\"Fn::Sub\":\"${First.Version}\"}",
        "{\"Fn::Sub\":[\"${Value}\",{\"Value\":{\"Fn::GetAtt\":[\"First\",\"Version\"]}}]}",
        "{\"Fn::Join\":[\"\",[{\"Fn::Select\":[0,[{\"Fn::GetAtt\":[\"First\",\"Version\"]}]]}]]}",
        "{\"Fn::Select\":[0,{\"Fn::Split\":[\",\",{\"Fn::GetAtt\":[\"First\",\"Version\"]}]}]}",
        "{\"Fn::Base64\":{\"Fn::GetAtt\":[\"First\",\"Version\"]}}"
    })
    void numericStringOutputsFailAndRollBackOwnedResources(String value) {
        String stack = "numeric-output-" + UUID.randomUUID();
        String template = """
                {"Resources":{"First":{"Type":"AWS::EC2::PrefixList","Properties":{
                "PrefixListName":"%s","AddressFamily":"IPv4","MaxEntries":1}}},
                "Outputs":{"Version":{"Value":%s}}}
                """.formatted(stack, value);
        try {
            cloudFormation(stack, "CreateStack", template);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack, CFN_AUTH);
            assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
            assertFalse(describePrefixLists().contains(stack));
        } finally {
            deleteStack(stack);
        }
    }

    private static void cloudFormation(String stack, String action, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH).formParam("Action", action).formParam("StackName", stack);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static void deleteStack(String stack) {
        cloudFormation(stack, "DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(stack, CFN_AUTH);
    }

    private static String describePrefixLists() {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", EC2_AUTH).formParam("Action", "DescribeManagedPrefixLists")
                .formParam("Version", "2016-11-15")
                .when().post("/").then().statusCode(200).extract().asString();
    }
}

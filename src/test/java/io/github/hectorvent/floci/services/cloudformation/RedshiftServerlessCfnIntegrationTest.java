package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessRuntime;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * Provisions an {@code AWS::RedshiftServerless::Namespace} and its
 * {@code AWS::RedshiftServerless::Workgroup} through a stack. An unmapped type is stubbed as
 * CREATE_COMPLETE with a fake ARN, so the assertions that matter are the exact {@code Fn::GetAtt}
 * keys and that what they return is what the Redshift Serverless API serves.
 */
@QuarkusTest
class RedshiftServerlessCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261003/us-east-1/cloudformation/aws4_request";
    private static final String RS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261003/us-east-1/redshift-serverless/aws4_request";
    private static final String STACK = "redshift-serverless-cfn-it";
    private static final String NAMESPACE = "rs-cfn-it-ns";
    private static final String WORKGROUP = "rs-cfn-it-wg";

    private static final String TEMPLATE = """
        {
          "Parameters": {"Capacity": {"Type": "Number"}},
          "Resources": {
            "Ns": {
              "Type": "AWS::RedshiftServerless::Namespace",
              "Properties": {
                "NamespaceName": "%s",
                "AdminUsername": "admin",
                "AdminUserPassword": "Secret123!",
                "DbName": "analytics",
                "LogExports": ["userlog"],
                "Tags": [{"Key": "env", "Value": "dev"}]
              }
            },
            "Wg": {
              "Type": "AWS::RedshiftServerless::Workgroup",
              "Properties": {
                "WorkgroupName": "%s",
                "NamespaceName": {"Ref": "Ns"},
                "BaseCapacity": {"Ref": "Capacity"},
                "PubliclyAccessible": true
              }
            }
          },
          "Outputs": {
            "NsRef": {"Value": {"Ref": "Ns"}},
            "NsArn": {"Value": {"Fn::GetAtt": ["Ns", "Namespace.NamespaceArn"]}},
            "NsId": {"Value": {"Fn::GetAtt": ["Ns", "Namespace.NamespaceId"]}},
            "NsDb": {"Value": {"Fn::GetAtt": ["Ns", "Namespace.DbName"]}},
            "NsStatus": {"Value": {"Fn::GetAtt": ["Ns", "Namespace.Status"]}},
            "WgRef": {"Value": {"Ref": "Wg"}},
            "WgArn": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.WorkgroupArn"]}},
            "WgId": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.WorkgroupId"]}},
            "WgNamespace": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.NamespaceName"]}},
            "WgAddress": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.Endpoint.Address"]}},
            "WgPort": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.Endpoint.Port"]}},
            "WgBase": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.BaseCapacity"]}},
            "WgStatus": {"Value": {"Fn::GetAtt": ["Wg", "Workgroup.Status"]}}
          }
        }
        """.formatted(NAMESPACE, WORKGROUP);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /**
     * The metadata behaviour under test needs no PostgreSQL container, so the runtime is replaced:
     * these tests then run without Docker, and the real container and proxy are exercised by
     * {@code RedshiftServerlessRuntimeIntegrationTest}.
     */
    @InjectMock
    RedshiftServerlessRuntime runtime;

    @BeforeEach
    void stubRuntime() {
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
    }

    @Test
    void createUpdateAndDeleteANamespaceAndWorkgroup() {
        cloudFormation("CreateStack", "32");
        String created = describeStacks("CREATE_COMPLETE");

        assertEquals(NAMESPACE, outputValue(created, "NsRef"));
        assertEquals(WORKGROUP, outputValue(created, "WgRef"));
        assertEquals("analytics", outputValue(created, "NsDb"));
        assertEquals("AVAILABLE", outputValue(created, "NsStatus"));
        assertEquals("AVAILABLE", outputValue(created, "WgStatus"));
        assertEquals(NAMESPACE, outputValue(created, "WgNamespace"));
        assertEquals("32", outputValue(created, "WgBase"));
        assertTrue(outputValue(created, "WgArn").contains(":workgroup/" + outputValue(created, "WgId")),
                "GetAtt must be the ARN of the workgroup, not the 'Workgroup.WorkgroupArn' literal");
        assertNotEquals("Wg.Workgroup.Endpoint.Address", outputValue(created, "WgAddress"));
        assertTrue(Integer.parseInt(outputValue(created, "WgPort")) > 0);

        call("GetNamespace", "{\"namespaceName\":\"" + NAMESPACE + "\"}")
                .statusCode(200)
                .body("namespace.namespaceArn", equalTo(outputValue(created, "NsArn")))
                .body("namespace.namespaceId", equalTo(outputValue(created, "NsId")))
                .body("namespace.logExports", hasItem("userlog"));
        call("GetWorkgroup", "{\"workgroupName\":\"" + WORKGROUP + "\"}")
                .statusCode(200)
                .body("workgroup.workgroupArn", equalTo(outputValue(created, "WgArn")))
                .body("workgroup.baseCapacity", equalTo(32))
                .body("workgroup.publiclyAccessible", equalTo(true))
                .body("workgroup.endpoint.address", equalTo(outputValue(created, "WgAddress")));

        cloudFormation("UpdateStack", "64");
        String updated = describeStacks("UPDATE_COMPLETE");

        // The capacity changes on the same workgroup; nothing is replaced.
        assertEquals(outputValue(created, "WgId"), outputValue(updated, "WgId"));
        assertEquals("64", outputValue(updated, "WgBase"));
        call("GetWorkgroup", "{\"workgroupName\":\"" + WORKGROUP + "\"}")
                .statusCode(200)
                .body("workgroup.baseCapacity", equalTo(64));

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);

        // The workgroup is deleted before the namespace, which would otherwise be refused.
        call("GetWorkgroup", "{\"workgroupName\":\"" + WORKGROUP + "\"}").statusCode(400);
        call("GetNamespace", "{\"namespaceName\":\"" + NAMESPACE + "\"}").statusCode(400);
    }

    private static void cloudFormation(String action, String capacity) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", STACK);
        if (!"DeleteStack".equals(action)) {
            request.formParam("TemplateBody", TEMPLATE);
            request.formParam("Parameters.member.1.ParameterKey", "Capacity");
            request.formParam("Parameters.member.1.ParameterValue", capacity);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks(String expectedStatus) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", STACK)
                .when().post("/").then().statusCode(200)
                .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
                .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        Map<String, String> outputs = XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
        return outputs.get(key);
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .header("X-Amz-Target", "RedshiftServerless." + action)
                .header("Authorization", RS_AUTH)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when()
                .post("/")
                .then();
    }
}

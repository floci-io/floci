package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnRequests;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stands a STRICT policy store with its schema, a policy template, a static policy and a policy
 * linked to the template through {@code Fn::GetAtt PolicyTemplateId}, reads each back through
 * Verified Permissions' own API, updates the descriptions and tags in place, and deletes the stack.
 *
 * <p>Runs against the real Cedar sidecar image, started by {@code CedarSidecarManager} as in
 * production, since the service parses every policy and validates every schema through it. Needs
 * Docker; skipped without it, and locally when the pinned image is not present.
 */
@QuarkusTest
@TestProfile(SidecarContainersProfile.class)
class VerifiedPermissionsCfnIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/cloudformation/aws4_request";
    private static final String AVP_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/verifiedpermissions/aws4_request";
    private static final String STACK = "verifiedpermissions-cfn-it";
    private static final String ACCOUNT = "000000000000";
    private static final String SCHEMA = "{\\\"Demo\\\":{\\\"entityTypes\\\":{"
            + "\\\"User\\\":{\\\"shape\\\":{\\\"type\\\":\\\"Record\\\",\\\"attributes\\\":{}}},"
            + "\\\"Doc\\\":{\\\"shape\\\":{\\\"type\\\":\\\"Record\\\",\\\"attributes\\\":{}}}},"
            + "\\\"actions\\\":{\\\"read\\\":{\\\"appliesTo\\\":{\\\"principalTypes\\\":[\\\"User\\\"],"
            + "\\\"resourceTypes\\\":[\\\"Doc\\\"]}}}}}";
    private static final String TEMPLATE_STATEMENT =
            "permit(principal == ?principal, action == Demo::Action::\\\"read\\\", resource == ?resource);";
    private static final String STATIC_STATEMENT = "permit(principal, action == Demo::Action::\\\"read\\\", resource);";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Phase": {"Type": "String"},
            "Team": {"Type": "String"}
          },
          "Resources": {
            "Store": {
              "Type": "AWS::VerifiedPermissions::PolicyStore",
              "Properties": {
                "ValidationSettings": {"Mode": "STRICT"},
                "Description": {"Fn::Join": ["", ["authz ", {"Ref": "Phase"}]]},
                "DeletionProtection": {"Mode": "DISABLED"},
                "Schema": {"CedarJson": "%1$s"},
                "Tags": [{"Key": "team", "Value": {"Ref": "Team"}}]
              }
            },
            "ReadTemplate": {
              "Type": "AWS::VerifiedPermissions::PolicyTemplate",
              "Properties": {
                "PolicyStoreId": {"Ref": "Store"},
                "Statement": "%2$s",
                "Description": {"Fn::Join": ["", ["per-document read ", {"Ref": "Phase"}]]}
              }
            },
            "ReadAll": {
              "Type": "AWS::VerifiedPermissions::Policy",
              "Properties": {
                "PolicyStoreId": {"Fn::GetAtt": ["Store", "PolicyStoreId"]},
                "Definition": {"Static": {
                  "Statement": "%3$s",
                  "Description": {"Fn::Join": ["", ["everyone reads ", {"Ref": "Phase"}]]}
                }}
              }
            },
            "AliceReadsReadme": {
              "Type": "AWS::VerifiedPermissions::Policy",
              "Properties": {
                "PolicyStoreId": {"Ref": "Store"},
                "Definition": {"TemplateLinked": {
                  "PolicyTemplateId": {"Fn::GetAtt": ["ReadTemplate", "PolicyTemplateId"]},
                  "Principal": {"EntityType": "Demo::User", "EntityId": "alice"},
                  "Resource": {"EntityType": "Demo::Doc", "EntityId": "readme"}
                }}
              }
            }
          },
          "Outputs": {
            "StoreRef": {"Value": {"Ref": "Store"}},
            "StoreArn": {"Value": {"Fn::GetAtt": ["Store", "Arn"]}},
            "StoreId": {"Value": {"Fn::GetAtt": ["Store", "PolicyStoreId"]}},
            "TemplateRef": {"Value": {"Ref": "ReadTemplate"}},
            "TemplateId": {"Value": {"Fn::GetAtt": ["ReadTemplate", "PolicyTemplateId"]}},
            "ReadAllRef": {"Value": {"Ref": "ReadAll"}},
            "ReadAllId": {"Value": {"Fn::GetAtt": ["ReadAll", "PolicyId"]}},
            "ReadAllType": {"Value": {"Fn::GetAtt": ["ReadAll", "PolicyType"]}},
            "LinkedRef": {"Value": {"Ref": "AliceReadsReadme"}},
            "LinkedId": {"Value": {"Fn::GetAtt": ["AliceReadsReadme", "PolicyId"]}},
            "LinkedType": {"Value": {"Fn::GetAtt": ["AliceReadsReadme", "PolicyType"]}}
          }
        }
        """.formatted(SCHEMA, TEMPLATE_STATEMENT, STATIC_STATEMENT);

    @BeforeAll
    static void requireDockerAndTheSidecarImage() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        SidecarContainersProfile.requireDockerAndImage("floci.services.verifiedpermissions.cedar-image");
    }

    @Test
    void standsUpdatesAndDeletesAStoreWithItsSchemaTemplateAndPolicies() {
        deploy("CreateStack", "create", "platform");
        CfnStackWaits.awaitStatus(STACK, "CREATE_COMPLETE");
        XmlPath created = CfnRequests.describe(STACK, CFN_AUTH);

        String storeId = CfnRequests.output(created, "StoreId");
        String storeArn = "arn:aws:verifiedpermissions::" + ACCOUNT + ":policy-store/" + storeId;
        String templateId = CfnRequests.output(created, "TemplateId");
        String readAllId = CfnRequests.output(created, "ReadAllId");
        String linkedId = CfnRequests.output(created, "LinkedId");
        assertEquals(storeId, CfnRequests.output(created, "StoreRef"));
        assertEquals(storeArn, CfnRequests.output(created, "StoreArn"));
        assertEquals(storeId + "|" + templateId, CfnRequests.output(created, "TemplateRef"));
        assertEquals(readAllId + "|" + storeId, CfnRequests.output(created, "ReadAllRef"));
        assertEquals("STATIC", CfnRequests.output(created, "ReadAllType"));
        assertEquals(linkedId + "|" + storeId, CfnRequests.output(created, "LinkedRef"));
        assertEquals("TEMPLATE_LINKED", CfnRequests.output(created, "LinkedType"));

        avp("GetPolicyStore", "{\"policyStoreId\":\"" + storeId + "\",\"tags\":true}")
                .statusCode(200)
                .body("arn", equalTo(storeArn))
                .body("validationSettings.mode", equalTo("STRICT"))
                .body("deletionProtection", equalTo("DISABLED"))
                .body("description", equalTo("authz create"))
                .body("tags", hasEntry("team", "platform"));
        avp("GetSchema", "{\"policyStoreId\":\"" + storeId + "\"}")
                .statusCode(200)
                .body("namespaces[0]", equalTo("Demo"));
        avp("GetPolicyTemplate", "{\"policyStoreId\":\"" + storeId + "\",\"policyTemplateId\":\"" + templateId + "\"}")
                .statusCode(200)
                .body("policyTemplateId", matchesPattern("PT[A-Za-z0-9]+"))
                .body("statement", containsString("?principal"))
                .body("description", equalTo("per-document read create"));
        avp("GetPolicy", "{\"policyStoreId\":\"" + storeId + "\",\"policyId\":\"" + readAllId + "\"}")
                .statusCode(200)
                .body("policyType", equalTo("STATIC"))
                .body("definition.static.statement", containsString("Demo::Action::\"read\""))
                .body("definition.static.description", equalTo("everyone reads create"));
        avp("GetPolicy", "{\"policyStoreId\":\"" + storeId + "\",\"policyId\":\"" + linkedId + "\"}")
                .statusCode(200)
                .body("policyType", equalTo("TEMPLATE_LINKED"))
                .body("definition.templateLinked.policyTemplateId", equalTo(templateId))
                .body("definition.templateLinked.principal.entityId", equalTo("alice"))
                .body("definition.templateLinked.resource.entityType", equalTo("Demo::Doc"));

        deploy("UpdateStack", "update", "data");
        CfnStackWaits.awaitStatus(STACK, "UPDATE_COMPLETE");
        XmlPath updated = CfnRequests.describe(STACK, CFN_AUTH);

        assertEquals(storeId, CfnRequests.output(updated, "StoreRef"));
        assertEquals(storeId + "|" + templateId, CfnRequests.output(updated, "TemplateRef"));
        assertEquals(readAllId + "|" + storeId, CfnRequests.output(updated, "ReadAllRef"));
        assertEquals(linkedId + "|" + storeId, CfnRequests.output(updated, "LinkedRef"));
        avp("GetPolicyStore", "{\"policyStoreId\":\"" + storeId + "\",\"tags\":true}")
                .statusCode(200)
                .body("description", equalTo("authz update"))
                .body("tags", hasEntry("team", "data"))
                .body("tags", not(hasEntry("team", "platform")));
        avp("GetSchema", "{\"policyStoreId\":\"" + storeId + "\"}").statusCode(200);
        avp("GetPolicyTemplate", "{\"policyStoreId\":\"" + storeId + "\",\"policyTemplateId\":\"" + templateId + "\"}")
                .statusCode(200)
                .body("description", equalTo("per-document read update"));
        avp("GetPolicy", "{\"policyStoreId\":\"" + storeId + "\",\"policyId\":\"" + readAllId + "\"}")
                .statusCode(200)
                .body("definition.static.description", equalTo("everyone reads update"));

        CfnRequests.cfn("DeleteStack", STACK).header("Authorization", CFN_AUTH).when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(STACK);

        avp("GetPolicyStore", "{\"policyStoreId\":\"" + storeId + "\"}")
                .statusCode(400)
                .body("__type", containsString("ResourceNotFoundException"));
    }

    private static void deploy(String action, String phase, String team) {
        CfnRequests.cfn(action, STACK)
                .header("Authorization", CFN_AUTH)
                .formParam("TemplateBody", TEMPLATE)
                .formParam("Parameters.member.1.ParameterKey", "Phase")
                .formParam("Parameters.member.1.ParameterValue", phase)
                .formParam("Parameters.member.2.ParameterKey", "Team")
                .formParam("Parameters.member.2.ParameterValue", team)
                .when().post("/").then().statusCode(200);
    }

    private static ValidatableResponse avp(String action, String body) {
        return given().contentType("application/x-amz-json-1.0").header("Authorization", AVP_AUTH)
                .header("X-Amz-Target", "VerifiedPermissions." + action).body(body)
                .when().post("/").then();
    }
}

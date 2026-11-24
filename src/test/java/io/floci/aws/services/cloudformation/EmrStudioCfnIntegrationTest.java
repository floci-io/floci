package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.CfnStackWaits.output;
import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Provisions {@code AWS::EMR::SecurityConfiguration}, {@code AWS::EMR::Studio} and
 * {@code AWS::EMR::StudioSessionMapping} through a stack and reads them back through EMR: each {@code Ref} and the
 * Studio's {@code Fn::GetAtt} attributes name them, an update renames the Studio, retags it and gives the mapping a
 * new session policy in place, a later resource's failure puts all three back, and deleting the stack deletes them.
 */
@QuarkusTest
class EmrStudioCfnIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String POLICY = "arn:aws:iam::000000000000:policy/EMRStudio_Basic_User_Policy";

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("emr-studio-cfn-it");

    @Inject
    Ec2Service ec2;

    @Inject
    SsoAdminService ssoAdmin;

    @Inject
    IdentityStoreService identityStore;

    @Inject
    ObjectMapper objectMapper;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createUpdateRollBackAndDeleteASecurityConfigurationStudioAndSessionMapping() throws Exception {
        String vpcId = ec2.describeVpcs(REGION, List.of(), Map.of("is-default", List.of("true"))).get(0).getVpcId();
        List<String> subnetIds = ec2.describeSubnets(REGION, List.of(), Map.of("vpc-id", List.of(vpcId))).stream()
                .map(Subnet::getSubnetId).limit(2).toList();
        String securityGroupId = ec2.describeSecurityGroups(REGION, List.of(), List.of(),
                Map.of("vpc-id", List.of(vpcId))).get(0).getGroupId();
        String storeId = ssoAdmin.listInstances(ACCOUNT).get(0).identityStoreId();
        String userName = "cfn-" + UUID.randomUUID();
        String userId = identityStore.createUser(objectMapper.readTree("""
                {"IdentityStoreId": "%s", "UserName": "%s", "DisplayName": "Ana"}
                """.formatted(storeId, userName))).userId();
        try {
            stackLifecycle(vpcId, subnetIds, securityGroupId, userName);
        } finally {
            identityStore.deleteUser(objectMapper.readTree("""
                    {"IdentityStoreId": "%s", "UserId": "%s"}
                    """.formatted(storeId, userId)));
        }
    }

    private void stackLifecycle(String vpcId, List<String> subnetIds, String securityGroupId, String userName) {
        String securityName = stack.name() + "-security";
        Template template = new Template(securityName, vpcId, subnetIds, securityGroupId, userName);

        Stack created = stack.create(template.render("notebooks", "v1", POLICY, false));
        String studioId = output(created, "StudioRef");
        assertEquals(studioId, output(created, "StudioId"));
        assertEquals("arn:aws:elasticmapreduce:us-east-1:000000000000:studio/" + studioId,
                output(created, "StudioArn"));
        assertEquals(studioId + "|USER|" + userName, output(created, "MappingRef"));
        assertEquals(securityName, output(created, "SecurityRef"));
        describeStudio(studioId)
            .body("Studio.Name", equalTo("notebooks"))
            .body("Studio.AuthMode", equalTo("SSO"))
            .body("Studio.Url", equalTo(output(created, "StudioUrl")))
            .body("Studio.Tags[0].Value", equalTo("v1"));
        mapping(studioId, userName).body("SessionMapping.SessionPolicyArn", equalTo(POLICY));
        emr("DescribeSecurityConfiguration", "{\"Name\": \"" + securityName + "\"}").then().statusCode(200)
            .body("Name", equalTo(securityName));

        Stack updated = stack.update(template.render("renamed", "v2", POLICY + "_V2", false), "UPDATE_COMPLETE");
        assertEquals(studioId, output(updated, "StudioRef"));
        describeStudio(studioId).body("Studio.Name", equalTo("renamed")).body("Studio.Tags[0].Value", equalTo("v2"));
        mapping(studioId, userName).body("SessionMapping.SessionPolicyArn", equalTo(POLICY + "_V2"));

        stack.update(template.render("again", "v3", POLICY, true), "UPDATE_ROLLBACK_COMPLETE");
        describeStudio(studioId).body("Studio.Name", equalTo("renamed")).body("Studio.Tags[0].Value", equalTo("v2"));
        mapping(studioId, userName).body("SessionMapping.SessionPolicyArn", equalTo(POLICY + "_V2"));

        CfnStackWaits.deleteStack(stack.name());

        emr("DescribeStudio", "{\"StudioId\": \"" + studioId + "\"}").then().statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        emr("DescribeSecurityConfiguration", "{\"Name\": \"" + securityName + "\"}").then().statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
    }

    private static ValidatableResponse describeStudio(String studioId) {
        return emr("DescribeStudio", "{\"StudioId\": \"" + studioId + "\"}").then().statusCode(200);
    }

    private static ValidatableResponse mapping(String studioId, String userName) {
        return emr("GetStudioSessionMapping", """
                {"StudioId": "%s", "IdentityType": "USER", "IdentityName": "%s"}
                """.formatted(studioId, userName)).then().statusCode(200);
    }

    private record Template(String securityName, String vpcId, List<String> subnetIds, String securityGroupId,
                            String userName) {

        String render(String studioName, String stage, String policy, boolean laterFails) {
            return """
                {
                  "Resources": {
                    "Security": {
                      "Type": "AWS::EMR::SecurityConfiguration",
                      "Properties": {
                        "Name": "%s",
                        "SecurityConfiguration": {"EncryptionConfiguration": {
                          "EnableInTransitEncryption": false, "EnableAtRestEncryption": false}}
                      }
                    },
                    "Studio": {
                      "Type": "AWS::EMR::Studio",
                      "Properties": {
                        "Name": "%s",
                        "AuthMode": "SSO",
                        "VpcId": "%s",
                        "SubnetIds": ["%s", "%s"],
                        "ServiceRole": "arn:aws:iam::000000000000:role/EMRStudio_Service_Role",
                        "UserRole": "arn:aws:iam::000000000000:role/EMRStudio_User_Role",
                        "WorkspaceSecurityGroupId": "%s",
                        "EngineSecurityGroupId": "%s",
                        "DefaultS3Location": "s3://studio-bucket/workspaces",
                        "Tags": [{"Key": "stage", "Value": "%s"}]
                      }
                    },
                    "Mapping": {
                      "Type": "AWS::EMR::StudioSessionMapping",
                      "Properties": {
                        "StudioId": {"Ref": "Studio"},
                        "IdentityType": "USER",
                        "IdentityName": "%s",
                        "SessionPolicyArn": "%s"
                      }
                    }%s
                  },
                  "Outputs": {
                    "SecurityRef": {"Value": {"Ref": "Security"}},
                    "StudioRef": {"Value": {"Ref": "Studio"}},
                    "StudioId": {"Value": {"Fn::GetAtt": ["Studio", "StudioId"]}},
                    "StudioArn": {"Value": {"Fn::GetAtt": ["Studio", "Arn"]}},
                    "StudioUrl": {"Value": {"Fn::GetAtt": ["Studio", "Url"]}},
                    "MappingRef": {"Value": {"Ref": "Mapping"}}
                  }
                }
                """.formatted(securityName, studioName, vpcId, subnetIds.get(0), subnetIds.get(1), securityGroupId,
                    securityGroupId, stage, userName, policy,
                    laterFails ? CfnStackWaits.failingResourceAfter("Mapping") : "");
        }
    }
}

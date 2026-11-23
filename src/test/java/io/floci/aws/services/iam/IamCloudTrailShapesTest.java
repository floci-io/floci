package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

class IamCloudTrailShapesTest {

    private static final IamCloudTrailShapes SHAPES = IamCloudTrailShapes.get();

    private static MultivaluedMap<String, String> params(String... pairs) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.putSingle(pairs[i], pairs[i + 1]);
        }
        return params;
    }

    @Test
    void requestParametersAreTheMembersWithALowerFirstLetterAndTheirModelTypes() {
        ObjectNode read = SHAPES.requestParameters("CreateRole", params("Action", "CreateRole",
                "Version", "2010-05-08", "RoleName", "ops", "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\"}",
                "MaxSessionDuration", "7200", "Tags.member.1.Key", "team", "Tags.member.1.Value", "it",
                "Tags.member.2.Key", "env", "Tags.member.2.Value", "prod"));
        assertThat(read.path("roleName").asText(), equalTo("ops"));
        assertThat(read.path("assumeRolePolicyDocument").asText(), equalTo("{\"Version\":\"2012-10-17\"}"));
        assertThat(read.path("maxSessionDuration").isNumber(), equalTo(true));
        assertThat(read.path("maxSessionDuration").asInt(), equalTo(7200));
        assertThat(read.path("tags").size(), equalTo(2));
        assertThat(read.path("tags").get(1).path("key").asText(), equalTo("env"));
        assertThat(read.path("tags").get(1).path("value").asText(), equalTo("prod"));
        assertThat(read.has("action"), equalTo(false));
        assertThat(read.has("version"), equalTo(false));
    }

    @Test
    void aListOfStringsIsAnArrayOfThem() {
        ObjectNode read = SHAPES.requestParameters("UntagUser", params("Action", "UntagUser",
                "UserName", "alice", "TagKeys.member.1", "team", "TagKeys.member.2", "env"));
        assertThat(read.path("userName").asText(), equalTo("alice"));
        assertThat(read.path("tagKeys").size(), equalTo(2));
        assertThat(read.path("tagKeys").get(0).asText(), equalTo("team"));
    }

    @Test
    void aCallWhoseOnlyMembersAreSensitiveHasNoRequestParameters() {
        assertThat(SHAPES.requestParameters("ChangePassword",
                params("Action", "ChangePassword", "OldPassword", "old-one", "NewPassword", "new-one")), nullValue());
    }

    @Test
    void aSensitiveMemberIsLeftOutOfTheRequestParameters() {
        ObjectNode read = SHAPES.requestParameters("CreateLoginProfile", params("Action", "CreateLoginProfile",
                "UserName", "alice", "Password", "s3cr3t-P@ss", "PasswordResetRequired", "true"));
        assertThat(read.has("password"), equalTo(false));
        assertThat(read.path("userName").asText(), equalTo("alice"));
        assertThat(read.path("passwordResetRequired").isBoolean(), equalTo(true));
    }

    @Test
    void anOperationTheModelDoesNotCarryHasNoRequestParameters() {
        assertThat(SHAPES.requestParameters("MadeUpOperation", params("UserName", "alice")), nullValue());
    }

    @Test
    void theResultLeavesSecretsOutAndWritesTimestampsAsCloudTrailDoes() {
        String xml = """
                <CreateAccessKeyResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
                  <CreateAccessKeyResult><AccessKey>
                    <UserName>alice</UserName><AccessKeyId>AKIAIOSFODNN7EXAMPLE</AccessKeyId>
                    <Status>Active</Status><SecretAccessKey>wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY</SecretAccessKey>
                    <CreateDate>2023-07-19T21:25:09Z</CreateDate>
                  </AccessKey></CreateAccessKeyResult>
                  <ResponseMetadata><RequestId>7a62c49f-347e-4fc4-9331-6e8eEXAMPLE</RequestId></ResponseMetadata>
                </CreateAccessKeyResponse>""";
        ObjectNode result = SHAPES.result("CreateAccessKey", xml);
        assertThat(result.path("accessKey").path("accessKeyId").asText(), equalTo("AKIAIOSFODNN7EXAMPLE"));
        assertThat(result.path("accessKey").has("secretAccessKey"), equalTo(false));
        assertThat(result.path("accessKey").path("createDate").asText(), equalTo("Jul 19, 2023 9:25:09 PM"));
    }

    @Test
    void aServiceSpecificCredentialsPasswordIsLeftOutOfTheResult() {
        String xml = """
                <CreateServiceSpecificCredentialResponse><CreateServiceSpecificCredentialResult>
                  <ServiceSpecificCredential>
                    <UserName>alice</UserName><ServiceName>codecommit.amazonaws.com</ServiceName>
                    <ServiceUserName>alice-at-123456789012</ServiceUserName><ServicePassword>p4ss</ServicePassword>
                    <ServiceSpecificCredentialId>ACCAEXAMPLE123EXAMPLE</ServiceSpecificCredentialId>
                    <Status>Active</Status><CreateDate>2024-10-29T14:43:20Z</CreateDate>
                  </ServiceSpecificCredential>
                </CreateServiceSpecificCredentialResult></CreateServiceSpecificCredentialResponse>""";
        ObjectNode credential = (ObjectNode) SHAPES.result("CreateServiceSpecificCredential", xml)
                .path("serviceSpecificCredential");
        assertThat(credential.has("servicePassword"), equalTo(false));
        assertThat(credential.path("serviceUserName").asText(), equalTo("alice-at-123456789012"));
    }

    @ParameterizedTest
    @CsvSource({"NoSuchEntity,NoSuchEntityException", "DeleteConflict,DeleteConflictException",
            "MalformedPolicyDocument,MalformedPolicyDocumentException", "ReportInProgress,CredentialReportNotReadyException",
            "NotSupportedService,ServiceNotSupportedException", "AccessDenied,AccessDenied",
            "ValidationError,ValidationError"})
    void modeledErrorsAreRecordedUnderTheirShapesName(String code, String recorded) {
        assertThat(SHAPES.recordedErrorCode(code), equalTo(recorded));
    }

    @Test
    void theResultTypesBooleansAndNumbersAsTheModelDoes() {
        String xml = """
                <CreatePolicyResponse><CreatePolicyResult><Policy>
                  <PolicyName>Level3</PolicyName><DefaultVersionId>v1</DefaultVersionId>
                  <AttachmentCount>0</AttachmentCount><PermissionsBoundaryUsageCount>0</PermissionsBoundaryUsageCount>
                  <IsAttachable>true</IsAttachable><PolicyId>ANPAHI17SXDY4VPL1N1K8</PolicyId>
                  <Description>true</Description>
                </Policy></CreatePolicyResult></CreatePolicyResponse>""";
        ObjectNode policy = (ObjectNode) SHAPES.result("CreatePolicy", xml).path("policy");
        assertThat(policy.path("attachmentCount").isNumber(), equalTo(true));
        assertThat(policy.path("permissionsBoundaryUsageCount").isNumber(), equalTo(true));
        assertThat(policy.path("isAttachable").isBoolean(), equalTo(true));
        assertThat(policy.path("defaultVersionId").asText(), equalTo("v1"));
        assertThat(policy.path("description").isTextual(), equalTo(true));
    }

    @Test
    void aListInTheResultIsAnArrayAndAnEmptyResultIsNull() {
        String xml = """
                <AddUserToGroupResponse xmlns="https://iam.amazonaws.com/doc/2010-05-08/">
                  <ResponseMetadata><RequestId>1</RequestId></ResponseMetadata>
                </AddUserToGroupResponse>""";
        assertThat(SHAPES.result("AddUserToGroup", xml), nullValue());
        String listed = """
                <CreateRoleResponse><CreateRoleResult><Role>
                  <RoleName>ops</RoleName><MaxSessionDuration>3600</MaxSessionDuration>
                  <Tags><member><Key>a</Key><Value>1</Value></member></Tags>
                </Role></CreateRoleResult></CreateRoleResponse>""";
        ObjectNode role = (ObjectNode) SHAPES.result("CreateRole", listed).path("role");
        assertThat(role.path("tags").get(0).path("key").asText(), equalTo("a"));
        assertThat(role.path("tags").get(0).path("value").asText(), equalTo("1"));
        assertThat(role.path("maxSessionDuration").isNumber(), equalTo(true));
    }
}

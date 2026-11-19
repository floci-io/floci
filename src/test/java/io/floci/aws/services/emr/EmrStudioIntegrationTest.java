package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.identitystore.IdentityStoreService;
import io.floci.aws.services.ssoadmin.SsoAdminService;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EmrRequests.emr;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EMR Studios and their session mappings over the AWS JSON 1.1 wire protocol, against Floci's own EC2 (the Region's
 * default VPC, its subnets and security group) and IAM Identity Center (the account's instance and its identity store).
 */
@QuarkusTest
class EmrStudioIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String POLICY = "arn:aws:iam::000000000000:policy/EMRStudio_Basic_User_Policy";

    @Inject
    Ec2Service ec2;

    @Inject
    SsoAdminService ssoAdmin;

    @Inject
    IdentityStoreService identityStore;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EmrService emrService;

    private String vpcId;
    private List<String> subnetIds;
    private String securityGroupId;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void network() {
        vpcId = ec2.describeVpcs(REGION, List.of(), Map.of("is-default", List.of("true"))).get(0).getVpcId();
        subnetIds = ec2.describeSubnets(REGION, List.of(), Map.of("vpc-id", List.of(vpcId))).stream()
                .map(Subnet::getSubnetId).limit(3).toList();
        securityGroupId = ec2.describeSecurityGroups(REGION, List.of(), List.of(),
                Map.of("vpc-id", List.of(vpcId))).get(0).getGroupId();
        assertTrue(subnetIds.size() >= 3, "the default VPC's subnets: " + subnetIds);
    }

    @Test
    void anIdentityCenterStudioIsCreatedDescribedListedUpdatedAndDeleted() {
        String studioId = studio("SSO", "\"UserRole\": \"EMRStudio_User_Role\",", subnetIds.subList(0, 2));

        emr("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.StudioId", equalTo(studioId))
                .body("Studio.StudioArn", equalTo("arn:aws:elasticmapreduce:us-east-1:000000000000:studio/"
                        + studioId))
                .body("Studio.AuthMode", equalTo("SSO"))
                .body("Studio.VpcId", equalTo(vpcId))
                .body("Studio.SubnetIds", equalTo(subnetIds.subList(0, 2)))
                .body("Studio.UserRole", equalTo("EMRStudio_User_Role"))
                .body("Studio.Url", endsWith("/_aws/emr/studios/" + studioId))
                .body("Studio.TrustedIdentityPropagationEnabled", equalTo(false))
                .body("Studio.CreationTime", notNullValue())
                .body("Studio.Tags[0].Key", equalTo("team"));
        emr("ListStudios", "{}")
                .then().statusCode(200)
                .body("Studios.StudioId", hasItem(studioId));

        emr("UpdateStudio", "{\"StudioId\":\"" + studioId + "\",\"Name\":\"renamed\",\"SubnetIds\":"
                        + json(subnetIds) + "}")
                .then().statusCode(200);
        emr("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.Name", equalTo("renamed"))
                .body("Studio.SubnetIds", equalTo(subnetIds));
        // "must also include all of the subnet IDs previously associated with the Studio"
        emr("UpdateStudio", "{\"StudioId\":\"" + studioId + "\",\"SubnetIds\":" + json(subnetIds.subList(0, 1))
                        + "}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        emr("DeleteStudio", "{\"StudioId\":\"" + studioId + "\"}").then().statusCode(200);
        emr("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("ListStudios", "{}")
                .then().statusCode(200)
                .body("Studios.StudioId", not(hasItem(studioId)));
    }

    @Test
    void aStudiosRepeatedSubnetsAreKeptOnceAndItsTagsChangeThroughTheTagCalls() {
        String repeated = subnetIds.get(0);
        String studioId = studio("IAM", "", List.of(repeated, repeated, repeated, repeated, repeated, repeated));
        emr("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.SubnetIds", equalTo(List.of(repeated)));

        // A tag call's ResourceId is "a cluster identifier or an Amazon EMR Studio ID".
        emr("AddTags", "{\"ResourceId\":\"" + studioId + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}")
                .then().statusCode(200);
        emr("RemoveTags", "{\"ResourceId\":\"" + studioId + "\",\"TagKeys\":[\"team\"]}")
                .then().statusCode(200);
        emr("DescribeStudio", "{\"StudioId\":\"" + studioId + "\"}")
                .then().statusCode(200)
                .body("Studio.Tags.size()", equalTo(1))
                .body("Studio.Tags[0].Key", equalTo("env"))
                .body("Studio.Tags[0].Value", equalTo("dev"));
        // What an IAM condition on aws:ResourceTag reads of the Studio, and the ARN a tag call names it by.
        String arn = "arn:aws:elasticmapreduce:us-east-1:000000000000:studio/" + studioId;
        assertEquals(Map.of("env", "dev"), emrService.storedTags(arn));
        assertEquals(List.of(arn), emrService.through("ResourceId", studioId, REGION));
        emr("DeleteStudio", "{\"StudioId\":\"" + studioId + "\"}").then().statusCode(200);
    }

    @Test
    void aMappingWhoseUserHasGoneCanStillBeReadAndRemoved() throws Exception {
        String storeId = ssoAdmin.listInstances(ACCOUNT).get(0).identityStoreId();
        String userId = identityStore.createUser(objectMapper.readTree("""
                {"IdentityStoreId": "%s", "UserName": "gone-%s", "DisplayName": "Gone"}
                """.formatted(storeId, UUID.randomUUID()))).userId();
        String studioId = studio("SSO", "\"UserRole\": \"EMRStudio_User_Role\",", subnetIds.subList(0, 1));
        emr("CreateStudioSessionMapping", mapping(studioId, "USER", userId, null, POLICY)).then().statusCode(200);

        identityStore.deleteUser(objectMapper.readTree("""
                {"IdentityStoreId": "%s", "UserId": "%s"}
                """.formatted(storeId, userId)));

        emr("GetStudioSessionMapping", mapping(studioId, "USER", userId, null, null))
                .then().statusCode(200)
                .body("SessionMapping.IdentityId", equalTo(userId));
        emr("DeleteStudioSessionMapping", mapping(studioId, "USER", userId, null, null)).then().statusCode(200);
        emr("DeleteStudio", "{\"StudioId\":\"" + studioId + "\"}").then().statusCode(200);
    }

    @Test
    void aStudioIsRefusedForItsNetworkItsAuthModesRolesOrAMalformedRequest() {
        String sso = "\"UserRole\": \"EMRStudio_User_Role\",";
        refused(create("SSO", sso, List.of("subnet-1", "subnet-2", "subnet-3", "subnet-4", "subnet-5", "subnet-6"),
                vpcId, securityGroupId), "InvalidRequestException");
        refused(create("SSO", sso, subnetIds, "vpc-00000000000000000", securityGroupId), "InvalidRequestException");
        refused(create("SSO", sso, List.of("subnet-00000000000000000"), vpcId, securityGroupId),
                "InvalidRequestException");
        refused(create("SSO", sso, subnetIds, vpcId, "sg-00000000000000000"), "InvalidRequestException");
        refused(create("SSO", "", subnetIds, vpcId, securityGroupId), "InvalidRequestException");
        refused(create("IAM", sso, subnetIds, vpcId, securityGroupId), "InvalidRequestException");
        refused(create("SSO", sso + "\"IdpAuthUrl\": \"https://idp.example/login\",", subnetIds, vpcId,
                securityGroupId), "InvalidRequestException");
        refused(create("OIDC", "", subnetIds, vpcId, securityGroupId), "ValidationException");
        refused("{\"Name\": \"n\"}", "ValidationException");
        refused(create("SSO", sso, subnetIds, vpcId, securityGroupId).replace(json(subnetIds),
                "\"" + subnetIds.get(0) + "\""), "SerializationException");
    }

    @Test
    void anIdentityCenterStudiosUsersAndGroupsAreMappedFoundUpdatedListedAndRemoved() throws Exception {
        String storeId = ssoAdmin.listInstances(ACCOUNT).get(0).identityStoreId();
        String userName = "ana-" + UUID.randomUUID();
        String userId = identityStore.createUser(objectMapper.readTree("""
                {"IdentityStoreId": "%s", "UserName": "%s", "DisplayName": "Ana",
                 "Name": {"GivenName": "Ana", "FamilyName": "Silva"}}
                """.formatted(storeId, userName))).userId();
        String groupName = "analysts-" + UUID.randomUUID();
        String groupId = identityStore.createGroup(objectMapper.readTree("""
                {"IdentityStoreId": "%s", "DisplayName": "%s"}
                """.formatted(storeId, groupName))).groupId();
        String studioId = studio("SSO", "\"UserRole\": \"EMRStudio_User_Role\",", subnetIds.subList(0, 1));

        emr("CreateStudioSessionMapping", mapping(studioId, "USER", null, userName, POLICY))
                .then().statusCode(200);
        emr("CreateStudioSessionMapping", mapping(studioId, "GROUP", groupId, null, POLICY))
                .then().statusCode(200);
        emr("GetStudioSessionMapping", mapping(studioId, "USER", userId, null, null))
                .then().statusCode(200)
                .body("SessionMapping.StudioId", equalTo(studioId))
                .body("SessionMapping.IdentityId", equalTo(userId))
                .body("SessionMapping.IdentityName", equalTo(userName))
                .body("SessionMapping.IdentityType", equalTo("USER"))
                .body("SessionMapping.SessionPolicyArn", equalTo(POLICY))
                .body("SessionMapping.LastModifiedTime", notNullValue());
        emr("CreateStudioSessionMapping", mapping(studioId, "USER", userId, null, POLICY))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        String advanced = "arn:aws:iam::000000000000:policy/EMRStudio_Advanced_User_Policy";
        emr("UpdateStudioSessionMapping", mapping(studioId, "GROUP", null, groupName, advanced))
                .then().statusCode(200);
        emr("ListStudioSessionMappings", "{\"StudioId\":\"" + studioId + "\",\"IdentityType\":\"GROUP\"}")
                .then().statusCode(200)
                .body("SessionMappings.size()", equalTo(1))
                .body("SessionMappings[0].IdentityId", equalTo(groupId))
                .body("SessionMappings[0].IdentityName", equalTo(groupName))
                .body("SessionMappings[0].SessionPolicyArn", equalTo(advanced));
        emr("ListStudioSessionMappings", "{}")
                .then().statusCode(200)
                .body("SessionMappings.IdentityId", hasItem(userId));

        // "Either IdentityName or IdentityId must be specified, but not both."
        emr("GetStudioSessionMapping", mapping(studioId, "USER", userId, userName, null))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("CreateStudioSessionMapping", mapping(studioId, "USER", null, "nobody-" + UUID.randomUUID(), POLICY))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        emr("DeleteStudioSessionMapping", mapping(studioId, "USER", userId, null, null)).then().statusCode(200);
        emr("GetStudioSessionMapping", mapping(studioId, "USER", userId, null, null))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));

        String iamStudio = studio("IAM", "", subnetIds.subList(0, 1));
        emr("CreateStudioSessionMapping", mapping(iamStudio, "USER", userId, null, POLICY))
                .then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        emr("DeleteStudio", "{\"StudioId\":\"" + studioId + "\"}").then().statusCode(200);
        emr("DeleteStudio", "{\"StudioId\":\"" + iamStudio + "\"}").then().statusCode(200);
    }

    private String studio(String authMode, String extra, List<String> subnets) {
        return emr("CreateStudio", create(authMode, extra, subnets, vpcId, securityGroupId))
                .then().statusCode(200)
                .body("StudioId", matchesPattern("es-[A-Z0-9]{25}"))
                .extract().path("StudioId");
    }

    private static String create(String authMode, String extra, List<String> subnets, String vpc, String group) {
        return """
                {"Name": "studio-%s", "AuthMode": "%s", %s "VpcId": "%s", "SubnetIds": %s,
                 "ServiceRole": "EMRStudio_Service_Role", "WorkspaceSecurityGroupId": "%s",
                 "EngineSecurityGroupId": "%s", "DefaultS3Location": "s3://bucket/studio/",
                 "Tags": [{"Key": "team", "Value": "data"}]}
                """.formatted(UUID.randomUUID(), authMode, extra, vpc, json(subnets), group, group);
    }

    private static void refused(String body, String type) {
        emr("CreateStudio", body).then().statusCode(400).body("__type", equalTo(type));
    }

    private static String mapping(String studioId, String type, String id, String name, String policy) {
        StringBuilder body = new StringBuilder("{\"StudioId\":\"" + studioId + "\",\"IdentityType\":\"" + type + "\"");
        if (id != null) {
            body.append(",\"IdentityId\":\"").append(id).append('"');
        }
        if (name != null) {
            body.append(",\"IdentityName\":\"").append(name).append('"');
        }
        if (policy != null) {
            body.append(",\"SessionPolicyArn\":\"").append(policy).append('"');
        }
        return body.append('}').toString();
    }

    private static String json(List<String> values) {
        return "[" + String.join(",", values.stream().map(value -> "\"" + value + "\"").toList()) + "]";
    }
}

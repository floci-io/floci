package io.github.hectorvent.floci.services.ssoportal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.identitystore.IdentityStoreService;
import io.github.hectorvent.floci.services.ssoadmin.SsoAdminService;
import io.github.hectorvent.floci.services.ssooidc.SsoOidcService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;

@QuarkusTest
class SsoPortalIntegrationTest {

    @Inject
    ObjectMapper mapper;

    @Inject
    IdentityStoreService identityStoreService;

    @Inject
    SsoAdminService ssoAdminService;

    @Inject
    SsoOidcService oidcService;

    @Test
    void listAccountsIncludesDirectAndGroupAssignmentsForTokenPrincipal() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String storeId = ssoAdminService.getIdentityStoreId();
        var userRequest = mapper.createObjectNode();
        userRequest.put("IdentityStoreId", storeId);
        userRequest.put("UserName", "portal-" + suffix + "@example.com");
        String userId = identityStoreService.createUser(userRequest).userId();

        var groupRequest = mapper.createObjectNode();
        groupRequest.put("IdentityStoreId", storeId);
        groupRequest.put("DisplayName", "PortalGroup" + suffix);
        String groupId = identityStoreService.createGroup(groupRequest).groupId();
        var membershipRequest = mapper.createObjectNode();
        membershipRequest.put("IdentityStoreId", storeId);
        membershipRequest.put("GroupId", groupId);
        membershipRequest.putObject("MemberId").put("UserId", userId);
        identityStoreService.createMembership(membershipRequest);

        String instanceArn = ssoAdminService.getInstanceArn();
        String directPermissionSet = createPermissionSet(instanceArn, "PortalDirect" + suffix);
        String groupPermissionSet = createPermissionSet(instanceArn, "PortalGroupPs" + suffix);
        createAssignment(instanceArn, "111111111111", directPermissionSet, userId, "USER");
        createAssignment(instanceArn, "222222222222", groupPermissionSet, groupId, "GROUP");

        String accessToken = accessTokenFor(userId, suffix);

        given()
                .header("x-amz-sso_bearer_token", accessToken)
            .when().get("/assignment/accounts")
            .then().statusCode(200)
                .body("accountList.accountId", hasItems("111111111111", "222222222222"));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
                .queryParam("account_id", "111111111111")
            .when().get("/assignment/roles")
            .then().statusCode(200)
                .body("roleList[0].accountId", org.hamcrest.Matchers.equalTo("111111111111"))
                .body("roleList[0].roleName", org.hamcrest.Matchers.equalTo("PortalDirect" + suffix));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
            .when().get("/assignment/roles")
            .then().statusCode(400)
                .body("__type", containsString("InvalidRequestException"));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
                .queryParam("account_id", "111111111111")
                .queryParam("role_name", "PortalDirect" + suffix)
            .when().get("/federation/credentials")
            .then().statusCode(200)
                .body("roleCredentials.accessKeyId", org.hamcrest.Matchers.startsWith("ASIA"))
                .body("roleCredentials.secretAccessKey", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString()))
                .body("roleCredentials.sessionToken", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString()))
                .body("roleCredentials.expiration", org.hamcrest.Matchers.greaterThan(System.currentTimeMillis()));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
                .queryParam("account_id", "111111111111")
                .queryParam("role_name", "NotAssigned")
            .when().get("/federation/credentials")
            .then().statusCode(404)
                .body("__type", containsString("ResourceNotFoundException"));

        given()
            .when().get("/assignment/accounts")
            .then().statusCode(401)
                .body("__type", containsString("UnauthorizedException"));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
                .queryParam("max_result", "0")
            .when().get("/assignment/accounts")
            .then().statusCode(400)
                .body("__type", containsString("InvalidRequestException"));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
                .queryParam("max_result", "not-a-number")
            .when().get("/assignment/accounts")
            .then().statusCode(400)
                .body("__type", containsString("InvalidRequestException"));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
            .when().post("/logout")
            .then().statusCode(200)
                .body(org.hamcrest.Matchers.emptyOrNullString());

        given()
                .header("x-amz-sso_bearer_token", accessToken)
            .when().get("/assignment/accounts")
            .then().statusCode(401)
                .body("__type", containsString("UnauthorizedException"));

        given()
                .header("x-amz-sso_bearer_token", accessToken)
            .when().post("/logout")
            .then().statusCode(401)
                .body("__type", containsString("UnauthorizedException"));
    }

    private String createPermissionSet(String instanceArn, String name) {
        var request = mapper.createObjectNode();
        request.put("InstanceArn", instanceArn);
        request.put("Name", name);
        return ssoAdminService.createPermissionSet(request).arn();
    }

    private void createAssignment(String instanceArn, String accountId, String permissionSetArn,
                                  String principalId, String principalType) {
        var request = mapper.createObjectNode();
        request.put("InstanceArn", instanceArn);
        request.put("TargetId", accountId);
        request.put("TargetType", "AWS_ACCOUNT");
        request.put("PermissionSetArn", permissionSetArn);
        request.put("PrincipalId", principalId);
        request.put("PrincipalType", principalType);
        ssoAdminService.createAssignment(request);
    }

    private String accessTokenFor(String principalId, String suffix) {
        var registration = mapper.createObjectNode();
        registration.put("clientName", "Portal Integration " + suffix);
        registration.put("clientType", "public");
        registration.putArray("grantTypes").add("urn:ietf:params:oauth:grant-type:device_code");
        registration.putArray("scopes").add("sso:account:access");
        var client = oidcService.registerClient(registration);

        var start = mapper.createObjectNode();
        start.put("clientId", client.clientId());
        start.put("clientSecret", client.clientSecret());
        start.put("startUrl", "https://example.awsapps.com/start");
        var authorization = oidcService.startDeviceAuthorization(start);
        oidcService.authorizeDevice(authorization.userCode(), principalId);

        var token = mapper.createObjectNode();
        token.put("clientId", client.clientId());
        token.put("clientSecret", client.clientSecret());
        token.put("grantType", "urn:ietf:params:oauth:grant-type:device_code");
        token.put("deviceCode", authorization.deviceCode());
        return oidcService.createToken(token).accessToken();
    }
}

package io.github.hectorvent.floci.services.securityhub;

import io.github.hectorvent.floci.services.organizations.OrganizationsService;
import jakarta.inject.Inject;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SecurityHubOrganizationIntegrationTest {

    @Inject
    OrganizationsService organizationsService;

    @Test
    void organizationAndConfigurationPolicyLifecycle() {
        String management = "920000000001";
        String administrator = "920000000002";
        String member = "920000000003";
        createOrganization(management, administrator, member);

        given().header("Authorization", auth(management)).get("/organization/admin").then().statusCode(200)
                .body("AdminAccounts", hasSize(0));
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"AdminAccountId\":\"" + administrator + "\"}").post("/organization/admin/enable")
                .then().statusCode(200)
                .body("AdminAccountId", equalTo(administrator))
                .body("Feature", equalTo("SecurityHub"));
        given().header("Authorization", auth(administrator)).get("/accounts").then().statusCode(200)
                .body("HubArn", notNullValue());
        String aggregatorArn = given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"RegionLinkingMode\":\"ALL_REGIONS\"}").post("/findingAggregator/create")
                .then().statusCode(200).extract().path("FindingAggregatorArn");
        given().header("Authorization", auth(administrator)).get("/findingAggregator/get/" + aggregatorArn)
                .then().statusCode(200).body("RegionLinkingMode", equalTo("ALL_REGIONS"));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"OrganizationConfiguration\":{\"ConfigurationType\":\"CENTRAL\"},"
                        + "\"AutoEnable\":false,\"AutoEnableStandards\":\"NONE\"}")
                .post("/organization/configuration").then().statusCode(200);
        given().header("Authorization", auth(administrator)).get("/organization/configuration").then().statusCode(200)
                .body("OrganizationConfiguration.ConfigurationType", equalTo("CENTRAL"));
        String policyId = given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Name\":\"security-policy\",\"ConfigurationPolicy\":{"
                        + "\"SecurityHub\":{\"ServiceEnabled\":true}}}")
                .post("/configurationPolicy/create").then().statusCode(200).extract().path("Id");
        given().header("Authorization", auth(administrator)).get("/configurationPolicy/get/" + policyId)
                .then().statusCode(200).body("Id", equalTo(policyId));

        String association = "{\"ConfigurationPolicyIdentifier\":\"" + policyId
                + "\",\"Target\":{\"AccountId\":\"" + member + "\"}}";
        given().contentType("application/json").header("Authorization", auth(administrator)).body(association)
                .post("/configurationPolicyAssociation/associate").then().statusCode(200);
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Target\":{\"AccountId\":\"" + member + "\"}}")
                .post("/configurationPolicyAssociation/get").then().statusCode(200)
                .body("AssociationStatus", equalTo("PENDING"));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Target\":{\"AccountId\":\"" + member + "\"}}")
                .post("/configurationPolicyAssociation/get").then().statusCode(200)
                .body("AssociationStatus", equalTo("SUCCESS"));
        given().header("Authorization", auth(member)).get("/accounts").then().statusCode(200);
    }

    @Test
    void organizationBoundariesAreEnforced() {
        String management = "920000000011";
        String administrator = "920000000012";
        String outsider = "930000000013";
        createOrganization(management, administrator);

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"AdminAccountId\":\"" + administrator + "\"}").post("/organization/admin/enable")
                .then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
        given().header("Authorization", auth(administrator)).get("/organization/admin")
                .then().statusCode(401).body("__type", equalTo("InvalidAccessException"));
        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"AdminAccountId\":\"" + outsider + "\"}").post("/organization/admin/enable")
                .then().statusCode(400).body("__type", equalTo("InvalidInputException"));

        given().contentType("application/json").header("Authorization", auth(management))
                .body("{\"AdminAccountId\":\"" + administrator + "\"}").post("/organization/admin/enable")
                .then().statusCode(200);
        String policyId = given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Name\":\"bounded-policy\",\"ConfigurationPolicy\":{"
                        + "\"SecurityHub\":{\"ServiceEnabled\":true}}}")
                .post("/configurationPolicy/create").then().statusCode(200).extract().path("Id");
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"ConfigurationPolicyIdentifier\":\"" + policyId
                        + "\",\"Target\":{\"AccountId\":\"" + outsider + "\"}}")
                .post("/configurationPolicyAssociation/associate").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void malformedPoliciesAndInvalidUpdatesAreRejected() {
        String management = "920000000021";
        String administrator = "920000000022";
        createOrganization(management, administrator);
        designateAdministrator(management, administrator);

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Name\":\"bad-policy\",\"ConfigurationPolicy\":{"
                        + "\"SecurityHub\":{\"ServiceEnabled\":\"yes\"}}}")
                .post("/configurationPolicy/create").then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        String policyId = given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Name\":\"valid-policy\",\"ConfigurationPolicy\":{"
                        + "\"SecurityHub\":{\"ServiceEnabled\":true}}}")
                .post("/configurationPolicy/create").then().statusCode(200).extract().path("Id");
        String longName = "a".repeat(129);
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"Name\":\"" + longName + "\"}")
                .patch("/configurationPolicy/" + policyId).then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void findingAggregatorValidatesRegionElementsButAllowsEmptyExclusionList() {
        String management = "920000000031";
        String administrator = "920000000032";
        createOrganization(management, administrator);
        designateAdministrator(management, administrator);

        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"RegionLinkingMode\":\"ALL_REGIONS_EXCEPT_SPECIFIED\",\"Regions\":[123]}")
                .post("/findingAggregator/create").then().statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        given().contentType("application/json").header("Authorization", auth(administrator))
                .body("{\"RegionLinkingMode\":\"ALL_REGIONS_EXCEPT_SPECIFIED\"}")
                .post("/findingAggregator/create").then().statusCode(200)
                .body("RegionLinkingMode", equalTo("ALL_REGIONS_EXCEPT_SPECIFIED"));
    }

    @Test
    void invalidAdminAccountIdReturnsValidationException() {
        given().contentType("application/json").header("Authorization", auth("920000000041"))
                .body("{\"AdminAccountId\":\"bad\"}").post("/organization/admin/enable")
                .then().statusCode(400).body("__type", equalTo("InvalidInputException"));
    }

    private void createOrganization(String managementAccountId, String... members) {
        organizationsService.createOrganization(managementAccountId, "ALL");
        for (String member : members) {
            var handshake = organizationsService.inviteAccountToOrganization(
                    managementAccountId, member, "ACCOUNT", null);
            organizationsService.acceptHandshake(member, handshake.getId());
        }
    }

    private void designateAdministrator(String managementAccountId, String administratorAccountId) {
        given().contentType("application/json").header("Authorization", auth(managementAccountId))
                .body("{\"AdminAccountId\":\"" + administratorAccountId + "\"}")
                .post("/organization/admin/enable").then().statusCode(200);
    }

    private static String auth(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260101/us-east-1/securityhub/aws4_request";
    }
}

package io.floci.aws.testing;

import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;

import java.util.LinkedHashSet;
import java.util.Set;

import static io.restassured.RestAssured.given;

public final class OrganizationsFixture {
    private final Set<String> owners = new LinkedHashSet<>();

    public RequestSpecification request(String owner, String action, String body) {
        if ("CreateOrganization".equals(action)) {
            owners.add(owner);
        }
        return OrganizationsRequests.request(owner, action, body);
    }

    public String createWithTrustedAccess(String owner) {
        request(owner, "CreateOrganization", "{\"FeatureSet\":\"ALL\"}")
                .post("/").then().statusCode(200);
        given().header("Authorization", EnforcementFixtures.auth(owner, "cloudformation"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ActivateOrganizationsAccess")
                .post("/").then().statusCode(200);
        return request(owner, "ListRoots", "{}").post("/").then().statusCode(200)
                .extract().jsonPath().getString("Roots[0].Id");
    }

    public void cleanup() {
        for (String owner : owners) {
            if (request(owner, "DescribeOrganization", "{}").post("/").statusCode() != 200) {
                continue;
            }
            JsonPath accounts = request(owner, "ListAccounts", "{}").post("/").jsonPath();
            for (String account : accounts.getList("Accounts.Id", String.class)) {
                if (!owner.equals(account)) {
                    request(owner, "RemoveAccountFromOrganization", "{\"AccountId\":\"" + account + "\"}")
                            .post("/").then().statusCode(200);
                }
            }
            JsonPath roots = request(owner, "ListRoots", "{}").post("/").jsonPath();
            for (String root : roots.getList("Roots.Id", String.class)) {
                deleteUnitsUnder(owner, root);
            }
            request(owner, "DeleteOrganization", "{}").post("/").then().statusCode(200);
        }
        owners.clear();
    }

    private void deleteUnitsUnder(String owner, String parent) {
        JsonPath units = request(owner, "ListOrganizationalUnitsForParent", "{\"ParentId\":\"" + parent + "\"}")
                .post("/").jsonPath();
        for (String unit : units.getList("OrganizationalUnits.Id", String.class)) {
            deleteUnitsUnder(owner, unit);
            request(owner, "DeleteOrganizationalUnit", "{\"OrganizationalUnitId\":\"" + unit + "\"}")
                    .post("/").then().statusCode(200);
        }
    }
}

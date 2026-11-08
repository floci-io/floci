package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/**
 * Organizations calls over its JSON 1.1 wire, signed as the calling account the way
 * {@link EnforcementFixtures#auth} signs every other fixture here.
 */
public final class OrganizationsRequests {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";

    private OrganizationsRequests() {
    }

    /** One call, named by its action, carrying {@code body} as its payload. */
    public static RequestSpecification request(String accountId, String action, String body) {
        return given()
                .header("Authorization", EnforcementFixtures.auth(accountId, "organizations"))
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .contentType(CONTENT_TYPE)
                .body(body);
    }
}

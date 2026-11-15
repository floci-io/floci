package io.floci.aws.services.glue.iceberg;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Under IAM enforcement, no rule maps the Glue Iceberg REST endpoint's routes to the Glue actions AWS checks for them
 * yet, so the endpoint refuses an IAM principal, even one allowed every Glue action, rather than let it through
 * unchecked; the account's root credentials still reach it, and so does a token Floci minted for its query engine,
 * as the account and Region it was minted for.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class GlueIcebergIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    GlueIcebergTokens tokens;

    @Test
    void anIamPrincipalIsRefusedAndRootCredentialsAreAnswered() throws Exception {
        String allowEverything = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"glue:*\",\"Resource\":\"*\"}]}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "glue-iceberg-", "glue", allowEverything)) {
            get(user.signer("glue"), "/iceberg/v1/config").then().statusCode(403)
                    .body("error.type", equalTo("ForbiddenException"));
            get(user.signer("glue"), "/iceberg/v1/catalogs/:/namespaces").then().statusCode(403);
        }
        get(AwsRequestSigner.signedAs("test", "test", "glue"), "/iceberg/v1/config").then().statusCode(200)
                .body("overrides.prefix", equalTo("catalogs/:"));
    }

    @Test
    void aMintedTokenActsAsItsAccountUntilRevokedAndAnUnknownOneReachesNothing() {
        String token = tokens.mint("111122223333", "eu-west-1", Duration.ofMinutes(5));
        try {
            bearer(token, "/iceberg/v1/config?warehouse=111122223333").then().statusCode(200)
                    .body("overrides.prefix", equalTo("catalogs/111122223333"));
            bearer(token, "/iceberg/v1/config?warehouse=000000000000").then().statusCode(400)
                    .body("error.type", equalTo("BadRequestException"));
        } finally {
            tokens.revoke(token);
        }
        bearer(token, "/iceberg/v1/config?warehouse=111122223333").then().statusCode(not(equalTo(200)));
        bearer("not-a-minted-token", "/iceberg/v1/config").then().statusCode(not(equalTo(200)));
    }

    private Response bearer(String token, String path) {
        return given().urlEncodingEnabled(false).header("Authorization", "Bearer " + token)
                .get(endpoint.resolve(path));
    }

    private Response get(AwsRequestSigner signer, String path) throws Exception {
        URI uri = endpoint.resolve(path);
        return given().urlEncodingEnabled(false).headers(signer.headersFor("GET", uri, Map.of(), new byte[0]))
                .request("GET", uri);
    }
}

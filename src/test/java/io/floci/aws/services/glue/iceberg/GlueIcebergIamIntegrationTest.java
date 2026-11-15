package io.floci.aws.services.glue.iceberg;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Under IAM enforcement, no rule maps the Glue Iceberg REST endpoint's routes to the Glue actions AWS checks for them
 * yet, so the endpoint refuses an IAM principal, even one allowed every Glue action, rather than let it through
 * unchecked; the account's root credentials still reach it.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class GlueIcebergIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

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

    private Response get(AwsRequestSigner signer, String path) throws Exception {
        URI uri = endpoint.resolve(path);
        return given().urlEncodingEnabled(false).headers(signer.headersFor("GET", uri, Map.of(), new byte[0]))
                .request("GET", uri);
    }
}

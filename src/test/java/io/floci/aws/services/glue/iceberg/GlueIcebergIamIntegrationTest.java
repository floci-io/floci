package io.floci.aws.services.glue.iceberg;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Under IAM enforcement, each route of the Glue Iceberg REST endpoint is checked as the Glue operation AWS's Glue
 * Developer Guide documents it as, on the account's Data Catalog, the one resource the request-resource table reads
 * for a request that carries no Glue member, as an Iceberg request does not; a token Floci minted for its query engine
 * is answered as the account and Region it was minted for.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class GlueIcebergIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    GlueIcebergTokens tokens;

    @Test
    void eachRouteIsCheckedAsTheGlueOperationAwsDocumentsItAs() throws Exception {
        String catalog = "arn:aws:glue:us-east-1:000000000000:catalog";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "glue-iceberg-", "glue",
                policy(allow("glue:GetCatalog", catalog) + "," + allow("glue:GetDatabase", catalog)))) {
            AwsRequestSigner signer = user.signer("glue");
            send(signer, "GET", "/iceberg/v1/config", null).then().statusCode(200)
                    .body("overrides.prefix", equalTo("catalogs/:"));
            send(signer, "GET", "/iceberg/v1/catalogs/:/namespaces", null).then().statusCode(200);
            refused(send(signer, "POST", "/iceberg/v1/catalogs/:/namespaces", "{\"namespace\":[\"refused\"]}"),
                    "glue:CreateDatabase");
            // An operation's alternative route of another method is checked as that operation too.
            refused(send(signer, "HEAD", "/iceberg/v1/catalogs/:/namespaces/refused/tables/t", null), null);
            refused(send(signer, "GET", "/iceberg/v1/catalogs/:/namespaces/refused/tables/t", null), "glue:GetTable");
        }
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "glue-iceberg-", "glue",
                policy(allow("glue:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"glue:GetDatabase\","
                        + "\"Resource\":\"" + catalog + "\"}"))) {
            send(user.signer("glue"), "GET", "/iceberg/v1/config", null).then().statusCode(200);
            refused(send(user.signer("glue"), "GET", "/iceberg/v1/catalogs/:/namespaces", null), "glue:GetDatabase");
        }
        send(AwsRequestSigner.signedAs("test", "test", "glue"), "GET", "/iceberg/v1/config", null).then()
                .statusCode(200).body("overrides.prefix", equalTo("catalogs/:"));
    }

    /**
     * A Glue member an Iceberg request's body carries is not read: the request is Apache Iceberg's, so a caller added
     * it. It gives no condition key a value, as a tag the namespace never gets would, and names no resource, as a
     * database other than the one the request creates would.
     */
    @Test
    void aGlueMemberAnIcebergBodyCarriesIsNotRead() throws Exception {
        String catalog = "arn:aws:glue:us-east-1:000000000000:catalog";
        String taggedCreate = "{\"Effect\":\"Allow\",\"Action\":[\"glue:CreateDatabase\",\"glue:TagResource\"],"
                + "\"Resource\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"data\"}}}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "glue-iceberg-", "glue",
                policy(allow("glue:GetCatalog", catalog) + "," + taggedCreate))) {
            refused(send(user.signer("glue"), "POST", "/iceberg/v1/catalogs/:/namespaces",
                    "{\"namespace\":[\"tagged\"],\"Tags\":{\"team\":\"data\"}}"), "glue:CreateDatabase");
        }
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "glue-iceberg-", "glue",
                policy(allow("glue:*", "*") + ",{\"Effect\":\"Deny\",\"Action\":\"glue:CreateDatabase\","
                        + "\"Resource\":\"arn:aws:glue:us-east-1:000000000000:database/decoy\"}"))) {
            send(user.signer("glue"), "POST", "/iceberg/v1/catalogs/:/namespaces",
                    "{\"namespace\":[\"named\"],\"DatabaseInput\":{\"Name\":\"decoy\"}}").then().statusCode(200);
        }
        send(AwsRequestSigner.signedAs("test", "test", "glue"), "DELETE", "/iceberg/v1/catalogs/:/namespaces/named",
                null).then().statusCode(204);
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

    private Response send(AwsRequestSigner signer, String method, String path, String body) throws Exception {
        URI uri = endpoint.resolve(path);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        RequestSpecification request = given().urlEncodingEnabled(false)
                .headers(signer.headersFor(method, uri, Map.of(), bytes));
        if (body != null) {
            request = request.contentType("application/json").body(bytes);
        }
        return request.request(method, uri);
    }

    /** A refusal of the action, which a HEAD answers with its status alone. */
    private static void refused(Response response, String action) {
        response.then().statusCode(403);
        if (action != null) {
            response.then().body(containsString("not authorized to perform: " + action));
        }
    }

    private static String policy(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}

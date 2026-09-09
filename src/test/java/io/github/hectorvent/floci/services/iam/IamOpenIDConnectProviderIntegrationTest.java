package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsQueryController;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for IAM OIDC identity providers via the Query Protocol, covering the full
 * HTTP stack through {@link AwsQueryController} → {@link IamQueryHandler}.
 *
 * <p>Ordered because the cases drive one provider through its lifecycle.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamOpenIDConnectProviderIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static final String OIDC_URL =
            "https://oidc.eks.eu-central-1.amazonaws.com/id/INTEGRATION0EXAMPLE";
    private static final String OIDC_HOST =
            "oidc.eks.eu-central-1.amazonaws.com/id/INTEGRATION0EXAMPLE";
    private static final String THUMBPRINT = "9e99a48a9960b14926bb7f3b02e22da2b0ab7280";

    private static String providerArn;

    @Test
    @Order(1)
    void createOpenIDConnectProvider() {
        providerArn = given()
            .formParam("Action", "CreateOpenIDConnectProvider")
            .formParam("Url", OIDC_URL)
            .formParam("ClientIDList.member.1", "sts.amazonaws.com")
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .formParam("Tags.member.1.Key", "env")
            .formParam("Tags.member.1.Value", "test")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .contentType("application/xml")
            .body("CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult"
                    + ".OpenIDConnectProviderArn", containsString(":oidc-provider/" + OIDC_HOST))
            .extract().path("CreateOpenIDConnectProviderResponse.CreateOpenIDConnectProviderResult"
                    + ".OpenIDConnectProviderArn");
    }

    @Test
    @Order(2)
    void getReturnsTheUrlWithoutItsScheme() {
        given()
            .formParam("Action", "GetOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.Url", equalTo(OIDC_HOST))
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ClientIDList.member",
                    equalTo("sts.amazonaws.com"))
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ThumbprintList.member",
                    equalTo(THUMBPRINT))
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.CreateDate",
                    not(emptyOrNullString()));
    }

    @Test
    @Order(3)
    void listReturnsTheCreatedProvider() {
        given()
            .formParam("Action", "ListOpenIDConnectProviders")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListOpenIDConnectProvidersResponse.ListOpenIDConnectProvidersResult"
                    + ".OpenIDConnectProviderList.member.Arn", equalTo(providerArn));
    }

    @Test
    @Order(4)
    void creatingTheSameUrlTwiceIsRejected() {
        given()
            .formParam("Action", "CreateOpenIDConnectProvider")
            .formParam("Url", OIDC_URL)
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(409)
            .body("ErrorResponse.Error.Code", equalTo("EntityAlreadyExists"));
    }

    @Test
    @Order(5)
    void addAndRemoveClientId() {
        given()
            .formParam("Action", "AddClientIDToOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .formParam("ClientID", "extra.audience")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ClientIDList.member",
                    hasItems("sts.amazonaws.com", "extra.audience"));

        given()
            .formParam("Action", "RemoveClientIDFromOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .formParam("ClientID", "extra.audience")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void updateThumbprint() {
        given()
            .formParam("Action", "UpdateOpenIDConnectProviderThumbprint")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .formParam("ThumbprintList.member.1", "1111111111111111111111111111111111111111")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetOpenIDConnectProviderResponse.GetOpenIDConnectProviderResult.ThumbprintList.member",
                    equalTo("1111111111111111111111111111111111111111"));
    }

    @Test
    @Order(7)
    void listTags() {
        given()
            .formParam("Action", "ListOpenIDConnectProviderTags")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListOpenIDConnectProviderTagsResponse.ListOpenIDConnectProviderTagsResult"
                    + ".Tags.member.Key", equalTo("env"));
    }

    @Test
    @Order(8)
    void getUnknownArnIsRejected() {
        given()
            .formParam("Action", "GetOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn",
                    "arn:aws:iam::000000000000:oidc-provider/nope.example.com/id/X")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }

    @Test
    @Order(9)
    void nonHttpsUrlIsRejected() {
        given()
            .formParam("Action", "CreateOpenIDConnectProvider")
            .formParam("Url", "http://oidc.example.com/id/insecure")
            .formParam("ThumbprintList.member.1", THUMBPRINT)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(10)
    void deleteOpenIDConnectProvider() {
        given()
            .formParam("Action", "DeleteOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetOpenIDConnectProvider")
            .formParam("OpenIDConnectProviderArn", providerArn)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body("ErrorResponse.Error.Code", equalTo("NoSuchEntity"));
    }
}

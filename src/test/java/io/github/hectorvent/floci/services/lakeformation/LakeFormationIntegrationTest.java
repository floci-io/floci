package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.MethodName.class)
class LakeFormationIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20201022/us-east-1/lakeformation/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy";

    @Test
    void unknownAction_returnsUnknownOperationError() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.UnknownAction")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void putAndGetDataLakeSettings() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.PutDataLakeSettings")
            .header("Authorization", AUTH_HEADER)
            .body("{\"DataLakeSettings\":{\"DataLakeAdmins\":[{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:user/admin\"}]}}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.GetDataLakeSettings")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DataLakeSettings.DataLakeAdmins[0].DataLakePrincipalIdentifier", equalTo("arn:aws:iam::111122223333:user/admin"));
    }

    @Test
    void createAndGetLFTag() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.CreateLFTag")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\",\"TagValues\":[\"sales\",\"engineering\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.GetLFTag")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagKey", equalTo("department"))
            .body("TagValues", containsInAnyOrder("sales", "engineering"));
    }

    @Test
    void updateLFTag() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.CreateLFTag")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\",\"TagValues\":[\"sales\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.UpdateLFTag")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\",\"TagValuesToAdd\":[\"marketing\"],\"TagValuesToDelete\":[\"sales\"]}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.GetLFTag")
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagKey", equalTo("department"))
            .body("TagValues", contains("marketing"));
    }

    @Test
    void grantAndListPermissions() {
        String grantBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"SELECT\",\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.GrantPermissions")
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.ListPermissions")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Principal.DataLakePrincipalIdentifier", equalTo("arn:aws:iam::111122223333:role/my-role"))
            .body("PrincipalResourcePermissions[0].Resource.Table.DatabaseName", equalTo("default"))
            .body("PrincipalResourcePermissions[0].Resource.Table.Name", equalTo("my-table"))
            .body("PrincipalResourcePermissions[0].Permissions", containsInAnyOrder("SELECT", "INSERT"));
    }

    @Test
    void revokePermissions() {
        String grantBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"SELECT\",\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.GrantPermissions")
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String revokeBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.RevokePermissions")
            .header("Authorization", AUTH_HEADER)
            .body(revokeBody)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "AWSLakeFormation.ListPermissions")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Permissions", contains("SELECT"));
    }
}

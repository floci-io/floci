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

    private static final String CONTENT_TYPE = "application/json";
    private static final String AUTH_HEADER = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20201022/us-east-1/lakeformation/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy";




    @Test
    void putAndGetDataLakeSettings() {
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"DataLakeSettings\":{\"DataLakeAdmins\":[{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:user/admin\"}]}}")
        .when()
            .post("/PutDataLakeSettings")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/GetDataLakeSettings")
        .then()
            .statusCode(200)
            .body("DataLakeSettings.DataLakeAdmins[0].DataLakePrincipalIdentifier", equalTo("arn:aws:iam::111122223333:user/admin"));
    }

    @Test
    void createAndGetLFTag() {
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\",\"TagValues\":[\"sales\",\"engineering\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department\"}")
        .when()
            .post("/GetLFTag")
        .then()
            .statusCode(200)
            .body("TagKey", equalTo("department"))
            .body("TagValues", containsInAnyOrder("sales", "engineering"));
    }

    @Test
    void updateLFTag() {
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department2\",\"TagValues\":[\"sales\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department2\",\"TagValuesToAdd\":[\"marketing\"],\"TagValuesToDelete\":[\"sales\"]}")
        .when()
            .post("/UpdateLFTag")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"department2\"}")
        .when()
            .post("/GetLFTag")
        .then()
            .statusCode(200)
            .body("TagKey", equalTo("department2"))
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
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/GrantPermissions")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/ListPermissions")
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
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/GrantPermissions")
        .then()
            .statusCode(200);

        String revokeBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/my-role\"},"
            + "\"Resource\":{\"Table\":{\"DatabaseName\":\"default\",\"Name\":\"my-table\"}},"
            + "\"Permissions\":[\"INSERT\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(revokeBody)
        .when()
            .post("/RevokePermissions")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/ListPermissions")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Permissions", contains("SELECT"));
    }

    @Test
    void grantAndListPermissionsTBAC() {
        String grantBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/tbac\"},"
            + "\"Resource\":{\"LFTag\":{\"CatalogId\":\"111122223333\",\"TagKey\":\"env\",\"TagValues\":[\"dev\"]}},"
            + "\"Permissions\":[\"ASSOCIATE\"]"
            + "}";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(grantBody)
        .when()
            .post("/GrantPermissions")
        .then()
            .statusCode(200);

        String listBody = "{"
            + "\"Principal\":{\"DataLakePrincipalIdentifier\":\"arn:aws:iam::111122223333:role/tbac\"}"
            + "}";
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body(listBody)
        .when()
            .post("/ListPermissions")
        .then()
            .statusCode(200)
            .body("PrincipalResourcePermissions[0].Resource.LFTag.TagKey", equalTo("env"))
            .body("PrincipalResourcePermissions[0].Resource.LFTag.TagValues", contains("dev"))
            .body("PrincipalResourcePermissions[0].Permissions", contains("ASSOCIATE"));
    }

    @Test
    void resourceLifecycle() {
        String arn = "arn:aws:s3:::my-lake-bucket";
        String arn2 = "arn:aws:s3:::my-other-lake-bucket";
        
        // Register first
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"arn:aws:iam::111122223333:role/s3-role\",\"UseServiceLinkedRole\":true}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        // Register second
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn2 + "\",\"RoleArn\":\"arn:aws:iam::111122223333:role/s3-role\",\"UseServiceLinkedRole\":true}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        // Describe first
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(200)
            .body("ResourceInfo.ResourceArn", equalTo(arn));

        // List with filter (should only return the first resource)
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"FilterConditionList\":[{\"Field\":\"RESOURCE_ARN\",\"ComparisonOperator\":\"EQ\",\"StringValueList\":[\"" + arn + "\"]}]}")
        .when()
            .post("/ListResources")
        .then()
            .statusCode(200)
            .body("ResourceInfoList", hasSize(1))
            .body("ResourceInfoList[0].ResourceArn", equalTo(arn));

        // Deregister
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DeregisterResource")
        .then()
            .statusCode(200);
            
        // Describe should fail now
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void updateResourcePreservesOmittedValuesAndIsRegionScoped() {
        String arn = "arn:aws:s3:::update-resource-bucket";
        String initialRole = "arn:aws:iam::111122223333:role/initial";
        String updatedRole = "arn:aws:iam::111122223333:role/updated";
        String secondRegionHeader = "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20201022/us-west-2/lakeformation/aws4_request, SignedHeaders=host;x-amz-date, Signature=dummy";

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + initialRole + "\",\"WithFederation\":true}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", secondRegionHeader)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + initialRole + "\",\"WithFederation\":false}")
        .when()
            .post("/RegisterResource")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\",\"RoleArn\":\"" + updatedRole + "\"}")
        .when()
            .post("/UpdateResource")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(200)
            .body("ResourceInfo.RoleArn", equalTo(updatedRole))
            .body("ResourceInfo.WithFederation", equalTo(true));

        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", secondRegionHeader)
            .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
            .post("/DescribeResource")
        .then()
            .statusCode(200)
            .body("ResourceInfo.RoleArn", equalTo(initialRole))
            .body("ResourceInfo.WithFederation", equalTo(false));
    }

    @Test
    void listAndDeleteLFTag() {
        // Create tag
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"project\",\"TagValues\":[\"apollo\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        // List
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/ListLFTags")
        .then()
            .statusCode(200)
            .body("LFTags.TagKey", hasItem("project"));

        // Delete
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"project\"}")
        .when()
            .post("/DeleteLFTag")
        .then()
            .statusCode(200);
            
        // Get should fail
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"project\"}")
        .when()
            .post("/GetLFTag")
        .then()
            .statusCode(400)
            .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void addAndRemoveLFTagsFromResource() {
        String arn = "arn:aws:s3:::my-tagged-bucket";
        // Create tag
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"TagKey\":\"classification\",\"TagValues\":[\"confidential\"]}")
        .when()
            .post("/CreateLFTag")
        .then()
            .statusCode(200);

        // Add to resource
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Resource\":{\"DataLocation\":{\"ResourceArn\":\"" + arn + "\"}},\"LFTags\":[{\"TagKey\":\"classification\",\"TagValues\":[\"confidential\"]}]}")
        .when()
            .post("/AddLFTagsToResource")
        .then()
            .statusCode(200);

        // Remove from resource
        given()
            .contentType(CONTENT_TYPE)
            .header("Authorization", AUTH_HEADER)
            .body("{\"Resource\":{\"DataLocation\":{\"ResourceArn\":\"" + arn + "\"}},\"LFTags\":[{\"TagKey\":\"classification\",\"TagValues\":[\"confidential\"]}]}")
        .when()
            .post("/RemoveLFTagsFromResource")
        .then()
            .statusCode(200);
    }
}

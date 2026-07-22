package io.github.hectorvent.floci.services.lakeformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class LakeFormationIntegrationTest {
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSLakeFormation.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void dataLakeSettingsRoundTripThroughJsonHandler() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetDataLakeSettings")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("DataLakeSettings.CreateTableDefaultPermissions", hasSize(0))
                .body("DataLakeSettings.TrustedResourceOwners", hasSize(0));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutDataLakeSettings")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "DataLakeSettings": {
                            "CreateTableDefaultPermissions": [
                              {
                                "Principal": {
                                  "DataLakePrincipalIdentifier": "IAMAllowedPrincipals"
                                },
                                "Permissions": ["SELECT"],
                                "PermissionsWithGrantOption": []
                              }
                            ],
                            "TrustedResourceOwners": ["123456789012"]
                          }
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetDataLakeSettings")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("DataLakeSettings.CreateTableDefaultPermissions[0].Principal.DataLakePrincipalIdentifier", equalTo("IAMAllowedPrincipals"))
                .body("DataLakeSettings.CreateTableDefaultPermissions[0].Permissions[0]", equalTo("SELECT"))
                .body("DataLakeSettings.TrustedResourceOwners[0]", equalTo("123456789012"));
    }

    @Test
    void taggingAndDataCellsFilterRoundTripThroughJsonHandler() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "TagResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "ResourceArn": "arn:aws:lakeformation:us-east-1:123456789012:resource/abc",
                          "Tags": {
                            "environment": "test"
                          }
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "ListTagsForResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "ResourceArn": "arn:aws:lakeformation:us-east-1:123456789012:resource/abc"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags.environment", equalTo("test"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "UntagResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "ResourceArn": "arn:aws:lakeformation:us-east-1:123456789012:resource/abc",
                          "TagKeys": ["environment"]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "PutDataCellsFilter")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "TableData": {
                            "DatabaseName": "db",
                            "Name": "filter-1",
                            "TableCatalogId": "123456789012",
                            "TableName": "table1",
                            "ColumnNames": ["id"],
                            "RowFilter": {
                              "FilterExpression": "id > 10"
                            }
                          }
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetDataCellsFilter")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "DatabaseName": "db",
                          "Name": "filter-1",
                          "TableCatalogId": "123456789012",
                          "TableName": "table1"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("DataCellsFilter.Name", equalTo("filter-1"))
                .body("DataCellsFilter.DatabaseName", equalTo("db"))
                .body("DataCellsFilter.RowFilter.FilterExpression", equalTo("id > 10"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DeleteDataCellsFilter")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "DatabaseName": "db",
                          "Name": "filter-1",
                          "TableCatalogId": "123456789012",
                          "TableName": "table1"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    @Test
    void broaderLakeFormationApisAreAcceptedAndStored() {
        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GrantPermissions")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "CatalogId": "123456789012",
                          "Principal": {"DataLakePrincipalIdentifier": "IAMAllowedPrincipals"},
                          "Resource": {"Database": {"Name": "db"}},
                          "Permissions": ["SELECT"]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "ListPermissions")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("PrincipalResourcePermissions", hasSize(1));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateLakeFormationIdentityCenterConfiguration")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "CatalogId": "123456789012",
                          "ExternalFilteringConfiguration": {"EnableExternalFiltering": true}
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "DescribeLakeFormationIdentityCenterConfiguration")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ExternalFilteringConfiguration.EnableExternalFiltering", equalTo(true));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "CreateLFTag")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "CatalogId": "123456789012",
                          "TagKey": "env",
                          "TagValues": ["dev"]
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "GetLFTag")
                .contentType(CONTENT_TYPE)
                .body("{\"TagKey\":\"env\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("LFTag.TagKey", equalTo("env"));

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "RegisterResource")
                .contentType(CONTENT_TYPE)
                .body("""
                        {
                          "ResourceArn": "arn:aws:s3:::example",
                          "RoleArn": "arn:aws:iam::123456789012:role/service-role/example"
                        }
                        """)
        .when()
                .post("/")
        .then()
                .statusCode(200);

        given()
                .header("X-Amz-Target", TARGET_PREFIX + "ListResources")
                .contentType(CONTENT_TYPE)
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ResourceInfoList", hasSize(1));
    }
}

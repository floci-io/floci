package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConformancePackIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void putConformancePack() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "PutConformancePack")
            .contentType(CONTENT_TYPE)
            .body("""
                {
                    "ConformancePackName": "pack-crud-test",
                    "TemplateS3Uri": "s3://my-bucket/template.yaml"
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConformancePackArn", notNullValue());
    }

    @Test
    @Order(2)
    void describeConformancePacks() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConformancePacks")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConformancePackNames": ["pack-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConformancePackDetails", hasSize(1))
            .body("ConformancePackDetails[0].ConformancePackName", equalTo("pack-crud-test"))
            .body("ConformancePackDetails[0].ConformancePackArn", notNullValue())
            .body("ConformancePackDetails[0].ConformancePackId", notNullValue());
    }

    @Test
    @Order(3)
    void describeConformancePackStatus() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConformancePackStatus")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConformancePackNames": ["pack-crud-test"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ConformancePackStatusDetails", hasSize(1))
            .body("ConformancePackStatusDetails[0].ConformancePackName", equalTo("pack-crud-test"))
            .body("ConformancePackStatusDetails[0].ConformancePackState", equalTo("CREATE_SUCCESSFUL"))
            .body("ConformancePackStatusDetails[0].LastUpdateRequestedTime", notNullValue());
    }

    @Test
    @Order(4)
    void deleteConformancePack() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConformancePack")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConformancePackName": "pack-crud-test"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void deleteNonexistentConformancePack() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DeleteConformancePack")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConformancePackName": "no-such-pack"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConformancePackException"));
    }

    @Test
    @Order(6)
    void describeConformancePacksNonexistent() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "DescribeConformancePacks")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ConformancePackNames": ["no-such-pack"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchConformancePackException"));
    }
}

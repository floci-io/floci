package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3AnalyticsConfigurationIntegrationTest {

    private static final String BUCKET = "analytics-int-test";

    private static String configuration(String id, String filter) {
        return """
                <AnalyticsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Id>%s</Id>
                    %s
                    <StorageClassAnalysis>
                        <DataExport>
                            <OutputSchemaVersion>V_1</OutputSchemaVersion>
                            <Destination>
                                <S3BucketDestination>
                                    <Format>CSV</Format>
                                    <BucketAccountId>123456789012</BucketAccountId>
                                    <Bucket>arn:aws:s3:::analytics-destination</Bucket>
                                    <Prefix>exports/</Prefix>
                                </S3BucketDestination>
                            </Destination>
                        </DataExport>
                    </StorageClassAnalysis>
                </AnalyticsConfiguration>
                """.formatted(id, filter);
    }

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    /**
     * {@code PUT /{bucket}?analytics} must be handled before the unqualified CreateBucket, which
     * would otherwise answer with BucketAlreadyOwnedByYou. Real S3 stores the configuration and
     * returns 204.
     */
    @Test
    @Order(2)
    void putAnalyticsConfigurationIsNotTreatedAsCreateBucket() {
        given()
            .body(configuration("EntireBucket", ""))
        .when()
            .put("/" + BUCKET + "?analytics&id=EntireBucket")
        .then()
            .statusCode(204)
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(3)
    void getAnalyticsConfigurationReturnsWhatWasStored() {
        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=EntireBucket")
        .then()
            .statusCode(200)
            .body(containsString("<AnalyticsConfiguration"))
            .body(containsString("<Id>EntireBucket</Id>"))
            .body(containsString("<OutputSchemaVersion>V_1</OutputSchemaVersion>"))
            .body(containsString("<BucketAccountId>123456789012</BucketAccountId>"))
            .body(containsString("<Bucket>arn:aws:s3:::analytics-destination</Bucket>"));
    }

    @Test
    @Order(4)
    void putFilteredConfigurationKeepsTheFilter() {
        given()
            .body(configuration("Filtered", """
                    <Filter>
                        <And>
                            <Prefix>logs/</Prefix>
                            <Tag><Key>env</Key><Value>prod</Value></Tag>
                            <Tag><Key>team</Key><Value>core</Value></Tag>
                        </And>
                    </Filter>
                    """))
        .when()
            .put("/" + BUCKET + "?analytics&id=Filtered")
        .then()
            .statusCode(204);

        // AWS repeats <Tag> inside <And> with no wrapping element.
        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=Filtered")
        .then()
            .statusCode(200)
            .body(containsString("<And><Prefix>logs/</Prefix>"
                    + "<Tag><Key>env</Key><Value>prod</Value></Tag>"
                    + "<Tag><Key>team</Key><Value>core</Value></Tag></And>"));
    }

    @Test
    @Order(5)
    void listWithoutAnIdReturnsEveryConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?analytics")
        .then()
            .statusCode(200)
            .body(containsString("<ListBucketAnalyticsConfigurationResult"))
            .body(containsString("<Id>EntireBucket</Id>"))
            .body(containsString("<Id>Filtered</Id>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    @Order(6)
    void idInTheBodyMustMatchTheIdInTheQuery() {
        given()
            .body(configuration("Different", ""))
        .when()
            .put("/" + BUCKET + "?analytics&id=Mismatch")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(7)
    void unknownIdIsNotFound() {
        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));

        // AWS does not treat deleting an absent configuration as a no-op.
        given()
        .when()
            .delete("/" + BUCKET + "?analytics&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    /**
     * {@code DELETE /{bucket}?analytics} must not fall through to the unqualified DeleteBucket
     * and remove the whole bucket.
     */
    @Test
    @Order(8)
    void deleteAnalyticsConfigurationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?analytics&id=EntireBucket")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=Filtered")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=EntireBucket")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(9)
    void aRequestWithoutAnIdIsRefusedRatherThanGuessedAt() {
        given()
            .body(configuration("EntireBucket", ""))
        .when()
            .put("/" + BUCKET + "?analytics")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));

        given()
        .when()
            .delete("/" + BUCKET + "?analytics")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(10)
    void unqualifiedDeleteStillRemovesBucket() {
        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + BUCKET + "?analytics")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}

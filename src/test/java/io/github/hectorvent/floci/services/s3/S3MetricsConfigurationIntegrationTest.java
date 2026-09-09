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
class S3MetricsConfigurationIntegrationTest {

    private static final String BUCKET = "metrics-int-test";

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
     * Regression test for the bucket-creating bug where {@code PUT /{bucket}?metrics} was not
     * handled and fell through to the unqualified {@code CreateBucket}, which answered a metrics
     * call with BucketAlreadyOwnedByYou. Real S3 stores the configuration and returns 204.
     */
    @Test
    @Order(2)
    void putMetricsConfigurationIsNotTreatedAsCreateBucket() {
        given()
            .body("""
                    <MetricsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>EntireBucket</Id>
                    </MetricsConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?metrics&id=EntireBucket")
        .then()
            .statusCode(204)
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(3)
    void getMetricsConfigurationReturnsWhatWasStored() {
        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=EntireBucket")
        .then()
            .statusCode(200)
            .body(containsString("<MetricsConfiguration"))
            .body(containsString("<Id>EntireBucket</Id>"));
    }

    @Test
    @Order(4)
    void putFilteredConfigurationKeepsTheFilter() {
        given()
            .body("""
                    <MetricsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>Filtered</Id>
                        <Filter>
                            <And>
                                <Prefix>logs/</Prefix>
                                <Tag><Key>env</Key><Value>prod</Value></Tag>
                                <Tag><Key>team</Key><Value>core</Value></Tag>
                            </And>
                        </Filter>
                    </MetricsConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?metrics&id=Filtered")
        .then()
            .statusCode(204);

        // AWS repeats <Tag> inside <And> with no wrapping element.
        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=Filtered")
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
            .get("/" + BUCKET + "?metrics")
        .then()
            .statusCode(200)
            .body(containsString("<ListMetricsConfigurationsResult"))
            .body(containsString("<Id>EntireBucket</Id>"))
            .body(containsString("<Id>Filtered</Id>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    @Order(6)
    void idInTheBodyMustMatchTheIdInTheQuery() {
        given()
            .body("""
                    <MetricsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>Different</Id>
                    </MetricsConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?metrics&id=Mismatch")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(7)
    void unknownIdIsNotFound() {
        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));

        // AWS does not treat deleting an absent configuration as a no-op.
        given()
        .when()
            .delete("/" + BUCKET + "?metrics&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    /**
     * Regression test for the bucket-destroying bug: {@code DELETE /{bucket}?metrics} fell through
     * to the unqualified {@code DeleteBucket} and silently removed the whole bucket, reporting
     * success.
     */
    @Test
    @Order(8)
    void deleteMetricsConfigurationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?metrics&id=EntireBucket")
        .then()
            .statusCode(204);

        // The bucket, and every other configuration on it, must survive.
        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=Filtered")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=EntireBucket")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(9)
    void aRequestWithoutAnIdIsRefusedRatherThanGuessedAt() {
        given()
            .body("""
                    <MetricsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>EntireBucket</Id>
                    </MetricsConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?metrics")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));

        given()
        .when()
            .delete("/" + BUCKET + "?metrics")
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
            .get("/" + BUCKET + "?metrics")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(11)
    void aRecreatedBucketDoesNotInheritTheOldConfigurations() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=Filtered")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));

        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }
}

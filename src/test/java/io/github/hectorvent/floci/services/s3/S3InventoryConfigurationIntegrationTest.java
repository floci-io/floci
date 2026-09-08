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
class S3InventoryConfigurationIntegrationTest {

    private static final String BUCKET = "inventory-int-test";

    private static String configuration(String id, String extras) {
        return """
                <InventoryConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                    <Id>%s</Id>
                    <IsEnabled>true</IsEnabled>
                    <Destination>
                        <S3BucketDestination>
                            <Format>CSV</Format>
                            <AccountId>123456789012</AccountId>
                            <Bucket>arn:aws:s3:::inventory-destination</Bucket>
                            <Prefix>inventory/</Prefix>
                        </S3BucketDestination>
                    </Destination>
                    <Schedule>
                        <Frequency>Daily</Frequency>
                    </Schedule>
                    <IncludedObjectVersions>All</IncludedObjectVersions>
                    %s
                </InventoryConfiguration>
                """.formatted(id, extras);
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
     * {@code PUT /{bucket}?inventory} must be handled before the unqualified CreateBucket, which
     * would otherwise answer with BucketAlreadyOwnedByYou. Real S3 stores the configuration and
     * returns 204.
     */
    @Test
    @Order(2)
    void putInventoryConfigurationIsNotTreatedAsCreateBucket() {
        given()
            .body(configuration("report1", ""))
        .when()
            .put("/" + BUCKET + "?inventory&id=report1")
        .then()
            .statusCode(204)
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(3)
    void getInventoryConfigurationReturnsWhatWasStored() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=report1")
        .then()
            .statusCode(200)
            .body(containsString("<InventoryConfiguration"))
            .body(containsString("<Id>report1</Id>"))
            .body(containsString("<IsEnabled>true</IsEnabled>"))
            .body(containsString("<Frequency>Daily</Frequency>"))
            .body(containsString("<IncludedObjectVersions>All</IncludedObjectVersions>"))
            .body(containsString("<Bucket>arn:aws:s3:::inventory-destination</Bucket>"));
    }

    @Test
    @Order(4)
    void putConfigurationWithFilterAndOptionalFieldsKeepsThem() {
        given()
            .body(configuration("report2", """
                    <Filter><Prefix>docs/</Prefix></Filter>
                    <OptionalFields>
                        <Field>Size</Field>
                        <Field>LastModifiedDate</Field>
                    </OptionalFields>
                    """))
        .when()
            .put("/" + BUCKET + "?inventory&id=report2")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=report2")
        .then()
            .statusCode(200)
            .body(containsString("<Filter><Prefix>docs/</Prefix></Filter>"))
            .body(containsString("<OptionalFields><Field>Size</Field><Field>LastModifiedDate</Field></OptionalFields>"));
    }

    @Test
    @Order(5)
    void listWithoutAnIdReturnsEveryConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory")
        .then()
            .statusCode(200)
            .body(containsString("<ListInventoryConfigurationsResult"))
            .body(containsString("<Id>report1</Id>"))
            .body(containsString("<Id>report2</Id>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    @Order(6)
    void idInTheBodyMustMatchTheIdInTheQuery() {
        given()
            .body(configuration("Different", ""))
        .when()
            .put("/" + BUCKET + "?inventory&id=Mismatch")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(7)
    void unknownIdIsNotFound() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));

        // AWS does not treat deleting an absent configuration as a no-op.
        given()
        .when()
            .delete("/" + BUCKET + "?inventory&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    /**
     * {@code DELETE /{bucket}?inventory} must not fall through to the unqualified DeleteBucket
     * and remove the whole bucket.
     */
    @Test
    @Order(8)
    void deleteInventoryConfigurationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?inventory&id=report1")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=report2")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=report1")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(9)
    void aRequestWithoutAnIdIsRefusedRatherThanGuessedAt() {
        given()
            .body(configuration("report1", ""))
        .when()
            .put("/" + BUCKET + "?inventory")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));

        given()
        .when()
            .delete("/" + BUCKET + "?inventory")
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
            .get("/" + BUCKET + "?inventory")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}

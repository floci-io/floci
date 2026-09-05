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
class S3IntelligentTieringConfigurationIntegrationTest {

    private static final String BUCKET = "intelligent-tiering-int-test";

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
     * An unhandled {@code PUT /{bucket}?intelligent-tiering} would fall through to the
     * unqualified {@code CreateBucket} and answer BucketAlreadyOwnedByYou. Real S3 stores the
     * configuration and returns 204.
     */
    @Test
    @Order(2)
    void putIntelligentTieringConfigurationIsNotTreatedAsCreateBucket() {
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>EntireBucket</Id>
                        <Status>Enabled</Status>
                        <Tiering>
                            <AccessTier>ARCHIVE_ACCESS</AccessTier>
                            <Days>90</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=EntireBucket")
        .then()
            .statusCode(204)
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(3)
    void getIntelligentTieringConfigurationReturnsWhatWasStored() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=EntireBucket")
        .then()
            .statusCode(200)
            .body(containsString("<IntelligentTieringConfiguration"))
            .body(containsString("<Id>EntireBucket</Id>"))
            .body(containsString("<Status>Enabled</Status>"))
            .body(containsString("<AccessTier>ARCHIVE_ACCESS</AccessTier>"))
            .body(containsString("<Days>90</Days>"));
    }

    @Test
    @Order(4)
    void putFilteredConfigurationKeepsTheFilterAndEveryTiering() {
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>Filtered</Id>
                        <Filter>
                            <And>
                                <Prefix>logs/</Prefix>
                                <Tag><Key>env</Key><Value>prod</Value></Tag>
                                <Tag><Key>team</Key><Value>core</Value></Tag>
                            </And>
                        </Filter>
                        <Status>Disabled</Status>
                        <Tiering>
                            <AccessTier>ARCHIVE_ACCESS</AccessTier>
                            <Days>90</Days>
                        </Tiering>
                        <Tiering>
                            <AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier>
                            <Days>180</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(204);

        // AWS repeats <Tag> inside <And> with no wrapping element.
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(200)
            .body(containsString("<And><Prefix>logs/</Prefix>"
                    + "<Tag><Key>env</Key><Value>prod</Value></Tag>"
                    + "<Tag><Key>team</Key><Value>core</Value></Tag></And>"))
            .body(containsString("<Status>Disabled</Status>"))
            .body(containsString("<Tiering><AccessTier>ARCHIVE_ACCESS</AccessTier>"
                    + "<Days>90</Days></Tiering>"
                    + "<Tiering><AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier>"
                    + "<Days>180</Days></Tiering>"));
    }

    @Test
    @Order(5)
    void listWithoutAnIdReturnsEveryConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(200)
            .body(containsString("<ListBucketIntelligentTieringConfigurationsResult"))
            .body(containsString("<Id>EntireBucket</Id>"))
            .body(containsString("<Id>Filtered</Id>"))
            .body(containsString("<IsTruncated>false</IsTruncated>"));
    }

    @Test
    @Order(6)
    void puttingTheSameIdReplacesRatherThanDuplicates() {
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>EntireBucket</Id>
                        <Status>Disabled</Status>
                        <Tiering>
                            <AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier>
                            <Days>180</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=EntireBucket")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=EntireBucket")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Disabled</Status>"))
            .body(containsString("<Days>180</Days>"));

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(200)
            .body(containsString("<Id>EntireBucket</Id>"))
            .body(containsString("<Id>Filtered</Id>"));
    }

    @Test
    @Order(7)
    void idInTheBodyMustMatchTheIdInTheQuery() {
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>Different</Id>
                        <Status>Enabled</Status>
                        <Tiering>
                            <AccessTier>ARCHIVE_ACCESS</AccessTier>
                            <Days>90</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=Mismatch")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(8)
    void unknownIdIsNotFound() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));

        // AWS does not treat deleting an absent configuration as a no-op.
        given()
        .when()
            .delete("/" + BUCKET + "?intelligent-tiering&id=absent")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    /**
     * An unhandled {@code DELETE /{bucket}?intelligent-tiering} would fall through to the
     * unqualified {@code DeleteBucket} and silently remove the whole bucket, reporting success.
     */
    @Test
    @Order(9)
    void deleteIntelligentTieringConfigurationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?intelligent-tiering&id=EntireBucket")
        .then()
            .statusCode(204);

        // The bucket, and every other configuration on it, must survive.
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=EntireBucket")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(10)
    void aRequestWithoutAnIdIsRefusedRatherThanGuessedAt() {
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>EntireBucket</Id>
                        <Status>Enabled</Status>
                        <Tiering>
                            <AccessTier>ARCHIVE_ACCESS</AccessTier>
                            <Days>90</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));

        given()
        .when()
            .delete("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(11)
    void malformedBodiesLeaveExistingConfigurationsUnchanged() {
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>Filtered</Id>
                        <Status>Maybe</Status>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(200)
            .body(containsString("<Status>Disabled</Status>"));
    }

    @Test
    @Order(12)
    void aPutWithoutABodyIsMalformedXml() {
        given()
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(13)
    void unqualifiedDeleteStillRemovesBucket() {
        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(14)
    void aRecreatedBucketDoesNotInheritTheOldConfigurations() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=Filtered")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));

        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(15)
    void outOfRangeDaysReturnsInvalidArgumentWithArgumentNameAndValue() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);

        // ARCHIVE_ACCESS requires at least 90 days; AWS answers InvalidArgument with ArgumentName
        // and ArgumentValue so the SDK can surface which input was rejected.
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>TooFewDays</Id>
                        <Status>Enabled</Status>
                        <Tiering>
                            <AccessTier>ARCHIVE_ACCESS</AccessTier>
                            <Days>1</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=TooFewDays")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("<ArgumentName>Tiering.Days</ArgumentName>"))
            .body(containsString("<ArgumentValue>1</ArgumentValue>"));

        // DEEP_ARCHIVE_ACCESS requires at least 180 days; 90 is valid for ARCHIVE_ACCESS but not here.
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>TooFewDays</Id>
                        <Status>Enabled</Status>
                        <Tiering>
                            <AccessTier>DEEP_ARCHIVE_ACCESS</AccessTier>
                            <Days>90</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=TooFewDays")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("<ArgumentName>Tiering.Days</ArgumentName>"))
            .body(containsString("<ArgumentValue>90</ArgumentValue>"));

        // Both tiers are capped at 730 days.
        given()
            .body("""
                    <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Id>TooManyDays</Id>
                        <Status>Enabled</Status>
                        <Tiering>
                            <AccessTier>ARCHIVE_ACCESS</AccessTier>
                            <Days>731</Days>
                        </Tiering>
                    </IntelligentTieringConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=TooManyDays")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"))
            .body(containsString("<ArgumentName>Tiering.Days</ArgumentName>"))
            .body(containsString("<ArgumentValue>731</ArgumentValue>"));

        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }
}

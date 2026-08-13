package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Per-bucket configuration sub-resources: inventory, analytics, metrics and
 * intelligent-tiering. Each supports Put/Get/Delete keyed by {@code ?id=} plus a
 * {@code List*Configurations} form when {@code id} is absent.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3BucketConfigurationsIntegrationTest {

    private static final String BUCKET = "bucket-configurations-int-test";

    private static final String INVENTORY_XML = """
            <InventoryConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Id>inv-1</Id>
                <IsEnabled>true</IsEnabled>
                <IncludedObjectVersions>All</IncludedObjectVersions>
                <Schedule><Frequency>Daily</Frequency></Schedule>
                <Filter><Prefix>reports/</Prefix></Filter>
                <Destination>
                    <S3BucketDestination>
                        <AccountId>000000000000</AccountId>
                        <Bucket>arn:aws:s3:::inventory-destination</Bucket>
                        <Format>CSV</Format>
                    </S3BucketDestination>
                </Destination>
            </InventoryConfiguration>
            """;

    private static final String ANALYTICS_XML = """
            <AnalyticsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Id>ana-1</Id>
                <StorageClassAnalysis>
                    <DataExport>
                        <OutputSchemaVersion>V_1</OutputSchemaVersion>
                        <Destination>
                            <S3BucketDestination>
                                <Format>CSV</Format>
                                <BucketAccountId>111111111111</BucketAccountId>
                                <Bucket>arn:aws:s3:::analytics-destination</Bucket>
                            </S3BucketDestination>
                        </Destination>
                    </DataExport>
                </StorageClassAnalysis>
            </AnalyticsConfiguration>
            """;

    private static final String METRICS_XML = """
            <MetricsConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Id>met-1</Id>
                <Filter>
                    <Tag><Key>team</Key><Value>platform</Value></Tag>
                </Filter>
            </MetricsConfiguration>
            """;

    private static final String TIERING_XML = """
            <IntelligentTieringConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Id>tier-1</Id>
                <Status>Enabled</Status>
                <Tiering>
                    <Days>90</Days>
                    <AccessTier>ARCHIVE_ACCESS</AccessTier>
                </Tiering>
            </IntelligentTieringConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    // ── Inventory ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void getInventoryBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=inv-1")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(11)
    void listInventoryOnEmptyBucketReturnsEmptyResult() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory")
        .then()
            .statusCode(200)
            .body(containsString("<ListInventoryConfigurationsResult"))
            .body(containsString("<IsTruncated>false</IsTruncated>"))
            .body(not(containsString("<InventoryConfiguration")));
    }

    @Test
    @Order(12)
    void putInventoryConfiguration() {
        given()
            .body(INVENTORY_XML)
        .when()
            .put("/" + BUCKET + "?inventory&id=inv-1")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(13)
    void getInventoryReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=inv-1")
        .then()
            .statusCode(200)
            .body(containsString("<InventoryConfiguration"))
            .body(containsString("<Id>inv-1</Id>"))
            .body(containsString("<Prefix>reports/</Prefix>"));
    }

    @Test
    @Order(14)
    void listInventoryReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?inventory")
        .then()
            .statusCode(200)
            .body(containsString("<ListInventoryConfigurationsResult"))
            .body(containsString("<InventoryConfiguration"))
            .body(containsString("<Id>inv-1</Id>"))
            .body(containsString("<Prefix>reports/</Prefix>"));
    }

    @Test
    @Order(15)
    void deleteInventoryConfiguration() {
        given()
        .when()
            .delete("/" + BUCKET + "?inventory&id=inv-1")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?inventory&id=inv-1")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(16)
    void deleteUnknownInventoryConfigurationReturns404() {
        given()
        .when()
            .delete("/" + BUCKET + "?inventory&id=does-not-exist")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void getAnalyticsBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=ana-1")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(21)
    void putAnalyticsConfiguration() {
        given()
            .body(ANALYTICS_XML)
        .when()
            .put("/" + BUCKET + "?analytics&id=ana-1")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(22)
    void getAnalyticsReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=ana-1")
        .then()
            .statusCode(200)
            .body(containsString("<AnalyticsConfiguration"))
            .body(containsString("<BucketAccountId>111111111111</BucketAccountId>"));
    }

    @Test
    @Order(23)
    void listAnalyticsReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?analytics")
        .then()
            .statusCode(200)
            .body(containsString("<ListBucketAnalyticsConfigurationResult"))
            .body(containsString("<AnalyticsConfiguration"))
            .body(containsString("<Id>ana-1</Id>"));
    }

    @Test
    @Order(24)
    void deleteAnalyticsConfiguration() {
        given()
        .when()
            .delete("/" + BUCKET + "?analytics&id=ana-1")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?analytics&id=ana-1")
        .then()
            .statusCode(404);
    }

    // ── Metrics ───────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void getMetricsBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=met-1")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(31)
    void putMetricsConfiguration() {
        given()
            .body(METRICS_XML)
        .when()
            .put("/" + BUCKET + "?metrics&id=met-1")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(32)
    void getMetricsReturnsStoredTagFilter() {
        given()
        .when()
            .get("/" + BUCKET + "?metrics&id=met-1")
        .then()
            .statusCode(200)
            .body(containsString("<MetricsConfiguration"))
            .body(containsString("<Key>team</Key>"))
            .body(containsString("<Value>platform</Value>"));
    }

    @Test
    @Order(33)
    void listMetricsReturnsEveryStoredConfiguration() {
        given()
            .body(METRICS_XML.replace("met-1", "met-2"))
        .when()
            .put("/" + BUCKET + "?metrics&id=met-2")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?metrics")
        .then()
            .statusCode(200)
            .body(containsString("<ListMetricsConfigurationsResult"))
            .body(containsString("<Id>met-1</Id>"))
            .body(containsString("<Id>met-2</Id>"));
    }

    @Test
    @Order(34)
    void deleteMetricsConfigurations() {
        given()
        .when()
            .delete("/" + BUCKET + "?metrics&id=met-1")
        .then()
            .statusCode(204);

        given()
        .when()
            .delete("/" + BUCKET + "?metrics&id=met-2")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?metrics")
        .then()
            .statusCode(200)
            .body(not(containsString("<MetricsConfiguration")));
    }

    // ── Intelligent tiering ───────────────────────────────────────────────────

    @Test
    @Order(40)
    void getIntelligentTieringBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=tier-1")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchConfiguration"));
    }

    @Test
    @Order(41)
    void putIntelligentTieringConfiguration() {
        given()
            .body(TIERING_XML)
        .when()
            .put("/" + BUCKET + "?intelligent-tiering&id=tier-1")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(42)
    void getIntelligentTieringReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=tier-1")
        .then()
            .statusCode(200)
            .body(containsString("<IntelligentTieringConfiguration"))
            .body(containsString("<AccessTier>ARCHIVE_ACCESS</AccessTier>"));
    }

    @Test
    @Order(43)
    void listIntelligentTieringReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering")
        .then()
            .statusCode(200)
            .body(containsString("<ListBucketIntelligentTieringConfigurationsResult"))
            .body(containsString("<Id>tier-1</Id>"));
    }

    @Test
    @Order(44)
    void deleteIntelligentTieringConfiguration() {
        given()
        .when()
            .delete("/" + BUCKET + "?intelligent-tiering&id=tier-1")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/" + BUCKET + "?intelligent-tiering&id=tier-1")
        .then()
            .statusCode(404);
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void putWithMismatchedDocumentReturnsMalformedXml() {
        given()
            .body(METRICS_XML)
        .when()
            .put("/" + BUCKET + "?inventory&id=inv-9")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(51)
    void putWithoutIdReturnsInvalidArgument() {
        given()
            .body(METRICS_XML)
        .when()
            .put("/" + BUCKET + "?metrics")
        .then()
            .statusCode(400)
            .body(containsString("InvalidArgument"));
    }

    @Test
    @Order(52)
    void configurationsOnMissingBucketReturn404() {
        given()
        .when()
            .get("/no-such-configuration-bucket?metrics")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    @Test
    @Order(60)
    void deleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
    }
}

package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_MAP;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_NULL;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.NUMBER;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.STRUCTURE;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.authFor;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * BatchGetMetricData, CreateImportJob and CreateExportJob check their body against the SES v2
 * shapes, so a member of the wrong JSON type, nested ones included, is a SerializationException
 * ahead of the operation's own validation. BatchGetMetricData keeps its VDM gate in front of the
 * check, so its cases run in a region of their own with VDM enabled.
 */
@QuarkusTest
class SesMetricsJobsMemberTypeIntegrationTest {

    private static final String VDM_REGION_AUTH = authFor("af-south-1");
    private static final String VDM_OFF_REGION_AUTH = authFor("ap-east-1");
    private static final String METRICS = "/v2/email/metrics/batch";
    private static final String IMPORT = "/v2/email/import-jobs";
    private static final String EXPORT = "/v2/email/export-jobs";

    static Stream<Arguments> batchGetMetricDataCases() {
        return Stream.of(
                Arguments.of("objectQueries", "{\"Queries\": {}}", STRUCTURE),
                Arguments.of("stringQueries", "{\"Queries\": \"x\"}", EXPECTED_LIST),
                Arguments.of("stringQuery", "{\"Queries\": [\"x\"]}", EXPECTED_NULL),
                Arguments.of("numericId", "{\"Queries\": [{\"Id\": 1}]}", NUMBER),
                Arguments.of("arrayDimensions", "{\"Queries\": [{\"Dimensions\": []}]}", LIST),
                Arguments.of("stringDimensions", "{\"Queries\": [{\"Dimensions\": \"x\"}]}", EXPECTED_MAP),
                Arguments.of("numericDimensionValue", "{\"Queries\": [{\"Dimensions\": {\"ISP\": 1}}]}", NUMBER));
    }

    static Stream<Arguments> createImportJobCases() {
        return Stream.of(
                Arguments.of("stringImportDataSource", "{\"ImportDataSource\": \"x\"}", EXPECTED_NULL),
                Arguments.of("numericS3Url",
                        "{\"ImportDataSource\": {\"S3Url\": 1, \"DataFormat\": \"CSV\"}}", NUMBER),
                Arguments.of("arrayImportDestination", "{\"ImportDestination\": []}", LIST),
                Arguments.of("stringContactListDestination",
                        "{\"ImportDestination\": {\"ContactListDestination\": \"x\"}}", EXPECTED_NULL));
    }

    static Stream<Arguments> createExportJobCases() {
        return Stream.of(
                Arguments.of("stringExportDataSource", "{\"ExportDataSource\": \"x\"}", EXPECTED_NULL),
                Arguments.of("stringMetricsDataSource",
                        "{\"ExportDataSource\": {\"MetricsDataSource\": \"x\"}}", EXPECTED_NULL),
                Arguments.of("stringMetrics",
                        "{\"ExportDataSource\": {\"MetricsDataSource\": {\"Metrics\": \"SEND\"}}}", EXPECTED_LIST),
                Arguments.of("arrayDimensions",
                        "{\"ExportDataSource\": {\"MetricsDataSource\": {\"Dimensions\": []}}}", LIST),
                Arguments.of("stringDimensions",
                        "{\"ExportDataSource\": {\"MetricsDataSource\": {\"Dimensions\": \"x\"}}}", EXPECTED_MAP),
                Arguments.of("numericDimensionValue",
                        "{\"ExportDataSource\": {\"MetricsDataSource\": {\"Dimensions\": {\"ISP\": [1]}}}}", NUMBER),
                Arguments.of("objectIncludeFilter",
                        "{\"ExportDataSource\": {\"MessageInsightsDataSource\": {\"Include\": {\"Isp\": {}}}}}",
                        STRUCTURE),
                Arguments.of("numericDataFormat", "{\"ExportDestination\": {\"DataFormat\": 1}}", NUMBER));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("batchGetMetricDataCases")
    void batchGetMetricData_wrongMemberType(String caseName, String body, String message) {
        given()
            .contentType("application/json")
            .header("Authorization", VDM_REGION_AUTH)
            .body("{\"VdmAttributes\": {\"VdmEnabled\": \"ENABLED\"}}")
        .when()
            .put("/v2/email/account/vdm")
        .then()
            .statusCode(200);
        assertSerializationException(VDM_REGION_AUTH, "POST", METRICS, body, message);
    }

    @Test
    void batchGetMetricData_vdmGateStaysAheadOfTheShapeCheck() {
        given()
            .contentType("application/json")
            .header("Authorization", VDM_OFF_REGION_AUTH)
            .body("{\"Queries\": \"x\"}")
        .when()
            .post(METRICS)
        .then()
            .statusCode(404)
            .body("__type", equalTo("NotFoundException"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createImportJobCases")
    void createImportJob_wrongMemberType(String caseName, String body, String message) {
        assertSerializationException("POST", IMPORT, body, message);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("createExportJobCases")
    void createExportJob_wrongMemberTypeBeforeTheDataSourceCheck(String caseName, String body, String message) {
        assertSerializationException("POST", EXPORT, body, message);
    }
}

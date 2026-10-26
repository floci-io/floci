package io.github.hectorvent.floci.services.ses;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.EXPECTED_MAP;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.LIST;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.NUMBER;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.STRING_FOR_INTEGER;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertError;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.assertSerializationException;
import static io.github.hectorvent.floci.services.ses.SesV2SerializationErrors.authFor;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The list operations read a map {@code Filter} and an integer {@code PageSize} through the
 * SesV2Json member helpers rather than the shape check, so each endpoint is covered here: a scalar
 * filter is "Expected map or null", a string page size names the Integer target and any other
 * number is narrowed to an int before the range check. Every refused case fails before anything is
 * looked up.
 */
@QuarkusTest
class SesListMemberTypeIntegrationTest {

    private static final String NARROW_REGION_AUTH = authFor("ap-south-2");

    static Stream<Arguments> scalarFilterCases() {
        return Stream.of(
                Arguments.of("listEmailIdentities", "/v2/email/list-identities", "{\"Filter\": \"x\"}"),
                Arguments.of("listConfigurationSets", "/v2/email/list-configuration-sets", "{\"Filter\": \"x\"}"),
                Arguments.of("listTenants", "/v2/email/tenants/list", "{\"Filter\": 1}"),
                Arguments.of("listTenantResources", "/v2/email/tenants/resources/list",
                        "{\"TenantName\": \"ghost\", \"Filter\": \"x\"}"));
    }

    static Stream<Arguments> stringPageSizeCases() {
        String pageSize = "{\"PageSize\": \"1\"}";
        return Stream.of(
                Arguments.of("listEmailIdentities", "/v2/email/list-identities", pageSize),
                Arguments.of("listConfigurationSets", "/v2/email/list-configuration-sets", pageSize),
                Arguments.of("listContacts", "/v2/email/contact-lists/ghost/contacts/list", pageSize),
                Arguments.of("listImportJobs", "/v2/email/import-jobs/list", pageSize),
                Arguments.of("listExportJobs", "/v2/email/list-export-jobs", pageSize),
                Arguments.of("listEmailIdentityCertificates", "/v2/email/identity/certificates/list",
                        "{\"EmailIdentity\": \"ghost.example.com\", \"PageSize\": \"1\"}"),
                Arguments.of("listTenants", "/v2/email/tenants/list", pageSize),
                Arguments.of("listTenantResources", "/v2/email/tenants/resources/list",
                        "{\"TenantName\": \"ghost\", \"PageSize\": \"1\"}"),
                Arguments.of("listResourceTenants", "/v2/email/resources/tenants/list",
                        "{\"ResourceArn\": \"ghost\", \"PageSize\": \"1\"}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scalarFilterCases")
    void scalarFilter_isExpectedMap(String caseName, String path, String body) {
        assertSerializationException("POST", path, body, EXPECTED_MAP);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stringPageSizeCases")
    void stringPageSize_namesTheIntegerTarget(String caseName, String path, String body) {
        assertSerializationException("POST", path, body, STRING_FOR_INTEGER);
    }

    @Test
    void nonObjectBody_isASerializationException() {
        assertSerializationException("POST", "/v2/email/list-identities", "[]", LIST);
        for (String body : List.of("\"x\"", "1", "null")) {
            assertSerializationException("POST", "/v2/email/list-identities", body, null);
        }
    }

    @Test
    void pageSize_isNarrowedBeforeTheRangeCheck() {
        for (String identity : List.of("a@narrow.example.com", "b@narrow.example.com")) {
            given()
                .contentType("application/json")
                .header("Authorization", NARROW_REGION_AUTH)
                .body("{\"EmailIdentity\": \"" + identity + "\"}")
            .when()
                .post("/v2/email/identities")
            .then()
                .statusCode(200);
        }
        given()
            .contentType("application/json")
            .header("Authorization", NARROW_REGION_AUTH)
            .body("{\"PageSize\": 1.5}")
        .when()
            .post("/v2/email/list-identities")
        .then()
            .statusCode(200)
            .body("EmailIdentities", hasSize(1))
            .body("NextToken", notNullValue());
        assertError("POST", "/v2/email/list-identities", "{\"PageSize\": 4294967296}", "BadRequestException",
                "Value 2147483647 for parameter PageSize is invalid. PageSize must be between 1 and 1000.");
    }

    @Test
    void listTenantResources_filterValueNamesTheStringTarget() {
        assertSerializationException("POST", "/v2/email/tenants/resources/list",
                "{\"TenantName\": \"ghost\", \"Filter\": {\"RESOURCE_TYPE\": 1}}", NUMBER);
    }
}

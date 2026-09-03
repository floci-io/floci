package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * CompatibleRuntime, CompatibleArchitecture, MaxItems and Marker on ListLayers and
 * ListLayerVersions.
 *
 * <p>The list-wide assertions filter on {@code ruby3.4}, a runtime no other test publishes:
 * integration tests share one emulator, so an unfiltered ListLayers would also see whatever
 * layers a concurrently ordered class has published.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaListLayersParametersIntegrationTest {

    private static final String PAGE_RUNTIME = "ruby3.4";
    private static final String LAYER_A = "lp-alpha";
    private static final String LAYER_B = "lp-beta";
    private static final String LAYER_NO_ARCH = "lp-no-arch";
    private static final String LAYER_VERSIONS = "lp-versions";

    private static String layerZipBase64() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("ruby/shared.rb"));
            zos.write("VALUE = 1".getBytes());
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** Publishes a version; {@code architectures} may be null to omit CompatibleArchitectures. */
    private static int publish(String layerName, String runtime, String architectures) throws Exception {
        String archField = architectures == null ? "" : "\"CompatibleArchitectures\": " + architectures + ",";
        return given()
            .contentType("application/json")
            .body("""
                {
                    "Content": { "ZipFile": "%s" },
                    "CompatibleRuntimes": ["%s"],
                    %s
                    "Description": "list parameter probe"
                }
                """.formatted(layerZipBase64(), runtime, archField))
        .when()
            .post("/2018-10-31/layers/" + layerName + "/versions")
        .then()
            .statusCode(201)
            .extract().path("Version");
    }

    @Test
    @Order(1)
    void seedLayers() throws Exception {
        publish(LAYER_A, PAGE_RUNTIME, "[\"x86_64\"]");
        publish(LAYER_B, PAGE_RUNTIME, "[\"arm64\"]");
        publish(LAYER_NO_ARCH, PAGE_RUNTIME, null);
        // Two versions whose runtimes differ, so a filter can select the older one.
        publish(LAYER_VERSIONS, "ruby3.3", "[\"x86_64\"]");
        publish(LAYER_VERSIONS, "ruby4.0", "[\"arm64\"]");
    }

    // ── filters ──────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void listLayersFiltersByCompatibleRuntime() {
        given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers.LayerName", contains(LAYER_A, LAYER_B, LAYER_NO_ARCH));
    }

    @Test
    @Order(3)
    void listLayersFiltersByCompatibleArchitecture() {
        given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
            .queryParam("CompatibleArchitecture", "arm64")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers.LayerName", contains(LAYER_B));
    }

    @Test
    @Order(4)
    void layerWithoutCompatibleArchitecturesMatchesNoArchitectureFilter() {
        // Measured on the live service: unlike a function's Architectures, an absent
        // CompatibleArchitectures does not default to x86_64 for filtering purposes.
        for (String architecture : new String[] {"x86_64", "arm64"}) {
            given()
                .queryParam("CompatibleRuntime", PAGE_RUNTIME)
                .queryParam("CompatibleArchitecture", architecture)
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(200)
                .body("Layers.findAll { it.LayerName == '" + LAYER_NO_ARCH + "' }", empty());
        }
    }

    @Test
    @Order(5)
    void latestMatchingVersionIsTheNewestVersionSatisfyingTheFilter() {
        // v2 is ruby4.0, v1 is ruby3.3: filtering on ruby3.3 must report v1, not the newest.
        given()
            .queryParam("CompatibleRuntime", "ruby3.3")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers.find { it.LayerName == '" + LAYER_VERSIONS + "' }.LatestMatchingVersion.Version",
                    equalTo(1));
    }

    @Test
    @Order(6)
    void layerWithNoMatchingVersionDropsOutOfTheList() {
        given()
            .queryParam("CompatibleRuntime", "ruby2.5")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers.findAll { it.LayerName == '" + LAYER_VERSIONS + "' }", empty());
    }

    // ── pagination ───────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void maxItemsLimitsThePageAndMarkerResumesIt() {
        String marker = given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
            .queryParam("MaxItems", 2)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers.LayerName", contains(LAYER_A, LAYER_B))
            .body("NextMarker", notNullValue())
            .extract().path("NextMarker");

        given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
            .queryParam("MaxItems", 2)
            .queryParam("Marker", marker)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            // Resumes after the last item of page one, with no overlap and nothing skipped.
            .body("Layers.LayerName", contains(LAYER_NO_ARCH))
            .body("NextMarker", nullValue());
    }

    @Test
    @Order(8)
    void nextMarkerIsPresentAndNullOnTheLastPage() {
        given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            // AWS emits the field on every response rather than omitting it when there is no
            // next page, so a client that reads it unconditionally does not see a missing key.
            .body("$", hasKey("NextMarker"))
            .body("NextMarker", nullValue());
    }

    @Test
    @Order(9)
    void emptyMaxItemsIsTreatedAsOmitted() {
        // Matches the live service, which also answers 200 for MaxItems= rather than rejecting
        // it. Note the empty value never reaches the parameter: RESTEasy Reactive binds an
        // empty query value as null, which is why CompatibleRuntime= and Marker= are accepted
        // here but rejected by AWS - see the PR's "known divergence".
        given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
            .queryParam("MaxItems", "")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers", hasSize(3));
    }

    @Test
    @Order(10)
    void unknownMarkerIsInvalidPaginationKey() {
        for (String marker : new String[] {"garbage", "AAAAAAAA", "!!not-base64!!"}) {
            given()
                .queryParam("Marker", marker)
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid pagination key."));
        }
    }

    @Test
    @Order(10)
    void forgedMarkerIsRejectedEvenWhenWellFormed() {
        // A caller who knows the cursor format cannot mint a marker: the signature is over a
        // key this process generated, so a hand-built token is rejected rather than applied
        // as a cursor, which would silently skip or empty the page.
        for (String forged : new String[] {"lp-alpha.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                                           "lp-alpha.", ".signature", "lp-alpha"}) {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(forged.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            given()
                .queryParam("CompatibleRuntime", PAGE_RUNTIME)
                .queryParam("Marker", encoded)
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", equalTo("Invalid pagination key."));
        }
    }

    @Test
    @Order(10)
    void markerFromOneListingIsNotAcceptedWithADifferentCursorSpliced() {
        // Take a real marker, decode it, swap the cursor, re-encode: the signature no longer
        // matches, so the edited token is refused.
        String marker = given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
            .queryParam("MaxItems", 1)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .extract().path("NextMarker");

        String decoded = new String(Base64.getUrlDecoder().decode(marker),
                java.nio.charset.StandardCharsets.UTF_8);
        String spliced = LAYER_B + decoded.substring(decoded.lastIndexOf('.'));
        given()
            .queryParam("CompatibleRuntime", PAGE_RUNTIME)
            .queryParam("Marker", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(spliced.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    // ── parameter validation ─────────────────────────────────────────────────

    @Test
    @Order(11)
    void maxItemsBelowRangeIsValidationException() {
        given()
            .queryParam("MaxItems", 0)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '0' at 'maxItems' "
                    + "failed to satisfy constraint: Member must have value greater than or equal to 1"));
    }

    @Test
    @Order(12)
    void maxItemsAboveRangeIsValidationException() {
        given()
            .queryParam("MaxItems", 51)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("Member must have value less than or equal to 50"));
    }

    @Test
    @Order(13)
    void nonIntegerMaxItemsIsSerializationException() {
        for (String value : new String[] {"abc", "2.5", " 2 "}) {
            given()
                .queryParam("MaxItems", value)
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("'" + value + "' can not be converted to Integer"));
        }
    }

    @Test
    @Order(14)
    void unknownOrMiscasedRuntimeIsValidationException() {
        for (String runtime : new String[] {"bogus9.x", "RUBY3.4"}) {
            given()
                .queryParam("CompatibleRuntime", runtime)
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Value '" + runtime + "' at 'compatibleRuntime' "
                        + "failed to satisfy constraint: Member must satisfy enum value set: ["));
        }
    }

    @Test
    @Order(15)
    void unknownOrMiscasedArchitectureIsValidationException() {
        for (String architecture : new String[] {"sparc", "ARM64"}) {
            given()
                .queryParam("CompatibleArchitecture", architecture)
            .when()
                .get("/2018-10-31/layers")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", containsString("Value '" + architecture + "' at "
                        + "'compatibleArchitecture' failed to satisfy constraint: "
                        + "Member must satisfy enum value set: [x86_64, arm64]"));
        }
    }

    @Test
    @Order(16)
    void validationFailuresAccumulateInMemberOrder() {
        // Measured: the live service reports every failing member in one response, ordered
        // maxItems, compatibleRuntime, compatibleArchitecture whatever the query-string order.
        given()
            .queryParam("CompatibleArchitecture", "sparc")
            .queryParam("CompatibleRuntime", "bogus9.x")
            .queryParam("MaxItems", 0)
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("3 validation errors detected: Value '0' at 'maxItems'"))
            .body("message", containsString("; Value 'bogus9.x' at 'compatibleRuntime'"))
            .body("message", containsString("; Value 'sparc' at 'compatibleArchitecture'"));
    }

    @Test
    @Order(17)
    void serializationFailureShortCircuitsValidation() {
        // A MaxItems that will not parse is reported alone, without the runtime error.
        given()
            .queryParam("MaxItems", "abc")
            .queryParam("CompatibleRuntime", "bogus9.x")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("SerializationException"));
    }

    @Test
    @Order(18)
    void markerIsValidatedAfterTheEnums() {
        given()
            .queryParam("Marker", "garbage")
            .queryParam("CompatibleRuntime", "bogus9.x")
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    // ── ListLayerVersions takes the same four parameters ──────────────────────

    @Test
    @Order(19)
    void listLayerVersionsFiltersAndPaginates() {
        given()
            .queryParam("CompatibleRuntime", "ruby3.3")
        .when()
            .get("/2018-10-31/layers/" + LAYER_VERSIONS + "/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions.Version", contains(1))
            .body("NextMarker", nullValue());

        String marker = given()
            .queryParam("MaxItems", 1)
        .when()
            .get("/2018-10-31/layers/" + LAYER_VERSIONS + "/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions.Version", contains(1))
            .body("NextMarker", notNullValue())
            .extract().path("NextMarker");

        given()
            .queryParam("MaxItems", 1)
            .queryParam("Marker", marker)
        .when()
            .get("/2018-10-31/layers/" + LAYER_VERSIONS + "/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions.Version", contains(2))
            .body("NextMarker", nullValue());
    }

    @Test
    @Order(20)
    void listLayerVersionsRejectsTheSameBadParameters() {
        given()
            .queryParam("MaxItems", 0)
        .when()
            .get("/2018-10-31/layers/" + LAYER_VERSIONS + "/versions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));

        given()
            .queryParam("Marker", "garbage")
        .when()
            .get("/2018-10-31/layers/" + LAYER_VERSIONS + "/versions")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(21)
    void listLayerVersionsOfAnUnknownLayerIsAnEmptyPage() {
        // Measured on the live service: a layer that never existed is 200 with an empty list.
        given()
        .when()
            .get("/2018-10-31/layers/lp-no-such-layer/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions", empty())
            .body("NextMarker", nullValue());
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(22)
    void cleanup_deletePublishedVersions() {
        for (String layerName : List.of(LAYER_A, LAYER_B, LAYER_NO_ARCH, LAYER_VERSIONS)) {
            List<Integer> versions = given()
            .when()
                .get("/2018-10-31/layers/" + layerName + "/versions")
            .then()
                .statusCode(200)
                .extract().path("LayerVersions.Version");

            for (Integer version : versions) {
                given()
                .when()
                    .delete("/2018-10-31/layers/" + layerName + "/versions/" + version)
                .then()
                    .statusCode(204);
            }
        }
    }
}

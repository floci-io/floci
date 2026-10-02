package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class LambdaGetFunctionConcurrencyIntegrationTest {

    private static final String ACCOUNT = "000000004576";
    private static final String OTHER_ACCOUNT = "000000004577";
    private static final String REGION = "us-east-1";
    private static final String FUNCTIONS = "/2015-03-31/functions/";

    @Test
    void getFunctionReturnsCurrentReservationIncludingZeroAndOmitsItAfterDeletion() throws Exception {
        String name = createFunction(ACCOUNT, REGION);
        try {
            assertConcurrency(ACCOUNT, REGION, name, null, null);
            for (int reservation : new int[]{2, 7, 0}) {
                putConcurrency(ACCOUNT, REGION, name, reservation);
                assertConcurrency(ACCOUNT, REGION, name, null, reservation);
            }
            deleteConcurrency(ACCOUNT, REGION, name);
            assertConcurrency(ACCOUNT, REGION, name, null, null);
            given().header("Authorization", auth(ACCOUNT, REGION))
                    .when().get(FUNCTIONS + name + "/configuration")
                    .then().statusCode(200).body("$", not(hasKey("Concurrency")));
        } finally {
            deleteFunction(ACCOUNT, REGION, name);
        }
    }

    @Test
    void qualifiedReadsOmitConcurrencyEvenWhenAReservationIsSet() throws Exception {
        String name = createFunction(ACCOUNT, REGION);
        try {
            putConcurrency(ACCOUNT, REGION, name, 2);
            publishVersion(ACCOUNT, name);
            given().header("Authorization", auth(ACCOUNT, REGION)).contentType("application/json")
                    .body("{\"Name\":\"live\",\"FunctionVersion\":\"1\"}")
                    .when().post(FUNCTIONS + name + "/aliases").then().statusCode(201);
            String arn = "arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + name;

            for (int reservation : new int[]{7, 0}) {
                putConcurrency(ACCOUNT, REGION, name, reservation);
                assertConcurrency(ACCOUNT, REGION, name, null, reservation);
                assertConcurrency(ACCOUNT, REGION, arn, null, reservation);
                for (String qualifier : new String[]{"$LATEST", "1", "live"}) {
                    assertConcurrency(ACCOUNT, REGION, name, qualifier, null);
                    assertConcurrency(ACCOUNT, REGION, name + ":" + qualifier, null, null);
                    assertConcurrency(ACCOUNT, REGION, ACCOUNT + ":function:" + name + ":" + qualifier,
                            null, null);
                    assertConcurrency(ACCOUNT, REGION, arn, qualifier, null);
                    assertConcurrency(ACCOUNT, REGION, arn + ":" + qualifier, null, null);
                    assertConcurrency(ACCOUNT, REGION, arn + ":" + qualifier, qualifier, null);
                }
            }
            deleteConcurrency(ACCOUNT, REGION, name);
            assertConcurrency(ACCOUNT, REGION, name, "1", null);
            assertConcurrency(ACCOUNT, REGION, name, "live", null);
        } finally {
            deleteFunction(ACCOUNT, REGION, name);
        }
    }

    @Test
    void accountsHaveIndependentReservationsAndQualifiedArnsOmitConcurrency() throws Exception {
        String name = createFunction(ACCOUNT, REGION);
        createFunction(OTHER_ACCOUNT, REGION, name);
        try {
            putConcurrency(ACCOUNT, REGION, name, 2);
            putConcurrency(OTHER_ACCOUNT, REGION, name, 9);
            publishVersion(OTHER_ACCOUNT, name);
            putConcurrency(OTHER_ACCOUNT, REGION, name, 11);

            String arn = "arn:aws:lambda:" + REGION + ":" + OTHER_ACCOUNT + ":function:" + name;
            assertConcurrency(ACCOUNT, REGION, name, null, 2);
            assertConcurrency(OTHER_ACCOUNT, REGION, name, null, 11);
            assertConcurrency(OTHER_ACCOUNT, REGION, name, "1", null);
            assertConcurrency(ACCOUNT, REGION, arn + ":1", null, null);
            assertConcurrency(ACCOUNT, REGION, arn, "1", null);
        } finally {
            deleteFunction(ACCOUNT, REGION, name);
            deleteFunction(OTHER_ACCOUNT, REGION, name);
        }
    }

    @Test
    void reservationIsRegionScoped() throws Exception {
        String otherRegion = "eu-west-1";
        String name = createFunction(ACCOUNT, REGION);
        createFunction(ACCOUNT, otherRegion, name);
        try {
            putConcurrency(ACCOUNT, REGION, name, 2);
            putConcurrency(ACCOUNT, otherRegion, name, 9);
            assertConcurrency(ACCOUNT, REGION, name, null, 2);
            assertConcurrency(ACCOUNT, otherRegion, name, null, 9);
        } finally {
            deleteFunction(ACCOUNT, REGION, name);
            deleteFunction(ACCOUNT, otherRegion, name);
        }
    }

    private static String createFunction(String account, String region) throws Exception {
        String name = "get-concurrency-" + Long.toString(System.nanoTime(), 36);
        createFunction(account, region, name);
        return name;
    }

    private static void createFunction(String account, String region, String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async () => ({});".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        given().header("Authorization", auth(account, region)).contentType("application/json")
                .body("""
                    {"FunctionName":"%s","Runtime":"nodejs20.x",
                     "Role":"arn:aws:iam::%s:role/lambda-role","Handler":"index.handler",
                     "Code":{"ZipFile":"%s"}}
                    """.formatted(name, account, Base64.getEncoder().encodeToString(bytes.toByteArray())))
                .when().post(FUNCTIONS).then().statusCode(201);
    }

    private static void publishVersion(String account, String name) {
        given().header("Authorization", auth(account, REGION)).contentType("application/json").body("{}")
                .when().post(FUNCTIONS + name + "/versions")
                .then().statusCode(201).body("Version", equalTo("1"));
    }

    private static void putConcurrency(String account, String region, String name, int reservation) {
        given().header("Authorization", auth(account, region)).contentType("application/json")
                .body("{\"ReservedConcurrentExecutions\":" + reservation + "}")
                .when().put("/2017-10-31/functions/" + name + "/concurrency")
                .then().statusCode(200).body("ReservedConcurrentExecutions", equalTo(reservation));
    }

    private static void assertConcurrency(String account, String region, String name,
                                          String qualifier, Integer expected) {
        String path = FUNCTIONS + name + (qualifier == null ? "" : "?Qualifier=" + qualifier);
        if (expected == null) {
            given().header("Authorization", auth(account, region)).when().get(path)
                    .then().statusCode(200).body("$", not(hasKey("Concurrency")));
        } else {
            given().header("Authorization", auth(account, region)).when().get(path)
                    .then().statusCode(200)
                    .body("Concurrency.ReservedConcurrentExecutions", equalTo(expected));
        }
    }

    private static void deleteConcurrency(String account, String region, String name) {
        given().header("Authorization", auth(account, region))
                .when().delete("/2017-10-31/functions/" + name + "/concurrency")
                .then().statusCode(204);
    }

    private static void deleteFunction(String account, String region, String name) {
        given().header("Authorization", auth(account, region))
                .when().delete(FUNCTIONS + name).then().statusCode(204);
    }

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260929/" + region + "/lambda/aws4_request";
    }
}

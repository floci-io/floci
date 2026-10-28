package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Issue #2821: GetFunction and GetFunctionConfiguration ignored Qualifier and answered with
 * {@code $LATEST} for everything, including versions that were never published.
 *
 * <p>Two consequences, both measured against the live service by the reporter. A caller pinning a
 * published version silently got whatever {@code $LATEST} held at the time, which defeats the
 * immutability guarantee that is the point of publishing. And a read against a version that does
 * not exist succeeded instead of returning 404, so a typo or a stale alias looked live.
 */
@QuarkusTest
class LambdaQualifiedReadIntegrationTest {

    @ParameterizedTest
    @CsvSource({
            "name, true", "name, false",
            "arn, true", "arn, false",
            "partial, true", "partial, false"
    })
    void aliasReadsKeepTheRequestedArnAndResolvedVersion(String form, boolean query) throws Exception {
        String fn = "alias-identity-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "published");
        try {
            String arn = given().when().get("/2015-03-31/functions/" + fn)
                    .then().statusCode(200).extract().path("Configuration.FunctionArn");
            String publishedHash = given().contentType("application/json").body("{}")
                    .when().post("/2015-03-31/functions/" + fn + "/versions")
                    .then().statusCode(201).body("Version", equalTo("1"))
                    .extract().path("CodeSha256");
            String aliasArn = given().contentType("application/json")
                    .body("{\"Name\":\"stable\",\"FunctionVersion\":\"1\"}")
                    .when().post("/2015-03-31/functions/" + fn + "/aliases")
                    .then().statusCode(anyOf200Or201()).extract().path("AliasArn");
            String latestHash = given().contentType("application/json")
                    .body("{\"ZipFile\": \"%s\"}".formatted(zipB64("latest")))
                    .when().put("/2015-03-31/functions/" + fn + "/code")
                    .then().statusCode(200).extract().path("CodeSha256");
            assertNotEquals(publishedHash, latestHash);
            String reference = switch (form) {
                case "arn" -> arn;
                case "partial" -> "000000000000:function:" + fn;
                default -> fn;
            };
            String path = "/2015-03-31/functions/" + reference + (query ? "" : ":stable");
            String suffix = query ? "?Qualifier=stable" : "";
            given().when().get(path + suffix).then().statusCode(200)
                    .body("Configuration.FunctionArn", equalTo(aliasArn))
                    .body("Configuration.Version", equalTo("1"))
                    .body("Configuration.CodeSha256", equalTo(publishedHash));
            given().when().get(path + "/configuration" + suffix).then().statusCode(200)
                    .body("FunctionArn", equalTo(aliasArn))
                    .body("Version", equalTo("1"))
                    .body("CodeSha256", equalTo(publishedHash));
            given().when().get("/2015-03-31/functions/" + fn + ":1").then().statusCode(200)
                    .body("Configuration.FunctionArn", equalTo(arn + ":1"))
                    .body("Configuration.Version", equalTo("1"))
                    .body("Configuration.CodeSha256", equalTo(publishedHash));
            given().when().get("/2015-03-31/functions/" + fn).then().statusCode(200)
                    .body("Configuration.FunctionArn", equalTo(arn))
                    .body("Configuration.Version", equalTo("$LATEST"))
                    .body("Configuration.CodeSha256", equalTo(latestHash));
            given().when().get("/2015-03-31/functions/" + fn + "/configuration")
                    .then().statusCode(200).body("CodeSha256", equalTo(latestHash));
        } finally {
            given().when().delete("/2015-03-31/functions/" + fn).then().statusCode(204);
        }
    }

    private static String zipB64(String body) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("handler.py"));
            zos.write(("def handler(e, c):\n    return {'body': '" + body + "'}\n").getBytes("UTF-8"));
            zos.closeEntry();
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private static String createFunction(String name, String body) throws Exception {
        given()
            .contentType("application/json")
            .body("""
                {"FunctionName": "%s", "Runtime": "python3.12",
                 "Role": "arn:aws:iam::000000000000:role/r", "Handler": "handler.handler",
                 "Code": {"ZipFile": "%s"}}
                """.formatted(name, zipB64(body)))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(anyOf200Or201());
        return name;
    }

    private static Matcher<Integer> anyOf200Or201() {
        return Matchers.anyOf(equalTo(200), equalTo(201));
    }

    @Test
    void aQualifierSelectsThePublishedVersionRatherThanLatest() throws Exception {
        String fn = "qual-read-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");

        String publishedHash = given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201).body("Version", equalTo("1")).extract().path("CodeSha256");

        // $LATEST moves on past version 1.
        String latestHash = given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"%s\"}".formatted(zipB64("v2")))
        .when()
            .put("/2015-03-31/functions/" + fn + "/code")
        .then()
            .statusCode(200).extract().path("CodeSha256");
        assertNotEquals(publishedHash, latestHash);

        given().when().get("/2015-03-31/functions/" + fn + "?Qualifier=1")
            .then().statusCode(200).body("Configuration.Version", equalTo("1"))
            .body("Configuration.CodeSha256", equalTo(publishedHash));

        given().when().get("/2015-03-31/functions/" + fn + "/configuration?Qualifier=1")
            .then().statusCode(200).body("Version", equalTo("1"))
            .body("CodeSha256", equalTo(publishedHash));

        // Unqualified and an explicit $LATEST both still mean $LATEST.
        given().when().get("/2015-03-31/functions/" + fn)
            .then().statusCode(200).body("Configuration.Version", equalTo("$LATEST"))
            .body("Configuration.CodeSha256", equalTo(latestHash));
        given().when().get("/2015-03-31/functions/" + fn + "?Qualifier=$LATEST")
            .then().statusCode(200).body("Configuration.Version", equalTo("$LATEST"))
            .body("Configuration.CodeSha256", equalTo(latestHash));
    }

    @Test
    void aQualifierThatDoesNotResolveIsNotFound() throws Exception {
        String fn = "qual-404-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");

        // A version number that was never published, and a name that is not an alias. Answering
        // 200 with $LATEST is what let a typo run the wrong code.
        given().when().get("/2015-03-31/functions/" + fn + "?Qualifier=99")
            .then().statusCode(404);
        given().when().get("/2015-03-31/functions/" + fn + "/configuration?Qualifier=99")
            .then().statusCode(404);
        given().when().get("/2015-03-31/functions/" + fn + "?Qualifier=notaversion")
            .then().statusCode(404);
    }

    @Test
    void aQualifierMayBeCarriedOnTheNameAndMustAgreeWithAnExplicitOne() throws Exception {
        String fn = "qual-name-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");
        given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201);

        given().when().get("/2015-03-31/functions/" + fn + ":1")
            .then().statusCode(200).body("Configuration.Version", equalTo("1"));

        // Agreeing is fine; disagreeing is a client error rather than a silent pick of one.
        given().when().get("/2015-03-31/functions/" + fn + ":1?Qualifier=1")
            .then().statusCode(200).body("Configuration.Version", equalTo("1"));
        given().when().get("/2015-03-31/functions/" + fn + ":1?Qualifier=2")
            .then().statusCode(400);
    }

    @Test
    void aWeightedAliasReadsDeterministicallyAsItsPrimaryVersion() throws Exception {
        // An alias with AdditionalVersionWeights shifts traffic, so the invoke path picks among the
        // weighted versions at random. That is right for running the function and wrong for
        // describing it: two reads of one alias must not disagree. AWS reports the alias's primary
        // FunctionVersion, so a 50/50 split must still read as version 1 every time.
        String fn = "qual-alias-" + Long.toString(System.nanoTime(), 36);
        createFunction(fn, "v1");
        given().contentType("application/json").body("{}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201).body("Version", equalTo("1"));

        given()
            .contentType("application/json")
            .body("{\"ZipFile\": \"%s\"}".formatted(zipB64("v2")))
        .when().put("/2015-03-31/functions/" + fn + "/code").then().statusCode(200);
        given().contentType("application/json").body("{\"Description\": \"second\"}")
        .when().post("/2015-03-31/functions/" + fn + "/versions")
        .then().statusCode(201).body("Version", equalTo("2"));

        given()
            .contentType("application/json")
            .body("""
                {"Name": "live", "FunctionVersion": "1",
                 "RoutingConfig": {"AdditionalVersionWeights": {"2": 0.5}}}
                """)
        .when().post("/2015-03-31/functions/" + fn + "/aliases")
        .then().statusCode(Matchers.anyOf(equalTo(200), equalTo(201)));

        // Repeat enough that a random pick would almost certainly show version 2 at least once.
        for (int i = 0; i < 20; i++) {
            given().when().get("/2015-03-31/functions/" + fn + "?Qualifier=live")
                .then().statusCode(200).body("Configuration.Version", equalTo("1"));
            given().when().get("/2015-03-31/functions/" + fn + "/configuration?Qualifier=live")
                .then().statusCode(200).body("Version", equalTo("1"));
        }
    }
}

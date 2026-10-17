package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class LambdaS3DeploymentIntegrationTest {

    @Test
    void reactiveS3SyncPreservesPublishedVersions() throws Exception {
        String name = "s3-deployment-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "function.zip";
        String functionPath = "/2015-03-31/functions/" + name;
        given().when().put("/" + name).then().statusCode(200);
        try {
            given().body(makeZip("v1")).when().put("/" + name + "/" + key).then().statusCode(200);
            String codeHash = given().contentType("application/json").body(Map.of(
                            "FunctionName", name, "Runtime", "nodejs20.x",
                            "Role", "arn:aws:iam::000000000000:role/role",
                            "Handler", "index.handler", "Timeout", 30,
                            "Code", Map.of("S3Bucket", name, "S3Key", key)))
                    .when().post("/2015-03-31/functions").then().statusCode(201)
                    .extract().path("CodeSha256");
            try {
                String version = given().contentType("application/json").body("{}")
                        .when().post(functionPath + "/versions").then().statusCode(201)
                        .body("CodeSha256", equalTo(codeHash)).extract().path("Version");
                given().body("{}").when().post(functionPath + "/invocations")
                        .then().statusCode(200).body("body", equalTo("v1"));

                given().body(makeZip("v2")).when().put("/" + name + "/" + key).then().statusCode(200);
                given().body("{}").when().post(functionPath + "/invocations")
                        .then().statusCode(200).body("body", equalTo("v2"));
                given().queryParam("Qualifier", version).body("{}")
                        .when().post(functionPath + "/invocations")
                        .then().statusCode(200).body("body", equalTo("v1"));
            } finally {
                given().when().delete(functionPath).then().statusCode(204);
            }
        } finally {
            given().when().delete("/" + name + "/" + key).then().statusCode(204);
            given().when().delete("/" + name).then().statusCode(204);
        }
    }

    private static byte[] makeZip(String body) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(("exports.handler = async () => ({ body: '" + body + "' });")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}

package io.floci.aws.services.s3;

import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3InternalLambdaCodeDownloadIntegrationTest {

    private static final String REGION = "us-east-2";
    private static final String TASKS_BUCKET = "awslambda-us-east-2-tasks";

    @Inject
    S3Service s3;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void bothAccountsDownloadTheirFunctionAndLayerCodeWithIamAndSignatureEnforcement() throws Exception {
        String name = "owned-code-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, byte[]> packages = new LinkedHashMap<>();
        for (String account : List.of("481111110001", "481111110002")) {
            byte[] archive = archive(account);
            packages.put(account, archive);
            String authorization = EnforcementFixtures.auth(account, REGION, "lambda");
            cleanup.register(() -> RequestScopes.runAs(account, REGION, () -> {
                if (s3.ownsBucket(TASKS_BUCKET)) {
                    s3.deleteBucket(TASKS_BUCKET);
                }
                assertFalse(s3.ownsBucket(TASKS_BUCKET));
            }));
            cleanup.register(() -> given().header("Authorization", authorization)
                    .delete("/2015-03-31/functions/" + name));
            given().header("Authorization", authorization).contentType("application/json")
                    .body(Map.of("FunctionName", name, "Runtime", "nodejs20.x",
                            "Role", "arn:aws:iam::" + account + ":role/local-role", "Handler", "index.handler",
                            "Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(archive))))
                    .post("/2015-03-31/functions").then().statusCode(201);
            cleanup.register(() -> given().header("Authorization", authorization)
                    .delete("/2018-10-31/layers/" + name + "/versions/1"));
            given().header("Authorization", authorization).contentType("application/json")
                    .body(Map.of("Content", Map.of("ZipFile", Base64.getEncoder().encodeToString(archive))))
                    .post("/2018-10-31/layers/" + name + "/versions").then().statusCode(201);
        }
        for (Map.Entry<String, byte[]> entry : packages.entrySet()) {
            String account = entry.getKey();
            String authorization = EnforcementFixtures.auth(account, REGION, "lambda");
            String functionLocation = given().header("Authorization", authorization)
                    .get("/2015-03-31/functions/" + name).then().statusCode(200)
                    .extract().path("Code.Location");
            String layerLocation = given().header("Authorization", authorization)
                    .get("/2018-10-31/layers/" + name + "/versions/1").then().statusCode(200)
                    .extract().path("Content.Location");
            for (String location : List.of(functionLocation, layerLocation)) {
                URI uri = URI.create(location);
                assertArrayEquals(entry.getValue(), given().urlEncodingEnabled(false).get(location)
                        .then().statusCode(200).extract().asByteArray());
                given().get(uri.getRawPath()).then().statusCode(403);
                given().urlEncodingEnabled(false)
                        .get(location.replaceFirst("X-Amz-Signature=[0-9a-f]+",
                                "X-Amz-Signature=" + "0".repeat(64)))
                        .then().statusCode(403).body(containsString("SignatureDoesNotMatch"));
            }
        }
    }

    private static byte[] archive(String marker) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(("exports.handler = async () => '" + marker + "';").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}

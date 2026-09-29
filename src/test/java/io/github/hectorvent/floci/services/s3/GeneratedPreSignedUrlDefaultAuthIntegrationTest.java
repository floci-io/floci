package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GeneratedPreSignedUrlDefaultAuthIntegrationTest {

    @Inject
    PreSignedUrlGenerator generator;

    @Test
    void generatedUrlStillVerifiesItsSignatureWhenGlobalSignatureChecksAreDisabled() {
        String bucket = "generated-default-auth-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("original").when().put("/" + bucket + "/key").then().statusCode(200);

        URI uri = URI.create(generator.generatePresignedUrl(
                "http://localhost:" + RestAssured.port, bucket, "key", "GET", 60));
        String pathAndQuery = uri.getRawPath() + "?" + uri.getRawQuery();
        given().urlEncodingEnabled(false).when().get(pathAndQuery)
                .then().statusCode(200).body(equalTo("original"));

        String tampered = pathAndQuery.replaceFirst("X-Amz-Signature=[0-9a-f]+",
                "X-Amz-Signature=" + "0".repeat(64));
        given().urlEncodingEnabled(false).when().get(tampered)
                .then().statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));

        given().when().delete("/" + bucket + "/key").then().statusCode(204);
        given().when().delete("/" + bucket).then().statusCode(204);
    }
}

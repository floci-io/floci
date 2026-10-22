package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * GetObjectAttributes lists at most 1,000 parts, as ListParts does, however many {@code x-amz-max-parts}
 * asks for. A composite object with more than 1,000 parts needs 5 GB of parts, so the test checks the
 * limit the request hands the service.
 */
@QuarkusTest
class S3GetObjectAttributesMaxPartsIntegrationTest {

    @InjectSpy
    S3Service s3Service;

    @Test
    void maxPartsAboveOneThousandIsLimitedToOneThousand() {
        String bucket = "attributes-max-parts-" + UUID.randomUUID().toString().substring(0, 8);
        given().when().put("/" + bucket).then().statusCode(200);
        given().body("data").when().put("/" + bucket + "/k.txt").then().statusCode(200);
        try {
            given()
                .header("x-amz-object-attributes", "ObjectParts")
                .header("x-amz-max-parts", 2000)
            .when()
                .get("/" + bucket + "/k.txt?attributes")
            .then()
                .statusCode(200);

            verify(s3Service).getObjectAttributes(eq(bucket), eq("k.txt"), isNull(), anySet(), eq(1000), isNull());
        } finally {
            given().when().delete("/" + bucket + "/k.txt");
            given().when().delete("/" + bucket);
        }
    }
}

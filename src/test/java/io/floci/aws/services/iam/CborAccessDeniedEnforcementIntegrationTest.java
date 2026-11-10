package io.floci.aws.services.iam;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With enforcement on, a CBOR call the caller is not allowed to make is refused in CBOR. It used
 * to be refused with a JSON body, which the CBOR client that sent the call cannot read.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CborAccessDeniedEnforcementIntegrationTest {

    /**
     * The teardown runs after Quarkus has reset the port RestAssured points at, so it has to say
     * where the application is. The test port is random, which leaves this as the only place to
     * read it from.
     */
    @TestHTTPResource("/")
    static URI baseUri;

    /**
     * A QuarkusTest shares one application with every other test class, so the user and key this
     * test needs would otherwise outlive it and be visible to anything that lists users unscoped.
     * Registered before the create runs, and the registered call reads the key back off the user
     * rather than being told it, so a failure between the two still takes both away.
     */
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aCborCallTheCallerIsNotAllowedIsRefusedInCbor() {
        String userName = "cbor-denied-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth("test", "iam"), userName).accessKeyId();

        byte[] body = given()
                .contentType("application/x-amz-cbor-1.1")
                .header("Authorization", auth(accessKeyId, "kinesis"))
                .header("X-Amz-Target", "Kinesis_20131202.ListStreams")
                .body(new byte[] {(byte) 0xa0})
        .when()
                .post("/")
        .then()
                .statusCode(403)
                .contentType("application/x-amz-cbor-1.1")
                .extract().asByteArray();

        assertTrue(new String(body, StandardCharsets.ISO_8859_1).contains("AccessDeniedException"),
                "the refusal must arrive CBOR-encoded, not as JSON");
    }

}

package io.floci.aws.services.kms;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.InvalidMarkerException;
import software.amazon.awssdk.services.kms.model.ListKeyRotationsResponse;
import software.amazon.awssdk.services.kms.model.RotationsListEntry;

import java.net.URI;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class KmsKeyRotationsIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sdkReadsRotationDatesAndPaginatesWithoutBreakingDecryption() {
        try (KmsClient kms = client()) {
            String keyId = kms.createKey().keyMetadata().keyId();
            try {
                assertTrue(kms.listKeyRotations(b -> b.keyId(keyId)).rotations().isEmpty());
                SdkBytes plaintext = SdkBytes.fromUtf8String("encrypted before rotation");
                SdkBytes ciphertext = kms.encrypt(b -> b.keyId(keyId).plaintext(plaintext)).ciphertextBlob();
                Instant before = Instant.now().minusSeconds(1);
                kms.rotateKeyOnDemand(b -> b.keyId(keyId));
                kms.rotateKeyOnDemand(b -> b.keyId(keyId));

                ListKeyRotationsResponse first = kms.listKeyRotations(b -> b.keyId(keyId).limit(1));
                assertTrue(first.truncated());
                assertEquals(1, first.rotations().size());
                assertNotNull(first.nextMarker());
                ListKeyRotationsResponse second = kms.listKeyRotations(
                        b -> b.keyId(keyId).marker(first.nextMarker()).limit(1));
                assertFalse(second.truncated());
                assertNull(second.nextMarker());
                Set<String> materials = new HashSet<>();
                for (RotationsListEntry entry : List.of(first.rotations().getFirst(), second.rotations().getFirst())) {
                    assertTrue(materials.add(entry.keyMaterialId()));
                    assertEquals(keyId, entry.keyId());
                    assertEquals("ON_DEMAND", entry.rotationTypeAsString());
                    assertNotNull(entry.rotationDate());
                    assertTrue(entry.rotationDate().isAfter(before));
                    assertTrue(entry.rotationDate().isBefore(Instant.now().plusSeconds(1)));
                }
                ListKeyRotationsResponse all = kms.listKeyRotations(
                        b -> b.keyId(keyId).includeKeyMaterial("ALL_KEY_MATERIAL"));
                assertEquals(3, all.rotations().size());
                String arn = kms.describeKey(b -> b.keyId(keyId)).keyMetadata().arn();
                for (String identifier : List.of(keyId, arn)) {
                    assertTrue(kms.listKeyRotations(b -> b.keyId(identifier).includeKeyMaterial("ALL_KEY_MATERIAL"))
                            .rotations().stream().allMatch(entry -> keyId.equals(entry.keyId())));
                }
                assertEquals(1, all.rotations().stream().filter(r -> "CURRENT".equals(r.keyMaterialStateAsString())).count());
                assertEquals(1, all.rotations().stream().filter(r -> r.rotationDate() == null).count());
                assertNull(all.rotations().getFirst().rotationType());
                assertEquals(plaintext, kms.decrypt(b -> b.ciphertextBlob(ciphertext)).plaintext());
                assertThrows(InvalidMarkerException.class, () -> kms.listKeyRotations(
                        b -> b.keyId(keyId).includeKeyMaterial("ALL_KEY_MATERIAL").marker(first.nextMarker())));
            } finally {
                kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"KeyId\":\"\"}",
        "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"Limit\":0}",
        "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"Limit\":1001}",
        "{\"KeyId\":\"00000000-0000-0000-0000-000000000000\",\"IncludeKeyMaterial\":\"invalid\"}"
    })
    void malformedRequestsReturnAwsValidationErrors(String request) {
        listRotations(request)
                .then().statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void aliasesAreRejectedForKeyOnlyListing() {
        listRotations("{\"KeyId\":\"alias/rotation-history\"}")
                .then().statusCode(400).body("__type", equalTo("InvalidArnException"));
    }

    private Response listRotations(String request) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "TrentService.ListKeyRotations")
                .body(request).post("/");
    }

    private KmsClient client() {
        return AwsTestClients.create(KmsClient.builder(), "test", Region.US_EAST_1, endpoint);
    }
}

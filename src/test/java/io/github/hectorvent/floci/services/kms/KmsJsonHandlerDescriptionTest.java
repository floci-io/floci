package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kms.model.KmsKey;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * github.com/floci-io/floci/issues/4387: AWS defaults CreateKey's Description to "" (never
 * null), but Floci's KeyMetadata response echoed a null description as JSON null, which a
 * client modeling the field as a non-optional string rejects.
 *
 * <p>The write paths ({@code KmsService.createKey}/{@code updateKeyDescription}) now store ""
 * instead of null, but a key persisted by a pre-fix build may still carry a stored null, and
 * DescribeKey reads that stored value directly. This exercises {@link KmsJsonHandler} against a
 * key store seeded with exactly that pre-fix state, bypassing KmsService's write path entirely,
 * to prove the response itself is defensive regardless of what is on disk.</p>
 */
class KmsJsonHandlerDescriptionTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InMemoryStorage<String, KmsKey> keyStore;
    private KmsJsonHandler handler;

    @BeforeEach
    void setUp() {
        keyStore = new InMemoryStorage<>();
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        KmsService kmsService = new KmsService(keyStore, new InMemoryStorage<>(),
                new InMemoryStorage<>(), regionResolver);
        handler = new KmsJsonHandler(kmsService, objectMapper, regionResolver);
    }

    @Test
    void describeKey_preFixPersistedNullDescription_isReportedAsEmptyString() {
        KmsKey preFixKey = new KmsKey();
        preFixKey.setKeyId("pre-fix-key");
        preFixKey.setArn("arn:aws:kms:" + REGION + ":" + ACCOUNT + ":key/pre-fix-key");
        preFixKey.setDescription(null);
        keyStore.put(REGION + "::pre-fix-key", preFixKey);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("KeyId", "pre-fix-key");

        Response response = handler.handle("DescribeKey", request, REGION);

        assertEquals(200, response.getStatus());
        JsonNode body = objectMapper.valueToTree(response.getEntity());
        JsonNode description = body.path("KeyMetadata").path("Description");
        assertTrue(description.isTextual(),
                "Description must be a JSON string, not null, but was: " + description);
        assertEquals("", description.asText());
    }
}

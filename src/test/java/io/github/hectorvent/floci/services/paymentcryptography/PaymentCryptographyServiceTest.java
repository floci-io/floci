package io.github.hectorvent.floci.services.paymentcryptography;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyAlias;
import io.github.hectorvent.floci.services.paymentcryptography.model.PaymentCryptographyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentCryptographyServiceTest {

    private PaymentCryptographyService service;

    @BeforeEach
    void setUp() {
        service = new PaymentCryptographyService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"));
    }

    private PaymentCryptographyKey createKey(String region) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("KeyAlgorithm", "AES_256");
        attributes.put("KeyClass", "SYMMETRIC_KEY");
        attributes.put("KeyUsage", "TR31_D0_SYMMETRIC_DATA_ENCRYPTION_KEY");
        return service.createKey(region, attributes, true);
    }

    @Test
    void aCreatedKeyIsInItsRegionAndCarriesMaterial() {
        PaymentCryptographyKey key = createKey("us-east-1");

        assertTrue(key.getArn().startsWith("arn:aws:payment-cryptography:us-east-1:000000000000:key/"),
                key.getArn());
        assertEquals("CREATE_COMPLETE", key.getKeyState());
        assertEquals(64, key.getKeyMaterialHex().length());
        assertNotNull(key.getKeyCheckValue());
        assertEquals(6, key.getKeyCheckValue().length());
    }

    @Test
    void theCheckValueIsTheLeftmostThreeBytesOfTheCmac() {
        PaymentCryptographyKey key = createKey("us-east-1");

        // The CMAC of sixteen zero bytes under the key, leftmost three bytes.
        assertEquals(PaymentCryptographyCrypto.checkValue(key.getKeyMaterialHex()),
                key.getKeyCheckValue());
    }

    @Test
    void twoKeysGetDifferentMaterial() {
        PaymentCryptographyKey first = createKey("us-east-1");
        PaymentCryptographyKey second = createKey("us-east-1");

        assertTrue(!first.getKeyMaterialHex().equals(second.getKeyMaterialHex()),
                "two keys share their material");
        assertTrue(!first.getKeyCheckValue().equals(second.getKeyCheckValue()),
                "two keys share their check value");
    }

    @Test
    void theAliasNameIsUniqueInARegion() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createAlias("alias/team/card-key", createKey("us-east-1")));
        assertEquals("ConflictException", conflict.getErrorCode());
    }

    @Test
    void theSameAliasNameInAnotherRegionIsAnotherAlias() {
        service.createAlias("alias/team/card-key", createKey("us-east-1"));
        PaymentCryptographyAlias second = service.createAlias("alias/team/card-key",
                createKey("us-west-2"));

        assertEquals("us-west-2", second.getRegion());
        assertEquals(1, service.listAliases("us-east-1", null).size());
        assertEquals(1, service.listAliases("us-west-2", null).size());
    }

    @Test
    void theTagsAreTheKeysOwn() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.tag(key, Map.of("Origin", "PRIMARY"));

        assertEquals("PRIMARY", service.listTags(key).get("Origin"));
        assertEquals(1, service.listTags(key).size());

        service.untag(key, List.of("Origin"));
        assertTrue(service.listTags(key).isEmpty());
    }

    @Test
    void aKeyIsFoundByItsArnAndByItsAlias() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);

        assertEquals(key.getKeyId(), service.findKey("us-east-1", key.getArn()).getKeyId());
        assertEquals(key.getKeyId(),
                service.findKey("us-east-1", "alias/team/card-key").getKeyId());
        assertNull(service.findKey("us-west-2", "alias/team/card-key"),
                "an alias of one region is not an alias of another");
    }

    @Test
    void aDeletedKeyTakesItsAliasesWithIt() {
        PaymentCryptographyKey key = createKey("us-east-1");
        service.createAlias("alias/team/card-key", key);
        service.deleteKey(key);

        assertNull(service.findKey("us-east-1", key.getArn()));
        assertNull(service.findAlias("us-east-1", "alias/team/card-key"));
    }
}

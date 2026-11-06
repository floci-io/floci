package io.floci.aws.services.route53resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tagging behaviour that depends on storage state the public API cannot produce: a
 * pre-multi-account (unprefixed) resource entry, and reads of the shared tag store.
 */
class Route53ResolverTaggingStorageTest {

    private static final String REGION = "us-east-1";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OWNER = "111111111111";
    private static final String OTHER = "222222222222";
    private static final String ID = "rslvr-out-legacy0000001";
    private static final String ARN =
            "arn:aws:route53resolver:us-east-1:" + OWNER + ":resolver-endpoint/" + ID;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Route53ResolverService service(InMemoryStorage<String, ObjectNode> endpointDelegate,
                                           InMemoryStorage<String, ObjectNode> tagStore) {
        return new Route53ResolverService(new InMemoryStorage<>(),
                new AccountAwareStorageBackend<>(endpointDelegate, null, DEFAULT_ACCOUNT),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), tagStore,
                objectMapper);
    }

    private InMemoryStorage<String, ObjectNode> legacyEndpointStore() {
        InMemoryStorage<String, ObjectNode> delegate = new InMemoryStorage<>();
        delegate.put(ID, objectMapper.createObjectNode().put("Id", ID).put("Arn", ARN));
        return delegate;
    }

    private ArrayNode tags(String key, String value) {
        ArrayNode tags = objectMapper.createArrayNode();
        tags.addObject().put("Key", key).put("Value", value);
        return tags;
    }

    @Test
    void legacyResource_isNotClaimedByAnotherAccountNamingItsArn() {
        InMemoryStorage<String, ObjectNode> delegate = legacyEndpointStore();
        Route53ResolverService service = service(delegate, new InMemoryStorage<>());

        AwsException tag = assertThrows(AwsException.class,
                () -> service.tagResource(ARN, tags("k", "v"), REGION, OTHER));
        assertEquals("ResourceNotFoundException", tag.getErrorCode());
        AwsException list = assertThrows(AwsException.class,
                () -> service.listTagsForResource(ARN, null, null, REGION, OTHER));
        assertEquals("ResourceNotFoundException", list.getErrorCode());
        // Naming the same id under its own account must not drag the legacy entry over either.
        AwsException ownArn = assertThrows(AwsException.class,
                () -> service.listTagsForResource(ARN.replace(OWNER, OTHER), null, null, REGION, OTHER));
        assertEquals("ResourceNotFoundException", ownArn.getErrorCode());

        // The legacy entry stays where it was; no other account's partition gained it.
        assertTrue(delegate.get(ID).isPresent());
        assertEquals(1, delegate.keys().size(), delegate.keys().toString());

        // Its real owner can still tag it, which migrates it into the owner's partition.
        service.tagResource(ARN, tags("k", "v"), REGION, OWNER);
        assertEquals("v", service.listTagsForResource(ARN, null, null, REGION, OWNER)
                .path("Tags").path(0).path("Value").asText());
        assertTrue(delegate.get(OWNER + "/" + ID).isPresent());
        assertFalse(delegate.get(ID).isPresent());
    }

    @Test
    void listTags_readsTheTagStoreUnderTheMutatorsMonitor() {
        AtomicReference<Route53ResolverService> self = new AtomicReference<>();
        AtomicBoolean unlockedRead = new AtomicBoolean();
        InMemoryStorage<String, ObjectNode> tagStore = new InMemoryStorage<>() {
            @Override
            public Optional<ObjectNode> get(String key) {
                if (!Thread.holdsLock(self.get())) {
                    unlockedRead.set(true);
                }
                return super.get(key);
            }
        };
        Route53ResolverService service = service(legacyEndpointStore(), tagStore);
        self.set(service);

        service.tagResource(ARN, tags("k", "v"), REGION, OWNER);
        service.listTagsForResource(ARN, null, null, REGION, OWNER);

        assertFalse(unlockedRead.get(), "ListTagsForResource read the live tag node outside the monitor");
    }
}

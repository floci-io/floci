package io.floci.aws.services.cloudfront;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.model.KeyValueStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class CloudFrontKeyValueStoreServiceTest {

    private static final String ACCOUNT = "000000000000";

    private CloudFrontKeyValueStoreService newService() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        return new CloudFrontKeyValueStoreService(storageFactory, new RegionResolver("us-east-1", ACCOUNT));
    }

    private static void assertError(String code, int status, Runnable action) {
        AwsException e = assertThrows(AwsException.class, action::run);
        assertEquals(code, e.getErrorCode());
        assertEquals(status, e.getHttpStatus());
    }

    @Test
    void createStoresAReadyStoreWithAnArnAndAnEtag() {
        CloudFrontKeyValueStoreService service = newService();

        KeyValueStore created = service.create("tenants", "tenant to version");

        assertEquals("tenants", created.getName());
        assertEquals("READY", created.getStatus());
        assertEquals("tenant to version", created.getComment());
        assertEquals("arn:aws:cloudfront::" + ACCOUNT + ":key-value-store/" + created.getId(), created.getArn());
        assertTrue(created.getItems().isEmpty());
        assertEquals(created.getArn(), service.findByArn(created.getArn()).orElseThrow().getArn());
        assertEquals(created.getEtag(), service.describe("tenants").getEtag());
    }

    @Test
    void createRejectsAnInvalidNameADuplicateAndAnOverlongComment() {
        CloudFrontKeyValueStoreService service = newService();
        service.create("tenants", null);

        assertError("EntityAlreadyExists", 409, () -> service.create("tenants", null));
        assertError("InvalidArgument", 400, () -> service.create("bad name!", null));
        assertError("InvalidArgument", 400, () -> service.create("", null));
        assertError("InvalidArgument", 400, () -> service.create("a".repeat(65), null));
        assertError("InvalidArgument", 400, () -> service.create("commented", "c".repeat(129)));
    }

    @Test
    void describeOfAnUnknownStoreIsEntityNotFound() {
        assertError("EntityNotFound", 404, () -> newService().describe("missing"));
    }

    @Test
    void updateNeedsTheCurrentEtagAndRotatesIt() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", "one");
        String firstEtag = created.getEtag();

        assertError("InvalidIfMatchVersion", 400, () -> service.update("tenants", "stale", "two"));
        assertError("InvalidIfMatchVersion", 400, () -> service.update("tenants", null, "two"));

        KeyValueStore updated = service.update("tenants", firstEtag, "two");

        assertEquals("two", updated.getComment());
        assertNotEquals(firstEtag, updated.getEtag());
    }

    @Test
    void deleteNeedsTheCurrentEtag() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);

        assertError("InvalidIfMatchVersion", 400, () -> service.delete("tenants", "stale"));
        service.delete("tenants", created.getEtag());

        assertError("EntityNotFound", 404, () -> service.describe("tenants"));
    }

    @Test
    void listIsOrderedByNameAndPagesWithAMarker() {
        CloudFrontKeyValueStoreService service = newService();
        service.create("gamma", null);
        service.create("alpha", null);
        service.create("beta", null);

        List<KeyValueStore> firstPage = service.list(null, 2, null);
        List<KeyValueStore> secondPage = service.list("beta", 2, null);

        assertEquals(List.of("alpha", "beta"), firstPage.stream().map(KeyValueStore::getName).toList());
        assertEquals(List.of("gamma"), secondPage.stream().map(KeyValueStore::getName).toList());
    }

    @Test
    void putKeyThenGetKeyRoundTripsAndCountsBytes() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);

        KeyValueStore written = service.putKey(created.getArn(), created.getDataEtag(), "demo.example.com", "3-5-19-1234");

        assertEquals("3-5-19-1234", service.getKey(created.getArn(), "demo.example.com").value());
        assertEquals(1, CloudFrontKeyValueStoreService.itemCount(written));
        assertEquals("demo.example.com".length() + "3-5-19-1234".length(),
                CloudFrontKeyValueStoreService.totalSizeInBytes(written));
        assertNotEquals(created.getDataEtag(), written.getDataEtag());
    }

    @Test
    void sizeIsCountedInUtf8Bytes() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);

        KeyValueStore written = service.putKey(created.getArn(), created.getDataEtag(), "k", "é");

        assertEquals(1 + 2, CloudFrontKeyValueStoreService.totalSizeInBytes(written));
    }

    @Test
    void aDataPlaneWriteWithAStaleOrMissingEtagIsRejected() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);
        String arn = created.getArn();
        service.putKey(arn, created.getDataEtag(), "a", "1");

        assertError("ValidationException", 400, () -> service.putKey(arn, created.getDataEtag(), "b", "2"));
        assertError("ValidationException", 400, () -> service.putKey(arn, null, "b", "2"));
        assertNull(service.describeByArn(arn).getItems().get("b"));
    }

    @Test
    void theControlPlaneAndDataPlaneKeepIndependentEtags() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", "one");

        KeyValueStore updated = service.update("tenants", created.getEtag(), "two");
        assertNotEquals(created.getEtag(), updated.getEtag());
        assertEquals(created.getDataEtag(), updated.getDataEtag());
        KeyValueStore written = service.putKey(created.getArn(), created.getDataEtag(), "a", "1");
        assertEquals(updated.getEtag(), written.getEtag());
        assertNotEquals(created.getDataEtag(), written.getDataEtag());

        service.delete("tenants", updated.getEtag());
    }

    @Test
    void createUsesTheRequestAccountInTheArn() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        RegionResolver resolver = Mockito.spy(new RegionResolver("us-east-1", ACCOUNT));
        when(resolver.getAccountId()).thenReturn("111122223333");
        CloudFrontKeyValueStoreService service = new CloudFrontKeyValueStoreService(storageFactory, resolver);

        KeyValueStore created = service.create("tenants", null);

        assertTrue(created.getArn().startsWith("arn:aws:cloudfront::111122223333:key-value-store/"));
    }

    @Test
    void createRejectsPunctuationAndStopsAtTwoHundredStores() {
        CloudFrontKeyValueStoreService service = newService();
        assertError("InvalidArgument", 400, () -> service.create("a?b", null));
        assertError("InvalidArgument", 400, () -> service.create("a\\b", null));
        assertError("InvalidArgument", 400, () -> service.create("a:b", null));

        for (int i = 0; i < CloudFrontKeyValueStoreService.MAX_STORES; i++) {
            service.create("store-" + i, null);
        }

        assertError("EntityLimitExceeded", 400, () -> service.create("one-too-many", null));
    }

    @Test
    void anUnknownStoreArnIsResourceNotFoundOnTheDataPlane() {
        CloudFrontKeyValueStoreService service = newService();
        String arn = "arn:aws:cloudfront::" + ACCOUNT + ":key-value-store/00000000-0000-0000-0000-000000000000";

        assertError("ResourceNotFoundException", 404, () -> service.describeByArn(arn));
        assertError("ResourceNotFoundException", 404, () -> service.getKey(arn, "a"));
        assertError("ResourceNotFoundException", 404, () -> service.putKey(arn, "etag", "a", "1"));
    }

    @Test
    void aMissingKeyIsResourceNotFound() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);

        assertError("ResourceNotFoundException", 404, () -> service.getKey(created.getArn(), "missing"));
        assertError("ResourceNotFoundException", 404,
                () -> service.deleteKey(created.getArn(), created.getDataEtag(), "missing"));
    }

    @Test
    void keyAndValueLimitsAreEnforcedInBytes() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);
        String arn = created.getArn();
        String etag = created.getDataEtag();

        assertError("ValidationException", 400, () -> service.putKey(arn, etag, "k".repeat(513), "v"));
        assertError("ValidationException", 400, () -> service.putKey(arn, etag, "k", "v".repeat(1025)));
        assertError("ValidationException", 400, () -> service.putKey(arn, etag, "", "v"));

        KeyValueStore atTheLimit = service.putKey(arn, etag, "k".repeat(512), "v".repeat(1024));
        assertEquals(1, CloudFrontKeyValueStoreService.itemCount(atTheLimit));
    }

    @Test
    void aStoreCannotGrowPastFiveMebibytes() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);
        Map<String, String> puts = new LinkedHashMap<>();
        for (int i = 0; i < 5200; i++) {
            puts.put("key-%05d".formatted(i), "v".repeat(1024));
        }

        assertError("ServiceQuotaExceededException", 402,
                () -> service.updateKeys(created.getArn(), created.getDataEtag(), puts, List.of()));
        assertTrue(service.describeByArn(created.getArn()).getItems().isEmpty());
    }

    @Test
    void aBatchIsAtomicWhenOneEntryIsInvalid() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);
        Map<String, String> puts = new LinkedHashMap<>();
        puts.put("good", "1");
        puts.put("bad", "v".repeat(1025));

        assertError("ValidationException", 400,
                () -> service.updateKeys(created.getArn(), created.getDataEtag(), puts, List.of()));

        KeyValueStore unchanged = service.describeByArn(created.getArn());
        assertTrue(unchanged.getItems().isEmpty());
        assertEquals(created.getDataEtag(), unchanged.getDataEtag());
    }

    @Test
    void aBatchAppliesPutsAndDeletesTogetherAndSkipsDeletesOfAbsentKeys() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);
        KeyValueStore first = service.updateKeys(created.getArn(), created.getDataEtag(),
                Map.of("a", "1", "b", "2"), List.of());

        KeyValueStore second = service.updateKeys(created.getArn(), first.getDataEtag(),
                Map.of("c", "3"), List.of("a", "never-existed"));

        assertEquals(List.of("b", "c"), new ArrayList<>(new TreeMap<>(second.getItems()).keySet()));
        assertNotEquals(first.getDataEtag(), second.getDataEtag());
    }

    @Test
    void listKeysIsSortedAndPagesWithAToken() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);
        Map<String, String> puts = new LinkedHashMap<>();
        for (String key : List.of("echo", "alpha", "delta", "bravo", "charlie")) {
            puts.put(key, key.toUpperCase());
        }
        service.updateKeys(created.getArn(), created.getDataEtag(), puts, List.of());

        CloudFrontKeyValueStoreService.KeyPage first = service.listKeys(created.getArn(), null, 2);
        CloudFrontKeyValueStoreService.KeyPage second = service.listKeys(created.getArn(), first.nextToken(), 2);
        CloudFrontKeyValueStoreService.KeyPage third = service.listKeys(created.getArn(), second.nextToken(), 2);

        assertEquals(List.of("alpha", "bravo"), first.items().stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("charlie", "delta"), second.items().stream().map(Map.Entry::getKey).toList());
        assertEquals(List.of("echo"), third.items().stream().map(Map.Entry::getKey).toList());
        assertNull(third.nextToken());
    }

    @Test
    void listKeysValidatesMaxResultsAndTheToken() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);

        assertError("ValidationException", 400, () -> service.listKeys(created.getArn(), null, 0));
        assertError("ValidationException", 400, () -> service.listKeys(created.getArn(), null, 51));
        assertError("ValidationException", 400, () -> service.listKeys(created.getArn(), "!!not base64!!", 10));
        assertEquals(10, CloudFrontKeyValueStoreService.DEFAULT_LIST_KEYS);
    }

    @Test
    void listResumesAfterAMarkerStoreThatWasDeleted() {
        CloudFrontKeyValueStoreService service = newService();
        service.create("a", null);
        KeyValueStore b = service.create("b", null);
        service.create("c", null);
        service.delete("b", b.getEtag());

        List<KeyValueStore> page = service.list("b", 10, null);

        assertEquals(List.of("c"), page.stream().map(KeyValueStore::getName).toList());
    }

    @Test
    void updateWithoutACommentIsRejectedAndKeepsTheStore() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", "keep me");

        assertError("InvalidArgument", 400, () -> service.update("tenants", created.getEtag(), null));

        KeyValueStore unchanged = service.describe("tenants");
        assertEquals("keep me", unchanged.getComment());
        assertEquals(created.getEtag(), unchanged.getEtag());
    }

    @Test
    void findResolvesAStoreNameToItsStoredArn() {
        CloudFrontKeyValueStoreService service = newService();
        KeyValueStore created = service.create("tenants", null);

        assertEquals(created.getArn(), service.find("key-value-store", List.of("tenants"), "us-east-1").orElseThrow());
        assertTrue(service.find("key-value-store", List.of("missing"), "us-east-1").isEmpty());
        assertTrue(service.find("distribution", List.of("tenants"), "us-east-1").isEmpty());
    }
    @Test
    void listFiltersByStatus() {
        CloudFrontKeyValueStoreService service = newService();
        service.create("a", null);
        service.create("b", null);

        assertEquals(2, service.list(null, 10, "READY").size());
        assertTrue(service.list(null, 10, "PENDING").isEmpty());
    }}

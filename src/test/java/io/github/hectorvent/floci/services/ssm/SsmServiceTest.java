package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class SsmServiceTest {

    private SsmService ssmService;

    @BeforeEach
    void setUp() {
        ssmService = new SsmService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                5
        );
    }

    @Test
    void describeDocumentPermissionEmptyByDefault() {
        ssmService.createDocument("MyDoc", "{}", "Command", "us-east-1");
        List<String> accountIds = ssmService.describeDocumentPermission("MyDoc", "us-east-1");
        assertTrue(accountIds.isEmpty());
    }

    @Test
    void describeDocumentPermissionUnknownDocumentThrowsInvalidDocument() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.describeDocumentPermission("NoSuchDoc", "us-east-1"));
        assertEquals("InvalidDocument", ex.getErrorCode());
    }

    @Test
    void modifyDocumentPermissionUnknownDocumentThrowsInvalidDocument() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.modifyDocumentPermission("NoSuchDoc",
                        List.of("111111111111"), List.of(), "us-east-1"));
        assertEquals("InvalidDocument", ex.getErrorCode());
    }

    @Test
    void modifyDocumentPermissionAddsAndRemovesAccounts() {
        String region = "us-east-1";
        ssmService.createDocument("ShareDoc", "{}", "Command", region);
        ssmService.modifyDocumentPermission("ShareDoc",
                List.of("111111111111", "222222222222"), List.of(), region);
        assertEquals(List.of("111111111111", "222222222222"),
                ssmService.describeDocumentPermission("ShareDoc", region));

        ssmService.modifyDocumentPermission("ShareDoc",
                List.of(), List.of("111111111111"), region);
        assertEquals(List.of("222222222222"),
                ssmService.describeDocumentPermission("ShareDoc", region));
    }

    @Test
    void modifyDocumentPermissionIsIdempotentAndRegionScoped() {
        String region = "us-east-1";
        ssmService.createDocument("Doc", "{}", "Command", region);
        ssmService.modifyDocumentPermission("Doc", List.of("333333333333"), List.of(), region);
        ssmService.modifyDocumentPermission("Doc", List.of("333333333333"), List.of(), region);
        assertEquals(List.of("333333333333"), ssmService.describeDocumentPermission("Doc", region));
        // The document itself is region-scoped, so the other region has no document to describe.
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.describeDocumentPermission("Doc", "eu-west-1"));
        assertEquals("InvalidDocument", ex.getErrorCode());
    }

    @Test
    void putAndGetParameter() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/db/host", "localhost", "String", null, false, region);
        Parameter param = ssmService.getParameter("/app/db/host", region);

        assertEquals("/app/db/host", param.getName());
        assertEquals("localhost", param.getValue());
        assertEquals("String", param.getType());
        assertEquals(1, param.getVersion());
        assertNotNull(param.getLastModifiedDate());
    }

    @Test
    void putParameterOverwrite() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "v1", "String", null, false, region);
        ssmService.putParameter("/app/key", "v2", "String", null, true, region);
        Parameter param = ssmService.getParameter("/app/key", region);

        assertEquals("v2", param.getValue());
        assertEquals(2, param.getVersion());
    }

    @Test
    void putParameterWithoutOverwriteThrows() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "v1", "String", null, false, region);
        assertThrows(AwsException.class, () ->
                ssmService.putParameter("/app/key", "v2", "String", null, false, region));
    }

    @Test
    void getParameterNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.getParameter("/nonexistent", "eu-west-1"));
        assertEquals("ParameterNotFound", ex.getErrorCode());
    }

    @Test
    void getParameters() {
        String region = "eu-west-1";
        ssmService.putParameter("/a", "1", "String", null, false, region);
        ssmService.putParameter("/b", "2", "String", null, false, region);
        ssmService.putParameter("/c", "3", "String", null, false, region);

        List<Parameter> params = ssmService.getParameters(List.of("/a", "/c", "/missing"), region);
        assertEquals(2, params.size());
    }

    @Test
    void getParametersByPathRecursive() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/db/host", "localhost", "String", null, false, region);
        ssmService.putParameter("/app/db/port", "5432", "String", null, false, region);
        ssmService.putParameter("/app/db/nested/deep", "value", "String", null, false, region);
        ssmService.putParameter("/app/cache/host", "redis", "String", null, false, region);

        List<Parameter> results = ssmService.getParametersByPath("/app/db", true, region);
        assertEquals(3, results.size());
    }

    @Test
    void getParametersByPathNonRecursive() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/db/host", "localhost", "String", null, false, region);
        ssmService.putParameter("/app/db/port", "5432", "String", null, false, region);
        ssmService.putParameter("/app/db/nested/deep", "value", "String", null, false, region);

        List<Parameter> results = ssmService.getParametersByPath("/app/db", false, region);
        assertEquals(2, results.size());
    }

    @Test
    void deleteParameter() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "value", "String", null, false, region);
        ssmService.deleteParameter("/app/key", region);
        assertThrows(AwsException.class, () -> ssmService.getParameter("/app/key", region));
    }

    @Test
    void deleteParameterNotFoundThrows() {
        assertThrows(AwsException.class, () -> ssmService.deleteParameter("/missing", "eu-west-1"));
    }

    @Test
    void deleteParameters() {
        String region = "eu-west-1";
        ssmService.putParameter("/a", "1", "String", null, false, region);
        ssmService.putParameter("/b", "2", "String", null, false, region);

        List<String> deleted = ssmService.deleteParameters(List.of("/a", "/missing"), region);
        assertEquals(1, deleted.size());
        assertEquals("/a", deleted.getFirst());
    }

    @Test
    void getParameterHistory() {
        String region = "eu-west-1";
        ssmService.putParameter("/app/key", "v1", "String", null, false, region);
        ssmService.putParameter("/app/key", "v2", "String", null, true, region);
        ssmService.putParameter("/app/key", "v3", "String", null, true, region);

        List<ParameterHistory> history = ssmService.getParameterHistory("/app/key", region);
        assertEquals(3, history.size());
        assertEquals("v1", history.get(0).getValue());
        assertEquals("v3", history.get(2).getValue());
    }

    @Test
    void parameterHistoryIsTrimmedToMax() {
        String region = "eu-west-1";
        for (int i = 1; i <= 7; i++) {
            ssmService.putParameter("/app/key", "v" + i, "String", null, i == 1 ? false : true, region);
        }

        List<ParameterHistory> history = ssmService.getParameterHistory("/app/key", region);
        assertEquals(5, history.size());
        assertEquals("v3", history.get(0).getValue());
        assertEquals("v7", history.get(4).getValue());
    }

    @Test
    void getDocumentUnknownThrowsInvalidDocument() {
        // The AWS SDK maps this code to its InvalidDocument exception class;
        // LZA's session-manager-settings Lambda branches on it to create the doc.
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.getDocument("SSM-SessionManagerRunShell", "us-east-1"));
        assertEquals("InvalidDocument", ex.getErrorCode());
    }

    @Test
    void createAndGetDocument() {
        String region = "us-east-1";
        String content = "{\"schemaVersion\":\"1.0\",\"inputs\":{\"runAsEnabled\":false}}";
        ssmService.createDocument("SSM-SessionManagerRunShell", content, "Session", region);

        SsmDocument doc = ssmService.getDocument("SSM-SessionManagerRunShell", region);
        assertEquals("SSM-SessionManagerRunShell", doc.getName());
        assertEquals(content, doc.getContent());
        assertEquals("Session", doc.getDocumentType());
        assertEquals(1, doc.getDocumentVersion());
        assertEquals("Active", doc.getStatus());
    }

    @Test
    void createDocumentTwiceThrowsAlreadyExists() {
        String region = "us-east-1";
        ssmService.createDocument("Doc", "{}", "Command", region);
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.createDocument("Doc", "{}", "Command", region));
        assertEquals("DocumentAlreadyExists", ex.getErrorCode());
    }

    @Test
    void updateDocumentBumpsVersion() {
        String region = "us-east-1";
        ssmService.createDocument("Doc", "{\"a\":1}", "Session", region);

        SsmDocument updated = ssmService.updateDocument("Doc", "{\"a\":2}", region);
        assertEquals(2, updated.getDocumentVersion());
        assertEquals("{\"a\":2}", ssmService.getDocument("Doc", region).getContent());
    }

    @Test
    void updateDocumentSameContentThrowsDuplicateDocumentContent() {
        String region = "us-east-1";
        ssmService.createDocument("Doc", "{\"a\":1}", "Session", region);
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.updateDocument("Doc", "{\"a\":1}", region));
        assertEquals("DuplicateDocumentContent", ex.getErrorCode());
    }

    @Test
    void updateDocumentUnknownThrowsInvalidDocument() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.updateDocument("Missing", "{}", "us-east-1"));
        assertEquals("InvalidDocument", ex.getErrorCode());
    }

    @Test
    void documentsAreRegionScoped() {
        ssmService.createDocument("Doc", "{}", "Session", "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.getDocument("Doc", "eu-west-1"));
        assertEquals("InvalidDocument", ex.getErrorCode());
    }

    // ── Service settings (LZA ssm-block-public-document-sharing) ──

    private static final String PUBLIC_SHARING = "/ssm/documents/console/public-sharing-permission";

    @Test
    void getServiceSettingReturnsDefaultWhenNeverCustomized() {
        ServiceSetting setting = ssmService.getServiceSetting(PUBLIC_SHARING, "us-east-1");

        assertEquals(PUBLIC_SHARING, setting.getSettingId());
        assertEquals("Enable", setting.getSettingValue());
        assertEquals("Default", setting.getStatus());
        assertEquals("arn:aws:ssm:us-east-1:000000000000:servicesetting" + PUBLIC_SHARING,
                setting.getArn());
        assertNotNull(setting.getLastModifiedDate());
        assertNotNull(setting.getLastModifiedUser());
    }

    @Test
    void updateServiceSettingCustomizesValueAndStatus() {
        ssmService.updateServiceSetting(PUBLIC_SHARING, "Disable", "us-east-1");
        ServiceSetting setting = ssmService.getServiceSetting(PUBLIC_SHARING, "us-east-1");

        assertEquals("Disable", setting.getSettingValue());
        assertEquals("Customized", setting.getStatus());
        assertNotNull(setting.getLastModifiedDate());
    }

    @Test
    void resetServiceSettingRestoresDefault() {
        ssmService.updateServiceSetting(PUBLIC_SHARING, "Disable", "us-east-1");
        ServiceSetting reset = ssmService.resetServiceSetting(PUBLIC_SHARING, "us-east-1");
        assertEquals("Enable", reset.getSettingValue());

        ServiceSetting after = ssmService.getServiceSetting(PUBLIC_SHARING, "us-east-1");
        assertEquals("Enable", after.getSettingValue());
        assertEquals("Default", after.getStatus());
    }

    @Test
    void unknownServiceSettingThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.getServiceSetting("/ssm/bogus/does-not-exist", "us-east-1"));
        assertEquals("ServiceSettingNotFound", ex.getErrorCode());
    }

    @Test
    void updateUnknownServiceSettingThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.updateServiceSetting("/ssm/bogus/does-not-exist", "x", "us-east-1"));
        assertEquals("ServiceSettingNotFound", ex.getErrorCode());
    }

    @Test
    void serviceSettingsAreRegionScoped() {
        ssmService.updateServiceSetting(PUBLIC_SHARING, "Disable", "us-east-1");

        ServiceSetting other = ssmService.getServiceSetting(PUBLIC_SHARING, "eu-west-1");
        assertEquals("Enable", other.getSettingValue());
        assertEquals("Default", other.getStatus());
    }

    @Test
    void serviceSettingsAreAccountScoped() {
        // LZA assumes a role into each member account and updates the setting
        // there; a shared store must still keep per-account values separate.
        InMemoryStorage<String, ServiceSetting> sharedSettings = new InMemoryStorage<>();
        SsmService accountA = new SsmService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                sharedSettings, 5, new RegionResolver("us-east-1", "111111111111"));
        SsmService accountB = new SsmService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                sharedSettings, 5, new RegionResolver("us-east-1", "222222222222"));

        accountA.updateServiceSetting(PUBLIC_SHARING, "Disable", "us-east-1");

        assertEquals("Disable", accountA.getServiceSetting(PUBLIC_SHARING, "us-east-1").getSettingValue());
        ServiceSetting b = accountB.getServiceSetting(PUBLIC_SHARING, "us-east-1");
        assertEquals("Enable", b.getSettingValue());
        assertEquals("arn:aws:ssm:us-east-1:222222222222:servicesetting" + PUBLIC_SHARING, b.getArn());
    }

    /**
     * A delete that overlaps a same-name recreate-and-share must not let the delete's
     * trailing permission cleanup wipe out the recreated document's new shares.
     * {@code documentStore.delete} is instrumented to signal once it starts and then
     * block, giving a concurrent createDocument/modifyDocumentPermission call a window
     * to run before deleteDocument reaches documentPermissionStore.delete.
     */
    @Test
    void deleteOverlappingRecreateAndShareDoesNotLoseNewPermission() throws Exception {
        String region = "us-east-1";
        String name = "RaceDoc";

        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        InMemoryStorage<String, SsmDocument> realDocumentStore = new InMemoryStorage<>();
        StorageBackend<String, SsmDocument> instrumentedDocumentStore =
                new StorageBackend<>() {
                    @Override
                    public void put(String key, SsmDocument value) {
                        realDocumentStore.put(key, value);
                    }

                    @Override
                    public Optional<SsmDocument> get(String key) {
                        return realDocumentStore.get(key);
                    }

                    @Override
                    public void delete(String key) {
                        realDocumentStore.delete(key);
                        deleteStarted.countDown();
                        try {
                            releaseDelete.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public List<SsmDocument> scan(Predicate<String> keyFilter) {
                        return realDocumentStore.scan(keyFilter);
                    }

                    @Override
                    public Set<String> keys() {
                        return realDocumentStore.keys();
                    }

                    @Override
                    public void flush() {
                        realDocumentStore.flush();
                    }

                    @Override
                    public void load() {
                        realDocumentStore.load();
                    }

                    @Override
                    public void clear() {
                        realDocumentStore.clear();
                    }
                };

        SsmService service = new SsmService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), instrumentedDocumentStore,
                new InMemoryStorage<>(), 5, new RegionResolver(region, "000000000000"));
        service.createDocument(name, "{}", "Command", region);
        service.modifyDocumentPermission(name, List.of("111111111111"), List.of(), region);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> deleteFuture = pool.submit(() -> service.deleteDocument(name, region));
            assertTrue(deleteStarted.await(10, TimeUnit.SECONDS),
                    "delete must finish removing the document before this window opens");

            Future<?> recreateFuture = pool.submit(() -> {
                service.createDocument(name, "{}", "Command", region);
                service.modifyDocumentPermission(name, List.of("222222222222"), List.of(), region);
                return null;
            });
            assertThrows(TimeoutException.class, () -> recreateFuture.get(300, TimeUnit.MILLISECONDS),
                    "recreate-and-share must be serialized against the in-flight delete, "
                            + "not interleaved with it");

            releaseDelete.countDown();
            deleteFuture.get(10, TimeUnit.SECONDS);
            recreateFuture.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(List.of("222222222222"), service.describeDocumentPermission(name, region),
                "the delete's trailing permission cleanup must not remove the recreated "
                        + "document's new share");
    }
}

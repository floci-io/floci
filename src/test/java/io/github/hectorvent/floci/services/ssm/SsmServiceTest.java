package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ssm.model.Parameter;
import io.github.hectorvent.floci.services.ssm.model.ParameterHistory;
import io.github.hectorvent.floci.services.ssm.model.ServiceSetting;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.services.ssm.model.SsmDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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

    @Test
    void testListDocuments() {
        ssmService.createDocument("Doc1", "{}", "Command", "us-east-1");
        ssmService.createDocument("Doc2", "{}", "Automation", "us-east-1");
        ssmService.createDocument("Doc3", "{}", "Command", "us-west-2");

        List<SsmDocument> eastDocs = ssmService.listDocuments("us-east-1", null);
        assertEquals(2, eastDocs.size());
        assertTrue(eastDocs.stream().anyMatch(d -> "Doc1".equals(d.getName())));
        assertTrue(eastDocs.stream().anyMatch(d -> "Doc2".equals(d.getName())));

        List<SsmDocument> westDocs = ssmService.listDocuments("us-west-2", Map.of());
        assertEquals(1, westDocs.size());
        assertEquals("Doc3", westDocs.get(0).getName());

        // Filter by DocumentType
        List<SsmDocument> commandDocs = ssmService.listDocuments("us-east-1", Map.of("DocumentType", List.of("Command")));
        assertEquals(1, commandDocs.size());
        assertEquals("Doc1", commandDocs.get(0).getName());

        // Filter by Name
        List<SsmDocument> namedDocs = ssmService.listDocuments("us-east-1", Map.of("Name", List.of("Doc2")));
        assertEquals(1, namedDocs.size());
        assertEquals("Doc2", namedDocs.get(0).getName());

        // Blank Name filter value must match nothing, not everything
        List<SsmDocument> blankNameDocs = ssmService.listDocuments("us-east-1", Map.of("Name", List.of("")));
        assertTrue(blankNameDocs.isEmpty());

        // Owner=Self/All/the caller's account id match every visible document (all are self-owned)
        List<SsmDocument> selfDocs = ssmService.listDocuments("us-east-1", Map.of("Owner", List.of("Self")));
        assertEquals(2, selfDocs.size());
        List<SsmDocument> allDocs = ssmService.listDocuments("us-east-1", Map.of("Owner", List.of("All")));
        assertEquals(2, allDocs.size());

        // Owner=Amazon/Public/another account id match none: no AWS-owned or cross-account documents
        List<SsmDocument> amazonDocs = ssmService.listDocuments("us-east-1", Map.of("Owner", List.of("Amazon")));
        assertTrue(amazonDocs.isEmpty());

        // Filter by PlatformTypes (all documents default to Windows/Linux/MacOS)
        List<SsmDocument> windowsDocs = ssmService.listDocuments("us-east-1", Map.of("PlatformTypes", List.of("Windows")));
        assertEquals(2, windowsDocs.size());
        List<SsmDocument> aixDocs = ssmService.listDocuments("us-east-1", Map.of("PlatformTypes", List.of("AIX")));
        assertTrue(aixDocs.isEmpty());
    }

    @Test
    void testListDocuments_LegacyNullOwnerMatchesSelfAndAccountId() {
        InMemoryStorage<String, SsmDocument> legacyDocumentStore = new InMemoryStorage<>();
        SsmDocument legacyDoc = new SsmDocument("LegacyDoc", "{}", "Command");
        legacyDocumentStore.put("us-east-1::LegacyDoc", legacyDoc);
        SsmService service = new SsmService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                legacyDocumentStore, new InMemoryStorage<>(), 5,
                new RegionResolver("us-east-1", "123456789012"));

        assertNull(legacyDoc.getOwner());
        List<SsmDocument> selfDocs = service.listDocuments("us-east-1", Map.of("Owner", List.of("Self")));
        assertEquals(1, selfDocs.size());
        assertEquals("123456789012", selfDocs.get(0).getOwner());

        List<SsmDocument> accountDocs = service.listDocuments("us-east-1", Map.of("Owner", List.of("123456789012")));
        assertEquals(1, accountDocs.size());
        assertEquals("123456789012", accountDocs.get(0).getOwner());

        List<SsmDocument> unfilteredDocs = service.listDocuments("us-east-1", null);
        assertEquals(1, unfilteredDocs.size());
        assertEquals("123456789012", unfilteredDocs.get(0).getOwner());

        assertTrue(service.listDocuments("us-east-1", Map.of("Owner", List.of("999999999999"))).isEmpty());
    }

    @Test
    void testCreateAssociation_Success() {
        String region = "us-east-1";
        ssmService.createDocument("AWS-RunShellScript", "{}", "Command", region);

        List<SsmAssociation.Target> targets = List.of(new SsmAssociation.Target("tag:Env", List.of("prod")));
        Map<String, List<String>> parameters = Map.of("commands", List.of("echo hello"));
        SsmAssociation assoc = ssmService.createAssociation(
                "AWS-RunShellScript",
                "test-association",
                "1",
                "i-1234567890abcdef0",
                targets,
                parameters,
                "rate(30 minutes)",
                region
        );

        assertNotNull(assoc.getAssociationId());
        assertFalse(assoc.getAssociationId().isBlank());
        assertEquals("test-association", assoc.getAssociationName());
        assertEquals("AWS-RunShellScript", assoc.getName());
        assertEquals("1", assoc.getDocumentVersion());
        assertEquals("i-1234567890abcdef0", assoc.getInstanceId());
        assertEquals(targets, assoc.getTargets());
        assertEquals(parameters, assoc.getParameters());
        assertEquals("rate(30 minutes)", assoc.getScheduleExpression());
        assertNotNull(assoc.getStatus());
        assertEquals("Success", assoc.getStatus().getName());
        assertNotNull(assoc.getOverview());
        assertEquals("Success", assoc.getOverview().getStatus());
        assertEquals("Success", assoc.getOverview().getDetailedStatus());
        assertNotNull(assoc.getCreatedDate());
    }

    @Test
    void testCreateAssociation_DocumentNotFound() {
        String region = "us-east-1";
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.createAssociation("NonExistentDoc", "test-assoc", null, null, null, null, null, region));
        assertEquals("InvalidDocument", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testListAssociations() {
        String east = "us-east-1";
        String west = "us-west-2";
        ssmService.createDocument("DocEast", "{}", "Command", east);
        ssmService.createDocument("DocWest", "{}", "Command", west);

        ssmService.createAssociation("DocEast", "assoc-1", null, "i-111", null, null, null, east);
        ssmService.createAssociation("DocEast", "assoc-2", null, "i-222", null, null, null, east);
        ssmService.createAssociation("DocWest", "assoc-3", null, "i-333", null, null, null, west);

        List<SsmAssociation> eastAssocs = ssmService.listAssociations(east);
        assertEquals(2, eastAssocs.size());
        assertTrue(eastAssocs.stream().anyMatch(a -> "assoc-1".equals(a.getAssociationName())));
        assertTrue(eastAssocs.stream().anyMatch(a -> "assoc-2".equals(a.getAssociationName())));

        List<SsmAssociation> westAssocs = ssmService.listAssociations(west);
        assertEquals(1, westAssocs.size());
        assertEquals("assoc-3", westAssocs.get(0).getAssociationName());

        // Filter by InstanceId
        List<SsmAssociation> filtered = ssmService.listAssociations(east, Map.of("InstanceId", List.of("i-111")));
        assertEquals(1, filtered.size());
        assertEquals("assoc-1", filtered.get(0).getAssociationName());

        // Blank InstanceId filter value must match nothing, not everything
        List<SsmAssociation> blankFiltered = ssmService.listAssociations(east, Map.of("InstanceId", List.of("")));
        assertTrue(blankFiltered.isEmpty());
    }

    @Test
    void testDescribeAssociation_ById() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        SsmAssociation created = ssmService.createAssociation("MyDoc", "my-assoc", null, null, null, null, null, region);

        SsmAssociation found = ssmService.describeAssociation(created.getAssociationId(), null, null, region);
        assertEquals(created.getAssociationId(), found.getAssociationId());
        assertEquals("my-assoc", found.getAssociationName());

        // Region isolation
        AwsException exDiffRegion = assertThrows(AwsException.class, () ->
                ssmService.describeAssociation(created.getAssociationId(), null, null, "us-west-2"));
        assertEquals("AssociationDoesNotExist", exDiffRegion.getErrorCode());

        // Nonexistent ID
        AwsException exNotFound = assertThrows(AwsException.class, () ->
                ssmService.describeAssociation("non-existent-id", null, null, region));
        assertEquals("AssociationDoesNotExist", exNotFound.getErrorCode());
        assertEquals(400, exNotFound.getHttpStatus());
    }

    @Test
    void testDescribeAssociation_ByNameAndInstanceId() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        SsmAssociation created = ssmService.createAssociation("MyDoc", "my-assoc", null, "i-12345", null, null, null, region);

        SsmAssociation found = ssmService.describeAssociation(null, "MyDoc", "i-12345", region);
        assertEquals(created.getAssociationId(), found.getAssociationId());
        assertEquals("i-12345", found.getInstanceId());

        // Nonexistent pair
        AwsException exNotFound = assertThrows(AwsException.class, () ->
                ssmService.describeAssociation(null, "MyDoc", "i-99999", region));
        assertEquals("AssociationDoesNotExist", exNotFound.getErrorCode());
    }

    @Test
    void testDeleteAssociation() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        SsmAssociation created = ssmService.createAssociation("MyDoc", "my-assoc", null, "i-12345", null, null, null, region);

        // Delete by ID
        ssmService.deleteAssociation(created.getAssociationId(), null, null, region);

        // Subsequent describe throws AssociationDoesNotExist
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.describeAssociation(created.getAssociationId(), null, null, region));
        assertEquals("AssociationDoesNotExist", ex.getErrorCode());

        // Deleting again throws AssociationDoesNotExist
        AwsException exDeleteAgain = assertThrows(AwsException.class, () ->
                ssmService.deleteAssociation(created.getAssociationId(), null, null, region));
        assertEquals("AssociationDoesNotExist", exDeleteAgain.getErrorCode());

        // Delete by Name and InstanceId
        SsmAssociation created2 = ssmService.createAssociation("MyDoc", "my-assoc-2", null, "i-54321", null, null, null, region);
        ssmService.deleteAssociation(null, "MyDoc", "i-54321", region);

        AwsException ex2 = assertThrows(AwsException.class, () ->
                ssmService.describeAssociation(created2.getAssociationId(), null, null, region));
        assertEquals("AssociationDoesNotExist", ex2.getErrorCode());
    }

    @Test
    void testCreateAssociation_MaxErrorsMaxConcurrencyComplianceSeverityRoundTrip() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);

        SsmAssociation assoc = ssmService.createAssociation(
                "MyDoc", "my-assoc", null, "i-12345", null, null, null,
                "10%", "50%", "CRITICAL", region);

        assertEquals("1", assoc.getAssociationVersion());
        assertEquals("10%", assoc.getMaxErrors());
        assertEquals("50%", assoc.getMaxConcurrency());
        assertEquals("CRITICAL", assoc.getComplianceSeverity());

        SsmAssociation found = ssmService.describeAssociation(assoc.getAssociationId(), null, null, region);
        assertEquals("10%", found.getMaxErrors());
        assertEquals("50%", found.getMaxConcurrency());
        assertEquals("CRITICAL", found.getComplianceSeverity());
    }

    @Test
    void testCreateAssociation_ValidDocumentVersionSucceeds() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{\"v\":1}", "Command", region);

        SsmAssociation assoc = ssmService.createAssociation(
                "MyDoc", "my-assoc", "1", "i-12345", null, null, null, region);
        assertEquals("1", assoc.getDocumentVersion());

        // Update document to version 2; prior version 1 content is retained in version history
        ssmService.updateDocument("MyDoc", "{\"v\":2}", region);

        SsmAssociation assocV1 = ssmService.createAssociation(
                "MyDoc", "my-assoc-v1", "1", "i-11111", null, null, null, region);
        assertEquals("1", assocV1.getDocumentVersion());

        SsmAssociation assocV2 = ssmService.createAssociation(
                "MyDoc", "my-assoc-v2", "2", "i-22222", null, null, null, region);
        assertEquals("2", assocV2.getDocumentVersion());

        SsmDocument doc = ssmService.getDocument("MyDoc", region);
        assertEquals("{\"v\":1}", doc.getContentForVersion("1"));
        assertEquals("{\"v\":2}", doc.getContentForVersion("2"));

        SsmAssociation latest = ssmService.createAssociation(
                "MyDoc", "my-assoc-latest", "$LATEST", "i-99999", null, null, null, region);
        assertEquals("$LATEST", latest.getDocumentVersion());
    }

    @Test
    void testCreateAssociation_InvalidDocumentVersionThrows() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);

        AwsException exNonNumeric = assertThrows(AwsException.class, () ->
                ssmService.createAssociation("MyDoc", "my-assoc", "not-a-version", "i-12345", null, null, null, region));
        assertEquals("InvalidDocumentVersion", exNonNumeric.getErrorCode());
        assertEquals(400, exNonNumeric.getHttpStatus());

        AwsException exTooHigh = assertThrows(AwsException.class, () ->
                ssmService.createAssociation("MyDoc", "my-assoc", "99", "i-12345", null, null, null, region));
        assertEquals("InvalidDocumentVersion", exTooHigh.getErrorCode());
    }

    /**
     * A document persisted before {@code Versions} was tracked deserializes with an empty
     * versions map even though its DocumentVersion may already be past 1 (issue found in PR #3057
     * review): only its current content was ever retained, so versions "1"/"2" have no recorded
     * content, but they are still valid version numbers that must not be rejected as association
     * references. {@code getContentForVersion} must not fabricate content for them, though — a
     * later review comment on the same PR pointed out that substituting the current content would
     * mislabel it as an older version's real content in GetDocument/DescribeDocument.
     *
     * <p>A third review comment on the same thread pointed out the resulting asymmetry: this test
     * shows an association can reference "1" successfully while {@code hasRetainedContent("1")}
     * (what GetDocument/DescribeDocument check) is false for the same document. That divergence is
     * intentional, not a gap to close — see the javadoc on {@link SsmDocument#hasVersion} for why
     * both alternatives (rejecting the association, or fabricating GetDocument content) are worse.
     */
    @Test
    void testCreateAssociation_LegacyDocumentVersionWithoutHistoryIsStillValid() {
        InMemoryStorage<String, SsmDocument> legacyDocumentStore = new InMemoryStorage<>();
        SsmDocument legacyDoc = new SsmDocument();
        legacyDoc.setName("LegacyDoc");
        legacyDoc.setDocumentType("Command");
        legacyDoc.setContent("v3-content");
        legacyDoc.setDocumentVersion(3);
        legacyDocumentStore.put("us-east-1::LegacyDoc", legacyDoc);
        SsmService service = new SsmService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                legacyDocumentStore, new InMemoryStorage<>(), 5,
                new RegionResolver("us-east-1", "123456789012"));

        assertTrue(legacyDoc.hasVersion("1"));
        assertTrue(legacyDoc.hasVersion("2"));
        assertTrue(legacyDoc.hasVersion("3"));
        assertFalse(legacyDoc.hasVersion("4"));
        assertFalse(legacyDoc.hasRetainedContent("1"),
                "content for version 1 was never captured for a document already at version 3 when history tracking began");
        assertTrue(legacyDoc.hasRetainedContent("3"), "the current version's content is always retained");
        assertNull(legacyDoc.getContentForVersion("1"), "no content must be fabricated for an unretained version");

        SsmAssociation assoc = service.createAssociation(
                "LegacyDoc", "legacy-assoc", "1", "i-12345", null, null, null, "us-east-1");
        assertEquals("1", assoc.getDocumentVersion());
    }

    @Test
    void testUpdateAssociation_InvalidDocumentVersionThrows() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        SsmAssociation created = ssmService.createAssociation("MyDoc", "my-assoc", null, "i-12345", null, null, null, region);

        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.updateAssociation(created.getAssociationId(), null, "99", null, null, null, null, null, null, region));
        assertEquals("InvalidDocumentVersion", ex.getErrorCode());

        // The association's DocumentVersion is unchanged after the rejected update
        SsmAssociation found = ssmService.describeAssociation(created.getAssociationId(), null, null, region);
        assertNull(found.getDocumentVersion());
    }

    @Test
    void testCreateAssociation_DuplicateInstanceThrowsAssociationAlreadyExists() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        ssmService.createAssociation("MyDoc", "my-assoc", null, "i-12345", null, null, null, region);

        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.createAssociation("MyDoc", "my-assoc-2", null, "i-12345", null, null, null, region));
        assertEquals("AssociationAlreadyExists", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testCreateAssociation_DuplicateTargetsThrowsAssociationAlreadyExists() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        List<SsmAssociation.Target> targets = List.of(new SsmAssociation.Target("tag:Env", List.of("prod")));
        ssmService.createAssociation("MyDoc", "my-assoc", null, null, targets, null, null, region);

        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.createAssociation("MyDoc", "my-assoc-2", null, null,
                        List.of(new SsmAssociation.Target("tag:Env", List.of("prod"))), null, null, region));
        assertEquals("AssociationAlreadyExists", ex.getErrorCode());
    }

    @Test
    void testUpdateAssociation_Success() {
        String region = "us-east-1";
        ssmService.createDocument("MyDoc", "{}", "Command", region);
        SsmAssociation created = ssmService.createAssociation("MyDoc", "my-assoc", null, "i-12345", null, null, null, region);
        assertEquals("1", created.getAssociationVersion());

        SsmAssociation updated = ssmService.updateAssociation(
                created.getAssociationId(), null, null, null, null,
                "rate(1 hour)", "5", null, null, region);

        assertEquals("rate(1 hour)", updated.getScheduleExpression());
        assertEquals("5", updated.getMaxErrors());
        assertEquals("2", updated.getAssociationVersion());

        SsmAssociation found = ssmService.describeAssociation(created.getAssociationId(), null, null, region);
        assertEquals("rate(1 hour)", found.getScheduleExpression());
    }

    @Test
    void testUpdateAssociation_NotFound() {
        String region = "us-east-1";
        AwsException ex = assertThrows(AwsException.class, () ->
                ssmService.updateAssociation("non-existent-id", null, null, null, null, null, null, null, null, region));
        assertEquals("AssociationDoesNotExist", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }
}

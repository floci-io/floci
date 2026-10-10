package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.TempCredential;
import io.github.hectorvent.floci.services.redshift.model.Endpoint;
import io.github.hectorvent.floci.services.redshiftserverless.model.ConfigParameter;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import io.github.hectorvent.floci.services.redshiftserverless.model.PricePerformanceTarget;
import io.github.hectorvent.floci.services.redshiftserverless.model.RedshiftServerlessSnapshot;
import io.github.hectorvent.floci.services.redshiftserverless.model.Workgroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    private RedshiftServerlessService service;
    private AccountAwareStorageBackend<Namespace> store;
    private AccountAwareStorageBackend<Workgroup> workgroupStore;
    private RedshiftServerlessEndpoints endpoints;
    private RedshiftServerlessRuntime runtime;
    private int nextPort;

    @TempDir
    Path dumpDir;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        // A spy, not a plain in-memory backend: the in-memory store hands back the same object
        // reference a mutation already changed, so only an explicit write assertion can tell a
        // service that persists its change from one that merely mutated a shared instance. That
        // distinction is invisible under "memory" mode and load-bearing under "hybrid" and "wal".
        store = spy(AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-namespaces.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) store);
        workgroupStore = spy(AccountAwareStorageBackend.inMemory(ACCOUNT_ID));
        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-workgroups.json"),
                any(TypeReference.class))).thenReturn((AccountAwareStorageBackend) workgroupStore);

        when(storageFactory.create(eq("redshiftserverless"), eq("redshiftserverless-snapshots.json"),
                any(TypeReference.class))).thenReturn(AccountAwareStorageBackend.inMemory(ACCOUNT_ID));

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        nextPort = 7100;
        endpoints = mock(RedshiftServerlessEndpoints.class);
        when(endpoints.allocate()).thenAnswer(invocation -> new Endpoint("localhost", nextPort++));
        runtime = mock(RedshiftServerlessRuntime.class);
        when(runtime.generatePassword()).thenReturn("Generated123");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("127.0.0.1", 55432));
        EmulatorConfig config = mock(EmulatorConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(dumpDir.toString());
        service = new RedshiftServerlessService(storageFactory, regionResolver, endpoints, runtime, config);
    }

    @Test
    void createAppliesAwsDefaultsAndAUniqueNamespaceId() {
        Namespace first = create("first-ns");
        Namespace second = create("second-ns");

        assertEquals("dev", first.getDbName());
        assertEquals("AWS_OWNED_KMS_KEY", first.getKmsKeyId());
        assertEquals("AVAILABLE", first.getStatus());
        assertTrue(first.getLogExports().isEmpty());
        assertEquals("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":namespace/" + first.getNamespaceId(),
                first.getNamespaceArn());
        assertEquals(first.getNamespaceId(), UUID.fromString(first.getNamespaceId()).toString());
        assertNotEquals(first.getNamespaceId(), second.getNamespaceId());
    }

    @Test
    void createRejectsADuplicateNamespaceName() {
        create("duplicate-ns");
        AwsException conflict = assertThrows(AwsException.class, () -> create("duplicate-ns"));
        assertEquals("ConflictException", conflict.getErrorCode());
    }

    @Test
    void getAndDeleteRejectAnUnknownNamespace() {
        assertEquals("ResourceNotFoundException",
                assertThrows(AwsException.class, () -> service.getNamespace("absent-ns", REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException",
                assertThrows(AwsException.class, () -> service.deleteNamespace("absent-ns", REGION)).getErrorCode());
    }

    @Test
    void updatePersistsOnlyTheSuppliedFields() {
        service.createNamespace("update-ns", "admin", "analytics", null, null,
                List.of("arn:aws:iam::123456789012:role/one"), List.of("userlog"), Map.of(), REGION);

        service.updateNamespace("update-ns", null, "custom-key", null, List.of(), null, REGION);

        Namespace reread = service.getNamespace("update-ns", REGION);
        assertEquals("custom-key", reread.getKmsKeyId());
        assertEquals("admin", reread.getAdminUsername());
        assertEquals("analytics", reread.getDbName());
        assertEquals(List.of("userlog"), reread.getLogExports());
        assertTrue(reread.getIamRoles().isEmpty());
    }

    @Test
    void deleteReportsDeletingAndRemovesTheNamespace() {
        create("delete-ns");
        assertEquals("DELETING", service.deleteNamespace("delete-ns", REGION).getStatus());
        assertThrows(AwsException.class, () -> service.getNamespace("delete-ns", REGION));
    }

    @Test
    void invalidNamespaceNamesAndLogExportsAreRejected() {
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> create("Upper-Case")).getErrorCode());
        assertEquals("ValidationException",
                assertThrows(AwsException.class, () -> create("ab")).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createNamespace("logs-ns", "admin", null, null, null, null,
                        List.of("nosuchlog"), Map.of(), REGION)).getErrorCode());
    }

    @Test
    void reservedWordNamespaceNamesAreRejectedByEveryOperation() {
        // "select" and "user" pass the length and [a-z0-9-] rules, so only the reserved-word
        // check can reject them. Asserting on create and on the read path as well, because
        // update and delete reach the validator through getNamespace.
        for (String reserved : List.of("select", "user")) {
            assertEquals("ValidationException",
                    assertThrows(AwsException.class, () -> create(reserved)).getErrorCode());
            assertEquals("ValidationException",
                    assertThrows(AwsException.class, () -> service.getNamespace(reserved, REGION)).getErrorCode());
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.deleteNamespace(reserved, REGION)).getErrorCode());
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.updateNamespace(reserved, null, "k", null, null, null, REGION)).getErrorCode());
        }

        // A name that merely looks SQL-ish is not reserved and must still be accepted, and the
        // namespace must be readable back through a separate call rather than trusting create.
        create("analytics");
        assertEquals("analytics", service.getNamespace("analytics", REGION).getNamespaceName());
    }

    @Test
    void listIsScopedToTheRegionAndPaginates() {
        create("alpha-ns");
        create("beta-ns");
        service.createNamespace("gamma-ns", "admin", null, null, null, null, null, Map.of(), "us-west-2");

        assertEquals(List.of("alpha-ns", "beta-ns"),
                service.listNamespaces(REGION, null, null).items().stream()
                        .map(Namespace::getNamespaceName).toList());

        PaginatedResult<Namespace> firstPage = service.listNamespaces(REGION, 1, null);
        assertEquals(List.of("alpha-ns"), firstPage.items().stream().map(Namespace::getNamespaceName).toList());
        assertEquals(List.of("beta-ns"), service.listNamespaces(REGION, 1, firstPage.nextToken()).items().stream()
                .map(Namespace::getNamespaceName).toList());
    }

    @Test
    void clearRemovesPersistedState() {
        create("reset-ns");
        service.clear();
        assertTrue(service.listNamespaces(REGION, null, null).items().isEmpty());
    }

    @Test
    void tagsSuppliedAtCreateAreReadableThroughListTagsForResource() {
        Namespace created = service.createNamespace("tagged-ns", "admin", null, null, null, null, null,
                Map.of("env", "dev"), REGION);

        assertEquals(Map.of("env", "dev"),
                service.listTagsForResource(created.getNamespaceArn(), REGION));
    }

    @Test
    void tagResourceMergesAndUntagResourceRemovesByKey() {
        Namespace created = service.createNamespace("merge-ns", "admin", null, null, null, null, null,
                Map.of("env", "dev"), REGION);
        String arn = created.getNamespaceArn();

        service.tagResource(arn, Map.of("team", "data"), REGION);
        assertEquals(Map.of("env", "dev", "team", "data"), service.listTagsForResource(arn, REGION),
                "TagResource must merge: the pre-existing env tag has to survive a call that does not mention it");

        service.tagResource(arn, Map.of("env", "prod"), REGION);
        assertEquals(Map.of("env", "prod", "team", "data"), service.listTagsForResource(arn, REGION));

        service.untagResource(arn, List.of("env"), REGION);
        assertEquals(Map.of("team", "data"), service.listTagsForResource(arn, REGION));
    }

    @Test
    void taggingAnUnknownArnIsResourceNotFoundEvenWhenOtherNamespacesExist() {
        create("decoy-ns");
        create("second-decoy-ns");
        String absent = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID
                + ":namespace/00000000-0000-0000-0000-000000000000";
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listTagsForResource(absent, REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.tagResource(absent, Map.of("a", "b"), REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.untagResource(absent, List.of("a"), REGION)).getErrorCode());
    }

    @Test
    void tagsSurviveAnUnrelatedNamespaceUpdate() {
        Namespace created = service.createNamespace("keep-tags-ns", "admin", null, null, null, null, null,
                Map.of("env", "dev"), REGION);

        service.updateNamespace("keep-tags-ns", null, "custom-key", null, null, null, REGION);

        assertEquals(Map.of("env", "dev"), service.listTagsForResource(created.getNamespaceArn(), REGION));
    }

    @Test
    void anArnFromAnotherRegionIsNotTaggableFromThisOne() {
        Namespace elsewhere = service.createNamespace("west-ns", "admin", null, null, null, null, null,
                Map.of("env", "dev"), "us-west-2");

        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listTagsForResource(elsewhere.getNamespaceArn(), REGION)).getErrorCode());
        assertEquals(Map.of("env", "dev"),
                service.listTagsForResource(elsewhere.getNamespaceArn(), "us-west-2"));
    }

    @Test
    void tagMutationsAreWrittenBackToStorage() {
        Namespace created = service.createNamespace("persist-ns", "admin", null, null, null, null, null,
                Map.of("env", "dev"), REGION);
        String arn = created.getNamespaceArn();

        clearInvocations(store);
        service.tagResource(arn, Map.of("team", "data"), REGION);
        verify(store).put(eq(REGION + "::persist-ns"), any(Namespace.class));

        clearInvocations(store);
        service.untagResource(arn, List.of("team"), REGION);
        verify(store).put(eq(REGION + "::persist-ns"), any(Namespace.class));
    }

    @Test
    void updateStoresACopySoAReaderNeverSeesAHalfAppliedChange() {
        service.createNamespace("copy-ns", "admin", "analytics", null, null,
                List.of("arn:aws:iam::123456789012:role/one"), List.of("userlog"), Map.of(), REGION);
        Namespace readBeforeUpdate = service.getNamespace("copy-ns", REGION);

        service.updateNamespace("copy-ns", "newadmin", "custom-key", null, List.of(), null, REGION);

        Namespace readAfterUpdate = service.getNamespace("copy-ns", REGION);
        assertNotSame(readBeforeUpdate, readAfterUpdate, "update must store a new instance, not mutate the stored one");
        // The instance a concurrent reader was already holding is untouched, which is what makes
        // a torn read structurally impossible rather than merely unlikely.
        assertEquals("admin", readBeforeUpdate.getAdminUsername());
        assertEquals("AWS_OWNED_KMS_KEY", readBeforeUpdate.getKmsKeyId());
        assertEquals(List.of("arn:aws:iam::123456789012:role/one"), readBeforeUpdate.getIamRoles());
        assertEquals("newadmin", readAfterUpdate.getAdminUsername());
        assertEquals("custom-key", readAfterUpdate.getKmsKeyId());
        assertTrue(readAfterUpdate.getIamRoles().isEmpty());
    }

    @Test
    void tagMutationsAlsoStoreACopy() {
        Namespace created = service.createNamespace("copy-tags-ns", "admin", null, null, null, null, null,
                Map.of("env", "dev"), REGION);
        Namespace readBeforeTagging = service.getNamespace("copy-tags-ns", REGION);

        service.tagResource(created.getNamespaceArn(), Map.of("team", "data"), REGION);

        Namespace readAfterTagging = service.getNamespace("copy-tags-ns", REGION);
        assertNotSame(readBeforeTagging, readAfterTagging);
        assertEquals(Map.of("env", "dev"), readBeforeTagging.getTags());
        assertEquals(Map.of("env", "dev", "team", "data"), readAfterTagging.getTags());
    }

    @Test
    void deleteDoesNotMutateTheInstanceAReaderMayHold() {
        create("delete-copy-ns");
        Namespace readBeforeDelete = service.getNamespace("delete-copy-ns", REGION);

        Namespace returned = service.deleteNamespace("delete-copy-ns", REGION);

        assertEquals("DELETING", returned.getStatus());
        assertEquals("AVAILABLE", readBeforeDelete.getStatus(),
                "delete must not flip the status on an instance handed out by an earlier read");
    }

    @Test
    void theCopyConstructorSharesNoMutableState() {
        Namespace original = service.createNamespace("copy-ctor-ns", "admin", null, null, null,
                List.of("arn:aws:iam::123456789012:role/one"), List.of("userlog"),
                Map.of("env", "dev"), REGION);

        Namespace copy = new Namespace(original);
        copy.getIamRoles().add("arn:aws:iam::123456789012:role/two");
        copy.getLogExports().add("connectionlog");
        copy.getTags().put("team", "data");

        assertEquals(List.of("arn:aws:iam::123456789012:role/one"), original.getIamRoles());
        assertEquals(List.of("userlog"), original.getLogExports());
        assertEquals(Map.of("env", "dev"), original.getTags());
    }

    @Test
    void createWorkgroupAppliesAwsDefaultsAndAllocatesAnEndpoint() {
        create("wg-ns");

        Workgroup workgroup = createWorkgroup("wg-one", "wg-ns");

        assertEquals("AVAILABLE", workgroup.getStatus());
        assertEquals("wg-ns", workgroup.getNamespaceName());
        assertEquals(128, workgroup.getBaseCapacity());
        assertEquals(5439, workgroup.getPort());
        assertEquals("ipv4", workgroup.getIpAddressType());
        assertEquals("current", workgroup.getTrackName());
        assertEquals("DISABLED", workgroup.getPricePerformanceTarget().getStatus());
        assertEquals("localhost", workgroup.getEndpoint().getAddress());
        assertEquals(7100, workgroup.getEndpoint().getPort());
        assertEquals("arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/" + workgroup.getWorkgroupId(),
                workgroup.getWorkgroupArn());
        assertEquals(workgroup.getWorkgroupId(), UUID.fromString(workgroup.getWorkgroupId()).toString());
        assertEquals("wg-one", service.getWorkgroup("wg-one", REGION).getWorkgroupName());
    }

    @Test
    void createWorkgroupRequiresAnExistingNamespace() {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> createWorkgroup("wg-orphan", "absent-ns")).getErrorCode());
    }

    @Test
    void aNamespaceHasAtMostOneWorkgroupAndWorkgroupNamesAreUnique() {
        create("one-ns");
        create("two-ns");
        createWorkgroup("wg-first", "one-ns");

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> createWorkgroup("wg-second", "one-ns")).getErrorCode());
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> createWorkgroup("wg-first", "two-ns")).getErrorCode());
    }

    @Test
    void workgroupNamesAndSettingsAreValidated() {
        create("valid-ns");
        for (String name : List.of("ab", "Upper-Case", "has_underscore")) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> createWorkgroup(name, "valid-ns")).getErrorCode(), name);
        }
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createWorkgroup("bad-port", "valid-ns", settings(5000, null, null, null), null, REGION))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createWorkgroup("bad-ip", "valid-ns", settings(null, "ipv6", null, null), null, REGION))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createWorkgroup("bad-target", "valid-ns",
                        settings(null, null, new PricePerformanceTarget("MAYBE", null), null), null, REGION))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createWorkgroup("bad-level", "valid-ns",
                        settings(null, null, new PricePerformanceTarget("ENABLED", 101), null), null, REGION))
                .getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createWorkgroup("bad-track", "valid-ns", settings(null, null, null, "no-dashes"), null, REGION))
                .getErrorCode());
        assertTrue(service.listWorkgroups(REGION, null, null).items().isEmpty(),
                "a rejected create must leave nothing behind");
    }

    @Test
    void acceptedPortsIncludeBothDocumentedRanges() {
        create("ports-ns");
        create("ports-two-ns");
        assertEquals(5431, service.createWorkgroup("low-port", "ports-ns",
                settings(5431, null, null, null), null, REGION).getPort());
        assertEquals(8215, service.createWorkgroup("high-port", "ports-two-ns",
                settings(8215, null, null, null), null, REGION).getPort());
    }

    @Test
    void getAndDeleteRejectAnUnknownWorkgroup() {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getWorkgroup("absent-wg", REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.deleteWorkgroup("absent-wg", REGION)).getErrorCode());
    }

    @Test
    void updateWorkgroupAppliesOnlySuppliedFieldsAndRecordsAPendingTrack() {
        create("upd-ns");
        service.createWorkgroup("upd-wg", "upd-ns", new WorkgroupSettings(32, 64, null, true, null,
                List.of(new ConfigParameter("datestyle", "ISO, MDY")), List.of("sg-1"), List.of("subnet-1"),
                null, null, null, null), null, REGION);

        Workgroup updated = service.updateWorkgroup("upd-wg", new WorkgroupSettings(null, 128, null, null, null,
                null, null, null, null, null, null, "trailing"), REGION);

        assertEquals(32, updated.getBaseCapacity());
        assertEquals(128, updated.getMaxCapacity());
        assertTrue(updated.isPubliclyAccessible());
        assertEquals("datestyle", updated.getConfigParameters().get(0).getParameterKey());
        assertEquals(List.of("sg-1"), updated.getSecurityGroupIds());
        assertEquals("current", updated.getTrackName());
        assertEquals("trailing", updated.getPendingTrackName());

        Workgroup reverted = service.updateWorkgroup("upd-wg", new WorkgroupSettings(null, null, null, null, null,
                null, null, null, null, null, null, "current"), REGION);
        assertNull(reverted.getPendingTrackName());
    }

    @Test
    void updateStoresACopySoAReaderNeverSeesAHalfAppliedWorkgroupChange() {
        create("copy-wg-ns");
        createWorkgroup("copy-wg", "copy-wg-ns");
        Workgroup before = service.getWorkgroup("copy-wg", REGION);

        service.updateWorkgroup("copy-wg", new WorkgroupSettings(64, null, null, null, null,
                null, null, null, null, null, null, null), REGION);

        assertNotSame(before, service.getWorkgroup("copy-wg", REGION));
        assertEquals(128, before.getBaseCapacity());
        assertEquals(64, service.getWorkgroup("copy-wg", REGION).getBaseCapacity());
    }

    @Test
    void deleteWorkgroupReportsDeletingReleasesTheEndpointAndFreesTheNamespace() {
        create("del-wg-ns");
        Workgroup created = createWorkgroup("del-wg", "del-wg-ns");

        Workgroup deleted = service.deleteWorkgroup("del-wg", REGION);

        assertEquals("DELETING", deleted.getStatus());
        verify(endpoints).release(created.getEndpoint());
        assertThrows(AwsException.class, () -> service.getWorkgroup("del-wg", REGION));
        createWorkgroup("del-wg-again", "del-wg-ns");
    }

    @Test
    void deleteNamespaceIsRejectedWhileAWorkgroupIsAttached() {
        create("attached-ns");
        createWorkgroup("attached-wg", "attached-ns");

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.deleteNamespace("attached-ns", REGION)).getErrorCode());
        assertEquals("attached-ns", service.getNamespace("attached-ns", REGION).getNamespaceName());

        service.deleteWorkgroup("attached-wg", REGION);
        assertEquals("DELETING", service.deleteNamespace("attached-ns", REGION).getStatus());
    }

    @Test
    void listWorkgroupsIsScopedToTheRegionAndPaginates() {
        create("list-a-ns");
        create("list-b-ns");
        service.createNamespace("list-w-ns", "admin", null, null, null, null, null, Map.of(), "us-west-2");
        createWorkgroup("alpha-wg", "list-a-ns");
        createWorkgroup("beta-wg", "list-b-ns");
        service.createWorkgroup("gamma-wg", "list-w-ns", emptySettings(), null, "us-west-2");

        assertEquals(List.of("alpha-wg", "beta-wg"),
                service.listWorkgroups(REGION, null, null).items().stream().map(Workgroup::getWorkgroupName).toList());
        PaginatedResult<Workgroup> firstPage = service.listWorkgroups(REGION, 1, null);
        assertEquals(List.of("alpha-wg"), firstPage.items().stream().map(Workgroup::getWorkgroupName).toList());
        assertEquals(List.of("beta-wg"), service.listWorkgroups(REGION, 1, firstPage.nextToken()).items().stream()
                .map(Workgroup::getWorkgroupName).toList());
    }

    @Test
    void workgroupTagsAreManagedThroughTheWorkgroupArn() {
        create("tag-wg-ns");
        Workgroup created = service.createWorkgroup("tag-wg", "tag-wg-ns", emptySettings(),
                Map.of("env", "dev"), REGION);
        String arn = created.getWorkgroupArn();

        assertEquals(Map.of("env", "dev"), service.listTagsForResource(arn, REGION));
        service.tagResource(arn, Map.of("team", "data"), REGION);
        assertEquals(Map.of("env", "dev", "team", "data"), service.listTagsForResource(arn, REGION));
        service.untagResource(arn, List.of("env"), REGION);
        assertEquals(Map.of("team", "data"), service.listTagsForResource(arn, REGION));

        // The namespace's own tags are a separate set.
        assertTrue(service.listTagsForResource(service.getNamespace("tag-wg-ns", REGION).getNamespaceArn(), REGION)
                .isEmpty());
        verify(workgroupStore, atLeastOnce()).put(eq(REGION + "::tag-wg"), any(Workgroup.class));
    }

    @Test
    void clearRemovesWorkgroupsAndReleasesTheirEndpoints() {
        create("clear-wg-ns");
        Workgroup created = createWorkgroup("clear-wg", "clear-wg-ns");

        service.clear();

        assertTrue(service.listWorkgroups(REGION, null, null).items().isEmpty());
        verify(endpoints).release(created.getEndpoint());
    }

    @Test
    void createWorkgroupStartsTheRuntimeWithTheNamespaceAdminAndRecordsWhereItListens() {
        service.createNamespace("rt-ns", "root", "Secret123", "analytics", null, null, null, null, Map.of(), REGION);

        Workgroup workgroup = createWorkgroup("rt-wg", "rt-ns");

        verify(runtime).start(eq(ACCOUNT_ID), eq(REGION), eq("rt-wg"), eq("root"), eq("Secret123"),
                eq("analytics"), eq(workgroup.getEndpoint()), eq(false), any());
        Workgroup stored = service.getWorkgroup("rt-wg", REGION);
        assertEquals("127.0.0.1", stored.getRuntimeHost());
        assertEquals(55432, stored.getRuntimePort());
        assertEquals("root", stored.getMasterUsername());
    }

    @Test
    void aNamespaceWithoutAPasswordGetsAGeneratedOneThatIsKeptForTheBackend() {
        service.createNamespace("gen-ns", null, null, null, null, null, null, Map.of(), REGION);

        createWorkgroup("gen-wg", "gen-ns");

        verify(runtime).start(eq(ACCOUNT_ID), eq(REGION), eq("gen-wg"), eq("admin"), eq("Generated123"),
                eq("dev"), any(Endpoint.class), eq(false), any());
        assertEquals("Generated123", store.get(REGION + "::gen-ns").orElseThrow().getAdminUserPassword());
    }

    @Test
    void theAdminSupplierHandedToTheRuntimeTracksTheNamespaceAsItChanges() {
        service.createNamespace("sup-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("sup-wg", "sup-ns");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Optional<RedshiftServerlessRuntime.AdminCredentials>>> supplier =
                ArgumentCaptor.forClass(Supplier.class);
        verify(runtime).start(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), supplier.capture());

        assertEquals(new RedshiftServerlessRuntime.AdminCredentials("root", "Secret123"),
                supplier.getValue().get().orElseThrow());
        service.updateNamespace("sup-ns", "renamed", "Changed123", null, null, null, null, REGION);

        assertEquals(new RedshiftServerlessRuntime.AdminCredentials("renamed", "Changed123"),
                supplier.getValue().get().orElseThrow());
    }

    @Test
    void aRuntimeThatFailsToStartLeavesNoWorkgroupAndReleasesTheEndpoint() {
        create("fail-ns");
        when(runtime.start(any(String.class), any(String.class), any(String.class), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), anyBoolean(), any()))
                .thenThrow(new AwsException("InternalFailure", "Failed to start container", 500));

        assertThrows(AwsException.class, () -> createWorkgroup("fail-wg", "fail-ns"));

        assertTrue(service.listWorkgroups(REGION, null, null).items().isEmpty());
        verify(endpoints).release(any(Endpoint.class));
    }

    @Test
    void aFailedPersistAfterTheRuntimeStartedStopsTheRuntimeAndReleasesTheEndpoint() {
        create("persist-ns");
        doThrow(new IllegalStateException("disk full")).when(workgroupStore)
                .put(eq(REGION + "::persist-wg"), any(Workgroup.class));

        assertThrows(IllegalStateException.class, () -> createWorkgroup("persist-wg", "persist-ns"));

        verify(runtime).stop(ACCOUNT_ID, REGION, "persist-wg");
        verify(endpoints).release(any(Endpoint.class));
    }

    @Test
    void deleteWorkgroupStopsTheRuntimeFirstAndKeepsTheWorkgroupWhenThatFails() {
        create("stop-ns");
        createWorkgroup("stop-wg", "stop-ns");
        doThrow(new RuntimeException("listener would not close")).when(runtime).stop(ACCOUNT_ID, REGION, "stop-wg");

        AwsException failure = assertThrows(AwsException.class, () -> service.deleteWorkgroup("stop-wg", REGION));

        assertEquals("InternalServerException", failure.getErrorCode());
        assertEquals("stop-wg", service.getWorkgroup("stop-wg", REGION).getWorkgroupName());
        verify(endpoints, never()).release(any(Endpoint.class));
    }

    @Test
    void changingTheAdminPasswordReachesTheRunningBackendBeforeItIsStored() {
        service.createNamespace("pw-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("pw-wg", "pw-ns");

        service.updateNamespace("pw-ns", null, "Changed123", null, null, null, null, REGION);

        verify(runtime).changeMasterPassword(ACCOUNT_ID, REGION, "pw-wg", "root", "dev", "Secret123", "Changed123");
        assertEquals("Changed123", store.get(REGION + "::pw-ns").orElseThrow().getAdminUserPassword());
    }

    @Test
    void aPasswordTheBackendRefusesLeavesTheNamespaceUnchangedAndIsAValidationError() {
        service.createNamespace("bad-pw-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("bad-pw-wg", "bad-pw-ns");
        doThrow(new AwsException("InvalidParameterValue", "too short", 400)).when(runtime)
                .changeMasterPassword(any(), any(), any(), any(), any(), any(), eq("short"));

        AwsException rejected = assertThrows(AwsException.class,
                () -> service.updateNamespace("bad-pw-ns", null, "short", null, null, null, null, REGION));

        assertEquals("ValidationException", rejected.getErrorCode());
        assertEquals("Secret123", store.get(REGION + "::bad-pw-ns").orElseThrow().getAdminUserPassword());
    }

    @Test
    void aNamespaceThatCannotBeStoredPutsTheBackendPasswordBack() {
        service.createNamespace("undo-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("undo-wg", "undo-ns");
        doThrow(new IllegalStateException("disk full")).when(store)
                .put(eq(REGION + "::undo-ns"), any(Namespace.class));

        assertThrows(IllegalStateException.class,
                () -> service.updateNamespace("undo-ns", null, "Changed123", null, null, null, null, REGION));

        verify(runtime).restoreMasterPassword(ACCOUNT_ID, REGION, "undo-wg", "root", "dev", "Secret123");
    }

    @Test
    void aFailedPasswordRestoreDoesNotMaskTheStorageFailure() {
        service.createNamespace("mask-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("mask-wg", "mask-ns");
        IllegalStateException storageFailure = new IllegalStateException("disk full");
        doThrow(storageFailure).when(store).put(eq(REGION + "::mask-ns"), any(Namespace.class));
        doThrow(new RuntimeException("psql failed")).when(runtime)
                .restoreMasterPassword(any(), any(), any(), any(), any(), any());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.updateNamespace("mask-ns", null, "Changed123", null, null, null, null, REGION));

        assertEquals(storageFailure, thrown);
    }

    @Test
    void aNamespaceUpdateThatFailsValidationNeverTouchesTheBackendPassword() {
        service.createNamespace("order-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("order-wg", "order-ns");

        assertThrows(AwsException.class, () -> service.updateNamespace(
                "order-ns", null, "Changed123", null, null, null, List.of("bogus"), REGION));

        verify(runtime, never()).changeMasterPassword(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reconcileNamespaceResetsAnOmittedKmsKeyAndDefaultRole() {
        service.createNamespace("rec-ns", "root", null, null, "custom-key", "arn:aws:iam::000000000000:role/r",
                null, null, Map.of(), REGION);

        Namespace reconciled = service.reconcileNamespace("rec-ns", null, null, null, null, null, null, REGION);

        assertEquals(RedshiftServerlessService.AWS_OWNED_KMS_KEY, reconciled.getKmsKeyId());
        assertNull(reconciled.getDefaultIamRoleArn());
    }

    @Test
    void updateNamespaceKeepsAnOmittedKmsKeyAndDefaultRole() {
        service.createNamespace("keep-ns", "root", null, null, "custom-key", "arn:aws:iam::000000000000:role/r",
                null, null, Map.of(), REGION);

        Namespace updated = service.updateNamespace("keep-ns", null, null, null, null, null, null, REGION);

        assertEquals("custom-key", updated.getKmsKeyId());
        assertEquals("arn:aws:iam::000000000000:role/r", updated.getDefaultIamRoleArn());
    }

    @Test
    void clearKeepsTheEndpointReservedWhenTheRuntimeCannotBeStopped() {
        create("hold-ns");
        Workgroup created = createWorkgroup("hold-wg", "hold-ns");
        doThrow(new RuntimeException("listener still bound")).when(runtime).stop(ACCOUNT_ID, REGION, "hold-wg");

        service.clear();

        verify(endpoints, never()).release(created.getEndpoint());
    }

    @Test
    void anAdminPasswordChangeWithNoWorkgroupJustStoresIt() {
        service.createNamespace("solo-ns", "root", null, null, null, null, null, null, Map.of(), REGION);

        service.updateNamespace("solo-ns", null, "Changed123", null, null, null, null, REGION);

        verify(runtime, never()).changeMasterPassword(any(), any(), any(), any(), any(), any(), any());
        assertEquals("Changed123", store.get(REGION + "::solo-ns").orElseThrow().getAdminUserPassword());
    }

    @Test
    void getCredentialsMintsForTheDbUserWithTheDocumentedDurations() {
        create("cred-ns");
        createWorkgroup("cred-wg", "cred-ns");
        TempCredential minted = new TempCredential("IAM:alice", "pw", Instant.parse("2026-10-03T00:15:00Z"), List.of());
        when(runtime.issueCredential(ACCOUNT_ID, REGION, "cred-wg", "IAM:alice", 900)).thenReturn(minted);
        when(runtime.issueCredential(ACCOUNT_ID, REGION, "cred-wg", "IAM:alice", 3600)).thenReturn(minted);

        assertEquals(minted, service.getCredentials("cred-wg", null, null, "IAM:alice", REGION));
        assertEquals(minted, service.getCredentials("cred-wg", "dev", 3600, "IAM:alice", REGION));
        for (int bad : new int[] {899, 3601}) {
            assertEquals("ValidationException", assertThrows(AwsException.class,
                    () -> service.getCredentials("cred-wg", null, bad, "IAM:alice", REGION)).getErrorCode());
        }
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.getCredentials("cred-wg", "other", null, "IAM:alice", REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getCredentials("absent-wg", null, null, "IAM:alice", REGION)).getErrorCode());
    }

    @Test
    void aWorkgroupTargetResolvesByNameOrArnAndNeedsALiveRuntime() {
        service.createNamespace("tgt-ns", "root", "Secret123", "analytics", null, null, null, null, Map.of(), REGION);
        Workgroup created = createWorkgroup("tgt-wg", "tgt-ns");

        for (String reference : List.of("tgt-wg", created.getWorkgroupArn())) {
            RedshiftServerlessService.WorkgroupTarget target = service.getWorkgroupTarget(reference, REGION);
            assertEquals(created.getWorkgroupArn(), target.arn());
            assertEquals("127.0.0.1", target.host());
            assertEquals(55432, target.port());
            assertEquals("analytics", target.database());
            assertEquals("root", target.masterUsername());
            assertEquals("Secret123", target.masterPassword());
        }
        String absentArn = "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID
                + ":workgroup/00000000-0000-0000-0000-000000000000";
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getWorkgroupTarget(absentArn, REGION)).getErrorCode());

        Workgroup dead = new Workgroup(service.getWorkgroup("tgt-wg", REGION));
        dead.setRuntimeHost(null);
        dead.setRuntimePort(0);
        workgroupStore.put(REGION + "::tgt-wg", dead);
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.getWorkgroupTarget("tgt-wg", REGION)).getErrorCode());
    }

    @Test
    void startupRestoresEachWorkgroupRuntimeAndClearsTheOnesItCannotRestore() {
        service.createNamespace("rec-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        service.createNamespace("rec-bad-ns", "root", "Secret123", null, null, null, null, null, Map.of(), REGION);
        createWorkgroup("rec-wg", "rec-ns");
        createWorkgroup("rec-bad-wg", "rec-bad-ns");
        when(endpoints.restore(any(Endpoint.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runtime.start(any(String.class), any(String.class), eq("rec-bad-wg"), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), eq(true), any()))
                .thenThrow(new AwsException("InternalFailure", "no container", 500));
        when(runtime.start(any(String.class), any(String.class), eq("rec-wg"), any(String.class),
                any(String.class), any(String.class), any(Endpoint.class), eq(true), any()))
                .thenReturn(new RedshiftServerlessRuntime.Backend("10.0.0.9", 6000));

        service.onStart(null);

        assertEquals("10.0.0.9", service.getWorkgroup("rec-wg", REGION).getRuntimeHost());
        assertEquals(6000, service.getWorkgroup("rec-wg", REGION).getRuntimePort());
        assertNull(service.getWorkgroup("rec-bad-wg", REGION).getRuntimeHost());
        assertEquals(0, service.getWorkgroup("rec-bad-wg", REGION).getRuntimePort());
        assertEquals("AVAILABLE", service.getWorkgroup("rec-bad-wg", REGION).getStatus());
    }

    @Test
    void clearStopsEveryRuntime() {
        create("clr-ns");
        createWorkgroup("clr-wg", "clr-ns");

        service.clear();

        verify(runtime).stop(ACCOUNT_ID, REGION, "clr-wg");
    }

    @Test
    void listSnapshotsFiltersByNamespace() {
        create("snap-a");
        create("snap-b");
        RedshiftServerlessSnapshot snapshot = service.createSnapshot("snap-one", "snap-a", null, null, REGION);
        service.createSnapshot("snap-two", "snap-b", null, null, REGION);

        assertEquals("AVAILABLE", snapshot.getStatus());
        assertEquals(ACCOUNT_ID, snapshot.getOwnerAccount());
        assertEquals(List.of("snap-one"), service.listSnapshots("snap-a", null, null, null, null, REGION, null, null).items().stream()
                .map(RedshiftServerlessSnapshot::getSnapshotName).toList());
        assertEquals(2, service.listSnapshots(null, null, null, null, null, REGION, null, null).items().size());

        AwsException conflict = assertThrows(AwsException.class,
                () -> service.createSnapshot("snap-one", "snap-a", null, null, REGION));
        assertEquals("ConflictException", conflict.getErrorCode());
    }

    @Test
    void restoreRequiresAnExistingNamespaceWorkgroupAndSnapshot() {
        create("restore-ns");
        service.createSnapshot("restore-snap", "restore-ns", null, null, REGION);

        AwsException noWorkgroup = assertThrows(AwsException.class,
                () -> service.restoreFromSnapshot("restore-ns", "restore-wg", "restore-snap", null, REGION));
        assertEquals("ResourceNotFoundException", noWorkgroup.getErrorCode());

        createWorkgroup("restore-wg", "restore-ns");
        RedshiftServerlessService.RestoreResult restored =
                service.restoreFromSnapshot("restore-ns", "restore-wg", "restore-snap", null, REGION);
        assertEquals("AVAILABLE", restored.namespace().getStatus());
        assertEquals("restore-snap", restored.snapshot().getSnapshotName());

        AwsException noSnapshot = assertThrows(AwsException.class,
                () -> service.restoreFromSnapshot("restore-ns", "restore-wg", "missing-snap", null, REGION));
        assertEquals("ResourceNotFoundException", noSnapshot.getErrorCode());
    }

    @Test
    void restoreRejectsAWorkgroupOfAnotherNamespace() {
        create("rest-a");
        create("rest-b");
        service.createSnapshot("rest-snap", "rest-a", null, null, REGION);
        createWorkgroup("rest-b-wg", "rest-b");

        AwsException mismatch = assertThrows(AwsException.class,
                () -> service.restoreFromSnapshot("rest-a", "rest-b-wg", "rest-snap", null, REGION));
        assertEquals("ValidationException", mismatch.getErrorCode());
    }

    @Test
    void snapshotNameFromArnRejectsAnArnOutsideThisAccountAndRegion() {
        create("arn-ns");
        RedshiftServerlessSnapshot snapshot = service.createSnapshot("arn-snap", "arn-ns", null, null, REGION);

        assertEquals("arn-snap", service.snapshotNameFromArn(snapshot.getSnapshotArn(), REGION));
        for (String foreign : List.of(
                "arn:aws:redshift-serverless:eu-west-1:" + ACCOUNT_ID + ":snapshot/arn-snap",
                "arn:aws:redshift-serverless:us-east-1:999999999999:snapshot/arn-snap",
                "arn:aws:redshift-serverless:us-east-1:" + ACCOUNT_ID + ":workgroup/arn-snap")) {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.snapshotNameFromArn(foreign, REGION));
            assertEquals("ValidationException", rejected.getErrorCode());
        }
    }

    @Test
    void restoreWithoutANamespaceNameIsAValidationError() {
        create("nullns-ns");
        service.createSnapshot("nullns-snap", "nullns-ns", null, null, REGION);
        createWorkgroup("nullns-wg", "nullns-ns");

        AwsException missing = assertThrows(AwsException.class,
                () -> service.restoreFromSnapshot(null, "nullns-wg", "nullns-snap", null, REGION));
        assertEquals("ValidationException", missing.getErrorCode());
    }

    @Test
    void snapshotNamesAreValidated() {
        create("names-ns");
        for (String bad : new String[] {null, "", "ab", "has/slash", "has::colons", "UPPER"}) {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.createSnapshot(bad, "names-ns", null, null, REGION));
            assertEquals("ValidationException", rejected.getErrorCode());
        }
        AwsException missing = assertThrows(AwsException.class, () -> service.getSnapshot(null, REGION));
        assertEquals("ValidationException", missing.getErrorCode());
        assertThrows(AwsException.class, () -> service.deleteSnapshot(null, REGION));
    }

    @Test
    void createSnapshotDumpsALiveWorkgroupAndRestoreReplaysIt() throws Exception {
        create("dump-ns");
        createWorkgroup("dump-wg", "dump-ns");
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(5, Path.class), "-- dump");
            return null;
        }).when(runtime).takeSnapshot(any(), any(), any(), any(), any(), any());

        RedshiftServerlessSnapshot snapshot = service.createSnapshot("dump-snap", "dump-ns", null, null, REGION);

        assertTrue(Files.exists(Path.of(snapshot.getSqlDump())));
        service.restoreFromSnapshot("dump-ns", "dump-wg", "dump-snap", null, REGION);
        verify(runtime).restoreSnapshot(eq(ACCOUNT_ID), eq(REGION), eq("dump-wg"), any(), eq("dev"),
                eq(Path.of(snapshot.getSqlDump())));

        service.deleteSnapshot("dump-snap", REGION);
        assertFalse(Files.exists(Path.of(snapshot.getSqlDump())));
    }

    @Test
    void restoreFailsWhenTheDumpFileIsGone() throws Exception {
        create("gone-ns");
        createWorkgroup("gone-wg", "gone-ns");
        doAnswer(invocation -> {
            Files.writeString(invocation.getArgument(5, Path.class), "-- dump");
            return null;
        }).when(runtime).takeSnapshot(any(), any(), any(), any(), any(), any());
        RedshiftServerlessSnapshot snapshot = service.createSnapshot("gone-snap", "gone-ns", null, null, REGION);
        Files.delete(Path.of(snapshot.getSqlDump()));

        AwsException missing = assertThrows(AwsException.class,
                () -> service.restoreFromSnapshot("gone-ns", "gone-wg", "gone-snap", null, REGION));
        assertEquals("InternalServerException", missing.getErrorCode());
        verify(runtime, never()).restoreSnapshot(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createSnapshotWithoutALiveWorkgroupIsMetadataOnly() {
        create("meta-ns");
        createWorkgroup("meta-wg", "meta-ns");
        Workgroup stopped = new Workgroup(service.getWorkgroup("meta-wg", REGION));
        stopped.setRuntimeHost(null);
        stopped.setRuntimePort(0);
        workgroupStore.put(REGION + "::meta-wg", stopped);

        RedshiftServerlessSnapshot snapshot = service.createSnapshot("meta-snap", "meta-ns", null, null, REGION);
        service.restoreFromSnapshot("meta-ns", "meta-wg", "meta-snap", null, REGION);

        assertNull(snapshot.getSqlDump());
        verify(runtime, never()).takeSnapshot(any(), any(), any(), any(), any(), any());
        verify(runtime, never()).restoreSnapshot(any(), any(), any(), any(), any(), any());
    }

    @Test
    void snapshotCarriesRetentionNamespaceDetailsAndTags() {
        create("meta-fields-ns");

        RedshiftServerlessSnapshot snapshot = service.createSnapshot("fields-snap", "meta-fields-ns", 30,
                Map.of("env", "dev"), REGION);

        assertEquals(30, snapshot.getRetentionPeriod());
        assertEquals("admin", snapshot.getAdminUsername());
        assertEquals("AWS_OWNED_KMS_KEY", snapshot.getKmsKeyId());
        assertEquals(Map.of("env", "dev"), service.listTagsForResource(snapshot.getSnapshotArn(), REGION));

        service.tagResource(snapshot.getSnapshotArn(), Map.of("team", "data"), REGION);
        service.untagResource(snapshot.getSnapshotArn(), List.of("env"), REGION);
        assertEquals(Map.of("team", "data"), service.listTagsForResource(snapshot.getSnapshotArn(), REGION));

        assertNull(service.createSnapshot("indef-snap", "meta-fields-ns", -1, null, REGION).getRetentionPeriod());
        for (int bad : new int[] {0, 3654, -2}) {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.createSnapshot("bad-ret-snap", "meta-fields-ns", bad, null, REGION));
            assertEquals("ValidationException", rejected.getErrorCode());
        }
    }

    @Test
    void listSnapshotsAppliesEveryFilter() throws Exception {
        create("filt-a");
        create("filt-b");
        RedshiftServerlessSnapshot first = service.createSnapshot("filt-one", "filt-a", null, null, REGION);
        service.createSnapshot("filt-two", "filt-b", null, null, REGION);
        Instant created = first.getSnapshotCreateTime();

        assertEquals(1, service.listSnapshots(null, first.getNamespaceArn(), null, null, null, REGION, null, null)
                .items().size());
        assertEquals(2, service.listSnapshots(null, null, ACCOUNT_ID, null, null, REGION, null, null).items().size());
        assertEquals(0, service.listSnapshots(null, null, "999999999999", null, null, REGION, null, null)
                .items().size());
        assertEquals(0, service.listSnapshots(null, null, null, created.plusSeconds(3600), null, REGION, null, null)
                .items().size());
        assertEquals(0, service.listSnapshots(null, null, null, null, created.minusSeconds(3600), REGION, null, null)
                .items().size());
        assertEquals(2, service.listSnapshots(null, null, null, created.minusSeconds(3600),
                created.plusSeconds(3600), REGION, null, null).items().size());
    }

    @Test
    void restoreRejectsASnapshotOwnedByAnotherAccount() {
        create("owner-ns");
        service.createSnapshot("owner-snap", "owner-ns", null, null, REGION);
        createWorkgroup("owner-wg", "owner-ns");

        AwsException missing = assertThrows(AwsException.class,
                () -> service.restoreFromSnapshot("owner-ns", "owner-wg", "owner-snap", "999999999999", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        service.restoreFromSnapshot("owner-ns", "owner-wg", "owner-snap", ACCOUNT_ID, REGION);
    }

    @Test
    void deleteSnapshotRemovesIt() {
        create("snapdel-ns");
        service.createSnapshot("snapdel", "snapdel-ns", null, null, REGION);

        service.deleteSnapshot("snapdel", REGION);

        AwsException missing = assertThrows(AwsException.class, () -> service.getSnapshot("snapdel", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    private Namespace create(String namespaceName) {
        return service.createNamespace(namespaceName, "admin", null, null, null, null, null, Map.of(), REGION);
    }

    private Workgroup createWorkgroup(String workgroupName, String namespaceName) {
        return service.createWorkgroup(workgroupName, namespaceName, emptySettings(), null, REGION);
    }

    private static WorkgroupSettings emptySettings() {
        return new WorkgroupSettings(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static WorkgroupSettings settings(Integer port, String ipAddressType, PricePerformanceTarget target,
                                              String trackName) {
        return new WorkgroupSettings(null, null, null, null, null, null, null, null, port, target,
                ipAddressType, trackName);
    }
}

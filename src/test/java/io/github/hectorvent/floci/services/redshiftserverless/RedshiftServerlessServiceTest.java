package io.github.hectorvent.floci.services.redshiftserverless;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftServerlessServiceTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "123456789012";

    private RedshiftServerlessService service;
    private AccountAwareStorageBackend<Namespace> store;

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

        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.buildArn(eq("redshift-serverless"), any(String.class), any(String.class)))
                .thenAnswer(invocation -> "arn:aws:redshift-serverless:"
                        + invocation.getArgument(1, String.class) + ":" + ACCOUNT_ID + ":"
                        + invocation.getArgument(2, String.class));
        service = new RedshiftServerlessService(storageFactory, regionResolver);
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

        var firstPage = service.listNamespaces(REGION, 1, null);
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

    private Namespace create(String namespaceName) {
        return service.createNamespace(namespaceName, "admin", null, null, null, null, null, Map.of(), REGION);
    }
}

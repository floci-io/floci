package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociationSummary;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksPodIdentityAssociationServiceTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/pod-role";
    private static final String NAMESPACE = "default";
    private static final String SERVICE_ACCOUNT = "app-sa";

    @Test
    void createAndDescribeAssociation() throws Exception {
        Fixture fixture = fixture();
        CreatePodIdentityAssociationRequest request = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, "token-1",
                Map.of("env", "prod"), null, false, null);

        PodIdentityAssociation created = fixture.service.create(fixture.cluster, request);

        assertEquals("test-cluster", created.clusterName());
        assertEquals(NAMESPACE, created.namespace());
        assertEquals(SERVICE_ACCOUNT, created.serviceAccount());
        assertEquals(ROLE, created.roleArn());
        assertNotNull(created.associationId());
        assertTrue(created.associationId().startsWith("a-"));
        assertEquals(19, created.associationId().length());
        assertEquals("arn:aws:eks:us-east-1:123456789012:podidentityassociation/test-cluster/" + created.associationId(),
                created.associationArn());
        assertEquals(Map.of("env", "prod"), created.tags());
        assertTrue(created.createdAt() > 0);
        assertEquals(created.createdAt(), created.modifiedAt());
        assertNotNull(created.externalId());
        assertNull(created.ownerArn());

        // Verify serialization roundtrip
        EksPodIdentityAssociationService.StoredAssociation stored =
                fixture.storage.scan(key -> true).getFirst();
        ObjectMapper mapper = new ObjectMapper();
        EksPodIdentityAssociationService.StoredAssociation restored = mapper.readValue(
                mapper.writeValueAsBytes(stored), EksPodIdentityAssociationService.StoredAssociation.class);
        assertEquals(stored, restored);

        // Describe returns the same association
        PodIdentityAssociation described = fixture.service.describe(fixture.cluster, created.associationId());
        assertEquals(created, described);
    }

    @Test
    void retriesAreIdempotentButConflictingRequestsAndDuplicatesFail() {
        Fixture fixture = fixture();
        CreatePodIdentityAssociationRequest request = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, "retry-token",
                Map.of("app", "api"), null, null, null);

        PodIdentityAssociation first = fixture.service.create(fixture.cluster, request);
        PodIdentityAssociation retry = fixture.service.create(fixture.cluster, request);
        assertEquals(first, retry);

        // Same token with different parameters throws InvalidParameterException
        CreatePodIdentityAssociationRequest conflictingTokenRequest = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), "other-ns", SERVICE_ACCOUNT, ROLE, "retry-token",
                Map.of("app", "api"), null, null, null);
        AwsException exToken = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, conflictingTokenRequest));
        assertEquals("InvalidParameterException", exToken.getErrorCode());
        assertEquals(400, exToken.getHttpStatus());

        // Duplicate (namespace, serviceAccount) with different token throws ResourceInUseException (409)
        CreatePodIdentityAssociationRequest duplicateRequest = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, "different-token",
                Map.of(), null, null, null);
        AwsException exDup = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, duplicateRequest));
        assertEquals("ResourceInUseException", exDup.getErrorCode());
        assertEquals(409, exDup.getHttpStatus());
        assertTrue(exDup.getMessage().contains("Association already exists: " + first.associationId()));
    }

    @Test
    void updateAssociationValidationsAndModifiedAt() {
        Fixture fixture = fixture();
        CreatePodIdentityAssociationRequest createRequest = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, null, Map.of(), null, false, null);
        PodIdentityAssociation created = fixture.service.create(fixture.cluster, createRequest);

        // Update with no parameters fails
        UpdatePodIdentityAssociationRequest emptyUpdate = new UpdatePodIdentityAssociationRequest(
                null, null, null, null, null);
        AwsException exEmpty = assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, created.associationId(), emptyUpdate));
        assertEquals("InvalidParameterException", exEmpty.getErrorCode());
        assertEquals(400, exEmpty.getHttpStatus());

        // Update with non-existent role fails
        String nonExistentRole = "arn:aws:iam::123456789012:role/no-such-role";
        when(fixture.iam.findRole("123456789012", "no-such-role")).thenReturn(Optional.empty());
        UpdatePodIdentityAssociationRequest badRoleUpdate = new UpdatePodIdentityAssociationRequest(
                nonExistentRole, null, null, null, null);
        AwsException exRole = assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, created.associationId(), badRoleUpdate));
        assertEquals("InvalidParameterException", exRole.getErrorCode());

        // Update with valid new role and session tags
        String newRole = "arn:aws:iam::123456789012:role/new-role";
        when(fixture.iam.findRole("123456789012", "new-role")).thenReturn(Optional.of(
                new IamRole("AROA-new", "new-role", "/", newRole, "{}")));
        UpdatePodIdentityAssociationRequest validUpdate = new UpdatePodIdentityAssociationRequest(
                newRole, "update-token", null, true, null);

        PodIdentityAssociation updated = fixture.service.update(fixture.cluster, created.associationId(), validUpdate);
        assertEquals(newRole, updated.roleArn());
        assertTrue(updated.disableSessionTags());
        assertTrue(updated.modifiedAt() >= created.createdAt());

        // Idempotent retry of update
        PodIdentityAssociation retryUpdate = fixture.service.update(fixture.cluster, created.associationId(), validUpdate);
        assertEquals(updated, retryUpdate);

        // Update non-existent association
        assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, "a-unknown", validUpdate));
    }

    @Test
    void deleteAssociationReturnsDeletedAndRemovesFromStore() {
        Fixture fixture = fixture();
        CreatePodIdentityAssociationRequest request = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, null, Map.of(), null, null, null);
        PodIdentityAssociation created = fixture.service.create(fixture.cluster, request);

        PodIdentityAssociation deleted = fixture.service.delete(fixture.cluster, created.associationId());
        assertEquals(created.associationId(), deleted.associationId());

        AwsException ex = assertThrows(AwsException.class, () ->
                fixture.service.describe(fixture.cluster, created.associationId()));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());

        assertThrows(AwsException.class, () ->
                fixture.service.delete(fixture.cluster, created.associationId()));
    }

    @Test
    void listWithFiltersAndPagination() {
        Fixture fixture = fixture();
        String role2 = "arn:aws:iam::123456789012:role/role2";
        when(fixture.iam.findRole("123456789012", "role2")).thenReturn(Optional.of(
                new IamRole("AROA-role2", "role2", "/", role2, "{}")));
        String role3 = "arn:aws:iam::123456789012:role/role3";
        when(fixture.iam.findRole("123456789012", "role3")).thenReturn(Optional.of(
                new IamRole("AROA-role3", "role3", "/", role3, "{}")));

        PodIdentityAssociation a1 = fixture.service.create(fixture.cluster,
                new CreatePodIdentityAssociationRequest(fixture.cluster.getName(), "ns1", "sa1", ROLE, null, Map.of(), null, null, null));
        PodIdentityAssociation a2 = fixture.service.create(fixture.cluster,
                new CreatePodIdentityAssociationRequest(fixture.cluster.getName(), "ns1", "sa2", role2, null, Map.of(), null, null, null));
        PodIdentityAssociation a3 = fixture.service.create(fixture.cluster,
                new CreatePodIdentityAssociationRequest(fixture.cluster.getName(), "ns2", "sa1", role3, null, Map.of(), null, null, null));

        // Filter by namespace
        EksPodIdentityAssociationService.Page ns1Page = fixture.service.list(fixture.cluster, "ns1", null, null, null);
        assertEquals(2, ns1Page.associations().size());
        assertTrue(ns1Page.associations().stream().allMatch(s -> "ns1".equals(s.namespace())));

        // Filter by serviceAccount
        EksPodIdentityAssociationService.Page sa1Page = fixture.service.list(fixture.cluster, null, "sa1", null, null);
        assertEquals(2, sa1Page.associations().size());
        assertTrue(sa1Page.associations().stream().allMatch(s -> "sa1".equals(s.serviceAccount())));

        // Filter by both
        EksPodIdentityAssociationService.Page bothPage = fixture.service.list(fixture.cluster, "ns2", "sa1", null, null);
        assertEquals(1, bothPage.associations().size());
        assertEquals(a3.associationId(), bothPage.associations().getFirst().associationId());

        // Pagination with maxResults = 1
        EksPodIdentityAssociationService.Page p1 = fixture.service.list(fixture.cluster, null, null, 1, null);
        assertEquals(1, p1.associations().size());
        assertNotNull(p1.nextToken());

        EksPodIdentityAssociationService.Page p2 = fixture.service.list(fixture.cluster, null, null, 1, p1.nextToken());
        assertEquals(1, p2.associations().size());
        assertNotNull(p2.nextToken());
        assertNotEquals(p1.associations().getFirst().associationId(), p2.associations().getFirst().associationId());

        EksPodIdentityAssociationService.Page p3 = fixture.service.list(fixture.cluster, null, null, 1, p2.nextToken());
        assertEquals(1, p3.associations().size());
        assertNull(p3.nextToken());

        // Out of range maxResults
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, null, null, 0, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, null, null, 101, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, null, null, 1, "invalid-token"));
    }

    @Test
    void clusterDeletionCleansAssociationsAndRecreationCannotInheritThem() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster,
                new CreatePodIdentityAssociationRequest(fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, null, Map.of(), null, null, null));

        assertEquals(1, fixture.service.list(fixture.cluster, null, null, null, null).associations().size());

        fixture.service.deleteClusterAssociations(fixture.cluster);
        assertTrue(fixture.storage.scan(key -> true).isEmpty());

        // Recreated cluster with new createdAt cannot inherit old associations or pagination tokens
        fixture.service.create(fixture.cluster,
                new CreatePodIdentityAssociationRequest(fixture.cluster.getName(), "ns1", "sa1", ROLE, null, Map.of(), null, null, null));
        fixture.service.create(fixture.cluster,
                new CreatePodIdentityAssociationRequest(fixture.cluster.getName(), "ns2", "sa2", ROLE, null, Map.of(), null, null, null));
        EksPodIdentityAssociationService.Page pageBefore = fixture.service.list(fixture.cluster, null, null, 1, null);
        assertNotNull(pageBefore.nextToken());

        fixture.cluster.setCreatedAt(fixture.cluster.getCreatedAt().plusSeconds(10));
        assertTrue(fixture.service.list(fixture.cluster, null, null, null, null).associations().isEmpty());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, null, null, 1, pageBefore.nextToken()));
    }

    @Test
    void inactiveClusterRejectsOperations() {
        Fixture fixture = fixture();
        fixture.cluster.setStatus(ClusterStatus.CREATING);

        CreatePodIdentityAssociationRequest request = new CreatePodIdentityAssociationRequest(
                fixture.cluster.getName(), NAMESPACE, SERVICE_ACCOUNT, ROLE, null, Map.of(), null, null, null);
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster, request));
        assertThrows(AwsException.class, () -> fixture.service.describe(fixture.cluster, "a-123"));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, null, null, null, null));
    }

    private static Fixture fixture() {
        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");
        cluster.setCreatedAt(Instant.parse("2026-09-20T12:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);

        IamService iam = mock(IamService.class);
        IamRole role = new IamRole("AROA-pod", "pod-role", "/", ROLE, "{}");
        when(iam.findRole("123456789012", "pod-role")).thenReturn(Optional.of(role));

        InMemoryStorage<String, EksPodIdentityAssociationService.StoredAssociation> storage = new InMemoryStorage<>();
        return new Fixture(cluster, iam, role, storage, new EksPodIdentityAssociationService(storage, iam));
    }

    private record Fixture(Cluster cluster, IamService iam, IamRole role,
                           InMemoryStorage<String, EksPodIdentityAssociationService.StoredAssociation> storage,
                           EksPodIdentityAssociationService service) {}
}

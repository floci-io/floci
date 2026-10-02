package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.AssociatedAccessPolicy;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAccessEntryServiceTest {
    private static final String PRINCIPAL = "arn:aws:iam::123456789012:role/path/worker";
    private static final String ADMIN = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy";
    private static final String VIEW = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
    private static final String EDIT = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy";

    @Test
    void nodeEntryUsesGeneratedIdentityAndCapturesStablePrincipalId() throws Exception {
        Fixture fixture = fixture();
        AccessEntry entry = fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "retry"));
        assertEquals("system:node:{{EC2PrivateDNSName}}", entry.username());
        assertEquals(List.of("system:nodes"), entry.kubernetesGroups());
        assertEquals(Map.of("team", "platform"), entry.tags());
        assertTrue(entry.createdAt() > 0);
        EksAccessEntryService.StoredEntry stored = fixture.storage.scan(key -> true).getFirst();
        assertEquals("AROA-worker", stored.principalId());
        ObjectMapper mapper = new ObjectMapper();
        EksAccessEntryService.StoredEntry restored = mapper.readValue(mapper.writeValueAsBytes(stored),
                EksAccessEntryService.StoredEntry.class);
        assertEquals(stored, restored);
        assertFalse(mapper.writeValueAsString(entry).contains("principalId"));
        fixture.role.setRoleId("AROA-recreated");
        assertEquals("AROA-worker", fixture.storage.scan(key -> true).getFirst().principalId());
        assertEquals(entry, fixture.service.describe(fixture.cluster, PRINCIPAL));
    }

    @Test
    void retriesAreIdempotentButConflictingRequestsAndDuplicatesFail() {
        Fixture fixture = fixture();
        CreateAccessEntryRequest request = request(PRINCIPAL, "EC2_LINUX", "retry");
        AccessEntry first = fixture.service.create(fixture.cluster, request);
        assertEquals(first, fixture.service.create(fixture.cluster, request));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "STANDARD", "retry"))).getErrorCode());
        assertEquals(409, assertThrows(AwsException.class, () -> fixture.service.create(
                fixture.cluster, request(PRINCIPAL, "EC2_LINUX", "other"))).getHttpStatus());
    }

    @Test
    void standardRoleDefaultsStripTheIamPath() {
        Fixture fixture = fixture();
        AccessEntry entry = fixture.service.create(fixture.cluster, request(PRINCIPAL, null, null));
        assertEquals("STANDARD", entry.type());
        assertEquals("arn:aws:sts::123456789012:assumed-role/worker/{{SessionName}}", entry.username());
    }

    @Test
    void standardUserCanBelongToAnotherAccount() {
        Fixture fixture = fixture();
        String principal = "arn:aws:iam::999999999999:user/team/reader";
        when(fixture.iam.findUser("999999999999", "reader")).thenReturn(Optional.of(
                new IamUser("AIDA-reader", "reader", "/team/", principal)));
        AccessEntry entry = fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(principal, null, null, List.of("readers"), null, null));
        assertEquals(principal, entry.username());
        assertEquals(List.of("readers"), entry.kubernetesGroups());
        assertEquals("AIDA-reader", fixture.storage.scan(key -> true).getFirst().principalId());
    }

    @Test
    void nodeOverridesForeignAccountsAndMissingPrincipalsAreRejected() {
        Fixture fixture = fixture();
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, "EC2_LINUX", "custom", null, null, null)));
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                new CreateAccessEntryRequest(PRINCIPAL, "EC2_LINUX", null, List.of(), null, null)));
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL.replace("123456789012", "999999999999"), "EC2_LINUX", null)));
        when(fixture.iam.findRole("123456789012", "worker")).thenReturn(Optional.empty());
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL, "STANDARD", null)));
    }

    @Test
    void paginationTokensCannotCrossClusterOrAccountBoundaries() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        String second = PRINCIPAL.replace("worker", "worker2");
        when(fixture.iam.findRole("123456789012", "worker2")).thenReturn(Optional.of(
                new IamRole("AROA-second", "worker2", "/path/", second, "{}")));
        fixture.service.create(fixture.cluster, request(second, "STANDARD", null));
        EksAccessEntryService.Page first = fixture.service.list(fixture.cluster, 1, null);
        assertEquals(List.of(PRINCIPAL), first.accessEntries());
        assertNotNull(first.nextToken());
        EksAccessEntryService.Page next = fixture.service.list(fixture.cluster, 1, first.nextToken());
        assertEquals(List.of(second), next.accessEntries());
        assertNull(next.nextToken());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 0, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 101, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 1, "invalid"));
        fixture.cluster.setArn(fixture.cluster.getArn().replace("123456789012", "999999999999"));
        assertTrue(fixture.service.list(fixture.cluster, 100, null).accessEntries().isEmpty());
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 1, first.nextToken()));
    }

    @Test
    void clusterDeletionCleansEntriesAndRecreationCannotInheritThem() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        fixture.service.deleteClusterEntries(fixture.cluster);
        assertTrue(fixture.storage.scan(key -> true).isEmpty());
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        fixture.cluster.setCreatedAt(fixture.cluster.getCreatedAt().plusSeconds(1));
        assertTrue(fixture.service.list(fixture.cluster, null, null).accessEntries().isEmpty());
        assertEquals(404, assertThrows(AwsException.class,
                () -> fixture.service.describe(fixture.cluster, PRINCIPAL)).getHttpStatus());
    }

    @Test
    void configMapAndInactiveClustersRejectAccessEntryOperations() {
        Fixture fixture = fixture();
        fixture.cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", true));
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.list(fixture.cluster, null, null)).getErrorCode());
        fixture.cluster.setAccessConfig(new AccessConfig("API", false));
        fixture.cluster.setStatus(ClusterStatus.CREATING);
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster,
                request(PRINCIPAL, "EC2_LINUX", null)));
    }

    @Test
    void associatingAgainReplacesTheScopeAndKeepsTheAssociationTime() throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        AssociatedAccessPolicy first = fixture.service.associate(fixture.cluster, PRINCIPAL,
                policy(ADMIN, "cluster", null));
        assertEquals(new AccessScope("cluster", List.of()), first.accessScope());
        assertEquals(first.associatedAt(), first.modifiedAt());
        Thread.sleep(5);
        AssociatedAccessPolicy replaced = fixture.service.associate(fixture.cluster, PRINCIPAL,
                policy(ADMIN, "namespace", List.of("dev-*", "payments", "dev-*")));
        assertEquals(new AccessScope("namespace", List.of("dev-*", "payments")), replaced.accessScope());
        assertEquals(first.associatedAt(), replaced.associatedAt());
        assertTrue(replaced.modifiedAt() > first.modifiedAt());
        fixture.service.associate(fixture.cluster, PRINCIPAL, policy(VIEW, "cluster", List.of()));
        assertEquals(List.of(ADMIN, VIEW), fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null)
                .associatedAccessPolicies().stream().map(AssociatedAccessPolicy::policyArn).toList());
        assertEquals(replaced, fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null)
                .associatedAccessPolicies().getFirst());
    }

    @Test
    void disassociatingRemovesOnlyThatPolicyAndAMissingOneIsNotFound() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        fixture.service.associate(fixture.cluster, PRINCIPAL, policy(ADMIN, "cluster", null));
        fixture.service.associate(fixture.cluster, PRINCIPAL, policy(VIEW, "cluster", null));
        fixture.service.disassociate(fixture.cluster, PRINCIPAL, ADMIN);
        assertEquals(List.of(VIEW), fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null)
                .associatedAccessPolicies().stream().map(AssociatedAccessPolicy::policyArn).toList());
        AwsException missing = assertThrows(AwsException.class,
                () -> fixture.service.disassociate(fixture.cluster, PRINCIPAL, ADMIN));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
        assertEquals(404, missing.getHttpStatus());
    }

    @Test
    void deletingTheEntryDropsItsPoliciesSoARecreatedEntryStartsEmpty() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        fixture.service.associate(fixture.cluster, PRINCIPAL, policy(ADMIN, "cluster", null));
        fixture.service.delete(fixture.cluster, PRINCIPAL);
        assertEquals(404, assertThrows(AwsException.class, () -> fixture.service.listAccessPolicies(
                fixture.cluster, PRINCIPAL, null, null)).getHttpStatus());
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        assertTrue(fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null)
                .associatedAccessPolicies().isEmpty());
    }

    @Test
    void invalidAssociationsAreRejectedWithoutStoringAnything() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        for (AssociateAccessPolicyRequest invalid : List.of(
                new AssociateAccessPolicyRequest(null, new AccessScope("cluster", null)),
                new AssociateAccessPolicyRequest(ADMIN, null),
                policy("arn:aws:iam::aws:policy/AdministratorAccess", "cluster", null),
                policy(ADMIN.replace("arn:aws:", "arn:aws-cn:"), "cluster", null),
                policy(ADMIN + "/extra", "cluster", null),
                policy(ADMIN, null, null),
                policy(ADMIN, "global", null),
                policy(ADMIN, "namespace", null),
                policy(ADMIN, "namespace", List.of()),
                policy(ADMIN, "namespace", Arrays.asList("dev", null)),
                policy(ADMIN, "namespace", List.of("dev", " ")),
                policy(ADMIN, "cluster", List.of("dev")))) {
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.associate(fixture.cluster, PRINCIPAL, invalid)).getErrorCode(),
                    invalid.toString());
        }
        assertTrue(fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null)
                .associatedAccessPolicies().isEmpty());
        assertEquals(404, assertThrows(AwsException.class, () -> fixture.service.associate(fixture.cluster,
                PRINCIPAL.replace("worker", "absent"), policy(ADMIN, "cluster", null))).getHttpStatus());
    }

    @Test
    void onlyStandardEntriesTakeAccessPolicies() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "EC2_LINUX", null));
        assertEquals("InvalidRequestException", assertThrows(AwsException.class, () -> fixture.service.associate(
                fixture.cluster, PRINCIPAL, policy(ADMIN, "cluster", null))).getErrorCode());
        assertTrue(fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null)
                .associatedAccessPolicies().isEmpty());
    }

    @Test
    void associatedPolicyPagesCannotBeContinuedForAnotherEntry() {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        String second = PRINCIPAL.replace("worker", "worker2");
        when(fixture.iam.findRole("123456789012", "worker2")).thenReturn(Optional.of(
                new IamRole("AROA-second", "worker2", "/path/", second, "{}")));
        fixture.service.create(fixture.cluster, request(second, "STANDARD", null));
        fixture.service.associate(fixture.cluster, PRINCIPAL, policy(ADMIN, "cluster", null));
        fixture.service.associate(fixture.cluster, PRINCIPAL, policy(VIEW, "cluster", null));
        fixture.service.associate(fixture.cluster, second, policy(EDIT, "cluster", null));
        fixture.service.associate(fixture.cluster, second, policy(VIEW, "cluster", null));
        EksAccessEntryService.PolicyPage first = fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, 1, null);
        assertEquals(List.of(ADMIN), first.associatedAccessPolicies().stream().map(AssociatedAccessPolicy::policyArn).toList());
        assertNotNull(first.nextToken());
        EksAccessEntryService.PolicyPage next = fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, 1,
                first.nextToken());
        assertEquals(List.of(VIEW), next.associatedAccessPolicies().stream().map(AssociatedAccessPolicy::policyArn).toList());
        assertNull(next.nextToken());
        for (Executable invalid : List.<Executable>of(
                () -> fixture.service.listAccessPolicies(fixture.cluster, second, 1, first.nextToken()),
                () -> fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, 0, null),
                () -> fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, 101, null),
                () -> fixture.service.listAccessPolicies(fixture.cluster, PRINCIPAL, 1, "x"))) {
            assertEquals("InvalidParameterException", assertThrows(AwsException.class, invalid).getErrorCode());
        }
    }

    @Test
    void entriesPersistedBeforeAccessPoliciesExistedStillLoad(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture();
        fixture.service.create(fixture.cluster, request(PRINCIPAL, "STANDARD", null));
        String key = fixture.storage.keys().iterator().next();
        EksAccessEntryService.StoredEntry stored = fixture.storage.get(key).orElseThrow();
        Path file = directory.resolve("eks-access-entries.json");
        Files.writeString(file, new ObjectMapper().writeValueAsString(Map.of(key,
                Map.of("entry", stored.entry(), "principalId", stored.principalId()))));
        TypeReference<Map<String, EksAccessEntryService.StoredEntry>> type = new TypeReference<>() {};
        PersistentStorage<String, EksAccessEntryService.StoredEntry> persisted = new PersistentStorage<>(file, type);
        persisted.load();
        EksAccessEntryService service = new EksAccessEntryService(persisted, fixture.iam);
        assertTrue(service.listAccessPolicies(fixture.cluster, PRINCIPAL, null, null).associatedAccessPolicies().isEmpty());
        AssociatedAccessPolicy association = service.associate(fixture.cluster, PRINCIPAL,
                policy(ADMIN, "namespace", List.of("dev")));
        persisted.flush();
        PersistentStorage<String, EksAccessEntryService.StoredEntry> reloaded = new PersistentStorage<>(file, type);
        reloaded.load();
        assertEquals(List.of(association), new EksAccessEntryService(reloaded, fixture.iam)
                .listAccessPolicies(fixture.cluster, PRINCIPAL, null, null).associatedAccessPolicies());
    }

    private static AssociateAccessPolicyRequest policy(String arn, String type, List<String> namespaces) {
        return new AssociateAccessPolicyRequest(arn, new AccessScope(type, namespaces));
    }

    private static CreateAccessEntryRequest request(String principal, String type, String token) {
        return new CreateAccessEntryRequest(principal, type, null, null, Map.of("team", "platform"), token);
    }

    private static Fixture fixture() {
        Cluster cluster = new Cluster();
        cluster.setName("nodes");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/nodes");
        cluster.setCreatedAt(Instant.parse("2026-09-16T10:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        IamService iam = mock(IamService.class);
        IamRole role = new IamRole("AROA-worker", "worker", "/path/", PRINCIPAL, "{}");
        when(iam.findRole("123456789012", "worker")).thenReturn(Optional.of(role));
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> storage = new InMemoryStorage<>();
        return new Fixture(cluster, iam, role, storage, new EksAccessEntryService(storage, iam));
    }

    private record Fixture(Cluster cluster, IamService iam, IamRole role,
                           InMemoryStorage<String, EksAccessEntryService.StoredEntry> storage,
                           EksAccessEntryService service) {}
}

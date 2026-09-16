package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAccessEntryServiceTest {
    private static final String PRINCIPAL = "arn:aws:iam::123456789012:role/path/worker";

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

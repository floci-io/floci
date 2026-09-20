package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonInfo;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAddonRequest;
import io.github.hectorvent.floci.services.eks.model.Update;
import io.github.hectorvent.floci.services.eks.model.UpdateAddonRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAddonServiceTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/addon-role";

    @Test
    void createAndDescribeAddon() throws Exception {
        Fixture fixture = fixture();
        CreateAddonRequest request = new CreateAddonRequest(
                "vpc-cni",
                "v1.18.1-eksbuild.1",
                ROLE,
                "OVERWRITE",
                "token-1",
                "{\"env\":{\"foo\":\"bar\"}}",
                Map.of("env", "prod"),
                null
        );

        Addon created = fixture.service.create(fixture.cluster, request);

        assertEquals("vpc-cni", created.addonName());
        assertEquals("test-cluster", created.clusterName());
        assertEquals("v1.18.1-eksbuild.1", created.addonVersion());
        assertEquals("ACTIVE", created.status());
        assertNotNull(created.addonArn());
        assertTrue(created.addonArn().startsWith("arn:aws:eks:us-east-1:123456789012:addon/test-cluster/vpc-cni/"));
        assertEquals(ROLE, created.serviceAccountRoleArn());
        assertEquals("{\"env\":{\"foo\":\"bar\"}}", created.configurationValues());
        assertEquals(Map.of("env", "prod"), created.tags());
        assertNotNull(created.health());
        assertTrue(created.health().issues().isEmpty());
        assertEquals("aws", created.owner());
        assertEquals("eks", created.publisher());
        assertTrue(created.createdAt() > 0);
        assertEquals(created.createdAt(), created.modifiedAt());

        // Serialization roundtrip
        EksAddonService.StoredAddon stored = fixture.storage.scan(k -> true).getFirst();
        ObjectMapper mapper = new ObjectMapper();
        EksAddonService.StoredAddon restored = mapper.readValue(
                mapper.writeValueAsBytes(stored), EksAddonService.StoredAddon.class);
        assertEquals(stored, restored);

        // Describe returns the same addon
        Addon described = fixture.service.describe(fixture.cluster, "vpc-cni");
        assertEquals(created, described);
    }

    @Test
    void createResolvesDefaultVersionWhenOmitted() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");

        CreateAddonRequest request = new CreateAddonRequest(
                "vpc-cni",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        Addon created = fixture.service.create(fixture.cluster, request);
        assertEquals("v1.18.1-eksbuild.1", created.addonVersion());
    }

    @Test
    void createRejectsUnknownAddonAndUnsupportedVersion() {
        Fixture fixture = fixture();

        CreateAddonRequest unknownAddon = new CreateAddonRequest(
                "unknown-plugin",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        AwsException ex1 = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, unknownAddon));
        assertEquals("InvalidParameterException", ex1.getErrorCode());
        assertEquals(400, ex1.getHttpStatus());
        assertTrue(ex1.getMessage().contains("unknown-plugin"));

        CreateAddonRequest badVersion = new CreateAddonRequest(
                "vpc-cni",
                "v99.99.99",
                null,
                null,
                null,
                null,
                null,
                null
        );
        AwsException ex2 = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, badVersion));
        assertEquals("InvalidParameterException", ex2.getErrorCode());
        assertEquals(400, ex2.getHttpStatus());
        assertTrue(ex2.getMessage().contains("Addon version specified is not supported"));
    }

    @Test
    void retriesAreIdempotentButConflictingRequestsAndDuplicatesFail() {
        Fixture fixture = fixture();
        CreateAddonRequest request = new CreateAddonRequest(
                "coredns",
                "v1.11.1-eksbuild.4",
                null,
                null,
                "retry-token",
                null,
                Map.of("k", "v"),
                null
        );

        Addon first = fixture.service.create(fixture.cluster, request);
        Addon retry = fixture.service.create(fixture.cluster, request);
        assertEquals(first, retry);

        // Same token with different parameters throws InvalidParameterException
        CreateAddonRequest conflict = new CreateAddonRequest(
                "coredns",
                "v1.11.1-eksbuild.4",
                ROLE,
                null,
                "retry-token",
                null,
                Map.of("k", "v"),
                null
        );
        AwsException exConflict = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, conflict));
        assertEquals("InvalidParameterException", exConflict.getErrorCode());
        assertEquals(400, exConflict.getHttpStatus());

        // Duplicate addon with different token throws ResourceInUseException (409)
        CreateAddonRequest duplicate = new CreateAddonRequest(
                "coredns",
                "v1.11.1-eksbuild.4",
                null,
                null,
                "different-token",
                null,
                Map.of(),
                null
        );
        AwsException exDup = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, duplicate));
        assertEquals("ResourceInUseException", exDup.getErrorCode());
        assertEquals(409, exDup.getHttpStatus());
    }

    @Test
    void updateAddonValidationsAndModifiedAt() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.30");
        CreateAddonRequest createReq = new CreateAddonRequest(
                "vpc-cni",
                "v1.18.1-eksbuild.1",
                null,
                null,
                null,
                null,
                null,
                null
        );
        Addon created = fixture.service.create(fixture.cluster, createReq);

        // Update version to another supported version
        UpdateAddonRequest updateReq = new UpdateAddonRequest(
                "v1.18.5-eksbuild.1",
                ROLE,
                "OVERWRITE",
                "up-token",
                "{\"some\":\"config\"}",
                null
        );
        Update update = fixture.service.update(fixture.cluster, "vpc-cni", updateReq);
        assertEquals("Successful", update.status());
        assertEquals("AddonUpdate", update.type());
        assertNotNull(update.id());
        assertTrue(update.errors().isEmpty());

        Addon updated = fixture.service.describe(fixture.cluster, "vpc-cni");
        assertEquals("v1.18.5-eksbuild.1", updated.addonVersion());
        assertEquals(ROLE, updated.serviceAccountRoleArn());
        assertEquals("{\"some\":\"config\"}", updated.configurationValues());
        assertTrue(updated.modifiedAt() >= created.createdAt());

        // Update with unsupported version fails
        UpdateAddonRequest badUpdate = new UpdateAddonRequest("v0.0.0", null, null, null, null, null);
        AwsException exBad = assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, "vpc-cni", badUpdate));
        assertEquals("InvalidParameterException", exBad.getErrorCode());

        // Update non-existent addon fails
        assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, "kube-proxy", updateReq));
    }

    @Test
    void deleteAddonReturnsDeletedAndRemovesFromStore() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");
        CreateAddonRequest request = new CreateAddonRequest("kube-proxy", null, null, null, null, null, null, null);
        Addon created = fixture.service.create(fixture.cluster, request);

        Addon deleted = fixture.service.delete(fixture.cluster, "kube-proxy", false);
        assertEquals("DELETING", deleted.status());
        assertEquals("kube-proxy", deleted.addonName());

        AwsException ex = assertThrows(AwsException.class, () ->
                fixture.service.describe(fixture.cluster, "kube-proxy"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());

        assertThrows(AwsException.class, () ->
                fixture.service.delete(fixture.cluster, "kube-proxy", false));
    }

    @Test
    void listAddonsWithPagination() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");

        fixture.service.create(fixture.cluster, new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null));
        fixture.service.create(fixture.cluster, new CreateAddonRequest("coredns", null, null, null, null, null, null, null));
        fixture.service.create(fixture.cluster, new CreateAddonRequest("kube-proxy", null, null, null, null, null, null, null));

        // List all
        EksAddonService.AddonNamesPage allPage = fixture.service.list(fixture.cluster, null, null);
        assertEquals(3, allPage.addons().size());
        assertEquals(List.of("coredns", "kube-proxy", "vpc-cni"), allPage.addons());
        assertNull(allPage.nextToken());

        // Paginate limit 1
        EksAddonService.AddonNamesPage p1 = fixture.service.list(fixture.cluster, 1, null);
        assertEquals(1, p1.addons().size());
        assertEquals("coredns", p1.addons().getFirst());
        assertNotNull(p1.nextToken());

        EksAddonService.AddonNamesPage p2 = fixture.service.list(fixture.cluster, 1, p1.nextToken());
        assertEquals(1, p2.addons().size());
        assertEquals("kube-proxy", p2.addons().getFirst());
        assertNotNull(p2.nextToken());

        EksAddonService.AddonNamesPage p3 = fixture.service.list(fixture.cluster, 1, p2.nextToken());
        assertEquals(1, p3.addons().size());
        assertEquals("vpc-cni", p3.addons().getFirst());
        assertNull(p3.nextToken());

        // Invalid maxResults
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 0, null));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, 101, null));
    }

    @Test
    void clusterDeletionCleansAddonsAndRecreationCannotInheritThem() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");
        fixture.service.create(fixture.cluster, new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null));

        assertEquals(1, fixture.service.list(fixture.cluster, null, null).addons().size());

        fixture.service.deleteClusterAddons(fixture.cluster);
        assertTrue(fixture.storage.scan(k -> true).isEmpty());

        // Recreated cluster with new createdAt cannot inherit old addons or pagination tokens
        fixture.service.create(fixture.cluster, new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null));
        fixture.service.create(fixture.cluster, new CreateAddonRequest("coredns", null, null, null, null, null, null, null));
        EksAddonService.AddonNamesPage pageBefore = fixture.service.list(fixture.cluster, 1, null);
        assertNotNull(pageBefore.nextToken());

        fixture.cluster.setCreatedAt(fixture.cluster.getCreatedAt().plusSeconds(10));
        assertTrue(fixture.service.list(fixture.cluster, null, null).addons().isEmpty());
    }

    @Test
    void inactiveClusterRejectsOperations() {
        Fixture fixture = fixture();
        fixture.cluster.setStatus(ClusterStatus.CREATING);

        CreateAddonRequest request = new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null);
        assertThrows(AwsException.class, () -> fixture.service.create(fixture.cluster, request));
        assertThrows(AwsException.class, () -> fixture.service.describe(fixture.cluster, "vpc-cni"));
        assertThrows(AwsException.class, () -> fixture.service.list(fixture.cluster, null, null));
        assertThrows(AwsException.class, () -> fixture.service.update(fixture.cluster, "vpc-cni", null));
        assertThrows(AwsException.class, () -> fixture.service.delete(fixture.cluster, "vpc-cni", false));
    }

    @Test
    void isAddonInstalledQuery() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");

        assertFalse(fixture.service.isAddonInstalled(fixture.cluster, "vpc-cni"));
        assertFalse(fixture.service.isAddonInstalled("test-cluster", "vpc-cni"));

        fixture.service.create(fixture.cluster, new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null));

        assertTrue(fixture.service.isAddonInstalled(fixture.cluster, "vpc-cni"));
        assertTrue(fixture.service.isAddonInstalled("test-cluster", "vpc-cni"));
        assertFalse(fixture.service.isAddonInstalled(fixture.cluster, "coredns"));

        fixture.service.delete(fixture.cluster, "vpc-cni", false);
        assertFalse(fixture.service.isAddonInstalled(fixture.cluster, "vpc-cni"));
        assertFalse(fixture.service.isAddonInstalled("test-cluster", "vpc-cni"));
    }

    @Test
    void describeAddonVersionsWithFilteringAndPagination() {
        Fixture fixture = fixture();

        // All addons
        EksAddonService.AddonVersionsPage all = fixture.service.describeAddonVersions(
                null, null, null, null, null, null, null);
        assertTrue(all.addons().size() >= 4);

        // Filter by addonName
        EksAddonService.AddonVersionsPage vpcCniOnly = fixture.service.describeAddonVersions(
                "vpc-cni", null, null, null, null, null, null);
        assertEquals(1, vpcCniOnly.addons().size());
        assertEquals("vpc-cni", vpcCniOnly.addons().getFirst().addonName());

        // Filter by kubernetesVersion
        EksAddonService.AddonVersionsPage k8s128 = fixture.service.describeAddonVersions(
                "vpc-cni", "1.28", null, null, null, null, null);
        assertEquals(1, k8s128.addons().size());
        AddonInfo info128 = k8s128.addons().getFirst();
        assertTrue(info128.addonVersions().stream()
                .anyMatch(v -> "v1.16.0-eksbuild.1".equals(v.addonVersion())));

        // Unknown addon returns empty list
        EksAddonService.AddonVersionsPage empty = fixture.service.describeAddonVersions(
                "unknown-addon", null, null, null, null, null, null);
        assertTrue(empty.addons().isEmpty());

        // Pagination
        EksAddonService.AddonVersionsPage page1 = fixture.service.describeAddonVersions(
                null, null, 2, null, null, null, null);
        assertEquals(2, page1.addons().size());
        assertNotNull(page1.nextToken());

        EksAddonService.AddonVersionsPage page2 = fixture.service.describeAddonVersions(
                null, null, 2, page1.nextToken(), null, null, null);
        assertTrue(page2.addons().size() >= 2);
    }

    @Test
    void roleArnAndTagValidations() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");

        // Bad role ARN format
        CreateAddonRequest badRoleArn = new CreateAddonRequest(
                "vpc-cni", null, "invalid-arn", null, null, null, null, null);
        AwsException exRole = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, badRoleArn));
        assertEquals("InvalidParameterException", exRole.getErrorCode());

        // Non-existent role
        when(fixture.iam.findRole("123456789012", "missing-role")).thenReturn(Optional.empty());
        CreateAddonRequest missingRole = new CreateAddonRequest(
                "vpc-cni", null, "arn:aws:iam::123456789012:role/missing-role", null, null, null, null, null);
        AwsException exMiss = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, missingRole));
        assertEquals("InvalidParameterException", exMiss.getErrorCode());
        assertTrue(exMiss.getMessage().contains("Role not found"));

        // Too many tags (>50)
        Map<String, String> tooManyTags = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            tooManyTags.put("key" + i, "val" + i);
        }
        CreateAddonRequest tagOver = new CreateAddonRequest(
                "vpc-cni", null, null, null, null, null, tooManyTags, null);
        AwsException exTags = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, tagOver));
        assertEquals("InvalidParameterException", exTags.getErrorCode());
        assertTrue(exTags.getMessage().contains("Too many tags"));
    }

    private static Fixture fixture() {
        Cluster cluster = new Cluster();
        cluster.setName("test-cluster");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/test-cluster");
        cluster.setCreatedAt(Instant.parse("2026-09-20T12:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);

        IamService iam = mock(IamService.class);
        IamRole role = new IamRole("AROA-addon", "addon-role", "/", ROLE, "{}");
        when(iam.findRole("123456789012", "addon-role")).thenReturn(Optional.of(role));

        EksAddonCatalog catalog = new EksAddonCatalog();
        InMemoryStorage<String, EksAddonService.StoredAddon> storage = new InMemoryStorage<>();
        EksAddonService service = new EksAddonService(storage, catalog, iam, null);

        return new Fixture(cluster, iam, role, storage, catalog, service);
    }

    private record Fixture(Cluster cluster, IamService iam, IamRole role,
                           InMemoryStorage<String, EksAddonService.StoredAddon> storage,
                           EksAddonCatalog catalog, EksAddonService service) {}
}

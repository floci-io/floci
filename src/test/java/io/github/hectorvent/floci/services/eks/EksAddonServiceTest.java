package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.Addon;
import io.github.hectorvent.floci.services.eks.model.AddonInfo;
import io.github.hectorvent.floci.services.eks.model.AddonPodIdentityAssociation;
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

    private static final String ACCOUNT = "123456789012";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/addon-role";

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
    void describeUpdateAndNotFound() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");
        fixture.service.create(fixture.cluster, new CreateAddonRequest("vpc-cni", null, null, null, null, null, null, null));

        Update update = fixture.service.update(fixture.cluster, "vpc-cni",
                new UpdateAddonRequest("v1.18.1-eksbuild.1", null, null, null, null, null));
        assertNotNull(update.id());
        assertEquals("Successful", update.status());
        assertEquals("AddonUpdate", update.type());

        // Describe without addonName filter
        Update fetched = fixture.service.describeUpdate(fixture.cluster, update.id(), null);
        assertEquals(update.id(), fetched.id());
        assertEquals("Successful", fetched.status());

        // Describe with matching addonName filter
        Update fetchedFiltered = fixture.service.describeUpdate(fixture.cluster, update.id(), "vpc-cni");
        assertEquals(update.id(), fetchedFiltered.id());

        // Describe with mismatched addonName filter
        AwsException exMismatch = assertThrows(AwsException.class, () ->
                fixture.service.describeUpdate(fixture.cluster, update.id(), "coredns"));
        assertEquals("ResourceNotFoundException", exMismatch.getErrorCode());
        assertEquals(404, exMismatch.getHttpStatus());

        // Describe nonexistent updateId
        AwsException exNotFound = assertThrows(AwsException.class, () ->
                fixture.service.describeUpdate(fixture.cluster, "missing-update-id", null));
        assertEquals("ResourceNotFoundException", exNotFound.getErrorCode());
        assertEquals(404, exNotFound.getHttpStatus());
    }

    @Test
    void podIdentityAssociationsLifecycle() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");

        AddonPodIdentityAssociation assoc1 = new AddonPodIdentityAssociation(ROLE, "aws-node");
        CreateAddonRequest request = new CreateAddonRequest(
                "vpc-cni", null, null, null, null, null, null, List.of(assoc1));

        Addon created = fixture.service.create(fixture.cluster, request);
        assertEquals(1, created.podIdentityAssociations().size());
        String assocArn = created.podIdentityAssociations().getFirst();

        // Verify association was created in kube-system namespace
        EksPodIdentityAssociationService.Page page = fixture.podIdentityAssociations.list(
                fixture.cluster, "kube-system", "aws-node", null, null);
        assertEquals(1, page.associations().size());
        assertEquals(assocArn, page.associations().getFirst().associationArn());

        // Update with same or new association succeeds without duplicate error
        Update update = fixture.service.update(fixture.cluster, "vpc-cni",
                new UpdateAddonRequest(null, null, null, null, null, List.of(assoc1)));
        assertNotNull(update.id());
        // Verify UpdateParam for PodIdentityAssociations is JSON string, not Java toString()
        assertTrue(update.params().stream().anyMatch(p ->
                "PodIdentityAssociations".equals(p.type())
                && p.value().contains("\"roleArn\"")
                && !p.value().startsWith("AddonPodIdentityAssociation[")
        ));

        // Re-read addon and verify association still present in kube-system
        Addon updated = fixture.service.describe(fixture.cluster, "vpc-cni");
        assertEquals(1, updated.podIdentityAssociations().size());

        // Update with invalid association fails validation BEFORE deleting existing associations
        AddonPodIdentityAssociation invalidAssoc = new AddonPodIdentityAssociation(
                "arn:aws:iam::" + ACCOUNT + ":role/non-existent-role", "aws-node");
        AwsException exInvalidUpdate = assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, "vpc-cni",
                        new UpdateAddonRequest(null, null, null, null, null, List.of(invalidAssoc))));
        assertEquals("InvalidParameterException", exInvalidUpdate.getErrorCode());
        // Verify existing association was NOT deleted
        Addon afterFailedUpdate = fixture.service.describe(fixture.cluster, "vpc-cni");
        assertEquals(1, afterFailedUpdate.podIdentityAssociations().size());
        EksPodIdentityAssociationService.Page stillThere = fixture.podIdentityAssociations.list(
                fixture.cluster, "kube-system", "aws-node", null, null);
        assertEquals(1, stillThere.associations().size());

        // Delete with preserve=false removes association from cluster
        fixture.service.delete(fixture.cluster, "vpc-cni", false);
        EksPodIdentityAssociationService.Page afterDelete = fixture.podIdentityAssociations.list(
                fixture.cluster, "kube-system", "aws-node", null, null);
        assertTrue(afterDelete.associations().isEmpty());

        // Re-creating addon with the same association now succeeds because old one was deleted
        Addon recreated = fixture.service.create(fixture.cluster, request);
        assertEquals(1, recreated.podIdentityAssociations().size());

        // Delete with preserve=true keeps the association in cluster
        fixture.service.delete(fixture.cluster, "vpc-cni", true);
        EksPodIdentityAssociationService.Page afterPreserveDelete = fixture.podIdentityAssociations.list(
                fixture.cluster, "kube-system", "aws-node", null, null);
        assertEquals(1, afterPreserveDelete.associations().size());
    }

    @Test
    void explicitVersionMustBeCompatibleWithClusterKubernetesVersion() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.29");

        // v1.16.0-eksbuild.1 is only compatible with k8s 1.28
        CreateAddonRequest reqIncompatible = new CreateAddonRequest(
                "vpc-cni", "v1.16.0-eksbuild.1", null, null, null, null, null, null);
        AwsException exCreate = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, reqIncompatible));
        assertEquals("InvalidParameterException", exCreate.getErrorCode());
        assertEquals(400, exCreate.getHttpStatus());

        // Compatible version works
        CreateAddonRequest reqCompatible = new CreateAddonRequest(
                "vpc-cni", "v1.18.1-eksbuild.1", null, null, null, null, null, null);
        Addon created = fixture.service.create(fixture.cluster, reqCompatible);
        assertEquals("v1.18.1-eksbuild.1", created.addonVersion());

        // Updating to incompatible version fails
        UpdateAddonRequest updateIncompatible = new UpdateAddonRequest(
                "v1.16.0-eksbuild.1", null, null, null, null, null);
        AwsException exUpdate = assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, "vpc-cni", updateIncompatible));
        assertEquals("InvalidParameterException", exUpdate.getErrorCode());
        assertEquals(400, exUpdate.getHttpStatus());
    }

    @Test
    void clusterVersionOutsideCatalogRangeAllowsCatalogVersionsAndResolvesDefault() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.35");

        // Create with no version resolves a default version from the catalog without error
        CreateAddonRequest reqDefault = new CreateAddonRequest(
                "vpc-cni", null, null, null, null, null, null, null);
        Addon created = fixture.service.create(fixture.cluster, reqDefault);
        assertNotNull(created.addonVersion());
        assertEquals("ACTIVE", created.status());

        // Update with another valid catalog version succeeds
        Update update = fixture.service.update(fixture.cluster, "vpc-cni",
                new UpdateAddonRequest("v1.18.1-eksbuild.1", null, null, null, null, null));
        assertNotNull(update.id());
        Addon updated = fixture.service.describe(fixture.cluster, "vpc-cni");
        assertEquals("v1.18.1-eksbuild.1", updated.addonVersion());

        // Updating with a version NOT in catalog at all still fails
        UpdateAddonRequest updateBad = new UpdateAddonRequest(
                "v99.0.0", null, null, null, null, null);
        AwsException ex = assertThrows(AwsException.class, () ->
                fixture.service.update(fixture.cluster, "vpc-cni", updateBad));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void clusterVersion132SupportedInCatalog() {
        Fixture fixture = fixture();
        fixture.cluster.setVersion("1.32");

        CreateAddonRequest reqKubeProxy = new CreateAddonRequest(
                "kube-proxy", null, null, null, null, null, null, null);
        Addon created = fixture.service.create(fixture.cluster, reqKubeProxy);
        assertEquals("v1.32.0-eksbuild.1", created.addonVersion());

        // Incompatible version (v1.16.0 only compatible with 1.28) is rejected
        CreateAddonRequest reqIncompatible = new CreateAddonRequest(
                "vpc-cni", "v1.16.0-eksbuild.1", null, null, null, null, null, null);
        AwsException ex = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, reqIncompatible));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
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

    @Test
    void ebsCsiDriverAddonSupportedAcrossClusterVersions() {
        Fixture fixture = fixture();

        // The default version resolves for each cluster version the catalog covers
        Map<String, String> expectedDefaults = Map.of(
                "1.28", "v1.26.1-eksbuild.1",
                "1.29", "v1.28.0-eksbuild.1",
                "1.30", "v1.31.0-eksbuild.1",
                "1.31", "v1.35.0-eksbuild.1",
                "1.32", "v1.38.1-eksbuild.1"
        );
        for (Map.Entry<String, String> entry : expectedDefaults.entrySet()) {
            fixture.cluster.setVersion(entry.getKey());
            assertEquals(Optional.of(entry.getValue()),
                    fixture.catalog.resolveDefaultVersion("aws-ebs-csi-driver", entry.getKey()));
        }

        // CreateAddon for aws-ebs-csi-driver succeeds and DescribeAddon returns it
        fixture.cluster.setVersion("1.30");
        CreateAddonRequest req = new CreateAddonRequest(
                "aws-ebs-csi-driver", null, ROLE, null, null, null, null, null);
        Addon created = fixture.service.create(fixture.cluster, req);
        assertEquals("aws-ebs-csi-driver", created.addonName());
        assertEquals("v1.31.0-eksbuild.1", created.addonVersion());
        assertEquals("ACTIVE", created.status());
        assertEquals("aws", created.owner());
        assertEquals("eks", created.publisher());

        Addon described = fixture.service.describe(fixture.cluster, "aws-ebs-csi-driver");
        assertEquals(created, described);

        // DescribeAddonVersions returns the new addon with its versions and compatibilities
        EksAddonService.AddonVersionsPage byName = fixture.service.describeAddonVersions(
                "aws-ebs-csi-driver", null, null, null, null, null, null);
        assertEquals(1, byName.addons().size());
        AddonInfo addonInfo = byName.addons().getFirst();
        assertEquals("aws-ebs-csi-driver", addonInfo.addonName());
        assertEquals("storage", addonInfo.type());
        assertEquals("aws", addonInfo.owner());
        assertEquals("eks", addonInfo.publisher());
        assertEquals(5, addonInfo.addonVersions().size());

        // Filtering by addon name and cluster version works
        EksAddonService.AddonVersionsPage byCluster = fixture.service.describeAddonVersions(
                "aws-ebs-csi-driver", "1.32", null, null, null, null, null);
        assertEquals(1, byCluster.addons().size());
        assertEquals(1, byCluster.addons().getFirst().addonVersions().size());
        assertEquals("v1.38.1-eksbuild.1", byCluster.addons().getFirst().addonVersions().getFirst().addonVersion());

        // The four existing addons are unchanged
        for (String existing : List.of("vpc-cni", "coredns", "kube-proxy", "eks-pod-identity-agent")) {
            assertTrue(fixture.catalog.isKnownAddon(existing));
            assertNotNull(fixture.catalog.findAddon(existing).orElse(null));
        }

        // An addon name that is still not in the catalog is rejected exactly as before
        assertFalse(fixture.catalog.isKnownAddon("unsupported-addon"));
        CreateAddonRequest unsupportedReq = new CreateAddonRequest(
                "unsupported-addon", null, null, null, null, null, null, null);
        AwsException ex = assertThrows(AwsException.class, () ->
                fixture.service.create(fixture.cluster, unsupportedReq));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains("unsupported-addon"));
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
        InMemoryStorage<String, EksAddonService.StoredUpdate> updatesStorage = new InMemoryStorage<>();
        InMemoryStorage<String, EksPodIdentityAssociationService.StoredAssociation> assocStorage = new InMemoryStorage<>();
        EksPodIdentityAssociationService podIdentityAssociations = new EksPodIdentityAssociationService(assocStorage, iam);
        EksAddonService service = new EksAddonService(storage, updatesStorage, catalog, iam, podIdentityAssociations);

        return new Fixture(cluster, iam, role, storage, updatesStorage, catalog, podIdentityAssociations, service);
    }

    private record Fixture(Cluster cluster, IamService iam, IamRole role,
                           InMemoryStorage<String, EksAddonService.StoredAddon> storage,
                           InMemoryStorage<String, EksAddonService.StoredUpdate> updatesStorage,
                           EksAddonCatalog catalog,
                           EksPodIdentityAssociationService podIdentityAssociations,
                           EksAddonService service) {}
}

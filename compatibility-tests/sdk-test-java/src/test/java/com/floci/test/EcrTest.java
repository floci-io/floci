package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ECR Container Registry")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcrTest {

    private static EcrClient ecr;
    private static final List<String> reposToCleanup = new ArrayList<>();

    private static final String REPO_NAME = "floci-it/app";

    @BeforeAll
    static void setup() {
        ecr = TestFixtures.ecrClient();
    }

    @AfterAll
    static void cleanup() {
        if (ecr != null) {
            for (String name : reposToCleanup) {
                try {
                    ecr.deleteRepository(b -> b.repositoryName(name).force(true));
                } catch (Exception ignored) {}
            }
            ecr.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("CreateRepository returns a repositoryUri pointing at loopback")
    void createRepository() {
        CreateRepositoryResponse resp = ecr.createRepository(b -> b.repositoryName(REPO_NAME));
        reposToCleanup.add(REPO_NAME);

        Repository repo = resp.repository();
        assertThat(repo.repositoryName()).isEqualTo(REPO_NAME);
        assertThat(repo.repositoryArn()).startsWith("arn:" + TestFixtures.partition() + ":ecr:");
        assertThat(repo.repositoryArn()).contains(":repository/" + REPO_NAME);
        assertThat(repo.repositoryUri()).contains("/" + REPO_NAME);
        // Hostname must resolve to loopback so docker auto-trusts it as insecure.
        assertThat(repo.repositoryUri()).containsAnyOf(".localhost:", "localhost:");
    }

    @Test
    @Order(2)
    @DisplayName("CreateRepository on an existing name returns RepositoryAlreadyExistsException")
    void createDuplicate() {
        assertThatThrownBy(() -> ecr.createRepository(b -> b.repositoryName(REPO_NAME)))
                .isInstanceOf(RepositoryAlreadyExistsException.class);
    }

    @Test
    @Order(3)
    @DisplayName("DescribeRepositories returns the created repository")
    void describeRepositories() {
        DescribeRepositoriesResponse resp = ecr.describeRepositories(
                b -> b.repositoryNames(REPO_NAME));
        assertThat(resp.repositories()).extracting(Repository::repositoryName).contains(REPO_NAME);
    }

    @Test
    @Order(4)
    @DisplayName("GetAuthorizationToken returns a usable docker login token")
    void getAuthorizationToken() {
        GetAuthorizationTokenResponse resp = ecr.getAuthorizationToken();
        assertThat(resp.authorizationData()).isNotEmpty();
        AuthorizationData data = resp.authorizationData().get(0);
        assertThat(data.authorizationToken()).isNotBlank();
        assertThat(data.proxyEndpoint()).startsWith("http");
        assertThat(data.expiresAt()).isNotNull();
        // Token MUST decode to "AWS:<password>" so `docker login` accepts it.
        String decoded = new String(Base64.getDecoder().decode(data.authorizationToken()));
        assertThat(decoded).startsWith("AWS:");
    }

    @Test
    @Order(5)
    @DisplayName("ListImages on an empty repository returns no image identifiers")
    void listImagesEmpty() {
        ListImagesResponse resp = ecr.listImages(b -> b.repositoryName(REPO_NAME));
        assertThat(resp.imageIds()).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("PutImageTagMutability round-trips IMMUTABLE")
    void tagMutabilityRoundTrip() {
        PutImageTagMutabilityResponse resp = ecr.putImageTagMutability(
                b -> b.repositoryName(REPO_NAME).imageTagMutability(ImageTagMutability.IMMUTABLE));
        assertThat(resp.imageTagMutability()).isEqualTo(ImageTagMutability.IMMUTABLE);

        DescribeRepositoriesResponse desc = ecr.describeRepositories(
                b -> b.repositoryNames(REPO_NAME));
        assertThat(desc.repositories().get(0).imageTagMutability())
                .isEqualTo(ImageTagMutability.IMMUTABLE);
    }

    @Test
    @Order(7)
    @DisplayName("PutImageScanningConfiguration supports enabling and disabling scan on push")
    void imageScanningConfigurationCanBeEnabledAndDisabled() {
        PutImageScanningConfigurationResponse enabled = ecr.putImageScanningConfiguration(builder -> builder
                .repositoryName(REPO_NAME)
                .imageScanningConfiguration(ImageScanningConfiguration.builder().scanOnPush(true).build()));
        assertThat(enabled.imageScanningConfiguration().scanOnPush()).isTrue();

        BatchGetRepositoryScanningConfigurationResponse enabledReadback =
                ecr.batchGetRepositoryScanningConfiguration(builder -> builder.repositoryNames(REPO_NAME));
        assertThat(enabledReadback.scanningConfigurations().get(0).scanOnPush()).isTrue();

        PutImageScanningConfigurationResponse disabled = ecr.putImageScanningConfiguration(builder -> builder
                .repositoryName(REPO_NAME)
                .imageScanningConfiguration(ImageScanningConfiguration.builder().scanOnPush(false).build()));
        assertThat(disabled.imageScanningConfiguration().scanOnPush()).isFalse();

        BatchGetRepositoryScanningConfigurationResponse disabledReadback =
                ecr.batchGetRepositoryScanningConfiguration(builder -> builder.repositoryNames(REPO_NAME));
        assertThat(disabledReadback.scanningConfigurations().get(0).scanOnPush()).isFalse();
    }

    @Test
    @Order(8)
    @DisplayName("PutLifecyclePolicy round-trips the policy text")
    void lifecyclePolicyRoundTrip() {
        String policy = "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"untagged\",\"countType\":\"imageCountMoreThan\",\"countNumber\":5},\"action\":{\"type\":\"expire\"}}]}";
        ecr.putLifecyclePolicy(b -> b.repositoryName(REPO_NAME).lifecyclePolicyText(policy));
        GetLifecyclePolicyResponse get = ecr.getLifecyclePolicy(b -> b.repositoryName(REPO_NAME));
        assertThat(get.lifecyclePolicyText()).isEqualTo(policy);
    }

    @Test
    @Order(9)
    @DisplayName("SetRepositoryPolicy round-trips the policy text")
    void repositoryPolicyRoundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"AllowAll\",\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"ecr:*\"}]}";
        ecr.setRepositoryPolicy(b -> b.repositoryName(REPO_NAME).policyText(policy));
        GetRepositoryPolicyResponse get = ecr.getRepositoryPolicy(b -> b.repositoryName(REPO_NAME));
        assertThat(get.policyText()).isEqualTo(policy);
    }

    @Test
    @Order(10)
    @DisplayName("DeleteRepository force=true removes the repository")
    void deleteRepositoryForce() {
        ecr.deleteRepository(b -> b.repositoryName(REPO_NAME).force(true));
        reposToCleanup.remove(REPO_NAME);
        assertThatThrownBy(() -> ecr.describeRepositories(b -> b.repositoryNames(REPO_NAME)))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    @Order(11)
    @DisplayName("DescribeRepositories on a missing name returns RepositoryNotFoundException")
    void describeMissing() {
        assertThatThrownBy(() -> ecr.describeRepositories(
                b -> b.repositoryNames("does-not-exist-" + System.nanoTime())))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    @Order(12)
    @DisplayName("Pull through cache rules round-trip through the AWS SDK")
    void pullThroughCacheRuleLifecycle() {
        String firstPrefix = "sdk-docker-hub";
        String secondPrefix = "sdk-kubernetes";
        try {
            CreatePullThroughCacheRuleResponse created = ecr.createPullThroughCacheRule(builder -> builder
                    .ecrRepositoryPrefix(firstPrefix)
                    .upstreamRegistryUrl("registry-1.docker.io"));
            ecr.createPullThroughCacheRule(builder -> builder
                    .ecrRepositoryPrefix(secondPrefix)
                    .upstreamRegistryUrl("registry.k8s.io"));

            assertThat(created.ecrRepositoryPrefix()).isEqualTo(firstPrefix);
            assertThat(created.upstreamRegistry()).isEqualTo(UpstreamRegistry.DOCKER_HUB);
            assertThat(created.upstreamRepositoryPrefix()).isEqualTo("ROOT");
            assertThat(created.createdAt()).isNotNull();

            DescribePullThroughCacheRulesResponse firstPage = ecr.describePullThroughCacheRules(
                    builder -> builder.maxResults(1));
            DescribePullThroughCacheRulesResponse secondPage = ecr.describePullThroughCacheRules(
                    builder -> builder.maxResults(1).nextToken(firstPage.nextToken()));
            DescribePullThroughCacheRulesResponse filtered = ecr.describePullThroughCacheRules(
                    builder -> builder.ecrRepositoryPrefixes(firstPrefix));

            assertThat(firstPage.pullThroughCacheRules()).hasSize(1);
            assertThat(firstPage.nextToken()).isNotBlank();
            assertThat(secondPage.pullThroughCacheRules()).hasSize(1);
            assertThat(filtered.pullThroughCacheRules())
                    .extracting(PullThroughCacheRule::ecrRepositoryPrefix)
                    .containsExactly(firstPrefix);

            String credentialArn =
                    TestFixtures.arn("secretsmanager", "000000000000", "secret:ecr-pullthroughcache/sdk");
            UpdatePullThroughCacheRuleResponse updated = ecr.updatePullThroughCacheRule(builder -> builder
                    .ecrRepositoryPrefix(firstPrefix)
                    .credentialArn(credentialArn));
            ValidatePullThroughCacheRuleResponse validated = ecr.validatePullThroughCacheRule(
                    builder -> builder.ecrRepositoryPrefix(firstPrefix));

            assertThat(updated.credentialArn()).isEqualTo(credentialArn);
            assertThat(updated.updatedAt()).isNotNull();
            assertThat(validated.isValid()).isTrue();
            assertThat(validated.credentialArn()).isEqualTo(credentialArn);
            assertThat(validated.upstreamRegistryUrl()).isEqualTo("registry-1.docker.io");

            DeletePullThroughCacheRuleResponse deleted = ecr.deletePullThroughCacheRule(
                    builder -> builder.ecrRepositoryPrefix(firstPrefix));
            assertThat(deleted.ecrRepositoryPrefix()).isEqualTo(firstPrefix);
            assertThatThrownBy(() -> ecr.deletePullThroughCacheRule(
                    builder -> builder.ecrRepositoryPrefix(firstPrefix)))
                    .isInstanceOf(PullThroughCacheRuleNotFoundException.class);
        } finally {
            for (String prefix : List.of(firstPrefix, secondPrefix)) {
                try {
                    ecr.deletePullThroughCacheRule(builder -> builder.ecrRepositoryPrefix(prefix));
                } catch (PullThroughCacheRuleNotFoundException expected) {
                    // The rule was already deleted by the lifecycle assertion.
                }
            }
        }
    }

    @Test
    @Order(13)
    @DisplayName("An image pushed through the layer API and PutImage reads back and retags")
    void pushThroughTheApiAndRetag() throws Exception {
        String repo = "floci-it/api-push-" + System.nanoTime();
        ecr.createRepository(b -> b.repositoryName(repo));
        reposToCleanup.add(repo);

        String config = "{\"architecture\":\"amd64\",\"os\":\"linux\"}";
        String layer = "floci layer bytes";
        String configDigest = uploadLayer(repo, config.getBytes(StandardCharsets.UTF_8));
        String layerDigest = uploadLayer(repo, layer.getBytes(StandardCharsets.UTF_8));

        BatchCheckLayerAvailabilityResponse check = ecr.batchCheckLayerAvailability(b -> b
                .repositoryName(repo).layerDigests(configDigest, layerDigest));
        assertThat(check.layers()).extracting(Layer::layerAvailability)
                .containsOnly(LayerAvailability.AVAILABLE);
        assertThat(check.failures()).isEmpty();

        String manifest = "{\"schemaVersion\":2,\"mediaType\":\"application/vnd.oci.image.manifest.v1+json\","
                + "\"config\":{\"mediaType\":\"application/vnd.oci.image.config.v1+json\",\"size\":"
                + config.length() + ",\"digest\":\"" + configDigest + "\"},"
                + "\"layers\":[{\"mediaType\":\"application/vnd.oci.image.layer.v1.tar\",\"size\":"
                + layer.length() + ",\"digest\":\"" + layerDigest + "\"}]}";
        PutImageResponse put = ecr.putImage(b -> b.repositoryName(repo).imageTag("v1").imageManifest(manifest));
        assertThat(put.image().imageId().imageDigest()).isEqualTo(sha256(manifest.getBytes(StandardCharsets.UTF_8)));

        String read = ecr.batchGetImage(b -> b.repositoryName(repo)
                .imageIds(ImageIdentifier.builder().imageTag("v1").build()))
                .images().get(0).imageManifest();
        ecr.putImage(b -> b.repositoryName(repo).imageTag("release").imageManifest(read));
        assertThat(ecr.listImages(b -> b.repositoryName(repo)).imageIds())
                .extracting(ImageIdentifier::imageTag).contains("v1", "release");
        assertThatThrownBy(() -> ecr.putImage(b -> b.repositoryName(repo).imageTag("release").imageManifest(read)))
                .isInstanceOf(ImageAlreadyExistsException.class);

        String url = ecr.getDownloadUrlForLayer(b -> b.repositoryName(repo).layerDigest(layerDigest)).downloadUrl();
        assertThat(url).endsWith("/v2/" + repo + "/blobs/" + layerDigest);
    }

    private static String uploadLayer(String repo, byte[] bytes) throws Exception {
        String uploadId = ecr.initiateLayerUpload(b -> b.repositoryName(repo)).uploadId();
        UploadLayerPartResponse part = ecr.uploadLayerPart(b -> b.repositoryName(repo).uploadId(uploadId)
                .partFirstByte(0L).partLastByte((long) bytes.length - 1).layerPartBlob(SdkBytes.fromByteArray(bytes)));
        assertThat(part.lastByteReceived()).isEqualTo(bytes.length - 1L);
        String digest = sha256(bytes);
        return ecr.completeLayerUpload(b -> b.repositoryName(repo).uploadId(uploadId).layerDigests(digest))
                .layerDigest();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @Order(14)
    @DisplayName("Registry policy, replication and scanning configuration round-trip")
    void registrySettingsRoundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Replicate\",\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:" + TestFixtures.partition() + ":iam::111111111111:root\"},"
                + "\"Action\":[\"ecr:CreateRepository\",\"ecr:ReplicateImage\"],\"Resource\":\"*\"}]}";
        try {
            assertThatThrownBy(() -> ecr.getRegistryPolicy(b -> {}))
                    .isInstanceOf(RegistryPolicyNotFoundException.class);
            ecr.putRegistryPolicy(b -> b.policyText(policy));
            assertThat(ecr.getRegistryPolicy(b -> {}).policyText()).isEqualTo(policy);

            ecr.putReplicationConfiguration(b -> b.replicationConfiguration(c -> c.rules(ReplicationRule.builder()
                    .destinations(ReplicationDestination.builder().region("us-west-2").registryId("111111111111").build())
                    .repositoryFilters(RepositoryFilter.builder().filter("prod").filterType("PREFIX_MATCH").build())
                    .build())));
            DescribeRegistryResponse registry = ecr.describeRegistry(b -> {});
            assertThat(registry.registryId()).matches("[0-9]{12}");
            assertThat(registry.replicationConfiguration().rules()).hasSize(1);
            assertThat(registry.replicationConfiguration().rules().get(0).destinations().get(0).region())
                    .isEqualTo("us-west-2");

            ecr.putRegistryScanningConfiguration(b -> b.scanType(ScanType.ENHANCED).rules(RegistryScanningRule.builder()
                    .scanFrequency(ScanFrequency.CONTINUOUS_SCAN)
                    .repositoryFilters(ScanningRepositoryFilter.builder().filter("prod-*")
                            .filterType(ScanningRepositoryFilterType.WILDCARD).build())
                    .build()));
            GetRegistryScanningConfigurationResponse scanning = ecr.getRegistryScanningConfiguration(b -> {});
            assertThat(scanning.scanningConfiguration().scanType()).isEqualTo(ScanType.ENHANCED);
            assertThat(scanning.scanningConfiguration().rules().get(0).scanFrequency())
                    .isEqualTo(ScanFrequency.CONTINUOUS_SCAN);
            assertThatThrownBy(() -> ecr.putRegistryScanningConfiguration(b -> b.scanType(ScanType.BASIC)
                    .rules(RegistryScanningRule.builder().scanFrequency(ScanFrequency.CONTINUOUS_SCAN)
                            .repositoryFilters(ScanningRepositoryFilter.builder().filter("*")
                                    .filterType(ScanningRepositoryFilterType.WILDCARD).build())
                            .build())))
                    .isInstanceOf(InvalidParameterException.class);
        } finally {
            // Registry settings are per account and Region, so put back what a fresh registry has.
            try {
                ecr.deleteRegistryPolicy(b -> {});
            } catch (RegistryPolicyNotFoundException ignored) {
                // Already absent.
            }
            ecr.putReplicationConfiguration(b -> b.replicationConfiguration(c -> c.rules(List.of())));
            ecr.putRegistryScanningConfiguration(b -> b.scanType(ScanType.BASIC).rules(List.of()));
        }
    }

    @Test
    @Order(15)
    @DisplayName("Repository creation templates create, update, describe and delete")
    void repositoryCreationTemplateLifecycle() {
        String prefix = "floci-sdk-" + System.currentTimeMillis();
        try {
            CreateRepositoryCreationTemplateResponse created = ecr.createRepositoryCreationTemplate(b -> b
                    .prefix(prefix)
                    .description("sdk template")
                    .appliedFor(RCTAppliedFor.PULL_THROUGH_CACHE, RCTAppliedFor.REPLICATION)
                    .resourceTags(Tag.builder().key("team").value("platform").build())
                    .imageTagMutability(ImageTagMutability.IMMUTABLE));
            assertThat(created.registryId()).isEqualTo("000000000000");
            assertThat(created.repositoryCreationTemplate().prefix()).isEqualTo(prefix);
            assertThat(created.repositoryCreationTemplate().encryptionConfiguration().encryptionType())
                    .isEqualTo(EncryptionType.AES256);
            assertThat(created.repositoryCreationTemplate().createdAt()).isNotNull();
            assertThatThrownBy(() -> ecr.createRepositoryCreationTemplate(b -> b
                    .prefix(prefix).appliedFor(RCTAppliedFor.REPLICATION)))
                    .isInstanceOf(TemplateAlreadyExistsException.class);

            UpdateRepositoryCreationTemplateResponse updated = ecr.updateRepositoryCreationTemplate(b -> b
                    .prefix(prefix)
                    .appliedFor(RCTAppliedFor.CREATE_ON_PUSH));
            assertThat(updated.repositoryCreationTemplate().appliedFor()).containsExactly(RCTAppliedFor.CREATE_ON_PUSH);
            assertThat(updated.repositoryCreationTemplate().description()).isEqualTo("sdk template");

            DescribeRepositoryCreationTemplatesResponse described =
                    ecr.describeRepositoryCreationTemplates(b -> b.prefixes(prefix));
            assertThat(described.repositoryCreationTemplates()).hasSize(1);
            assertThat(described.repositoryCreationTemplates().get(0).resourceTags())
                    .containsExactly(Tag.builder().key("team").value("platform").build());

            assertThat(ecr.deleteRepositoryCreationTemplate(b -> b.prefix(prefix))
                    .repositoryCreationTemplate().prefix()).isEqualTo(prefix);
            assertThatThrownBy(() -> ecr.deleteRepositoryCreationTemplate(b -> b.prefix(prefix)))
                    .isInstanceOf(TemplateNotFoundException.class);
        } finally {
            try {
                ecr.deleteRepositoryCreationTemplate(b -> b.prefix(prefix));
            } catch (TemplateNotFoundException expected) {
                // Already deleted by the lifecycle assertion.
            }
        }
    }
}

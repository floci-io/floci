package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;

import java.util.ArrayList;
import java.util.Base64;
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
        assertThat(repo.repositoryArn()).startsWith("arn:aws:ecr:");
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
    @DisplayName("PutLifecyclePolicy round-trips the policy text")
    void lifecyclePolicyRoundTrip() {
        String policy = "{\"rules\":[{\"rulePriority\":1,\"selection\":{\"tagStatus\":\"untagged\",\"countType\":\"imageCountMoreThan\",\"countNumber\":5},\"action\":{\"type\":\"expire\"}}]}";
        ecr.putLifecyclePolicy(b -> b.repositoryName(REPO_NAME).lifecyclePolicyText(policy));
        GetLifecyclePolicyResponse get = ecr.getLifecyclePolicy(b -> b.repositoryName(REPO_NAME));
        assertThat(get.lifecyclePolicyText()).isEqualTo(policy);
    }

    @Test
    @Order(8)
    @DisplayName("SetRepositoryPolicy round-trips the policy text")
    void repositoryPolicyRoundTrip() {
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"AllowAll\",\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"ecr:*\"}]}";
        ecr.setRepositoryPolicy(b -> b.repositoryName(REPO_NAME).policyText(policy));
        GetRepositoryPolicyResponse get = ecr.getRepositoryPolicy(b -> b.repositoryName(REPO_NAME));
        assertThat(get.policyText()).isEqualTo(policy);
    }

    @Test
    @Order(9)
    @DisplayName("DeleteRepository force=true removes the repository")
    void deleteRepositoryForce() {
        ecr.deleteRepository(b -> b.repositoryName(REPO_NAME).force(true));
        reposToCleanup.remove(REPO_NAME);
        assertThatThrownBy(() -> ecr.describeRepositories(b -> b.repositoryNames(REPO_NAME)))
                .isInstanceOf(RepositoryNotFoundException.class);
    }

    @Test
    @Order(10)
    @DisplayName("DescribeRepositories on a missing name returns RepositoryNotFoundException")
    void describeMissing() {
        assertThatThrownBy(() -> ecr.describeRepositories(
                b -> b.repositoryNames("does-not-exist-" + System.nanoTime())))
                .isInstanceOf(RepositoryNotFoundException.class);
    }
}

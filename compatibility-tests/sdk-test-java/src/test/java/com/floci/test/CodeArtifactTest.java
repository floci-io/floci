package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.codeartifact.CodeartifactClient;
import software.amazon.awssdk.services.codeartifact.model.*;
// Explicit import: this file's Tag usage is the CodeArtifact model type, not JUnit's @Tag.
import software.amazon.awssdk.services.codeartifact.model.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CodeArtifact")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CodeArtifactTest {

    private static final Logger LOG = Logger.getLogger(CodeArtifactTest.class);

    private static CodeartifactClient codeArtifact;

    private static final String DOMAIN = "compat-test-domain";
    private static final String STORE_REPO = "compat-test-store";
    private static final String REPO = "compat-test-repo";

    private static String domainArn;
    private static String repositoryArn;

    @BeforeAll
    static void setup() {
        codeArtifact = TestFixtures.codeArtifactClient();
    }

    @AfterAll
    static void cleanup() {
        if (codeArtifact == null) return;
        try {
            codeArtifact.deleteRepository(r -> r.domain(DOMAIN).repository(REPO));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete repository %s during test cleanup", REPO);
        }
        try {
            codeArtifact.deleteRepository(r -> r.domain(DOMAIN).repository(STORE_REPO));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete repository %s during test cleanup", STORE_REPO);
        }
        try {
            codeArtifact.deleteDomain(r -> r.domain(DOMAIN));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete domain %s during test cleanup", DOMAIN);
        }
        codeArtifact.close();
    }

    @Test
    @Order(10)
    @DisplayName("CreateDomain - creates a domain with tags")
    void createDomain() {
        CreateDomainResponse resp = codeArtifact.createDomain(r -> r
                .domain(DOMAIN)
                .tags(Tag.builder().key("owner").value("platform").build()));

        domainArn = resp.domain().arn();
        assertThat(resp.domain().name()).isEqualTo(DOMAIN);
        assertThat(domainArn).contains("domain/" + DOMAIN);
        assertThat(resp.domain().status()).isEqualTo(DomainStatus.ACTIVE);
        assertThat(resp.domain().repositoryCount()).isZero();
    }

    @Test
    @Order(11)
    @DisplayName("CreateDomain - duplicate returns ConflictException")
    void createDomainDuplicateFails() {
        assertThatThrownBy(() -> codeArtifact.createDomain(r -> r.domain(DOMAIN)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @Order(20)
    @DisplayName("CreateRepository - fails against a domain that does not exist")
    void createRepositoryMissingDomainFails() {
        assertThatThrownBy(() -> codeArtifact.createRepository(r -> r
                        .domain("does-not-exist-domain")
                        .repository(REPO)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(21)
    @DisplayName("CreateRepository - creates the upstream store repository")
    void createStoreRepository() {
        CreateRepositoryResponse resp = codeArtifact.createRepository(r -> r
                .domain(DOMAIN)
                .repository(STORE_REPO));

        assertThat(resp.repository().name()).isEqualTo(STORE_REPO);
        assertThat(resp.repository().upstreams()).isEmpty();
    }

    @Test
    @Order(22)
    @DisplayName("CreateRepository - creates a repository with an upstream and tags")
    void createRepository() {
        CreateRepositoryResponse resp = codeArtifact.createRepository(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .description("compat test repo")
                .upstreams(UpstreamRepository.builder().repositoryName(STORE_REPO).build())
                .tags(Tag.builder().key("team").value("data").build()));

        repositoryArn = resp.repository().arn();
        assertThat(resp.repository().domainName()).isEqualTo(DOMAIN);
        assertThat(resp.repository().upstreams()).hasSize(1);
        assertThat(resp.repository().upstreams().get(0).repositoryName()).isEqualTo(STORE_REPO);
    }

    @Test
    @Order(23)
    @DisplayName("DescribeDomain - reports the live repository count")
    void describeDomainReportsRepositoryCount() {
        DescribeDomainResponse resp = codeArtifact.describeDomain(r -> r.domain(DOMAIN));
        assertThat(resp.domain().repositoryCount()).isEqualTo(2);
    }

    @Test
    @Order(30)
    @DisplayName("TagResource / ListTagsForResource / UntagResource round-trip on the repository")
    void tagRoundTrip() {
        codeArtifact.tagResource(r -> r
                .resourceArn(repositoryArn)
                .tags(Tag.builder().key("env").value("compat").build()));

        List<Tag> tags = codeArtifact.listTagsForResource(r -> r.resourceArn(repositoryArn)).tags();
        assertThat(tags).extracting(Tag::key).contains("team", "env");

        codeArtifact.untagResource(r -> r.resourceArn(repositoryArn).tagKeys("env"));
        tags = codeArtifact.listTagsForResource(r -> r.resourceArn(repositoryArn)).tags();
        assertThat(tags).extracting(Tag::key).doesNotContain("env");
    }

    @Test
    @Order(31)
    @DisplayName("TagResource / ListTagsForResource round-trip on the domain")
    void tagRoundTripOnDomain() {
        codeArtifact.tagResource(r -> r
                .resourceArn(domainArn)
                .tags(Tag.builder().key("cost-center").value("platform-eng").build()));

        List<Tag> tags = codeArtifact.listTagsForResource(r -> r.resourceArn(domainArn)).tags();
        assertThat(tags).extracting(Tag::key).contains("owner", "cost-center");
    }

    @Test
    @Order(40)
    @DisplayName("GetRepositoryEndpoint - resolves for a real package format")
    void getRepositoryEndpoint() {
        GetRepositoryEndpointResponse resp = codeArtifact.getRepositoryEndpoint(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .format(PackageFormat.NPM));

        assertThat(resp.repositoryEndpoint()).contains("/codeartifact/npm/" + DOMAIN + "/" + REPO + "/");
    }

    @Test
    @Order(50)
    @DisplayName("Repository permissions policy - put/get/delete with optimistic locking")
    void repositoryPermissionsPolicyRoundTrip() {
        String policyDocument = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        PutRepositoryPermissionsPolicyResponse put = codeArtifact.putRepositoryPermissionsPolicy(r -> r
                .domain(DOMAIN)
                .repository(REPO)
                .policyDocument(policyDocument));

        assertThat(put.policy().document()).isEqualTo(policyDocument);

        assertThatThrownBy(() -> codeArtifact.putRepositoryPermissionsPolicy(r -> r
                        .domain(DOMAIN)
                        .repository(REPO)
                        .policyDocument(policyDocument)
                        .policyRevision("not-the-current-revision")))
                .isInstanceOf(ConflictException.class);

        GetRepositoryPermissionsPolicyResponse got = codeArtifact.getRepositoryPermissionsPolicy(r -> r
                .domain(DOMAIN)
                .repository(REPO));
        assertThat(got.policy().revision()).isEqualTo(put.policy().revision());

        codeArtifact.deleteRepositoryPermissionsPolicy(r -> r.domain(DOMAIN).repository(REPO));
        assertThatThrownBy(() -> codeArtifact.getRepositoryPermissionsPolicy(r -> r
                        .domain(DOMAIN)
                        .repository(REPO)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @Order(60)
    @DisplayName("AssociateExternalConnection - accepts a real AWS-hosted public upstream")
    void externalConnectionRoundTrip() {
        AssociateExternalConnectionResponse assoc = codeArtifact.associateExternalConnection(r -> r
                .domain(DOMAIN)
                .repository(STORE_REPO)
                .externalConnection("public:npmjs"));

        assertThat(assoc.repository().externalConnections()).hasSize(1);
        assertThat(assoc.repository().externalConnections().get(0).packageFormat()).isEqualTo(PackageFormat.NPM);

        assertThatThrownBy(() -> codeArtifact.associateExternalConnection(r -> r
                        .domain(DOMAIN)
                        .repository(STORE_REPO)
                        .externalConnection("public:pypi")))
                .isInstanceOf(ConflictException.class);

        DisassociateExternalConnectionResponse disassoc = codeArtifact.disassociateExternalConnection(r -> r
                .domain(DOMAIN)
                .repository(STORE_REPO)
                .externalConnection("public:npmjs"));
        assertThat(disassoc.repository().externalConnections()).isEmpty();
    }

    @Test
    @Order(90)
    @DisplayName("DeleteDomain - fails while repositories still exist")
    void deleteDomainWithRepositoriesFails() {
        assertThatThrownBy(() -> codeArtifact.deleteDomain(r -> r.domain(DOMAIN)))
                .isInstanceOf(ConflictException.class);
    }
}

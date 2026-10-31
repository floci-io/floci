package io.github.hectorvent.floci.services.opensearch;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-unit assertions on the version catalog. No Quarkus boot needed.
 */
class OpenSearchVersionsTest {

    @Test
    void supportedVersionsIncludesOpenSearch3xAnd2xLatest() {
        assertThat(OpenSearchVersions.supportedVersions(),
                hasItems("OpenSearch_3.6", "OpenSearch_3.0",
                        "OpenSearch_2.19", "Elasticsearch_7.10"));
    }

    @Test
    void supportedVersionsIncludeMissingAwsManagedReleases() {
        assertThat(OpenSearchVersions.supportedVersions(),
                hasItems("OpenSearch_3.7", "OpenSearch_1.1", "OpenSearch_1.0"));
        assertDoesNotThrow(() -> OpenSearchVersions.validate("OpenSearch_3.7"));
        assertDoesNotThrow(() -> OpenSearchVersions.validate("OpenSearch_1.1"));
        assertDoesNotThrow(() -> OpenSearchVersions.validate("OpenSearch_1.0"));
        assertEquals("opensearchproject/opensearch:3.7.0",
                OpenSearchVersions.imageFor("OpenSearch_3.7"));
        assertEquals("opensearchproject/opensearch:1.1.0",
                OpenSearchVersions.imageFor("OpenSearch_1.1"));
        assertEquals("opensearchproject/opensearch:1.0.0",
                OpenSearchVersions.imageFor("OpenSearch_1.0"));
    }

    @Test
    void supportedVersionsListsNewestOpenSearch3xFirst() {
        // Newest-first is what AWS's ListVersions returns; SDK clients render
        // dropdowns in this order, so a regression here would be user-visible.
        List<String> versions = OpenSearchVersions.supportedVersions();
        int os36 = versions.indexOf("OpenSearch_3.6");
        int os213 = versions.indexOf("OpenSearch_2.13");
        int es710 = versions.indexOf("Elasticsearch_7.10");
        assertThat(os36, lessThan(os213));
        assertThat(os213, lessThan(es710));
    }

    @Test
    void imageForResolvesOpenSearch3x() {
        assertThat(OpenSearchVersions.imageFor("OpenSearch_3.6"),
                equalTo("opensearchproject/opensearch:3.6.0"));
    }

    @Test
    void imageForResolvesElasticsearch7xToElasticRegistry() {
        // ES OSS images live on docker.elastic.co, not Docker Hub. The host
        // prefix has to be in the image string or the daemon resolves under
        // library/ and the pull 404s.
        assertThat(OpenSearchVersions.imageFor("Elasticsearch_7.10"),
                startsWith("docker.elastic.co/elasticsearch/elasticsearch-oss:"));
    }

    @Test
    void imageForReturnsDefaultWhenEngineVersionOmitted() {
        String defaultImage = OpenSearchVersions.imageFor(OpenSearchVersions.DEFAULT_VERSION);
        assertEquals(defaultImage, OpenSearchVersions.imageFor(null));
        assertEquals(defaultImage, OpenSearchVersions.imageFor(""));
    }

    @Test
    void imageForRejectsUnknownVersion() {
        AwsException e = assertThrows(AwsException.class,
                () -> OpenSearchVersions.imageFor("OpenSearch_99.0"));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void validateAllowsBlankAndKnownVersions() {
        assertDoesNotThrow(() -> OpenSearchVersions.validate(null));
        assertDoesNotThrow(() -> OpenSearchVersions.validate(""));
        assertDoesNotThrow(() -> OpenSearchVersions.validate("OpenSearch_3.0"));
        assertDoesNotThrow(() -> OpenSearchVersions.validate("Elasticsearch_7.10"));
    }

    @Test
    void validateRejectsTypos() {
        assertThrows(AwsException.class, () -> OpenSearchVersions.validate("Opensearch_2.19"));
        assertThrows(AwsException.class, () -> OpenSearchVersions.validate("OpenSearch2.19"));
    }

    @Test
    void compatibleTargetsCovers2xTo3xUpgrade() {
        // 2.19 is the documented bridge into the 3.x line — without this, AWS
        // SDK clients can't render an upgrade picker that reaches 3.x at all.
        assertThat(OpenSearchVersions.compatibleTargets("OpenSearch_2.19"),
                hasItem("OpenSearch_3.0"));
    }

    @Test
    void compatibleTargetsCoverOpenSearch1xUpgrades() {
        assertEquals(List.of("OpenSearch_1.1", "OpenSearch_1.2", "OpenSearch_1.3"),
                OpenSearchVersions.compatibleTargets("OpenSearch_1.0"));
        assertEquals(List.of("OpenSearch_1.2", "OpenSearch_1.3"),
                OpenSearchVersions.compatibleTargets("OpenSearch_1.1"));
        assertEquals(List.of("OpenSearch_1.3"),
                OpenSearchVersions.compatibleTargets("OpenSearch_1.2"));
    }

    @Test
    void compatibleTargetsCoverEverySupportedOpenSearch2xFrom1x() {
        assertEquals(List.of("OpenSearch_2.3", "OpenSearch_2.5", "OpenSearch_2.7", "OpenSearch_2.9",
                "OpenSearch_2.11", "OpenSearch_2.13", "OpenSearch_2.15", "OpenSearch_2.17",
                "OpenSearch_2.19"), OpenSearchVersions.compatibleTargets("OpenSearch_1.3"));
    }

    @Test
    void compatibleTargetsCoverElasticsearch78Upgrades() {
        assertEquals(List.of("Elasticsearch_7.9", "Elasticsearch_7.10", "OpenSearch_1.0",
                "OpenSearch_1.1", "OpenSearch_1.2", "OpenSearch_1.3"),
                OpenSearchVersions.compatibleTargets("Elasticsearch_7.8"));
    }

    @Test
    void compatibleTargetsCoverElasticsearch79Upgrades() {
        assertEquals(List.of("Elasticsearch_7.10", "OpenSearch_1.0", "OpenSearch_1.1",
                "OpenSearch_1.2", "OpenSearch_1.3"),
                OpenSearchVersions.compatibleTargets("Elasticsearch_7.9"));
    }

    @Test
    void compatibleTargetsCoverLaterSupportedOpenSearch2xVersions() {
        List<String> versions = List.of("OpenSearch_2.3", "OpenSearch_2.5", "OpenSearch_2.7",
                "OpenSearch_2.9", "OpenSearch_2.11", "OpenSearch_2.13", "OpenSearch_2.15",
                "OpenSearch_2.17", "OpenSearch_2.19");
        for (int i = 0; i < versions.size() - 1; i++) {
            assertEquals(versions.subList(i + 1, versions.size()),
                    OpenSearchVersions.compatibleTargets(versions.get(i)), versions.get(i));
        }
    }

    @Test
    void compatibleTargetsCoverEverySupportedOpenSearch3xFrom2xBridge() {
        assertEquals(List.of("OpenSearch_3.0", "OpenSearch_3.1", "OpenSearch_3.2", "OpenSearch_3.3",
                "OpenSearch_3.4", "OpenSearch_3.5", "OpenSearch_3.6", "OpenSearch_3.7"),
                OpenSearchVersions.compatibleTargets("OpenSearch_2.19"));
    }

    @Test
    void compatibleTargetsCoverLaterOpenSearch3xVersions() {
        List<String> versions = List.of("OpenSearch_3.0", "OpenSearch_3.1", "OpenSearch_3.2",
                "OpenSearch_3.3", "OpenSearch_3.4", "OpenSearch_3.5", "OpenSearch_3.6", "OpenSearch_3.7");
        for (int i = 0; i < versions.size(); i++) {
            assertEquals(versions.subList(i + 1, versions.size()),
                    OpenSearchVersions.compatibleTargets(versions.get(i)), versions.get(i));
        }
    }

    @Test
    void compatibleTargetsEmptyForUnknownSource() {
        assertThat(OpenSearchVersions.compatibleTargets("OpenSearch_99.0"), empty());
    }

    @Test
    void validateUpgradeRejectsSkipPath() {
        // 2.13 has no direct hop to 3.6 in the matrix; force users through the
        // intermediate 2.19 → 3.x bridge so we don't pretend AWS allows
        // unsupported upgrade jumps.
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_2.13", "OpenSearch_3.6"));
    }

    @Test
    void validateUpgradeRejects2xTo3xWithoutGoingThroughLatest2x() {
        // AWS docs require 1.3 / 2.x domains to upgrade to 2.19 before going
        // to 3.x. 2.13 / 2.15 / 2.17 → 3.0 must be rejected.
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_2.13", "OpenSearch_3.0"));
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_2.15", "OpenSearch_3.0"));
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_2.17", "OpenSearch_3.0"));
    }

    @Test
    void validateUpgradeRejectsDowngradesAndSkippingThe2xBridge() {
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_1.2", "OpenSearch_1.1"));
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_3.6", "OpenSearch_3.5"));
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_1.1", "OpenSearch_3.0"));
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_1.3", "OpenSearch_3.0"));
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("Elasticsearch_7.8", "OpenSearch_2.19"));
    }

    @Test
    void validateUpgradeAcceptsDocumentedHop() {
        assertDoesNotThrow(() -> OpenSearchVersions.validateUpgrade("OpenSearch_2.11", "OpenSearch_2.13"));
        assertDoesNotThrow(() -> OpenSearchVersions.validateUpgrade("OpenSearch_2.19", "OpenSearch_3.0"));
        assertDoesNotThrow(() -> OpenSearchVersions.validateUpgrade("Elasticsearch_7.10", "OpenSearch_2.19"));
    }

    @Test
    void validateUpgradeRejectsUnknownTarget() {
        assertThrows(AwsException.class,
                () -> OpenSearchVersions.validateUpgrade("OpenSearch_2.19", "OpenSearch_99.0"));
    }

    @Test
    void resolveImageHonorsOperatorOverrideAcrossEngineVersions() {
        // Override locks every domain to the supplied image regardless of
        // requested EngineVersion — private registry mirror use case.
        String override = "registry.example.com/opensearch:2.19.5";
        assertThat(OpenSearchVersions.resolveImage(Optional.of(override), "OpenSearch_3.6"),
                equalTo(override));
        assertThat(OpenSearchVersions.resolveImage(Optional.of(override), "Elasticsearch_7.10"),
                equalTo(override));
        assertThat(OpenSearchVersions.resolveImage(Optional.of(override), null),
                equalTo(override));
    }

    @Test
    void resolveImageFallsBackToVersionMapWhenOverrideUnset() {
        assertThat(OpenSearchVersions.resolveImage(Optional.empty(), "OpenSearch_3.6"),
                equalTo("opensearchproject/opensearch:3.6.0"));
        assertThat(OpenSearchVersions.resolveImage(Optional.empty(), "Elasticsearch_7.10"),
                startsWith("docker.elastic.co/elasticsearch/elasticsearch-oss:"));
    }
}

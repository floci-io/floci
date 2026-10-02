package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the vendored IAM service namespace catalog and how it fails when malformed. */
class AwsServiceNamespacesTest {

    @Test
    void theVendoredCatalogCarriesEveryServiceAwsPublishes() {
        List<String> namespaces = AwsServiceNamespaces.all();

        // AWS publishes several hundred; a catalog an order of magnitude smaller means the
        // vendored file was truncated rather than regenerated.
        assertTrue(namespaces.size() > 300,
                "expected several hundred namespaces, got " + namespaces.size());
    }

    /**
     * The whole reason this catalog is generated from AWS's service reference rather than botocore:
     * CloudWatch's credential scope and endpoint prefix are both {@code monitoring}, while its IAM
     * namespace is {@code cloudwatch}. Deriving it from botocore metadata produces the former.
     */
    @Test
    void cloudWatchIsCarriedByItsIamNamespaceNotItsCredentialScope() {
        assertTrue(AwsServiceNamespaces.all().contains("cloudwatch"),
                "cloudwatch is CloudWatch's IAM namespace and must be present");
        assertFalse(AwsServiceNamespaces.all().contains("monitoring"),
                "monitoring is a credential scope, not an IAM namespace");
    }

    @Test
    void theCatalogIsSortedAndFreeOfDuplicates() {
        List<String> namespaces = AwsServiceNamespaces.all();

        assertEquals(namespaces.stream().sorted().toList(), namespaces, "expected a sorted catalog");
        assertEquals(namespaces.size(), namespaces.stream().distinct().count(),
                "expected no duplicate namespaces");
    }

    @Test
    void everyNamespaceIsUsableInAPolicyActionPrefix() {
        for (String namespace : AwsServiceNamespaces.all()) {
            assertTrue(namespace.matches("[a-z0-9][a-z0-9-]{0,63}"),
                    namespace + " is not usable as an IAM action prefix");
        }
    }

    @Test
    void servicesFlociEmulatesArePresent() {
        for (String namespace : List.of("iam", "s3", "sts", "dynamodb", "lambda", "sqs", "sns",
                "logs", "events", "kms", "secretsmanager", "states", "ecs", "eks")) {
            assertTrue(AwsServiceNamespaces.all().contains(namespace),
                    namespace + " should be in the catalog");
        }
    }

    @Test
    void aCatalogWithNoNamespacesIsABuildDefect() {
        String json = "{\"serviceNamespaces\": []}";

        assertThrows(IllegalStateException.class, () -> AwsServiceNamespaces.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aCatalogWithABlankNamespaceIsABuildDefect() {
        String json = "{\"serviceNamespaces\": [\"s3\", \"\"]}";

        assertThrows(IllegalStateException.class, () -> AwsServiceNamespaces.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aCatalogMissingTheFieldEntirelyIsABuildDefect() {
        String json = "{\"somethingElse\": [\"s3\"]}";

        assertThrows(IllegalStateException.class, () -> AwsServiceNamespaces.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aWellFormedCatalogParsesInOrder() throws IOException {
        String json = "{\"serviceNamespaces\": [\"cloudwatch\", \"iam\", \"s3\"]}";

        assertEquals(List.of("cloudwatch", "iam", "s3"), AwsServiceNamespaces.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
    }
}

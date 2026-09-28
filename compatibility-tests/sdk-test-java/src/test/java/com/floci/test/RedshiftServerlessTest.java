package com.floci.test;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.ConflictException;
import software.amazon.awssdk.services.redshiftserverless.model.Namespace;
import software.amazon.awssdk.services.redshiftserverless.model.NamespaceStatus;
import software.amazon.awssdk.services.redshiftserverless.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftserverless.model.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift Serverless namespace lifecycle")
class RedshiftServerlessTest {

    private static final Logger LOG = Logger.getLogger(RedshiftServerlessTest.class);

    @Test
    void namespaceLifecycleUsesAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Creates a namespace and asserts emulator-local defaults");

        try (RedshiftServerlessClient client = TestFixtures.redshiftServerlessClient()) {
            String namespaceName = "floci-compat-ns";
            try {
                Namespace created = client.createNamespace(request -> request
                        .namespaceName(namespaceName)
                        .adminUsername("admin")
                        .adminUserPassword("Secret123!")).namespace();

                assertThat(created.namespaceName()).isEqualTo(namespaceName);
                assertThat(created.dbName()).isEqualTo("dev");
                assertThat(created.kmsKeyId()).isEqualTo("AWS_OWNED_KMS_KEY");
                assertThat(created.status()).isEqualTo(NamespaceStatus.AVAILABLE);
                assertThat(created.namespaceArn()).contains(":redshift-serverless:");

                Namespace fetched = client.getNamespace(request -> request.namespaceName(namespaceName)).namespace();
                assertThat(fetched.namespaceId()).isEqualTo(created.namespaceId());

                assertThat(client.listNamespaces(request -> {}).namespaces())
                        .extracting(Namespace::namespaceName)
                        .contains(namespaceName);

                assertThatThrownBy(() -> client.createNamespace(request -> request
                                .namespaceName(namespaceName)
                                .adminUsername("admin")))
                        .isInstanceOf(ConflictException.class);

                String arn = created.namespaceArn();
                client.tagResource(request -> request.resourceArn(arn)
                        .tags(Tag.builder().key("env").value("dev").build()));
                assertThat(client.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .extracting(Tag::key, Tag::value)
                        .contains(tuple("env", "dev"));

                client.untagResource(request -> request.resourceArn(arn).tagKeys("env"));
                assertThat(client.listTagsForResource(request -> request.resourceArn(arn)).tags())
                        .extracting(Tag::key)
                        .doesNotContain("env");
            } finally {
                deleteBestEffort(client, namespaceName);
            }

            assertThatThrownBy(() -> client.getNamespace(request -> request.namespaceName(namespaceName)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private static void deleteBestEffort(RedshiftServerlessClient client, String namespaceName) {
        try {
            client.deleteNamespace(request -> request.namespaceName(namespaceName));
        } catch (Exception cleanupError) {
            LOG.warnf(cleanupError, "Best-effort cleanup failed for namespaceName=%s", namespaceName);
        }
    }
}

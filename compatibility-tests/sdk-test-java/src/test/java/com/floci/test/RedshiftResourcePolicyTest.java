package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.InvalidPolicyException;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshift.model.ResourcePolicy;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A Redshift resource policy on a Redshift Serverless namespace, through the AWS SDK for Java v2. */
@DisplayName("Redshift resource policies")
class RedshiftResourcePolicyTest {

    private static final String NAMESPACE_NAME = "rp-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String POLICY = "{\"Statement\":[{\"Action\":\"redshift:AuthorizeInboundIntegration\","
            + "\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"redshift.amazonaws.com\"},\"Resource\":\"*\"}],"
            + "\"Version\":\"2012-10-17\"}";
    private static final String STORED_POLICY = """
            {
              "Version" : "2012-10-17",
              "Statement" : [ {
                "Effect" : "Allow",
                "Principal" : {
                  "Service" : "redshift.amazonaws.com"
                },
                "Action" : "redshift:AuthorizeInboundIntegration",
                "Resource" : "*"
              } ]
            }""";

    private static RedshiftClient redshift;
    private static RedshiftServerlessClient serverless;
    private static String namespaceArn;

    @BeforeAll
    static void setUp() {
        String endpoint = System.getenv("FLOCI_ENDPOINT");
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:4566";
        }
        if (!endpoint.startsWith("http")) {
            endpoint = "http://" + endpoint;
        }
        StaticCredentialsProvider credentials =
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        redshift = RedshiftClient.builder().endpointOverride(URI.create(endpoint))
                .region(TestFixtures.region()).credentialsProvider(credentials).build();
        serverless = RedshiftServerlessClient.builder().endpointOverride(URI.create(endpoint))
                .region(TestFixtures.region()).credentialsProvider(credentials).build();
        namespaceArn = serverless.createNamespace(r -> r.namespaceName(NAMESPACE_NAME)).namespace().namespaceArn();
    }

    @AfterAll
    static void cleanup() {
        try {
            serverless.deleteNamespace(r -> r.namespaceName(NAMESPACE_NAME));
        } catch (Exception ignored) {
            // Best effort: the test may have failed before creating it.
        }
        redshift.close();
        serverless.close();
    }

    @Test
    void aNamespacePolicyIsPutReadAndDeleted() {
        ResourcePolicy put = redshift.putResourcePolicy(r -> r.resourceArn(namespaceArn).policy(POLICY))
                .resourcePolicy();
        assertThat(put.resourceArn()).isEqualTo(namespaceArn);
        assertThat(put.policy()).isEqualTo(STORED_POLICY);

        ResourcePolicy got = redshift.getResourcePolicy(r -> r.resourceArn(namespaceArn)).resourcePolicy();
        assertThat(got.resourceArn()).isEqualTo(namespaceArn);
        assertThat(got.policy()).isEqualTo(STORED_POLICY);

        redshift.deleteResourcePolicy(r -> r.resourceArn(namespaceArn));
        assertThatThrownBy(() -> redshift.getResourcePolicy(r -> r.resourceArn(namespaceArn)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void refusalsMapToTheSdksExceptions() {
        assertThatThrownBy(() -> redshift.putResourcePolicy(r -> r.resourceArn(namespaceArn).policy("[]")))
                .isInstanceOf(InvalidPolicyException.class);

        String unknown = namespaceArn.substring(0, namespaceArn.lastIndexOf('/') + 1)
                + "00000000-0000-0000-0000-000000000000";
        // An empty policy is refused before the missing namespace is looked up; a valid one is not.
        assertThatThrownBy(() -> redshift.putResourcePolicy(r -> r.resourceArn(unknown).policy("{}")))
                .isInstanceOf(InvalidPolicyException.class);
        assertThatThrownBy(() -> redshift.putResourcePolicy(r -> r.resourceArn(unknown).policy(POLICY)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> redshift.getResourcePolicy(r -> r.resourceArn(unknown)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("The requested namespace doesn't exist.");

        String workgroup = namespaceArn.replace(":namespace/", ":workgroup/");
        assertThatThrownBy(() -> redshift.getResourcePolicy(r -> r.resourceArn(workgroup)))
                .isInstanceOfSatisfying(RedshiftException.class, e -> {
                    assertThat(e.statusCode()).isEqualTo(400);
                    assertThat(e.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterValue");
                });
    }
}

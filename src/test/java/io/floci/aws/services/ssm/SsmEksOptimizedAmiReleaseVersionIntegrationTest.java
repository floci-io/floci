package io.floci.aws.services.ssm;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.net.URI;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AWS publishes {@code image_id} and {@code release_version} under each recommended EKS optimized
 * AMI path. The release version is the node's Kubernetes patch version and the AMI build date,
 * {@code 1.14.9-20201007} in the Systems Manager guide, and the EKS managed node group module reads
 * it by default for Amazon Linux 2023 node groups.
 */
@QuarkusTest
class SsmEksOptimizedAmiReleaseVersionIntegrationTest {

    private static final String AL2023_ARM64_RECOMMENDED =
            "/aws/service/eks/optimized-ami/1.34/amazon-linux-2023/arm64/standard/recommended";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void recommendedReleaseVersionIsTheKubernetesPatchVersionAndTheImageBuildDate() {
        try (SsmClient ssm = client()) {
            String value = ssm.getParameter(r -> r.name(AL2023_ARM64_RECOMMENDED + "/release_version"))
                    .parameter().value();

            assertEquals("1.34.1-20230315", value);
        }
    }

    @Test
    void recommendedPathListsBothTheImageIdAndTheReleaseVersion() {
        try (SsmClient ssm = client()) {
            List<String> names = ssm.getParametersByPath(r -> r.path(AL2023_ARM64_RECOMMENDED))
                    .parameters().stream().map(Parameter::name).toList();

            assertThat(names, containsInAnyOrder(
                    AL2023_ARM64_RECOMMENDED + "/image_id", AL2023_ARM64_RECOMMENDED + "/release_version"));
        }
    }

    private SsmClient client() {
        return AwsTestClients.create(SsmClient.builder(), "000000000000", Region.US_EAST_1, endpoint);
    }
}

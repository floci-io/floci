package io.floci.aws.services.eks;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.eks.model.AMITypes;
import software.amazon.awssdk.services.eks.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.eks.model.Nodegroup;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A managed node group without a requested {@code releaseVersion} reports the release of the
 * recommended EKS optimized AMI for its Kubernetes version and AMI type, the
 * {@code <patch version>-<build date>} value SSM publishes under that AMI's
 * {@code recommended/release_version}. A node group whose launch template names a custom AMI
 * reports that AMI's id instead, as the {@code Nodegroup} API reference describes.
 */
@QuarkusTest
class EksNodegroupReleaseVersionIntegrationTest {

    private static final String NODE_ROLE = "arn:aws:iam::000000000000:role/eks-node-role";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void anAmazonLinux2023Arm64NodeGroupReportsTheRecommendedAmiRelease() {
        Nodegroup nodegroup = createNodegroup("1.34", AMITypes.AL2023_ARM_64_STANDARD);

        assertEquals("1.34.1-20230315", nodegroup.releaseVersion());
        assertEquals(recommendedReleaseVersion("1.34/amazon-linux-2023/arm64/standard"), nodegroup.releaseVersion());
    }

    @Test
    void anAmazonLinux2NodeGroupReportsTheRecommendedAmiRelease() {
        Nodegroup nodegroup = createNodegroup("1.30", AMITypes.AL2_X86_64);

        assertEquals("1.30.10-20230404", nodegroup.releaseVersion());
        assertEquals(recommendedReleaseVersion("1.30/amazon-linux-2"), nodegroup.releaseVersion());
    }

    @Test
    void aNodeGroupWhoseLaunchTemplateNamesACustomAmiReportsThatAmiId() {
        String launchTemplate = "custom-ami-" + UUID.randomUUID();
        try (Ec2Client ec2 = AwsTestClients.create(Ec2Client.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            ec2.createLaunchTemplate(r -> r.launchTemplateName(launchTemplate)
                    .launchTemplateData(d -> d.imageId("ami-0123456789abcdef0")));
        }

        Nodegroup nodegroup = createNodegroup("1.34", null,
                LaunchTemplateSpecification.builder().name(launchTemplate).build());

        assertEquals("ami-0123456789abcdef0", nodegroup.releaseVersion());
    }

    private Nodegroup createNodegroup(String kubernetesVersion, AMITypes amiType) {
        return createNodegroup(kubernetesVersion, amiType, null);
    }

    private Nodegroup createNodegroup(String kubernetesVersion, AMITypes amiType,
                                      LaunchTemplateSpecification launchTemplate) {
        String cluster = "release-version-" + UUID.randomUUID();
        try (EksClient eks = AwsTestClients.create(EksClient.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            eks.createCluster(r -> r.name(cluster).version(kubernetesVersion)
                    .roleArn("arn:aws:iam::000000000000:role/eks-role"));
            eks.createNodegroup(r -> r.clusterName(cluster).nodegroupName("ng").nodeRole(NODE_ROLE)
                    .subnets("subnet-abc").amiType(amiType).launchTemplate(launchTemplate));
            return eks.describeNodegroup(r -> r.clusterName(cluster).nodegroupName("ng")).nodegroup();
        }
    }

    private String recommendedReleaseVersion(String amiPath) {
        try (SsmClient ssm = AwsTestClients.create(SsmClient.builder(), "000000000000", Region.US_EAST_1, endpoint)) {
            return ssm.getParameter(r -> r.name("/aws/service/eks/optimized-ami/" + amiPath
                    + "/recommended/release_version")).parameter().value();
        }
    }
}

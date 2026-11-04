package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationResponse;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.VpcConfig;
import software.amazon.awssdk.services.lambda.model.VpcConfigResponse;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class LambdaVpcResourceValidationIntegrationTest {

    private final String name = "vpc-validation-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<Executable> cleanup = new ArrayList<>();
    private Ec2Client ec2;
    private IamClient iam;
    private LambdaClient lambda;
    private Network first;
    private Network second;
    private String roleArn;
    private FunctionCode code;

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeEach
    void createDeclaredNetworksAndRole() throws Exception {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        ec2 = Ec2Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(credentials).build();
        iam = IamClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(credentials).build();
        lambda = LambdaClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(credentials).build();
        first = network("10.90");
        second = network("10.91");
        roleArn = iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """)).role().arn();
        cleanup.add(() -> iam.deleteRole(r -> r.roleName(name)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write("exports.handler = async event => event;".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        code = FunctionCode.builder().zipFile(SdkBytes.fromByteArray(bytes.toByteArray())).build();
        cleanup.add(() -> {
            try {
                lambda.deleteFunction(r -> r.functionName(name));
            } catch (ResourceNotFoundException ignored) {
                // A rejected create must leave no function to delete.
            }
        });
    }

    @AfterEach
    void removeOwnedResources() {
        try {
            Collections.reverse(cleanup);
            assertAll(cleanup);
        } finally {
            if (lambda != null) {
                lambda.close();
            }
            if (iam != null) {
                iam.close();
            }
            if (ec2 != null) {
                ec2.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mixed-subnets", "mixed-groups", "only-subnets", "only-groups",
            "unknown-with-mixed-subnets", "unknown-with-mixed-groups"})
    void invalidVpcResourcesCannotCreateAFunction(String kind) {
        VpcConfig config = switch (kind) {
            case "mixed-subnets" -> VpcConfig.builder().subnetIds(first.subnet(), second.subnet())
                    .securityGroupIds(first.group()).build();
            case "mixed-groups" -> VpcConfig.builder().subnetIds(first.subnet())
                    .securityGroupIds(first.group(), second.group()).build();
            case "unknown-with-mixed-subnets" -> VpcConfig.builder()
                    .subnetIds(first.subnet(), "subnet-fffffffffffffffff", second.subnet())
                    .securityGroupIds(first.group()).build();
            case "unknown-with-mixed-groups" -> VpcConfig.builder()
                    .subnetIds("subnet-fffffffffffffffff")
                    .securityGroupIds(first.group(), "sg-fffffffffffffffff", second.group()).build();
            case "only-subnets" -> VpcConfig.builder().subnetIds(first.subnet()).build();
            case "only-groups" -> VpcConfig.builder().securityGroupIds(first.group()).build();
            default -> throw new IllegalArgumentException(kind);
        };
        InvalidParameterValueException failure = assertThrows(
                InvalidParameterValueException.class, () -> createFunction(config));
        assertEquals(400, failure.statusCode());
        assertThrows(ResourceNotFoundException.class,
                () -> lambda.getFunctionConfiguration(r -> r.functionName(name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"both", "subnet", "group", "second-subnet", "second-group"})
    void unknownVpcIdsRoundTripWithoutAnInferredVpc(String kind) {
        VpcConfig config = switch (kind) {
            case "both" -> VpcConfig.builder().subnetIds("subnet-fffffffffffffffff")
                    .securityGroupIds("sg-fffffffffffffffff").build();
            case "subnet" -> VpcConfig.builder().subnetIds("subnet-fffffffffffffffff")
                    .securityGroupIds(first.group()).build();
            case "group" -> VpcConfig.builder().subnetIds(first.subnet())
                    .securityGroupIds("sg-fffffffffffffffff").build();
            case "second-subnet" -> VpcConfig.builder().subnetIds(first.subnet(), "subnet-fffffffffffffffff")
                    .securityGroupIds(first.group()).build();
            case "second-group" -> VpcConfig.builder().subnetIds(first.subnet())
                    .securityGroupIds(first.group(), "sg-fffffffffffffffff").build();
            default -> throw new IllegalArgumentException(kind);
        };
        createFunction(config);
        assertEquals(config.subnetIds(), configuration().vpcConfig().subnetIds());
        assertEquals(config.securityGroupIds(), configuration().vpcConfig().securityGroupIds());
        assertNull(configuration().vpcConfig().vpcId());
    }

    @Test
    void unknownVpcUpdateClearsOnlyTheLatestInferredVpc() {
        createFunction(first.config());
        String version = lambda.publishVersion(r -> r.functionName(name)).version();
        VpcConfig placeholders = VpcConfig.builder().subnetIds("subnet-fffffffffffffffff")
                .securityGroupIds("sg-fffffffffffffffff").build();
        lambda.updateFunctionConfiguration(r -> r.functionName(name).description("updated")
                .vpcConfig(placeholders));
        assertEquals("updated", configuration().description());
        assertEquals(placeholders.subnetIds(), configuration().vpcConfig().subnetIds());
        assertEquals(placeholders.securityGroupIds(), configuration().vpcConfig().securityGroupIds());
        assertNull(configuration().vpcConfig().vpcId());
        assertNetwork(first, lambda.getFunctionConfiguration(
                r -> r.functionName(name).qualifier(version)).vpcConfig());
    }

    @Test
    void rejectedVpcUpdatePreservesTheExistingConfigurationAndRevision() {
        createFunction(first.config());
        GetFunctionConfigurationResponse before = configuration();

        InvalidParameterValueException failure = assertThrows(InvalidParameterValueException.class,
                () -> lambda.updateFunctionConfiguration(r -> r.functionName(name)
                        .description("must-not-change").timeout(30).memorySize(256)
                        .vpcConfig(VpcConfig.builder().subnetIds(first.subnet(), second.subnet())
                                .securityGroupIds(first.group()).build())));

        assertEquals(400, failure.statusCode());
        GetFunctionConfigurationResponse after = configuration();
        assertEquals(before.vpcConfig(), after.vpcConfig());
        assertEquals(before.description(), after.description());
        assertEquals(before.timeout(), after.timeout());
        assertEquals(before.memorySize(), after.memorySize());
        assertEquals(before.revisionId(), after.revisionId());
        assertEquals(before.lastModified(), after.lastModified());
    }

    @Test
    void validVpcUpdatePreservesThePublishedVersionsNetwork() {
        createFunction(first.config());
        String version = lambda.publishVersion(r -> r.functionName(name)).version();
        lambda.updateFunctionConfiguration(r -> r.functionName(name).vpcConfig(second.config()));

        assertNetwork(second, configuration().vpcConfig());
        assertNetwork(first, lambda.getFunctionConfiguration(
                r -> r.functionName(name).qualifier(version)).vpcConfig());
    }

    @Test
    void emptySubnetAndGroupListsDetachTheFunction() {
        createFunction(first.config());
        lambda.updateFunctionConfiguration(r -> r.functionName(name)
                .vpcConfig(VpcConfig.builder().subnetIds(List.of()).securityGroupIds(List.of()).build()));

        VpcConfigResponse current = configuration().vpcConfig();
        assertTrue(current == null || (!current.hasSubnetIds() && !current.hasSecurityGroupIds()
                && (current.vpcId() == null || current.vpcId().isEmpty())));
    }

    private Network network(String prefix) {
        String vpc = ec2.createVpc(r -> r.cidrBlock(prefix + ".0.0/16")).vpc().vpcId();
        cleanup.add(() -> ec2.deleteVpc(r -> r.vpcId(vpc)));
        String subnet = ec2.createSubnet(r -> r.vpcId(vpc).cidrBlock(prefix + ".1.0/24")).subnet().subnetId();
        cleanup.add(() -> ec2.deleteSubnet(r -> r.subnetId(subnet)));
        String group = ec2.createSecurityGroup(
                r -> r.vpcId(vpc).groupName(name).description("Declared Lambda network")).groupId();
        cleanup.add(() -> ec2.deleteSecurityGroup(r -> r.groupId(group)));
        return new Network(vpc, subnet, group);
    }

    private void createFunction(VpcConfig config) {
        lambda.createFunction(r -> r.functionName(name).runtime(Runtime.NODEJS22_X)
                .role(roleArn).handler("index.handler").code(code).description("original")
                .timeout(9).memorySize(128).vpcConfig(config));
    }

    private GetFunctionConfigurationResponse configuration() {
        return lambda.getFunctionConfiguration(r -> r.functionName(name));
    }

    private static void assertNetwork(Network expected, VpcConfigResponse actual) {
        assertEquals(expected.vpc(), actual.vpcId());
        assertEquals(List.of(expected.subnet()), actual.subnetIds());
        assertEquals(List.of(expected.group()), actual.securityGroupIds());
    }

    private record Network(String vpc, String subnet, String group) {
        private VpcConfig config() {
            return VpcConfig.builder().subnetIds(subnet).securityGroupIds(group).build();
        }
    }
}

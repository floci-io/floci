package io.floci.aws.services.rds;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.DBInstance;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #6273: with {@code endpoints-in-vpc} on, two real PostgreSQL instances both serve 5432, each at its own address in
 * its subnet under the endpoint name RDS gives it, which Floci's DNS server answers with that address. A client on the
 * VPC's Docker network, the stand-in for a container in the same VPC, signs in through each address and sees each
 * database's own data. A stopped instance's name has no address, and a started one is served again at the address it
 * had.
 *
 * <p>A Docker end-to-end check: it runs real database and relay containers. The client connects by address, as
 * Floci's DNS server is not reachable from containers on every runner.
 */
@QuarkusTest
@TestProfile(RdsEndpointsInVpcDockerNightlyIntegrationTest.Profile.class)
@EnabledIfSystemProperty(named = "nightly.checks.enabled", matches = "true")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class RdsEndpointsInVpcDockerNightlyIntegrationTest {

    /** Endpoints in the VPC, real databases, and Docker-backed VPC networks under a namespace of their own. */
    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.endpoints-in-vpc", "true",
                    "floci.services.rds.mock", "false",
                    "floci.services.ec2.mock", "false",
                    "floci.services.ec2.vpc-networks.enabled", "true",
                    "floci.network.security-group-enforcement.enabled", "false",
                    "floci.docker.resource-namespace", "rds-endpoints-in-vpc-it");
        }
    }

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String PASSWORD = "password123";

    @Inject
    DockerClient dockerClient;

    @Inject
    RdsService rdsService;

    @Inject
    RdsDnsRecordSource dnsRecordSource;

    @Inject
    VpcNetworkManager vpcNetworkManager;

    @BeforeEach
    void requireDocker() {
        assumeTrue(DockerAvailability.answers(dockerClient), "Docker must be available for the endpoints-in-VPC check");
    }

    @Test
    void twoInstancesServe5432AtTheirOwnAddressesUnderTheirRdsNames() throws Exception {
        String first = "vpc-one-" + UUID.randomUUID().toString().substring(0, 8);
        String second = "vpc-two-" + UUID.randomUUID().toString().substring(0, 8);
        String client = null;
        String vpcId = null;
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                for (String id : List.of(first, second)) {
                    rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres").engineVersion("16.3")
                            .masterUsername("admin").masterUserPassword(PASSWORD).dbName("appdb")
                            .dbInstanceClass("db.t3.micro").allocatedStorage(20).port(5432));
                }
                String firstName = AwsEndpoints.rdsInstanceHost(first, ACCOUNT, REGION);
                String secondName = AwsEndpoints.rdsInstanceHost(second, ACCOUNT, REGION);
                for (String id : List.of(first, second)) {
                    DBInstance described = rds.describeDBInstances(r -> r.dbInstanceIdentifier(id))
                            .dbInstances().getFirst();
                    assertEquals(AwsEndpoints.rdsInstanceHost(id, ACCOUNT, REGION), described.endpoint().address());
                    assertEquals(5432, described.endpoint().port());
                }
                String firstAddress = address(firstName);
                String secondAddress = address(secondName);
                assertNotEquals(firstAddress, secondAddress);

                vpcId = rdsService.getDbInstance(first).getVpcId();
                String network = vpcNetworkManager.networkNameFor(ACCOUNT, REGION, vpcId).orElseThrow();
                String image = dockerClient.inspectContainerCmd(rdsService.getDbInstance(first).getContainerId())
                        .exec().getConfig().getImage();
                client = dockerClient.createContainerCmd(image)
                        .withEntrypoint("sleep")
                        .withCmd("600")
                        .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                        .exec().getId();
                dockerClient.startContainerCmd(client).exec();

                assertEquals("first", psql(client, firstAddress,
                        "CREATE TABLE IF NOT EXISTS marker(v text PRIMARY KEY); "
                        + "INSERT INTO marker VALUES ('first') ON CONFLICT DO NOTHING; SELECT v FROM marker;"));
                assertEquals("0", psql(client, secondAddress,
                        "SELECT count(*) FROM pg_tables WHERE tablename = 'marker';"));

                rds.stopDBInstance(r -> r.dbInstanceIdentifier(first));
                assertTrue(dnsRecordSource.resolveAuthoritative(firstName, 1).orElseThrow().isEmpty(),
                        "a stopped instance's name has no address");
                rds.startDBInstance(r -> r.dbInstanceIdentifier(first));
                assertEquals(firstAddress, address(firstName));
                assertEquals("first", psql(client, firstAddress, "SELECT v FROM marker;"));
            } finally {
                for (String id : List.of(first, second)) {
                    try {
                        rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                    } catch (RuntimeException ignored) {
                        // Not created, or already gone: nothing of it is left to remove.
                    }
                }
            }
        } finally {
            if (client != null) {
                dockerClient.removeContainerCmd(client).withForce(true).exec();
            }
            if (vpcId != null) {
                vpcNetworkManager.deleteVpcNetwork(ACCOUNT, REGION, vpcId);
            }
        }
    }

    private String address(String name) {
        DnsAnswer answer = dnsRecordSource.resolveAuthoritative(name, 1).orElseThrow();
        assertEquals(1, answer.addresses().size(), name + " must resolve to one address");
        return answer.addresses().getFirst();
    }

    /** Runs {@code sql} as the master user through {@code address}:5432 from the client, once the endpoint answers. */
    private String psql(String client, String address, String sql) {
        String[] command = {"env", "PGPASSWORD=" + PASSWORD, "PGCONNECT_TIMEOUT=3",
                "PGOPTIONS=-c client_min_messages=warning", "psql", "-h", address, "-p", "5432",
                "-U", "admin", "-d", "appdb", "-tAq", "-c", sql};
        return await("psql through " + address).atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(2))
                .until(() -> {
                    ContainerExec.Result result = ContainerExec.runMerged(dockerClient, client, command, 30);
                    return result.exitCode() == 0 ? result.stdout().strip() : null;
                }, output -> output != null);
    }
}

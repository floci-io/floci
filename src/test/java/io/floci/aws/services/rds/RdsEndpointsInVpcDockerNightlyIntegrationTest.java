package io.floci.aws.services.rds;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.HostConfig;
import io.floci.aws.core.common.AwsEndpoints;
import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.docker.ContainerExec;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.DockerAvailability;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.rds.model.AuthScheme;
import software.amazon.awssdk.services.rds.model.DBCluster;
import software.amazon.awssdk.services.rds.model.DBInstance;
import software.amazon.awssdk.services.rds.model.UserAuthConfig;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * had. A real Aurora PostgreSQL cluster's instances serve its port the same way, and its cluster endpoint name
 * resolves to the writer, before and after a failover, and its reader endpoint name to the reader. A DB proxy's default
 * endpoint and an endpoint added to it serve 5432 at their own addresses, through the proxy to its target.
 *
 * <p>A Docker end-to-end check: it runs real database and relay containers. The client connects by address, as
 * Floci's DNS server is not reachable from containers on every runner. The tests share the default VPC, whose Docker
 * network goes once they have all run.
 */
@QuarkusTest
@TestProfile(RdsEndpointsInVpcDockerNightlyIntegrationTest.Profile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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

    /** The VPCs the tests served endpoints in, whose Docker networks go after the last test. */
    private final Set<String> vpcIds = new LinkedHashSet<>();

    @BeforeEach
    void requireDocker() {
        assumeTrue(DockerAvailability.answers(dockerClient), "Docker must be available for the endpoints-in-VPC check");
    }

    @Test
    void twoInstancesServe5432AtTheirOwnAddressesUnderTheirRdsNames() throws Exception {
        String first = "vpc-one-" + UUID.randomUUID().toString().substring(0, 8);
        String second = "vpc-two-" + UUID.randomUUID().toString().substring(0, 8);
        String client = null;
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                for (String id : List.of(first, second)) {
                    rds.createDBInstance(r -> r.dbInstanceIdentifier(id).engine("postgres").engineVersion("16.3")
                            .masterUsername("admin").masterUserPassword(PASSWORD).dbName("appdb")
                            .dbInstanceClass("db.t3.micro").allocatedStorage(20).port(5432));
                    vpcIds.add(rdsService.getDbInstance(id).getVpcId());
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

                String vpcId = rdsService.getDbInstance(first).getVpcId();
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
        }
    }

    @Test
    void aClustersNamesResolveToItsInstancesWhichServeItsPort() throws Exception {
        String clusterId = "vpc-cluster-" + UUID.randomUUID().toString().substring(0, 8);
        String writer = clusterId + "-1";
        String reader = clusterId + "-2";
        String client = null;
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                rds.createDBCluster(r -> r.dbClusterIdentifier(clusterId).engine("aurora-postgresql")
                        .engineVersion("16.3").masterUsername("admin").masterUserPassword(PASSWORD)
                        .databaseName("appdb").port(5432));
                vpcIds.add(rdsService.getDbCluster(clusterId).getVpcId());
                for (String id : List.of(writer, reader)) {
                    rds.createDBInstance(r -> r.dbInstanceIdentifier(id).dbClusterIdentifier(clusterId)
                            .engine("aurora-postgresql").dbInstanceClass("db.r6g.large"));
                }
                String clusterName = AwsEndpoints.rdsClusterEndpointHost(clusterId, "cluster", ACCOUNT, REGION);
                String readerName = AwsEndpoints.rdsClusterEndpointHost(clusterId, "cluster-ro", ACCOUNT, REGION);
                DBCluster described = rds.describeDBClusters(r -> r.dbClusterIdentifier(clusterId)).dbClusters()
                        .getFirst();
                assertEquals(clusterName, described.endpoint());
                assertEquals(readerName, described.readerEndpoint());
                assertEquals(5432, described.port());
                String writerAddress = address(AwsEndpoints.rdsInstanceHost(writer, ACCOUNT, REGION));
                String readerAddress = address(AwsEndpoints.rdsInstanceHost(reader, ACCOUNT, REGION));
                assertNotEquals(writerAddress, readerAddress);
                assertEquals(writerAddress, address(clusterName));
                assertEquals(readerAddress, address(readerName));

                String vpcId = rdsService.getDbCluster(clusterId).getVpcId();
                String network = vpcNetworkManager.networkNameFor(ACCOUNT, REGION, vpcId).orElseThrow();
                String image = dockerClient.inspectContainerCmd(rdsService.getDbCluster(clusterId).getContainerId())
                        .exec().getConfig().getImage();
                client = dockerClient.createContainerCmd(image)
                        .withEntrypoint("sleep")
                        .withCmd("600")
                        .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                        .exec().getId();
                dockerClient.startContainerCmd(client).exec();

                assertEquals("written", psql(client, address(clusterName),
                        "CREATE TABLE IF NOT EXISTS marker(v text PRIMARY KEY); "
                        + "INSERT INTO marker VALUES ('written') ON CONFLICT DO NOTHING; SELECT v FROM marker;"));
                assertEquals("written", psql(client, address(readerName), "SELECT v FROM marker;"));

                rds.failoverDBCluster(r -> r.dbClusterIdentifier(clusterId).targetDBInstanceIdentifier(reader));
                assertEquals(readerAddress, address(clusterName));
                assertEquals(writerAddress, address(readerName));
                assertEquals("written", psql(client, address(clusterName), "SELECT v FROM marker;"));
            } finally {
                for (String id : List.of(writer, reader)) {
                    try {
                        rds.deleteDBInstance(r -> r.dbInstanceIdentifier(id).skipFinalSnapshot(true));
                    } catch (RuntimeException ignored) {
                        // Not created, or already gone: nothing of it is left to remove.
                    }
                }
                try {
                    rds.deleteDBCluster(r -> r.dbClusterIdentifier(clusterId).skipFinalSnapshot(true));
                } catch (RuntimeException ignored) {
                    // Not created, or already gone.
                }
            }
        } finally {
            if (client != null) {
                dockerClient.removeContainerCmd(client).withForce(true).exec();
            }
        }
    }

    @Test
    void aProxysEndpointsServe5432AtTheirOwnAddressesThroughTheProxy() throws Exception {
        String instanceId = "vpc-target-" + UUID.randomUUID().toString().substring(0, 8);
        String proxyName = "vpc-proxy-" + UUID.randomUUID().toString().substring(0, 8);
        String endpointName = "vpc-reader-" + UUID.randomUUID().toString().substring(0, 8);
        String client = null;
        try (RdsClient rds = AwsTestClients.create(RdsClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            try {
                rds.createDBInstance(r -> r.dbInstanceIdentifier(instanceId).engine("postgres").engineVersion("16.3")
                        .masterUsername("admin").masterUserPassword(PASSWORD).dbName("appdb")
                        .dbInstanceClass("db.t3.micro").allocatedStorage(20).port(5432));
                vpcIds.add(rdsService.getDbInstance(instanceId).getVpcId());
                List<String> subnets = List.of(Ec2Service.defaultSubnetId(REGION, "a", ACCOUNT),
                        Ec2Service.defaultSubnetId(REGION, "b", ACCOUNT));
                String proxyEndpoint = rds.createDBProxy(r -> r.dbProxyName(proxyName).engineFamily("POSTGRESQL")
                        .roleArn("arn:aws:iam::" + ACCOUNT + ":role/proxy").vpcSubnetIds(subnets)
                        .auth(UserAuthConfig.builder().authScheme(AuthScheme.SECRETS)
                                .secretArn("arn:aws:secretsmanager:" + REGION + ":" + ACCOUNT + ":secret:db-AbCdEf")
                                .build())).dbProxy().endpoint();
                assertEquals(AwsEndpoints.rdsProxyHost(proxyName, ACCOUNT, REGION), proxyEndpoint);
                rds.registerDBProxyTargets(r -> r.dbProxyName(proxyName).dbInstanceIdentifiers(instanceId));
                String added = rds.createDBProxyEndpoint(r -> r.dbProxyName(proxyName)
                        .dbProxyEndpointName(endpointName).vpcSubnetIds(subnets)).dbProxyEndpoint().endpoint();
                assertEquals(AwsEndpoints.rdsProxyEndpointHost(endpointName, ACCOUNT, REGION), added);
                String proxyAddress = address(proxyEndpoint);
                String addedAddress = address(added);
                assertNotEquals(proxyAddress, addedAddress);

                String vpcId = rdsService.getDbProxy(proxyName, REGION).getVpcId();
                vpcIds.add(vpcId);
                String network = vpcNetworkManager.networkNameFor(ACCOUNT, REGION, vpcId).orElseThrow();
                String image = dockerClient.inspectContainerCmd(rdsService.getDbInstance(instanceId).getContainerId())
                        .exec().getConfig().getImage();
                client = dockerClient.createContainerCmd(image)
                        .withEntrypoint("sleep")
                        .withCmd("600")
                        .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network))
                        .exec().getId();
                dockerClient.startContainerCmd(client).exec();

                assertEquals("proxied", psql(client, proxyAddress,
                        "CREATE TABLE IF NOT EXISTS marker(v text PRIMARY KEY); "
                        + "INSERT INTO marker VALUES ('proxied') ON CONFLICT DO NOTHING; SELECT v FROM marker;"));
                assertEquals("proxied", psql(client, addedAddress, "SELECT v FROM marker;"));
            } finally {
                try {
                    rds.deleteDBProxy(r -> r.dbProxyName(proxyName));
                } catch (RuntimeException ignored) {
                    // Not created, or already gone.
                }
                try {
                    rds.deleteDBInstance(r -> r.dbInstanceIdentifier(instanceId).skipFinalSnapshot(true));
                } catch (RuntimeException ignored) {
                    // Not created, or already gone.
                }
            }
        } finally {
            if (client != null) {
                dockerClient.removeContainerCmd(client).withForce(true).exec();
            }
        }
    }

    @AfterAll
    void removeVpcNetworks() {
        for (String vpcId : vpcIds) {
            vpcNetworkManager.deleteVpcNetwork(ACCOUNT, REGION, vpcId);
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

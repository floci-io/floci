package io.github.hectorvent.floci.services.elasticache;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticache.ElastiCacheClient;
import software.amazon.awssdk.services.elasticache.model.ServerlessCache;
import software.amazon.awssdk.services.elasticache.model.ServerlessCacheNotFoundException;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ServerlessTestProfile.class)
class ElastiCacheServerlessIntegrationTest {
    @TestHTTPResource
    URI endpoint;
    @Inject
    Ec2Service ec2;

    private ElastiCacheClient client() {
        return ElastiCacheClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    void sdkCrudRoundtripsMetadataAndKeepsTheSameEndpoint() {
        String region = "us-east-1";
        String vpc = ec2.createVpc(region, "10.65.0.0/16", false).getVpcId();
        String subnet = ec2.createSubnet(region, vpc, "10.65.1.0/24", region + "a").getSubnetId();
        String securityGroup = ec2.createSecurityGroup(region, "serverless-first", "first", vpc).getGroupId();
        String updatedGroup = ec2.createSecurityGroup(region, "serverless-second", "second", vpc).getGroupId();
        try (ElastiCacheClient client = client()) {
            ServerlessCache created = client.createServerlessCache(request -> request.serverlessCacheName("sdk-serverless")
                    .engine("valkey").description("first").securityGroupIds(securityGroup).subnetIds(subnet)
                    .snapshotRetentionLimit(3).dailySnapshotTime("03:00")
                    .cacheUsageLimits(limits -> limits.dataStorage(storage -> storage.maximum(5).unit("GB"))
                            .ecpuPerSecond(ecpu -> ecpu.maximum(1000)))
                    .tags(tag -> tag.key("team").value("test"))).serverlessCache();
            assertEquals("available", created.status());
            assertEquals("valkey", created.engine());
            assertNotNull(created.createTime());
            assertNotNull(created.endpoint());
            assertEquals(created.endpoint(), created.readerEndpoint());
            assertTrue(created.arn().endsWith(":serverlesscache:sdk-serverless"));
            assertEquals(List.of(securityGroup), created.securityGroupIds());
            assertEquals(List.of(subnet), created.subnetIds());
            assertEquals(5, created.cacheUsageLimits().dataStorage().maximum());
            assertEquals(3, created.snapshotRetentionLimit());
            assertEquals("03:00", created.dailySnapshotTime());
            assertEquals("test", client.listTagsForResource(request -> request.resourceName(created.arn()))
                    .tagList().getFirst().value());
            ServerlessCache updated = client.modifyServerlessCache(request -> request.serverlessCacheName("sdk-serverless")
                    .description("second").securityGroupIds(updatedGroup)).serverlessCache();
            assertEquals(created.endpoint(), updated.endpoint());
            assertEquals("second", updated.description());
            assertEquals(List.of(updatedGroup), updated.securityGroupIds());
            assertEquals("second", client.describeServerlessCaches(request -> request.serverlessCacheName("sdk-serverless"))
                    .serverlessCaches().getFirst().description());
            assertTrue(client.describeCacheClusters().cacheClusters().stream()
                    .noneMatch(cluster -> created.endpoint().equals(cluster.configurationEndpoint())));
            assertEquals("deleting", client.deleteServerlessCache(request -> request.serverlessCacheName("sdk-serverless"))
                    .serverlessCache().status());
            assertThrows(ServerlessCacheNotFoundException.class,
                    () -> client.describeServerlessCaches(request -> request.serverlessCacheName("sdk-serverless")));
        } finally {
            try (ElastiCacheClient client = client()) {
                try {
                    client.deleteServerlessCache(request -> request.serverlessCacheName("sdk-serverless"));
                } catch (ServerlessCacheNotFoundException expected) {
                    // Already deleted by the successful test path.
                }
            }
            ec2.deleteSecurityGroup(region, updatedGroup);
            ec2.deleteSecurityGroup(region, securityGroup);
            ec2.deleteSubnet(region, subnet);
            ec2.deleteVpc(region, vpc);
        }
    }

    @Test
    void dockerValkeyPreservesDataAndAuthenticatesCurrentGroupMembers() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for the serverless data plane");
        try (ElastiCacheClient client = client()) {
            client.createUser(request -> request.userId("serverless-alice").userName("alice").engine("valkey")
                    .accessString("on ~* +@all").passwords("alice-password-123"));
            client.createUser(request -> request.userId("serverless-bob").userName("bob").engine("valkey")
                    .accessString("on ~* +@all").passwords("bob-password-123"));
            client.createUserGroup(request -> request.userGroupId("serverless-team").engine("valkey").userIds("serverless-alice"));
            try {
                ServerlessCache cache = client.createServerlessCache(request -> request.serverlessCacheName("serverless-data")
                        .engine("valkey").userGroupId("serverless-team")).serverlessCache();
                try (Socket socket = socket(cache)) {
                    resp(socket, "PING");
                    assertTrue(line(socket).startsWith("-NOAUTH"));
                }
                try (Socket socket = socket(cache)) {
                    resp(socket, "AUTH", "alice", "alice-password-123");
                    assertEquals("+OK\r\n", line(socket));
                    resp(socket, "SET", "serverless-key", "retained");
                    assertEquals("+OK\r\n", line(socket));
                    ServerlessCache updated = client.modifyServerlessCache(request -> request.serverlessCacheName("serverless-data")
                            .description("changed while connected")).serverlessCache();
                    assertEquals(cache.endpoint(), updated.endpoint());
                    resp(socket, "GET", "serverless-key");
                    assertEquals("$8\r\n", line(socket));
                    assertEquals("retained\r\n", line(socket));
                }
                client.modifyUserGroup(request -> request.userGroupId("serverless-team")
                        .userIdsToAdd("serverless-bob").userIdsToRemove("serverless-alice"));
                try (Socket socket = socket(cache)) {
                    resp(socket, "AUTH", "alice", "alice-password-123");
                    assertTrue(line(socket).startsWith("-ERR invalid username-password"));
                }
                try (Socket socket = socket(cache)) {
                    resp(socket, "AUTH", "bob", "bob-password-123");
                    assertEquals("+OK\r\n", line(socket));
                    resp(socket, "GET", "serverless-key");
                    assertEquals("$8\r\n", line(socket));
                    assertEquals("retained\r\n", line(socket));
                }
                client.modifyUserGroup(request -> request.userGroupId("serverless-team").userIdsToRemove("serverless-bob"));
                try (Socket socket = socket(cache)) {
                    resp(socket, "PING");
                    assertTrue(line(socket).startsWith("-NOAUTH"));
                }
                try (Socket socket = socket(cache)) {
                    resp(socket, "AUTH", "bob", "bob-password-123");
                    assertTrue(line(socket).startsWith("-ERR invalid username-password"));
                }
                client.modifyServerlessCache(request -> request.serverlessCacheName("serverless-data").removeUserGroup(true));
                try (Socket socket = socket(cache)) {
                    resp(socket, "PING");
                    assertEquals("+PONG\r\n", line(socket));
                }
            } finally {
                deleteIfPresent(client, "serverless-data");
                client.deleteUserGroup(request -> request.userGroupId("serverless-team"));
                client.deleteUser(request -> request.userId("serverless-alice"));
                client.deleteUser(request -> request.userId("serverless-bob"));
            }
        }
    }

    @Test
    void dockerMemcachedPreservesValuesAcrossMetadataChanges() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is required for the serverless Memcached data plane");
        try (ElastiCacheClient client = client()) {
            try {
                ServerlessCache cache = client.createServerlessCache(request -> request.serverlessCacheName("serverless-memcached")
                        .engine("memcached")).serverlessCache();
                try (Socket socket = socket(cache)) {
                    text(socket, "set serverless-key 0 60 8\r\nretained\r\n");
                    assertEquals("STORED\r\n", line(socket));
                    ServerlessCache updated = client.modifyServerlessCache(request -> request.serverlessCacheName("serverless-memcached")
                            .description("same data")).serverlessCache();
                    assertEquals(cache.endpoint(), updated.endpoint());
                    text(socket, "get serverless-key\r\n");
                    assertEquals("VALUE serverless-key 0 8\r\n", line(socket));
                    assertEquals("retained\r\n", line(socket));
                    assertEquals("END\r\n", line(socket));
                }
                assertTrue(client.describeCacheClusters().cacheClusters().stream()
                        .noneMatch(cluster -> "memcached".equals(cluster.engine())));
            } finally {
                deleteIfPresent(client, "serverless-memcached");
            }
        }
    }

    private static void deleteIfPresent(ElastiCacheClient client, String name) {
        try {
            client.deleteServerlessCache(request -> request.serverlessCacheName(name));
        } catch (ServerlessCacheNotFoundException expected) {
            // Creation failed before publication, or the successful test already deleted it.
        }
    }

    private static boolean dockerAvailable() throws IOException, InterruptedException {
        Process process;
        try {
            process = new ProcessBuilder("docker", "info").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException expected) {
            // Docker CLI is optional on development machines.
            return false;
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    private static Socket socket(ServerlessCache cache) throws IOException {
        Socket socket = new Socket(cache.endpoint().address(), cache.endpoint().port());
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static void resp(Socket socket, String... values) throws IOException {
        StringBuilder request = new StringBuilder("*" + values.length + "\r\n");
        for (String value : values) {
            request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(value).append("\r\n");
        }
        text(socket, request.toString());
    }

    private static void text(Socket socket, String value) throws IOException {
        socket.getOutputStream().write(value.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String line(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        StringBuilder result = new StringBuilder();
        int next;
        while ((next = input.read()) != -1) {
            result.append((char) next);
            if (result.length() >= 2 && result.charAt(result.length() - 2) == '\r'
                    && result.charAt(result.length() - 1) == '\n') {
                return result.toString();
            }
        }
        throw new IOException("Connection closed before reply terminator");
    }

    @Test
    void createServerlessCacheWithTagValueMissingKeyReturnsInvalidParameterValue() {
        given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261010/us-east-1/elasticache/aws4_request")
                .formParam("Action", "CreateServerlessCache")
                .formParam("ServerlessCacheName", "tag-fail-cache")
                .formParam("Engine", "valkey")
                .formParam("Tags.Tag.1.Value", "malformed-value")
                .when()
                .post(endpoint)
                .then()
                .statusCode(400)
                .body(containsString("InvalidParameterValue"))
                .body(containsString("Tag key cannot be null or empty"));

        try (ElastiCacheClient client = client()) {
            assertThrows(ServerlessCacheNotFoundException.class,
                    () -> client.describeServerlessCaches(r -> r.serverlessCacheName("tag-fail-cache")));
        }
    }
}

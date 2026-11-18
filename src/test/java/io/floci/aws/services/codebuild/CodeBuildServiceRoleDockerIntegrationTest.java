package io.floci.aws.services.codebuild;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.CurrentContainerNetworkResolver;
import io.floci.aws.services.ecs.container.EcsCredentialsProxy;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.testing.DockerAvailability;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.TestImages;
import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build runs as its project's service role, end to end: on a user-defined Docker network, with
 * IAM enforcement on, a build reads its role's session at {@code 169.254.170.2} through the
 * container credentials proxy, the AWS CLI's default chain finds it there, STS answers the role's
 * {@code AWSCodeBuild-<id>} session, and the role's policy alone decides what the build may list.
 * Once the build has ended, the path it was handed answers nothing.
 *
 * <p>Runs in both shapes Floci runs in. With Floci on the host, the builds join a network this test
 * creates, and the proxy forwards over {@code host.docker.internal}. With Floci itself in a
 * container on a user-defined network, the builds join Floci's own network, as no CodeBuild network
 * is configured, and the proxy forwards to Floci's address there. Floci in a container on the
 * default bridge cannot hand a build the endpoint, so the test is skipped there.
 *
 * <p>The build installs the AWS CLI with pip, so it needs to reach PyPI. Skipped without a Docker
 * daemon, like the other {@code *DockerIntegrationTest} classes.
 */
@QuarkusTest
@TestProfile(CodeBuildServiceRoleDockerIntegrationTest.Profile.class)
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class CodeBuildServiceRoleDockerIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String ACCOUNT = "000000000000";
    private static final String ADMIN = "test";
    private static final String AWS_CLI = "awscli==1.40.0";
    private static final Set<String> NO_KEYS = Set.of(
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "AWS_CONTAINER_CREDENTIALS_FULL_URI", "AWS_CONTAINER_AUTHORIZATION_TOKEN",
            "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerDetector containerDetector;

    @Inject
    CurrentContainerNetworkResolver currentContainerNetworkResolver;

    @Inject
    EcsCredentialsProxy credentialsProxy;

    @Inject
    IamService iamService;

    @Inject
    S3Service s3Service;

    @Inject
    EmulatorConfig config;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String roleName = "codebuild-service-role-" + suffix;
    private final String projectName = "service-role-" + suffix;
    private final String grantedBucket = "granted-" + suffix;
    private final String otherBucket = "other-" + suffix;
    private final String resultsBucket = "results-" + suffix;
    private String createdNetworkId;
    /** Whether the test ran, so that its cleanup has something to remove and a client set up to do it. */
    private boolean ran;

    @BeforeEach
    void requireANetworkABuildCanReachTheEndpointOn() throws Exception {
        Assumptions.assumeTrue(DockerAvailability.answers(dockerClient), "Docker daemon must be available");
        RestAssuredJsonUtils.configureAwsContentTypes();
        ran = true;
        if (containerDetector.isRunningInContainer()) {
            Optional<String> own = currentContainerNetworkResolver.resolveNetworkName();
            Assumptions.assumeTrue(own.filter(Profile::userDefined).isPresent(),
                    "Floci runs in a container on " + own.orElse("no network")
                            + ", where a build cannot hold a link-local address");
        } else if (dockerClient.listNetworksCmd().withNameFilter(Profile.NETWORK).exec().stream()
                .noneMatch(network -> Profile.NETWORK.equals(network.getName()))) {
            createdNetworkId = dockerClient.createNetworkCmd().withName(Profile.NETWORK).exec().getId();
        }
    }

    @AfterEach
    void removeWhatTheTestMade() throws InterruptedException {
        if (!ran) {
            return;
        }
        codeBuild("DeleteProject")
                .body(Map.of("name", projectName)).post("/");
        for (String bucket : List.of(grantedBucket, otherBucket, resultsBucket)) {
            if (s3Service.bucketExists(bucket)) {
                s3Service.listObjects(bucket, null, null, 1000).forEach(o -> s3Service.deleteObject(bucket, o.getKey()));
                s3Service.deleteBucket(bucket);
            }
        }
        if (iamService.findRole(ACCOUNT, roleName).isPresent()) {
            iamService.deleteRolePolicy(roleName, "ListOneBucket");
            iamService.deleteRole(roleName);
        }
        if (createdNetworkId != null) {
            // The proxy lives for the process; it has to go before the network it holds an address on.
            credentialsProxy.stopManagedContainers();
            awaitNoContainerOn(createdNetworkId);
            dockerClient.removeNetworkCmd(createdNetworkId).exec();
        }
    }

    /**
     * The runner removes the build's container after it has reported the build complete, so a
     * network removed at once still has that endpoint.
     */
    private void awaitNoContainerOn(String networkId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, ?> containers =
                    dockerClient.inspectNetworkCmd().withNetworkId(networkId).exec().getContainers();
            if (containers == null || containers.isEmpty()) {
                return;
            }
            Thread.sleep(200);
        }
    }

    @Test
    void aBuildRunsAsItsProjectsServiceRoleServedAtTheContainerCredentialsEndpoint() throws Exception {
        String roleArn = iamService.createRole(roleName, "/", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"codebuild.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """, null, 0, null).getArn();
        iamService.putRolePolicy(roleName, "ListOneBucket", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:ListBucket",
                "Resource":"arn:aws:s3:::%s"}]}
                """.formatted(grantedBucket));
        for (String bucket : List.of(grantedBucket, otherBucket, resultsBucket)) {
            given().header("Authorization", auth(ADMIN, "s3")).put("/" + bucket).then().statusCode(200);
        }

        createProject(roleArn);
        String buildId = startBuild();
        JsonPath build = awaitBuild(buildId);
        assertEquals("SUCCEEDED", build.getString("buildStatus"), () -> "phases: " + build.getList("phases")
                + "; the credentials read: " + resultIfAny("credentials-headers.txt"));

        String sessionSuffix = buildId.substring(buildId.indexOf(':') + 1);
        assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + roleName + "/AWSCodeBuild-" + sessionSuffix,
                result("caller.txt").strip());

        List<String> names = Arrays.asList(result("env-names.txt").strip().split("\n"));
        assertTrue(names.contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"), names::toString);
        assertEquals(List.of(), names.stream().filter(NO_KEYS::contains).toList());

        String relativeUri = result("relative-uri.txt").strip();
        assertTrue(relativeUri.startsWith("/") && !relativeUri.contains("://"), relativeUri);
        assertTrue(result("credentials-headers.txt").contains(" 200 "), () -> result("credentials-headers.txt"));
        JsonNode credentials = new ObjectMapper().readTree(result("credentials.json"));
        assertEquals(Set.of("AccessKeyId", "SecretAccessKey", "Token", "Expiration", "RoleArn"),
                Set.copyOf(iterableToList(credentials.fieldNames())));
        assertEquals(roleArn, credentials.get("RoleArn").asText());

        assertEquals("granted listed", result("granted.txt").strip());
        assertTrue(result("other.txt").contains("AccessDenied"), () -> result("other.txt"));

        assertTrue(answersNothingWithin(relativeUri, Duration.ofSeconds(10)),
                "the build's relative URI still answers credentials after the build ended");
    }

    private void createProject(String roleArn) {
        Map<String, Object> project = new HashMap<>();
        project.put("name", projectName);
        project.put("source", Map.of("type", "NO_SOURCE", "buildspec", buildspec()));
        project.put("artifacts", Map.of("type", "S3", "location", resultsBucket, "packaging", "NONE"));
        project.put("environment", Map.of("type", "LINUX_CONTAINER", "image", TestImages.PYTHON_ALPINE,
                "computeType", "BUILD_GENERAL1_SMALL"));
        project.put("serviceRole", roleArn);
        codeBuild("CreateProject").body(project).post("/").then().statusCode(200);
    }

    private String buildspec() {
        return """
                version: 0.2
                phases:
                  install:
                    commands:
                      - pip install --quiet --disable-pip-version-check --root-user-action=ignore %s
                  build:
                    commands:
                      - env | sed 's/=.*//' | sort > env-names.txt
                      - echo "$AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" > relative-uri.txt
                      - wget -S -O credentials.json "http://169.254.170.2$AWS_CONTAINER_CREDENTIALS_RELATIVE_URI" 2> credentials-headers.txt
                      - aws sts get-caller-identity --query Arn --output text > caller.txt
                      - aws configure set default.s3.addressing_style path
                      - aws s3api list-objects-v2 --bucket %s > /dev/null && echo "granted listed" > granted.txt
                      - if aws s3api list-objects-v2 --bucket %s > other.txt 2>&1; then echo "other listed" >> other.txt; fi
                artifacts:
                  files:
                    - '*.txt'
                    - credentials.json
                """.formatted(AWS_CLI, grantedBucket, otherBucket);
    }

    private String startBuild() {
        return codeBuild("StartBuild").body(Map.of("projectName", projectName)).post("/")
                .then().statusCode(200).extract().jsonPath().getString("build.id");
    }

    private JsonPath awaitBuild(String buildId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos();
        while (true) {
            JsonPath build = codeBuild("BatchGetBuilds").body(Map.of("ids", List.of(buildId))).post("/")
                    .then().statusCode(200).extract().jsonPath().setRootPath("builds[0]");
            if (build.getBoolean("buildComplete") || System.nanoTime() > deadline) {
                return build;
            }
            Thread.sleep(500);
        }
    }

    /** A CodeBuild call signed as the account's administrator, whom IAM enforcement lets do anything. */
    private static RequestSpecification codeBuild(String action) {
        return given().header("Authorization", auth(ADMIN, "codebuild"))
                .header("X-Amz-Target", "CodeBuild_20161006." + action).contentType(CONTENT_TYPE);
    }

    private String result(String key) {
        try {
            return new String(s3Service.getObject(resultsBucket, key).getData(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new AssertionError("the build left no " + key + " in " + resultsBucket, e);
        }
    }

    private String resultIfAny(String key) {
        try {
            return result(key).strip();
        } catch (AssertionError e) {
            return "none left";
        }
    }

    /**
     * Polls the credentials server from Floci's side, as the build would through the proxy, at the
     * port the running app has, not the one this class picked: the test profile's class is
     * initialised again in the app's class loader, where its pick differs. The runner revokes the
     * session after it has reported the build complete, hence the window.
     */
    private boolean answersNothingWithin(String relativeUri, Duration window) throws InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        int port = config.services().ecs().taskRoleCredentials().port();
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + relativeUri)).GET().build();
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 404) {
                    return true;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static <T> List<T> iterableToList(Iterator<T> iterator) {
        ArrayList<T> list = new ArrayList<>();
        iterator.forEachRemaining(list::add);
        return list;
    }

    public static final class Profile implements QuarkusTestProfile {
        static final String NETWORK = "floci-codebuild-service-role-test";
        /** Two distinct ports, picked together so neither pick can repeat the other. */
        private static final int[] PORTS = freePorts(2);
        static final int CREDENTIALS_PORT = PORTS[0];
        static final int PORT = PORTS[1];

        static boolean userDefined(String network) {
            return !Set.of("bridge", "default", "host", "none").contains(network);
        }

        private static int[] freePorts(int count) {
            try {
                return FreePorts.anyInterfacePorts(count);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(Map.of(
                    "floci.services.iam.enforcement-enabled", "true",
                    "floci.services.ecs.task-role-credentials.port", String.valueOf(CREDENTIALS_PORT),
                    "quarkus.http.test-port", String.valueOf(PORT),
                    "floci.port", String.valueOf(PORT),
                    "floci.base-url", "http://localhost:" + PORT));
            // Floci in a container puts a build on its own network; on the host, on the test's.
            if (!new ContainerDetector().isRunningInContainer()) {
                overrides.put("floci.services.codebuild.docker-network", NETWORK);
            }
            return overrides;
        }
    }
}

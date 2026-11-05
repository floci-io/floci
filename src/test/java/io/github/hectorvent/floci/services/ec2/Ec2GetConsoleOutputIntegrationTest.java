package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.Ec2Requests;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.GetConsoleOutputResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.REGION;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ec2;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GetConsoleOutput returns the guest's SSH host keys between the markers cloud-init prints, the part
 * clients read from the console log to verify the host they connect to.
 *
 * <p>It shares the application of {@link Ec2CapturedImageReclaimIntegrationTest}, which turns EC2's
 * mock mode off, with the container manager mocked as there.
 */
@QuarkusTest
@TestProfile(Ec2CapturedImageReclaimIntegrationTest.ContainerBackedEc2.class)
class Ec2GetConsoleOutputIntegrationTest {

    private static final String HOST_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj root@guest";
    private static final String HOST_KEY_BLOCK = "-----BEGIN SSH HOST KEY KEYS-----\n" + HOST_KEY
            + "\n-----END SSH HOST KEY KEYS-----\n";

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    @Test
    void consoleOutputCarriesTheGuestsSshHostKeys() {
        String containerId = "console-" + UUID.randomUUID();
        Instance instance = storedInstance(containerId, InstanceState.running());
        when(containerManager.readSshHostKeys(containerId)).thenReturn(HOST_KEY + "\n");
        try {
            XmlPath response = ec2("GetConsoleOutput").formParam("InstanceId", instance.getInstanceId())
                    .when().post("/")
                    .then().statusCode(200)
                    .body("GetConsoleOutputResponse.instanceId", equalTo(instance.getInstanceId()))
                    .extract().xmlPath();

            String output = decode(response.getString("GetConsoleOutputResponse.output"));
            assertTrue(output.contains(HOST_KEY_BLOCK), output);
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void theSdkReadsTheInstanceIdTimestampAndOutput() {
        String containerId = "console-" + UUID.randomUUID();
        Instance instance = storedInstance(containerId, InstanceState.running());
        when(containerManager.readSshHostKeys(containerId)).thenReturn(HOST_KEY + "\n");
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            GetConsoleOutputResponse response = client.getConsoleOutput(r -> r.instanceId(instance.getInstanceId()));

            assertEquals(instance.getInstanceId(), response.instanceId());
            assertNotNull(response.timestamp());
            assertTrue(decode(response.output()).contains(HOST_KEY_BLOCK), response.output());
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void anInstanceWithoutAContainerHasAnEmptyConsoleOutput() {
        Instance instance = storedInstance(null, InstanceState.running());
        try {
            assertEquals("", consoleOutput(instance));
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void aStoppedInstanceHasAnEmptyConsoleOutputWithoutTouchingItsContainer() {
        Instance instance = storedInstance("console-" + UUID.randomUUID(), InstanceState.stopped());
        try {
            assertEquals("", consoleOutput(instance));
            verify(containerManager, never()).readSshHostKeys(anyString());
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void aContainerThatCannotBeReadGivesAnEmptyConsoleOutput() {
        String containerId = "console-" + UUID.randomUUID();
        Instance instance = storedInstance(containerId, InstanceState.running());
        when(containerManager.readSshHostKeys(containerId)).thenThrow(new IllegalStateException("container is not running"));
        try {
            assertEquals("", consoleOutput(instance));
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void anInstanceInAnotherRegionIsNotFound() {
        Instance instance = storedInstance(null, InstanceState.running());
        try {
            Ec2Requests.ec2("GetConsoleOutput", "test", "us-west-2")
                    .formParam("InstanceId", instance.getInstanceId())
                    .when().post("/")
                    .then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-instance-id", "i-0123456789abcdef0"})
    void aDryRunIsAnsweredBeforeTheInstanceIsLookedUp(String instanceId) {
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "test", Region.US_EAST_1)) {
            Ec2Exception error = assertThrows(Ec2Exception.class,
                    () -> client.getConsoleOutput(r -> r.instanceId(instanceId).dryRun(true)));

            assertEquals("DryRunOperation", error.awsErrorDetails().errorCode());
        }
        verify(containerManager, never()).readSshHostKeys(anyString());
    }

    @Test
    void anUnknownInstanceIsNotFound() {
        ec2("GetConsoleOutput").formParam("InstanceId", "i-0123456789abcdef0")
                .when().post("/")
                .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidInstanceID.NotFound"));
    }

    @Test
    void aRequestWithoutAnInstanceIdIsRejected() {
        ec2("GetConsoleOutput")
                .when().post("/")
                .then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    private static String consoleOutput(Instance instance) {
        return ec2("GetConsoleOutput").formParam("InstanceId", instance.getInstanceId())
                .when().post("/")
                .then().statusCode(200)
                .extract().xmlPath().getString("GetConsoleOutputResponse.output");
    }

    private static String decode(String output) {
        return new String(Base64.getDecoder().decode(output), StandardCharsets.UTF_8);
    }

    private Instance storedInstance(String containerId, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId("i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
        instance.setRegion(REGION);
        instance.setDockerContainerId(containerId);
        instance.setState(state);
        service.putInstanceForTest(instance);
        return instance;
    }
}

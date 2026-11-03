package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.REGION;
import static io.github.hectorvent.floci.services.ec2.Ec2KeyPairFixtures.ec2;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    @Test
    void consoleOutputCarriesTheGuestsSshHostKeys() {
        String containerId = "console-" + UUID.randomUUID();
        Instance instance = storedInstance(containerId);
        when(containerManager.readSshHostKeys(containerId)).thenReturn(HOST_KEY + "\n");
        try {
            XmlPath response = ec2("GetConsoleOutput").formParam("InstanceId", instance.getInstanceId())
                    .when().post("/")
                    .then().statusCode(200)
                    .body("GetConsoleOutputResponse.instanceId", equalTo(instance.getInstanceId()))
                    .extract().xmlPath();

            String output = new String(Base64.getDecoder().decode(
                    response.getString("GetConsoleOutputResponse.output")), StandardCharsets.UTF_8);
            assertTrue(output.contains("-----BEGIN SSH HOST KEY KEYS-----\n" + HOST_KEY
                    + "\n-----END SSH HOST KEY KEYS-----\n"), output);
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
    }

    @Test
    void anInstanceWithoutAContainerHasAnEmptyConsoleOutput() {
        Instance instance = storedInstance(null);
        try {
            String output = ec2("GetConsoleOutput").formParam("InstanceId", instance.getInstanceId())
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().xmlPath().getString("GetConsoleOutputResponse.output");

            assertEquals("", output);
        } finally {
            service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        }
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

    private Instance storedInstance(String containerId) {
        Instance instance = new Instance();
        instance.setInstanceId("i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
        instance.setRegion(REGION);
        instance.setDockerContainerId(containerId);
        instance.setState(InstanceState.running());
        service.putInstanceForTest(instance);
        return instance;
    }
}

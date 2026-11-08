package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Instance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.REGION;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.createKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.deleteKeyPair;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.openSshEd25519Key;
import static io.floci.aws.services.ec2.Ec2KeyPairFixtures.sshEd25519Blob;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RunInstances copies a key pair's stored public key into the guest's authorized_keys, so an ED25519
 * key pair from CreateKeyPair has to be launched with the public half of the ED25519 key it returned,
 * in either format.
 *
 * <p>It shares the application of {@link Ec2CapturedImageReclaimIntegrationTest}, which turns EC2's
 * mock mode off so that a launch reaches the container manager, mocked here as there.
 */
@QuarkusTest
@TestProfile(Ec2CapturedImageReclaimIntegrationTest.ContainerBackedEc2.class)
class Ec2CreateKeyPairLaunchIntegrationTest {

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    @InjectMock
    AmiImageResolver amiImageResolver;

    @BeforeEach
    void resolveEveryAmiToABaseImage() {
        when(amiImageResolver.resolveImage(anyString())).thenReturn(ResolvedAmiImage.minimal("floci/base:latest"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pem", "ppk"})
    void anEd25519KeyPairIsLaunchedWithThePublicHalfOfTheKeyItReturned(String keyFormat) throws Exception {
        String name = "launch-key-" + UUID.randomUUID();
        XmlPath created = createKeyPair(name, "ed25519", keyFormat).statusCode(200).extract().xmlPath();
        Instance instance = null;
        try {
            String material = created.getString("CreateKeyPairResponse.keyMaterial");
            byte[] blob = "pem".equals(keyFormat)
                    ? sshEd25519Blob(openSshEd25519Key(material))
                    : PuttyKeyFile.parse(material).publicBlob();

            instance = service.runInstances(REGION, "ami-amazonlinux2023", "t3.micro", 1, 1, name,
                    List.of(), null, null, List.of(), null, null).getInstances().getFirst();

            ArgumentCaptor<String> injected = ArgumentCaptor.forClass(String.class);
            verify(containerManager).launch(eq(instance), any(), injected.capture(), anyString(), any(), any(), any());
            assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob), injected.getValue());
        } finally {
            // The application is shared with another class: the key pair goes, and the instance gives
            // back its address and volume, though the mocked container manager leaves its record pending.
            if (instance != null) {
                service.terminateInstances(REGION, List.of(instance.getInstanceId()));
            }
            deleteKeyPair(created.getString("CreateKeyPairResponse.keyPairId"));
        }
    }
}

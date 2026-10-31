package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CreateVolume's KmsKeyId, sent through the Query handler to a real service. An encrypted volume
 * keeps the key it was created with, or the region's default EBS key when it names none, and
 * CreateVolume and DescribeVolumes report it as {@code kmsKeyId}; a volume that is not encrypted
 * has no key, so the element is left out. The service here holds no EBS encryption settings, so
 * the default is the AWS-managed {@code alias/aws/ebs} every account starts with.
 */
class Ec2VolumeKmsKeyIdTest {

    private static final String REGION = "us-east-1";
    private static final String AZ = REGION + "a";
    private static final String KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";

    private Ec2QueryHandler handler;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(ec2.mockPublicAddress()).thenReturn(Optional.empty());
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        Ec2Service service = new Ec2Service(config, mock(Ec2ContainerManager.class),
                mock(Ec2PortForwardManager.class), new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(), new MemoryFactory());
        handler = new Ec2QueryHandler(service, mock(EmulatorConfig.class), mock(FlowLogService.class),
                mock(Ec2EbsEncryptionService.class), mock(Ec2SnapshotBlockPublicAccessService.class),
                mock(Ec2IpamService.class), new RegionResolver(REGION, "000000000000"));
    }

    /** Each form CreateVolume documents for KmsKeyId is kept as the caller gave it. */
    @ParameterizedTest
    @ValueSource(strings = {
            "1234abcd-12ab-34cd-56ef-1234567890ab",
            "alias/ExampleAlias",
            KEY_ARN,
            "arn:aws:kms:us-east-1:000000000000:alias/ExampleAlias"
    })
    void anEncryptedVolumeReportsTheKeyItWasCreatedWith(String kmsKeyId) {
        String created = ok("CreateVolume", "AvailabilityZone", AZ, "Size", "1",
                "Encrypted", "true", "KmsKeyId", kmsKeyId);
        assertEquals("true", first(created, "encrypted"));
        assertEquals(kmsKeyId, first(created, "kmsKeyId"));

        String described = ok("DescribeVolumes", "VolumeId.1", first(created, "volumeId"));
        assertEquals("true", first(described, "encrypted"));
        assertEquals(kmsKeyId, first(described, "kmsKeyId"));
    }

    /** A blank KmsKeyId names no key, so the volume gets the default rather than an empty key. */
    @ParameterizedTest
    @ValueSource(strings = {"absent", "", "   "})
    void anEncryptedVolumeThatNamesNoKeyGetsTheDefaultKey(String kmsKeyId) {
        String created = kmsKeyId.equals("absent")
                ? ok("CreateVolume", "AvailabilityZone", AZ, "Size", "1", "Encrypted", "true")
                : ok("CreateVolume", "AvailabilityZone", AZ, "Size", "1", "Encrypted", "true",
                        "KmsKeyId", kmsKeyId);
        assertEquals("alias/aws/ebs", first(created, "kmsKeyId"));

        String described = ok("DescribeVolumes", "VolumeId.1", first(created, "volumeId"));
        assertEquals("alias/aws/ebs", first(described, "kmsKeyId"));
    }

    /**
     * AWS requires Encrypted=true beside a KmsKeyId. Floci still accepts the request, as it did
     * before, but an unencrypted volume has no key to report.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "false"})
    void aKeyIsNotKeptOnAVolumeThatIsNotEncrypted(String encrypted) {
        String created = encrypted.isEmpty()
                ? ok("CreateVolume", "AvailabilityZone", AZ, "Size", "1", "KmsKeyId", KEY_ARN)
                : ok("CreateVolume", "AvailabilityZone", AZ, "Size", "1", "Encrypted", encrypted,
                        "KmsKeyId", KEY_ARN);
        assertEquals("false", first(created, "encrypted"));
        assertEquals(List.of(), XmlParser.extractAll(created, "kmsKeyId"), created);

        String described = ok("DescribeVolumes", "VolumeId.1", first(created, "volumeId"));
        assertEquals("false", first(described, "encrypted"));
        assertEquals(List.of(), XmlParser.extractAll(described, "kmsKeyId"), described);
    }

    @Test
    void theKeyStaysWithTheVolumeWhenItIsModifiedAndTagged() {
        String volumeId = first(ok("CreateVolume", "AvailabilityZone", AZ, "Size", "1", "VolumeType", "gp3",
                "Encrypted", "true", "KmsKeyId", KEY_ARN), "volumeId");

        ok("ModifyVolume", "VolumeId", volumeId, "Size", "2");
        ok("CreateTags", "ResourceId.1", volumeId, "Tag.1.Key", "Team", "Tag.1.Value", "blue");

        String described = ok("DescribeVolumes", "VolumeId.1", volumeId);
        assertEquals("2", first(described, "size"));
        assertEquals(KEY_ARN, first(described, "kmsKeyId"));
    }

    private String ok(String action, String... params) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            form.add(params[i], params[i + 1]);
        }
        Response response = handler.handle(action, form, REGION);
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), action + ": " + body);
        return body;
    }

    private static String first(String xml, String element) {
        List<String> values = XmlParser.extractAll(xml, element);
        assertFalse(values.isEmpty(), "no <" + element + "> in " + xml);
        return values.getFirst();
    }

    private static final class MemoryFactory extends StorageFactory {
        private MemoryFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}

package io.floci.aws.services.ec2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.XmlParser;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static io.floci.aws.testing.Ec2ServiceFixtures.ACCOUNT;
import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

/**
 * CreateVolume's ClientToken, sent through the Query handler to a real service, as the EBS CSI
 * driver sends it when it retries. A retry with the token and the request the first call made
 * returns that call's volume and creates no other; the token with another parameter or other
 * creation tags fails with IdempotentParameterMismatch; without a token every call creates a volume.
 */
class Ec2CreateVolumeClientTokenTest {

    private static final String REGION = "us-east-1";
    private static final String TOKEN = "4f9a8d0c2b7e41f3a6c5d8e9b0a1c2d3";

    private final Ec2QueryHandler handler = new Ec2QueryHandler(mockModeService(), mock(EmulatorConfig.class),
            mock(FlowLogService.class), mock(Ec2EbsEncryptionService.class),
            mock(Ec2BlockPublicAccessService.class), mock(Ec2IpamService.class),
            new RegionResolver(REGION, ACCOUNT));

    @Test
    void aRetryWithTheSameTokenAndParametersReturnsTheFirstVolume() {
        String first = volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1"));

        assertEquals(first, volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1")));
        assertEquals(List.of(first), describedVolumeIds());
    }

    @Test
    void theSameTokenWithAnotherSizeOrOtherTagsIsAMismatch() {
        String first = volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1"));

        assertEquals("IdempotentParameterMismatch", errorCode(createVolume(TOKEN, "us-east-1a", "11", "pvc-1")));
        assertEquals("IdempotentParameterMismatch", errorCode(createVolume(TOKEN, "us-east-1a", "10", "pvc-2")));
        assertEquals(List.of(first), describedVolumeIds());
    }

    @Test
    void withoutATokenEveryCallCreatesAVolume() {
        String first = volumeId(createVolume(null, "us-east-1a", "10", "pvc-1"));
        String second = volumeId(createVolume(null, "us-east-1a", "10", "pvc-1"));

        assertNotEquals(first, second);
        assertEquals(Stream.of(first, second).sorted().toList(), describedVolumeIds());
    }

    /** The guide exempts the Availability Zone from the mismatch check: the token is zonal. */
    @Test
    void theSameTokenInAnotherZoneCreatesThatZonesVolume() {
        String first = volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1"));
        String other = volumeId(createVolume(TOKEN, "us-east-1b", "10", "pvc-1"));

        assertNotEquals(first, other);
        assertEquals(Stream.of(first, other).sorted().toList(), describedVolumeIds());
        assertEquals(other, volumeId(createVolume(TOKEN, "us-east-1b", "10", "pvc-1")));
    }

    /** The retry is compared with the first request, not with the volume as it is now. */
    @Test
    void aRetryStillMatchesAfterTheVolumeWasModifiedAndTagged() {
        String first = volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1"));
        ok("ModifyVolume", "VolumeId", first, "Size", "20");
        ok("CreateTags", "ResourceId.1", first, "Tag.1.Key", "Team", "Tag.1.Value", "storage");

        assertEquals(first, volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1")));
        assertEquals(List.of(first), describedVolumeIds());
    }

    /** A gp3 CreateVolume tagged with {@code Name}, as the CSI driver sends it; a null token is left out. */
    private Response createVolume(String token, String zone, String size, String name) {
        MultivaluedMap<String, String> form = form("AvailabilityZone", zone, "Size", size, "VolumeType", "gp3",
                "TagSpecification.1.ResourceType", "volume",
                "TagSpecification.1.Tag.1.Key", "Name", "TagSpecification.1.Tag.1.Value", name);
        if (token != null) {
            form.add("ClientToken", token);
        }
        return handler.handle("CreateVolume", form, REGION);
    }

    private List<String> describedVolumeIds() {
        return XmlParser.extractAll(ok("DescribeVolumes"), "volumeId").stream().sorted().toList();
    }

    private String ok(String action, String... params) {
        Response response = handler.handle(action, form(params), REGION);
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), action + ": " + body);
        return body;
    }

    private static MultivaluedMap<String, String> form(String... params) {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            form.add(params[i], params[i + 1]);
        }
        return form;
    }

    private static String volumeId(Response response) {
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), body);
        return XmlParser.extractFirst(body, "volumeId", null);
    }

    private static String errorCode(Response response) {
        String body = String.valueOf(response.getEntity());
        assertEquals(400, response.getStatus(), body);
        return XmlParser.extractFirst(body, "Code", null);
    }
}

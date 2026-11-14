package io.floci.aws.services.ec2;

import io.floci.aws.core.common.XmlParser;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ec2.model.Volume;
import io.floci.aws.testing.InMemoryStorageFactory;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static io.floci.aws.testing.Ec2QueryHandlerFixtures.ok;
import static io.floci.aws.testing.Ec2QueryHandlerFixtures.queryHandler;
import static io.floci.aws.testing.Ec2QueryHandlerFixtures.send;
import static io.floci.aws.testing.Ec2ServiceFixtures.ACCOUNT;
import static io.floci.aws.testing.Ec2ServiceFixtures.mockModeService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * CreateVolume's ClientToken, sent through the Query handler to a real service, as the EBS CSI
 * driver sends it when it retries. A retry with the token and the request the first call made
 * returns that call's volume and creates no other; the token with another parameter or other
 * creation tags fails with IdempotentParameterMismatch; without a token every call creates a volume.
 */
class Ec2CreateVolumeClientTokenTest {

    private static final String TOKEN = "4f9a8d0c2b7e41f3a6c5d8e9b0a1c2d3";
    private static final String MISMATCH = "Parameters differ from the previous request with this ClientToken.";

    private final Ec2QueryHandler handler = queryHandler(mockModeService());

    @Test
    void aRetryWithTheSameTokenAndParametersReturnsTheFirstVolume() {
        String first = volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1"));

        assertEquals(first, volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1")));
        assertEquals(List.of(first), describedVolumeIds());
    }

    @Test
    void theSameTokenWithAnotherSizeOrOtherTagsIsAMismatch() {
        String first = volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1"));

        assertMismatch(createVolume(TOKEN, "us-east-1a", "11", "pvc-1"));
        assertMismatch(createVolume(TOKEN, "us-east-1a", "10", "pvc-2"));
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
        ok(handler, "ModifyVolume", "VolumeId", first, "Size", "20");
        ok(handler, "CreateTags", "ResourceId.1", first, "Tag.1.Key", "Team", "Tag.1.Value", "storage");

        assertEquals(first, volumeId(createVolume(TOKEN, "us-east-1a", "10", "pvc-1")));
        assertEquals(List.of(first), describedVolumeIds());
    }

    /** Creates that race with one token are held apart by the token's lock, so one volume is made. */
    @Test
    void concurrentCreatesWithOneClientTokenMakeOneVolume() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 50; round++) {
                Ec2QueryHandler racing = queryHandler(mockModeService());
                CountDownLatch go = new CountDownLatch(1);
                List<Future<String>> ids = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    ids.add(pool.submit(() -> {
                        go.await();
                        return volumeId(createVolume(racing, TOKEN, "us-east-1a", "10", "pvc-1"));
                    }));
                }
                go.countDown();
                Set<String> distinct = ConcurrentHashMap.newKeySet();
                for (Future<String> id : ids) {
                    distinct.add(id.get());
                }
                assertEquals(1, distinct.size(), "round " + round + ": " + distinct);
                assertEquals(List.copyOf(distinct), XmlParser.extractAll(ok(racing, "DescribeVolumes"), "volumeId"));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Without a token there is nothing to replay, so a create does not read the volume store. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void aCreateWithoutATokenDoesNotScanTheVolumes(String token) {
        AccountAwareStorageBackend<Volume> volumes = spy(AccountAwareStorageBackend.<Volume>inMemory(ACCOUNT));
        Ec2QueryHandler tokenless = queryHandler(mockModeService(new InMemoryStorageFactory(ACCOUNT)
                .withStore("ec2-volumes.json", volumes)));
        clearInvocations(volumes);

        volumeId(createVolume(tokenless, token, "us-east-1a", "10", "pvc-1"));

        verify(volumes, never()).scan(any());
    }

    private Response createVolume(String token, String zone, String size, String name) {
        return createVolume(handler, token, zone, size, name);
    }

    /** A gp3 CreateVolume tagged with {@code Name}, as the CSI driver sends it; a null token is left out. */
    private static Response createVolume(Ec2QueryHandler handler, String token, String zone, String size,
                                         String name) {
        List<String> params = new ArrayList<>(List.of("AvailabilityZone", zone, "Size", size, "VolumeType", "gp3",
                "TagSpecification.1.ResourceType", "volume",
                "TagSpecification.1.Tag.1.Key", "Name", "TagSpecification.1.Tag.1.Value", name));
        if (token != null) {
            params.addAll(List.of("ClientToken", token));
        }
        return send(handler, "CreateVolume", params.toArray(String[]::new));
    }

    private List<String> describedVolumeIds() {
        return XmlParser.extractAll(ok(handler, "DescribeVolumes"), "volumeId").stream().sorted().toList();
    }

    private static String volumeId(Response response) {
        String body = String.valueOf(response.getEntity());
        assertEquals(200, response.getStatus(), body);
        return XmlParser.extractFirst(body, "volumeId", null);
    }

    private static void assertMismatch(Response response) {
        String body = String.valueOf(response.getEntity());
        assertEquals(400, response.getStatus(), body);
        assertEquals("IdempotentParameterMismatch", XmlParser.extractFirst(body, "Code", null), body);
        assertEquals(MISMATCH, XmlParser.extractFirst(body, "Message", null), body);
    }
}

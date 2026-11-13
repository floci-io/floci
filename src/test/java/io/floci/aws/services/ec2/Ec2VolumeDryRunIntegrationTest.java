package io.floci.aws.services.ec2;

import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DryRun on the volume and tag calls the EBS CSI driver makes is answered with DryRunOperation and
 * HTTP 412 once the request has passed every check the real call makes, and it changes nothing: no
 * volume is created or deleted, no attachment made or ended, no tag written or removed, and no
 * backing device touched. The driver's controller sends a dry run CreateVolume each time it starts.
 */
@QuarkusTest
class Ec2VolumeDryRunIntegrationTest {

    /** Each test works in an account of its own, so DescribeVolumes lists only the fixture's volumes. */
    private static final AtomicLong ACCOUNTS = new AtomicLong(726100000000L);
    private static final String MISSING_VOLUME = "vol-0123456789abcdef0";
    private static final String VOLUME = "DescribeVolumesResponse.volumeSet.item";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";

    @InjectMock
    Ec2VolumeBlockDeviceManager blockDevices;

    private String account;
    private String instanceId;
    private String zone;
    /** Attached to the instance at /dev/xvdf. */
    private String attachedVolumeId;
    /** Not attached, tagged team=storage. */
    private String availableVolumeId;

    @BeforeEach
    void launchAnInstanceWithAnAttachedAndAnAvailableVolume() {
        account = Long.toString(ACCOUNTS.incrementAndGet());
        XmlPath launched = ec2("RunInstances", "ImageId", "ami-1234567890abcdef0", "InstanceType", "t3.micro",
                "MinCount", "1", "MaxCount", "1");
        instanceId = launched.getString("RunInstancesResponse.instancesSet.item.instanceId");
        zone = launched.getString("RunInstancesResponse.instancesSet.item.placement.availabilityZone");
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertEquals("running", ec2("DescribeInstances", "InstanceId.1", instanceId)
                        .getString(INSTANCE + ".instanceState.name")));

        attachedVolumeId = createVolume();
        ec2("AttachVolume", "VolumeId", attachedVolumeId, "InstanceId", instanceId, "Device", "/dev/xvdf");
        availableVolumeId = createVolume();
        ec2("CreateTags", "ResourceId.1", availableVolumeId, "Tag.1.Key", "team", "Tag.1.Value", "storage");
        clearInvocations(blockDevices);
    }

    @AfterEach
    void terminateTheInstance() {
        ec2("TerminateInstances", "InstanceId.1", instanceId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CreateVolume", "DeleteVolume", "AttachVolume", "DetachVolume", "CreateTags", "DeleteTags"})
    void aDryRunIsAnsweredWithDryRunOperationAndChangesNothing(String action) {
        List<String> before = volumes();

        dryRunnable(action).formParam("DryRun", "true").post("/").then()
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"))
                .body("Response.Errors.Error.Message",
                        equalTo("Request would have succeeded, but DryRun flag is set."));

        assertEquals(before, volumes());
        verify(blockDevices, never()).createVolume(anyString(), anyInt());
        verify(blockDevices, never()).deleteVolume(anyString());
        verify(blockDevices, never()).attachVolume(any(), any(), anyString());
        verify(blockDevices, never()).detachVolume(any(), any(), anyString());
    }

    /** A dry run the real call would refuse keeps the real call's error, and changes nothing either. */
    @ParameterizedTest
    @CsvSource({
            "delete-attached-volume, VolumeInUse",
            "delete-missing-volume, InvalidVolume.NotFound",
            "attach-volume-in-use, VolumeInUse",
            "detach-available-volume, InvalidVolume.NotAttached",
            "create-volume-with-reserved-tag, InvalidParameterValue",
            "tag-missing-volume, InvalidVolume.NotFound",
            "untag-reserved-key, InvalidParameterValue"
    })
    void aDryRunTheRealCallWouldRefuseKeepsItsError(String request, String errorCode) {
        List<String> before = volumes();

        refused(request).formParam("DryRun", "true").post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));
        refused(request).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(errorCode));

        assertEquals(before, volumes());
    }

    /** A DryRun value that is not a boolean is refused as one, and the request changes nothing. */
    @ParameterizedTest
    @CsvSource({
            "CreateVolume, maybe", "CreateVolume, ''",
            "DeleteVolume, maybe", "DeleteVolume, ''",
            "AttachVolume, maybe", "AttachVolume, ''",
            "DetachVolume, maybe", "DetachVolume, ''",
            "CreateTags, maybe", "CreateTags, ''",
            "DeleteTags, maybe", "DeleteTags, ''"
    })
    void aDryRunValueThatIsNotABooleanIsRefused(String action, String value) {
        List<String> before = volumes();

        dryRunnable(action).formParam("DryRun", value).post("/").then()
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("DryRun is not a valid boolean."));

        assertEquals(before, volumes());
    }

    /** A request for {@code action} that would succeed against the fixture. */
    private RequestSpecification dryRunnable(String action) {
        return switch (action) {
            // Like the EBS CSI driver's IOPS limit probe, which tags its 4 GiB volume this way.
            case "CreateVolume" -> request(action, "AvailabilityZone", zone, "VolumeType", "io2", "Size", "4",
                    "Iops", "64000", "TagSpecification.1.ResourceType", "volume",
                    "TagSpecification.1.Tag.1.Key", "CSIVolumeName",
                    "TagSpecification.1.Tag.1.Value", "IopsLimitDryRun");
            case "DeleteVolume" -> request(action, "VolumeId", availableVolumeId);
            case "AttachVolume" -> request(action, "VolumeId", availableVolumeId, "InstanceId", instanceId,
                    "Device", "/dev/xvdg");
            case "DetachVolume" -> request(action, "VolumeId", attachedVolumeId, "InstanceId", instanceId);
            case "CreateTags" -> request(action, "ResourceId.1", availableVolumeId,
                    "Tag.1.Key", "team", "Tag.1.Value", "compute", "Tag.2.Key", "owner", "Tag.2.Value", "csi");
            case "DeleteTags" -> request(action, "ResourceId.1", availableVolumeId, "Tag.1.Key", "team");
            default -> throw new IllegalArgumentException(action);
        };
    }

    /** The request named by {@code request}, which the real call refuses. */
    private RequestSpecification refused(String request) {
        return switch (request) {
            case "delete-attached-volume" -> request("DeleteVolume", "VolumeId", attachedVolumeId);
            case "delete-missing-volume" -> request("DeleteVolume", "VolumeId", MISSING_VOLUME);
            case "attach-volume-in-use" -> request("AttachVolume", "VolumeId", attachedVolumeId,
                    "InstanceId", instanceId, "Device", "/dev/xvdh");
            case "detach-available-volume" -> request("DetachVolume", "VolumeId", availableVolumeId);
            case "create-volume-with-reserved-tag" -> request("CreateVolume", "AvailabilityZone", zone, "Size", "4",
                    "TagSpecification.1.ResourceType", "volume",
                    "TagSpecification.1.Tag.1.Key", "aws:reserved", "TagSpecification.1.Tag.1.Value", "x");
            case "tag-missing-volume" -> request("CreateTags", "ResourceId.1", MISSING_VOLUME,
                    "Tag.1.Key", "team", "Tag.1.Value", "compute");
            case "untag-reserved-key" -> request("DeleteTags", "ResourceId.1", availableVolumeId,
                    "Tag.1.Key", "aws:reserved");
            default -> throw new IllegalArgumentException(request);
        };
    }

    /** Every volume of the account with its state, attachments and tags, ordered by volume id. */
    private List<String> volumes() {
        XmlPath described = ec2("DescribeVolumes");
        List<String> volumes = new ArrayList<>();
        for (String volumeId : described.getList(VOLUME + ".volumeId", String.class)) {
            String volume = VOLUME + ".find { it.volumeId == '" + volumeId + "' }";
            volumes.add(volumeId + " " + described.getString(volume + ".status")
                    + " attachments " + described.getList(volume + ".attachmentSet.item.collect { "
                            + "it.instanceId.text() + ' ' + it.device.text() + ' ' + it.status.text() }", String.class)
                    + " tags " + described.getList(volume + ".tagSet.item.collect { "
                            + "it.key.text() + '=' + it.value.text() }", String.class));
        }
        volumes.sort(null);
        return volumes;
    }

    private String createVolume() {
        return ec2("CreateVolume", "AvailabilityZone", zone, "Size", "1")
                .getString("CreateVolumeResponse.volumeId");
    }

    private XmlPath ec2(String action, String... params) {
        return request(action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private RequestSpecification request(String action, String... params) {
        return QueryRequests.request(account, "ec2", action, params);
    }
}

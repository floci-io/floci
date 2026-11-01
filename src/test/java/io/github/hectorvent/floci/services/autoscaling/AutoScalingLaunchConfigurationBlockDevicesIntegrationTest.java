package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.testing.QueryRequests.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An Auto Scaling group launching from a launch configuration creates the volumes the
 * configuration's block device mappings describe, as one launching from a template does, for
 * the instances an instance refresh launches too; a mapping EC2 refuses fails the launch.
 */
@QuarkusTest
class AutoScalingLaunchConfigurationBlockDevicesIntegrationTest {

    /** Each test works in an account of its own, so no other test's images or volumes show up in it. */
    private static final String RESIZING_ACCOUNT = "724700000001";
    private static final String MEMBERS_ACCOUNT = "724700000002";
    private static final String REFRESH_ACCOUNT = "724700000003";
    private static final String REFUSED_ACCOUNT = "724700000004";
    private static final String GROUP = "DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
            + ".AutoScalingGroups.member";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item";
    private static final String VOLUME = "DescribeVolumesResponse.volumeSet.item";
    private static final String ACTIVITY = "DescribeScalingActivitiesResponse.DescribeScalingActivitiesResult"
            + ".Activities.member";
    private static final String SNAPSHOT = "snap-0724700000000a001";

    @Inject
    AutoScalingReconciler reconciler;

    /** The groups a test created, each named as its launch configuration and deleted with it. */
    private final List<Group> created = new ArrayList<>();

    @AfterEach
    void deleteTheGroups() {
        for (Group group : created) {
            call(group.account(), "autoscaling", "DeleteAutoScalingGroup",
                    "AutoScalingGroupName", group.name(), "ForceDelete", "true");
            call(group.account(), "autoscaling", "DeleteLaunchConfiguration",
                    "LaunchConfigurationName", group.name());
        }
    }

    @Test
    void aGroupLaunchesWithItsLaunchConfigurationsBlockDeviceMappings() {
        String account = RESIZING_ACCOUNT;
        String name = "lc-block-devices";
        // The AMI maps a data device the configuration suppresses, so a NoDevice the launch did
        // not receive would show up as a /dev/sdf volume.
        String image = call(account, "ec2", "RegisterImage", "Name", name,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8",
                "BlockDeviceMapping.1.Ebs.VolumeType", "gp2",
                "BlockDeviceMapping.1.Ebs.DeleteOnTermination", "false",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "10")
                .getString("RegisterImageResponse.imageId");

        String instance = launchFromConfiguration(account, name, image,
                "BlockDeviceMappings.member.1.DeviceName", "/dev/xvda",
                "BlockDeviceMappings.member.1.Ebs.VolumeSize", "30",
                "BlockDeviceMappings.member.1.Ebs.VolumeType", "io1",
                "BlockDeviceMappings.member.1.Ebs.Iops", "1500",
                "BlockDeviceMappings.member.2.DeviceName", "/dev/sdf",
                "BlockDeviceMappings.member.2.NoDevice", "true",
                "BlockDeviceMappings.member.3.DeviceName", "/dev/sdg",
                "BlockDeviceMappings.member.3.Ebs.VolumeSize", "12",
                "BlockDeviceMappings.member.3.Ebs.VolumeType", "gp3",
                "BlockDeviceMappings.member.3.Ebs.DeleteOnTermination", "false");

        XmlPath described = call(account, "ec2", "DescribeInstances", "InstanceId.1", instance);
        assertEquals(List.of("/dev/xvda", "/dev/sdg"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        // The configuration defaults to deleting the root even when the AMI keeps it.
        assertEquals(List.of("true", "false"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.ebs.deleteOnTermination", String.class));
        XmlPath root = volume(account, described, 0);
        assertEquals("30", root.getString(VOLUME + ".size"));
        assertEquals("io1", root.getString(VOLUME + ".volumeType"));
        assertEquals("1500", root.getString(VOLUME + ".iops"));
        XmlPath data = volume(account, described, 1);
        assertEquals("12", data.getString(VOLUME + ".size"));
        assertEquals("gp3", data.getString(VOLUME + ".volumeType"));
    }

    @Test
    void aGroupLaunchesWithEveryMemberOfItsLaunchConfigurationsEntries() {
        String account = MEMBERS_ACCOUNT;
        String name = "lc-block-device-members";
        // The configuration names two data devices the AMI maps: /dev/sdh with NoDevice false,
        // which keeps it, and /dev/sdj as an instance store volume, which leaves no EBS volume
        // there. A NoDevice taken as set, or a VirtualName the launch did not receive, would
        // change the devices the instance gets.
        String image = call(account, "ec2", "RegisterImage", "Name", name,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.SnapshotId", SNAPSHOT,
                "BlockDeviceMapping.1.Ebs.VolumeSize", "16",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdh",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "10",
                "BlockDeviceMapping.3.DeviceName", "/dev/sdj",
                "BlockDeviceMapping.3.Ebs.VolumeSize", "10")
                .getString("RegisterImageResponse.imageId");

        String instance = launchFromConfiguration(account, name, image,
                "BlockDeviceMappings.member.1.DeviceName", "/dev/sdh",
                "BlockDeviceMappings.member.1.NoDevice", "false",
                "BlockDeviceMappings.member.2.DeviceName", "/dev/sdj",
                "BlockDeviceMappings.member.2.VirtualName", "ephemeral0",
                "BlockDeviceMappings.member.3.DeviceName", "/dev/sdg",
                "BlockDeviceMappings.member.3.Ebs.VolumeSize", "12",
                "BlockDeviceMappings.member.3.Ebs.VolumeType", "gp3",
                "BlockDeviceMappings.member.3.Ebs.Throughput", "250",
                "BlockDeviceMappings.member.3.Ebs.Encrypted", "true",
                "BlockDeviceMappings.member.4.DeviceName", "/dev/sdi",
                "BlockDeviceMappings.member.4.Ebs.SnapshotId", SNAPSHOT);

        XmlPath described = call(account, "ec2", "DescribeInstances", "InstanceId.1", instance);
        assertEquals(List.of("/dev/xvda", "/dev/sdg", "/dev/sdh", "/dev/sdi"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
        // Auto Scaling's DeleteOnTermination default is true, for the devices the configuration adds too.
        assertEquals(List.of("true", "true", "true", "true"),
                described.getList(INSTANCE + ".blockDeviceMapping.item.ebs.deleteOnTermination", String.class));
        XmlPath added = volume(account, described, 1);
        assertEquals("12", added.getString(VOLUME + ".size"));
        assertEquals("gp3", added.getString(VOLUME + ".volumeType"));
        assertEquals("250", added.getString(VOLUME + ".throughput"));
        assertEquals("true", added.getString(VOLUME + ".encrypted"));
        XmlPath fromSnapshot = volume(account, described, 3);
        assertEquals(SNAPSHOT, fromSnapshot.getString(VOLUME + ".snapshotId"));
        assertEquals("16", fromSnapshot.getString(VOLUME + ".size"));
    }

    @Test
    void anInstanceRefreshReplacesTheInstanceWithTheLaunchConfigurationsBlockDeviceMappings() {
        String account = REFRESH_ACCOUNT;
        String name = "lc-block-devices-refresh";
        String image = call(account, "ec2", "RegisterImage", "Name", name,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8",
                "BlockDeviceMapping.2.DeviceName", "/dev/sdf",
                "BlockDeviceMapping.2.Ebs.VolumeSize", "10")
                .getString("RegisterImageResponse.imageId");
        String original = launchFromConfiguration(account, name, image,
                "BlockDeviceMappings.member.1.DeviceName", "/dev/sdf",
                "BlockDeviceMappings.member.1.NoDevice", "true",
                "BlockDeviceMappings.member.2.DeviceName", "/dev/sdg",
                "BlockDeviceMappings.member.2.Ebs.VolumeSize", "12");
        // The next pass puts the instance in service before the refresh replaces it.
        reconciler.reconcileAll();

        String refresh = call(account, "autoscaling", "StartInstanceRefresh", "AutoScalingGroupName", name)
                .getString("StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");
        reconciler.reconcileAll();
        reconciler.reconcileAll();

        assertEquals("Successful", call(account, "autoscaling", "DescribeInstanceRefreshes",
                "AutoScalingGroupName", name, "InstanceRefreshIds.member.1", refresh)
                .getString("DescribeInstanceRefreshesResponse.DescribeInstanceRefreshesResult"
                        + ".InstanceRefreshes.member.Status"));
        String replacement = call(account, "autoscaling", "DescribeAutoScalingGroups",
                "AutoScalingGroupNames.member.1", name).getString(GROUP + ".Instances.member.InstanceId");
        assertNotEquals(original, replacement);
        assertEquals(List.of("/dev/xvda", "/dev/sdg"),
                call(account, "ec2", "DescribeInstances", "InstanceId.1", replacement)
                        .getList(INSTANCE + ".blockDeviceMapping.item.deviceName", String.class));
    }

    @Test
    void aGroupWhoseLaunchConfigurationMapsADeviceNameEc2RefusesRecordsAFailedLaunch() {
        String account = REFUSED_ACCOUNT;
        String name = "lc-block-devices-refused";
        String image = call(account, "ec2", "RegisterImage", "Name", name,
                "RootDeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.DeviceName", "/dev/xvda",
                "BlockDeviceMapping.1.Ebs.VolumeSize", "8")
                .getString("RegisterImageResponse.imageId");

        // CreateLaunchConfiguration stores the mapping and the launch refuses it, so the group gets
        // no instance and records the failed launch.
        launchFromConfiguration(account, name, image,
                "BlockDeviceMappings.member.1.DeviceName", "/dev/sd;f",
                "BlockDeviceMappings.member.1.Ebs.VolumeSize", "5");

        assertEquals(List.of(), call(account, "autoscaling", "DescribeAutoScalingGroups",
                "AutoScalingGroupNames.member.1", name).getList(GROUP + ".Instances.member.InstanceId", String.class));
        XmlPath activities = call(account, "autoscaling", "DescribeScalingActivities", "AutoScalingGroupName", name);
        assertEquals("Failed", activities.getString(ACTIVITY + "[0].StatusCode"));
        assertEquals("Invalid device name /dev/sd;f Launching EC2 instance failed.",
                activities.getString(ACTIVITY + "[0].StatusMessage"));
    }

    /**
     * Creates a launch configuration with these block device mappings and a group of one instance
     * launching from it, and returns the instance the group launches, if any.
     */
    private String launchFromConfiguration(String account, String name, String image, String... mappings) {
        List<String> configuration = new ArrayList<>(List.of(
                "LaunchConfigurationName", name, "ImageId", image, "InstanceType", "t3.micro"));
        configuration.addAll(List.of(mappings));
        call(account, "autoscaling", "CreateLaunchConfiguration", configuration.toArray(String[]::new));
        call(account, "autoscaling", "CreateAutoScalingGroup",
                "AutoScalingGroupName", name,
                "LaunchConfigurationName", name,
                "MinSize", "1", "MaxSize", "1", "DesiredCapacity", "1",
                "AvailabilityZones.member.1", "us-east-1a");
        created.add(new Group(account, name));
        reconciler.reconcileAll();
        return call(account, "autoscaling", "DescribeAutoScalingGroups",
                "AutoScalingGroupNames.member.1", name).getString(GROUP + ".Instances.member.InstanceId");
    }

    /** The volume an instance's block device mapping entry at this index names. */
    private static XmlPath volume(String account, XmlPath instance, int entry) {
        return call(account, "ec2", "DescribeVolumes", "VolumeId.1",
                instance.getString(INSTANCE + ".blockDeviceMapping.item[" + entry + "].ebs.volumeId"));
    }

    private static XmlPath call(String account, String service, String action, String... params) {
        return request(account, service, action, params).post("/").then().statusCode(200).extract().xmlPath();
    }

    private record Group(String account, String name) {}
}

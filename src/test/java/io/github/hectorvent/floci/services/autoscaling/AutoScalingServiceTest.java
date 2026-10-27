package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.AvailabilityZoneDistribution;
import io.github.hectorvent.floci.services.autoscaling.model.CapacityReservationSpecification;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceMaintenancePolicy;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefresh;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfigurationBlockDeviceMapping;
import io.github.hectorvent.floci.services.autoscaling.model.MixedInstancesPolicy;
import io.github.hectorvent.floci.services.autoscaling.model.ScheduledAction;
import io.github.hectorvent.floci.services.autoscaling.model.WarmPoolConfiguration;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AutoScalingServiceTest {

    private static final String REGION = "us-east-1";
    private AutoScalingService service;

    @BeforeEach
    void setUp() {
        service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION,
                "test-asg",
                null,
                "lt-original",
                null,
                "1",
                null,
                0,
                3,
                1,
                300,
                List.of("us-east-1a"),
                List.of("subnet-12345678"),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                Map.of(), Map.of(), AsgOptionalFields.none());
    }

    @Test
    void zoneOnlyGroupAcceptsRealZonesOutsideTheLocalSubnetCatalog() {
        service.ec2Service = mock(Ec2Service.class);
        service.createLaunchConfiguration(REGION, "zone-lc", null, "ami-test", "t3.nano",
                null, List.of(), null, null, null);
        service.createAutoScalingGroup(REGION, "zone-only", "zone-lc", null, null, null, null,
                0, 0, 0, 300, List.of("us-east-1d"), List.of(), List.of(), List.of(),
                "EC2", 0, List.of(), Map.of(), Map.of(), AsgOptionalFields.none());
        assertEquals(List.of("us-east-1d"), service.describeAutoScalingGroups(
                REGION, List.of("zone-only")).getFirst().getAvailabilityZones());
        service.updateAutoScalingGroup(REGION, "zone-only", null, null, null, null, null,
                null, 10, null, null, List.of("us-east-1d", "us-east-1f"), null,
                null, null, null, AsgOptionalFields.none());
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("zone-only")).getFirst();
        assertEquals(List.of("us-east-1d", "us-east-1f"), group.getAvailabilityZones());
        assertEquals(10, group.getMaxSize());
    }

    @Test
    void startInstanceRefreshStoresCompletedRefreshAndAppliesDesiredLaunchTemplate() {
        InstanceRefresh request = new InstanceRefresh();
        request.setStrategy("Rolling");
        request.setDesiredLaunchTemplateId("lt-updated");
        request.setDesiredLaunchTemplateVersion("2");
        request.setMinHealthyPercentage(90);
        request.setMaxHealthyPercentage(120);
        request.setInstanceWarmup(200);
        request.setSkipMatching(true);
        request.setAutoRollback(true);
        request.setCheckpointPercentages(List.of(50, 100));

        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", request);

        assertNotNull(refresh.getInstanceRefreshId());
        assertEquals("test-asg", refresh.getAutoScalingGroupName());
        assertEquals("Rolling", refresh.getStrategy());
        assertEquals("Successful", refresh.getStatus());
        assertEquals(100, refresh.getPercentageComplete());
        assertEquals(0, refresh.getInstancesToUpdate());
        assertEquals("lt-updated", refresh.getDesiredLaunchTemplateId());
        assertEquals("2", refresh.getDesiredLaunchTemplateVersion());
        assertEquals(90, refresh.getMinHealthyPercentage());
        assertEquals(120, refresh.getMaxHealthyPercentage());
        assertEquals(200, refresh.getInstanceWarmup());
        assertEquals(Boolean.TRUE, refresh.getSkipMatching());
        assertEquals(Boolean.TRUE, refresh.getAutoRollback());
        assertEquals(List.of(50, 100), refresh.getCheckpointPercentages());

        AutoScalingService.InstanceRefreshPage page =
                service.describeInstanceRefreshes(REGION, "test-asg", List.of(refresh.getInstanceRefreshId()), null, null);
        assertEquals(1, page.instanceRefreshes().size());
        assertEquals(refresh.getInstanceRefreshId(), page.instanceRefreshes().getFirst().getInstanceRefreshId());
        assertNull(page.nextToken());

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals("lt-updated", group.getLaunchTemplateId());
        assertEquals("2", group.getLaunchTemplateVersion());
        assertNull(group.getLaunchConfigurationName());
    }

    @Test
    void attachedInstancesReceiveThePropagatingTagsAndTheirGroupName() {
        service.ec2Service = mock(Ec2Service.class);
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.getTags().put("env", "group");
        group.getTags().put("hidden", "group");
        group.getTags().put("aws:autoscaling:groupName", "spoofed");
        group.getTagPropagateAtLaunch().putAll(Map.of("env", true, "hidden", false,
                "aws:autoscaling:groupName", true));
        ec2HoldsInstances("i-attached");

        service.attachInstances(REGION, "test-asg", List.of("i-attached"));

        ArgumentCaptor<List<Tag>> appliedTags = ArgumentCaptor.captor();
        verify(service.ec2Service).createSystemTags(eq(REGION), eq(List.of("i-attached")), appliedTags.capture());
        assertEquals(Map.of("env", "group", "aws:autoscaling:groupName", "test-asg"),
                appliedTags.getValue().stream().collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
        assertEquals(List.of("i-attached"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @Test
    void attachTagsOnlyTheInstancesEc2Holds() {
        service.ec2Service = mock(Ec2Service.class);
        ec2HoldsInstances("i-held");

        service.attachInstances(REGION, "test-asg", List.of("i-held", "i-unknown"));

        verify(service.ec2Service).createSystemTags(eq(REGION), eq(List.of("i-held")), anyList());
        verify(service.ec2Service, times(1)).createSystemTags(anyString(), anyList(), anyList());
    }

    @Test
    void attachCountsARepeatedInstanceOnce() {
        service.ec2Service = mock(Ec2Service.class);
        ec2HoldsInstances("i-repeated");

        service.attachInstances(REGION, "test-asg", List.of("i-repeated", "i-repeated"));

        verify(service.ec2Service).createSystemTags(eq(REGION), eq(List.of("i-repeated")), anyList());
        assertEquals(List.of("i-repeated"), service.describeAutoScalingGroups(REGION, List.of("test-asg"))
                .getFirst().getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void attachRejectsAnInstanceThatAlreadyBelongsToAGroupBeforeTaggingAnyOfTheBatch(boolean sameGroup) {
        service.createAutoScalingGroup(REGION, "other-asg", null, "lt-original", null, "1", null,
                0, 3, 0, 300, List.of(REGION + "a"), List.of(), List.of(), List.of(),
                "EC2", 0, List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        service.ec2Service = mock(Ec2Service.class);
        service.attachInstances(REGION, "test-asg", List.of("i-owned"));
        clearInvocations(service.ec2Service);
        String target = sameGroup ? "test-asg" : "other-asg";
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of(target)).getFirst();
        int desired = group.getDesiredCapacity();
        List<String> members = group.getInstances().stream().map(AsgInstance::getInstanceId).toList();

        AwsException failure = assertThrows(AwsException.class,
                () -> service.attachInstances(REGION, target, List.of("i-free", "i-owned")));

        assertEquals("ValidationError", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals("[The Instance: i-owned is already part of AutoScalingGroup:test-asg]", failure.getMessage());
        assertEquals(members, group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        assertEquals(desired, group.getDesiredCapacity());
        verifyNoInteractions(service.ec2Service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detachRemovesOnlyTheDetachedInstancesGroupNameTag(boolean decrementDesiredCapacity) {
        service.ec2Service = mock(Ec2Service.class);
        service.attachInstances(REGION, "test-asg", List.of("i-detached", "i-retained"));

        service.detachInstances(REGION, "test-asg", List.of("i-detached"), decrementDesiredCapacity);

        verify(service.ec2Service).deleteSystemTag(
                REGION, List.of("i-detached"), "aws:autoscaling:groupName", "test-asg");
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals(List.of("i-retained"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        assertEquals(decrementDesiredCapacity ? 1 : 2, group.getDesiredCapacity());
    }

    @Test
    void detachingAnInstanceTheGroupDoesNotHoldChangesNoTags() {
        service.ec2Service = mock(Ec2Service.class);
        service.attachInstances(REGION, "test-asg", List.of("i-member"));
        clearInvocations(service.ec2Service);

        service.detachInstances(REGION, "test-asg", List.of("i-stranger"), false);

        verify(service.ec2Service, never()).deleteSystemTag(anyString(), anyList(), anyString(), anyString());
        assertEquals(List.of("i-member"), service.describeAutoScalingGroups(REGION, List.of("test-asg"))
                .getFirst().getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    private void ec2HoldsInstances(String... instanceIds) {
        for (String id : instanceIds) {
            Instance instance = new Instance();
            instance.setInstanceId(id);
            when(service.ec2Service.findInstanceForAccount("000000000000", REGION, id)).thenReturn(Optional.of(instance));
        }
    }

    @Test
    void anInstanceRefreshResolvesTheProfileOfTheTemplateItsGroupLaunchesFromOnceItApplies() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        when(ec2Service.describeLaunchTemplateVersions(REGION, "lt-original", null, List.of("1")))
                .thenReturn(List.of(withProfile("version-one")));
        when(ec2Service.describeLaunchTemplateVersions(REGION, "lt-original", null, List.of("2")))
                .thenReturn(List.of(withProfile("version-two")));
        when(ec2Service.describeLaunchTemplateVersions(REGION, "lt-other", null, List.of("1")))
                .thenReturn(List.of(withProfile("other-at-one")));

        // The group launches from lt-original version 1: a version alone moves it, a template alone keeps it.
        assertEquals(Optional.of("version-two"),
                service.instanceRefreshInstanceProfile("test-asg", null, null, "2", REGION));
        assertEquals(Optional.of("other-at-one"),
                service.instanceRefreshInstanceProfile("test-asg", "lt-other", null, null, REGION));
        // Without a desired configuration the group keeps the template it was checked for when it named it.
        assertEquals(Optional.empty(), service.instanceRefreshInstanceProfile("test-asg", null, null, null, REGION));
        assertEquals(Optional.empty(),
                service.instanceRefreshInstanceProfile("no-such-group", null, null, "2", REGION));
    }

    @Test
    void aLaunchTemplateLookupNamingNoTemplateFindsNoProfileRatherThanAnyTemplatesProfile() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;

        assertEquals(Optional.empty(), service.launchTemplateInstanceProfile(null, " ", "2", REGION));
        verifyNoInteractions(ec2Service);
    }

    private static LaunchTemplate withProfile(String profileName) {
        LaunchTemplate version = new LaunchTemplate();
        version.getData().setIamInstanceProfile(new LaunchTemplateData.IamInstanceProfile(null, profileName));
        return version;
    }

    @Test
    void createAutoScalingGroupRejectsResolvedLaunchTemplateWithoutImageId() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        LaunchTemplate version = new LaunchTemplate();
        version.getData().setImageId(null);
        when(ec2Service.describeLaunchTemplateVersions(REGION, "lt-no-image", null, List.of("1")))
                .thenReturn(List.of(version));

        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "missing-image-asg",
                null,
                "lt-no-image",
                null,
                "1",
                null,
                0,
                1,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                Map.of(), Map.of(), AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE, error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("missing-image-asg")).isEmpty());
    }

    static Stream<String> rejectedUserData() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(new byte[10 * 1024 * 1024 + 1]);
        }
        return Stream.of("not base64!", "YQ==\n", "H4sI", Base64.getEncoder().encodeToString(new byte[16385]),
                Base64.getEncoder().encodeToString(bytes.toByteArray()));
    }

    @ParameterizedTest
    @MethodSource("rejectedUserData")
    void createLaunchConfigurationRejectsUnlaunchableDataBeforeSaving(String data) {
        AwsException error = assertThrows(AwsException.class, () -> service.createLaunchConfiguration(REGION,
                "invalid-data", null, "ami-test", "t3.micro", null, List.of(), data, null, null));
        assertEquals("InvalidParameterValue", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeLaunchConfigurations(REGION, List.of("invalid-data")).isEmpty());
        service.createLaunchConfiguration(REGION, "invalid-data", null, "ami-test", "t3.micro", null,
                List.of(), "YQ==", null, null);
        assertEquals("YQ==", service.describeLaunchConfigurations(REGION, List.of("invalid-data"))
                .getFirst().getUserData());
    }

    @Test
    void createLaunchConfigurationChecksSelectedInheritedDataAndAllowsValidOverride() {
        Ec2Service ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
        Instance source = new Instance();
        source.setImageId("ami-test");
        source.setInstanceType("t3.micro");
        source.setEncodedUserData("not base64!");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(source));
        when(ec2.describeInstances(REGION, List.of("i-source"), Map.of())).thenReturn(List.of(reservation));
        assertThrows(AwsException.class, () -> service.createLaunchConfiguration(REGION, "inherited-invalid",
                "i-source", null, null, null, List.of(), null, null, null));
        assertTrue(service.describeLaunchConfigurations(REGION, List.of("inherited-invalid")).isEmpty());
        LaunchConfiguration overridden = service.createLaunchConfiguration(REGION, "overridden-valid",
                "i-source", null, null, null, List.of(), "Yg==", null, null);
        assertEquals("Yg==", overridden.getUserData());
        assertEquals("not base64!", source.getEncodedUserData());
    }

    @Test
    void createLaunchConfigurationRejectsMissingImageOrInstanceTypeWithoutInstanceId() {
        AwsException missingImage = assertThrows(AwsException.class, () -> service.createLaunchConfiguration(REGION,
                "lc-no-image",
                null,
                "",
                "t3.micro",
                null,
                List.of(),
                null,
                null,
                false));

        assertEquals("ValidationError", missingImage.getErrorCode());
        assertEquals(AutoScalingService.INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE, missingImage.getMessage());
        assertEquals(400, missingImage.getHttpStatus());
        assertTrue(service.describeLaunchConfigurations(REGION, List.of("lc-no-image")).isEmpty());

        AwsException missingInstanceType = assertThrows(AwsException.class,
                () -> service.createLaunchConfiguration(REGION,
                        "lc-no-instance-type",
                        null,
                        "ami-12345678",
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        false));

        assertEquals("ValidationError", missingInstanceType.getErrorCode());
        assertEquals(AutoScalingService.INVALID_LAUNCH_CONFIGURATION_PARAMETERS_MESSAGE,
                missingInstanceType.getMessage());
        assertEquals(400, missingInstanceType.getHttpStatus());
        assertTrue(service.describeLaunchConfigurations(REGION, List.of("lc-no-instance-type")).isEmpty());
    }

    @Test
    void createLaunchConfigurationWithInstanceIdCopiesSourceInstanceLaunchAttributes() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        Instance source = new Instance();
        source.setInstanceId("i-1234567890abcdef0");
        source.setImageId("ami-from-instance");
        source.setInstanceType("m7g.large");
        source.setKeyName("source-key");
        source.setSecurityGroups(List.of(new GroupIdentifier("sg-source", "default")));
        source.setUserData("source-user-data");
        source.setIamInstanceProfileArn("arn:aws:iam::000000000000:instance-profile/source");
        when(ec2Service.resolveIamInstanceProfileName("source"))
                .thenReturn(source.getIamInstanceProfileArn());
        Reservation reservation = new Reservation();
        reservation.getInstances().add(source);
        when(ec2Service.describeInstances(REGION, List.of("i-1234567890abcdef0"), Map.of()))
                .thenReturn(List.of(reservation));

        LaunchConfiguration launchConfiguration = service.createLaunchConfiguration(REGION,
                "lc-from-instance",
                "i-1234567890abcdef0",
                null,
                null,
                null,
                List.of(),
                null,
                null,
                false);

        assertEquals("ami-from-instance", launchConfiguration.getImageId());
        assertEquals("m7g.large", launchConfiguration.getInstanceType());
        assertEquals("source-key", launchConfiguration.getKeyName());
        assertEquals(List.of("sg-source"), launchConfiguration.getSecurityGroups());
        assertEquals("c291cmNlLXVzZXItZGF0YQ==", launchConfiguration.getUserData());
        assertEquals(Boolean.TRUE, launchConfiguration.getUserDataBase64Encoded());
        assertEquals("arn:aws:iam::000000000000:instance-profile/source",
                launchConfiguration.getIamInstanceProfile());
        assertEquals(launchConfiguration,
                service.describeLaunchConfigurations(REGION, List.of("lc-from-instance")).getFirst());
    }

    @Test
    void createLaunchConfigurationDefaultsInstanceMonitoringToEnabled() {
        LaunchConfiguration lc = service.createLaunchConfiguration(REGION, "lc-default-monitoring", null,
                "ami-12345678", "t3.micro", null, List.of(), null, null, null);

        assertEquals(Boolean.TRUE, lc.getInstanceMonitoringEnabled());
        assertEquals(List.of(), lc.getBlockDeviceMappings());
        assertEquals(Boolean.TRUE,
                service.describeLaunchConfigurations(REGION, List.of("lc-default-monitoring"))
                        .getFirst().getInstanceMonitoringEnabled());
    }

    @Test
    void createLaunchConfigurationKeepsAnExplicitInstanceMonitoringOverride() {
        LaunchConfiguration lc = service.createLaunchConfiguration(REGION, "lc-monitoring-off", null,
                "ami-12345678", "t3.micro", null, List.of(), null, null, null, Boolean.FALSE, List.of());

        assertEquals(Boolean.FALSE, lc.getInstanceMonitoringEnabled());
        assertEquals(Boolean.FALSE,
                service.describeLaunchConfigurations(REGION, List.of("lc-monitoring-off"))
                        .getFirst().getInstanceMonitoringEnabled());
    }

    @Test
    void createLaunchConfigurationRoundTripsBlockDeviceMappings() {
        LaunchConfigurationBlockDeviceMapping.Ebs ebs = new LaunchConfigurationBlockDeviceMapping.Ebs();
        ebs.setVolumeSize(100);
        ebs.setVolumeType("gp3");
        ebs.setIops(3000);
        ebs.setThroughput(125);
        ebs.setDeleteOnTermination(true);
        LaunchConfigurationBlockDeviceMapping mapping = new LaunchConfigurationBlockDeviceMapping();
        mapping.setDeviceName("/dev/xvda");
        mapping.setEbs(ebs);

        service.createLaunchConfiguration(REGION, "lc-with-root-device", null,
                "ami-12345678", "t3.micro", null, List.of(), null, null, null, null, List.of(mapping));

        List<LaunchConfigurationBlockDeviceMapping> stored = service.describeLaunchConfigurations(REGION, List.of("lc-with-root-device"))
                .getFirst().getBlockDeviceMappings();
        assertEquals(1, stored.size());
        assertEquals("/dev/xvda", stored.getFirst().getDeviceName());
        assertEquals(100, stored.getFirst().getEbs().getVolumeSize());
        assertEquals("gp3", stored.getFirst().getEbs().getVolumeType());
        assertEquals(3000, stored.getFirst().getEbs().getIops());
        assertEquals(125, stored.getFirst().getEbs().getThroughput());
        assertEquals(Boolean.TRUE, stored.getFirst().getEbs().getDeleteOnTermination());
    }

    @Test
    void createAutoScalingGroupRejectsUnresolvedLaunchTemplateBeforeMutation() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        when(ec2Service.describeLaunchTemplateVersions(REGION, "lt-missing", null, List.of("1")))
                .thenReturn(List.of());

        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "missing-template-asg",
                null,
                "lt-missing",
                null,
                "1",
                null,
                0,
                1,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                Map.of(), Map.of(), AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.INVALID_LAUNCH_TEMPLATE_MESSAGE, error.getMessage());
        assertEquals(400, error.getHttpStatus());
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("missing-template-asg")).isEmpty());
    }

    @Test
    void updateAutoScalingGroupRejectsResolvedLaunchTemplateWithoutImageIdBeforeMutation() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        LaunchTemplate version = new LaunchTemplate();
        version.getData().setImageId("");
        when(ec2Service.describeLaunchTemplateVersions(REGION, "lt-no-image", null, List.of("2")))
                .thenReturn(List.of(version));

        AwsException error = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(
                REGION,
                "test-asg",
                null,
                "lt-no-image",
                null,
                "2",
                null,
                null,
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null, AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.MISSING_LAUNCH_TEMPLATE_IMAGE_ID_MESSAGE, error.getMessage());
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals("lt-original", group.getLaunchTemplateId());
        assertEquals(3, group.getMaxSize());
    }

    @Test
    void updateAutoScalingGroupRejectsVersionWithoutLaunchTemplateIdentifier() {
        AwsException error = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(
                REGION,
                "test-asg",
                null,
                null,
                null,
                "2",
                null,
                null,
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null, AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("LaunchTemplateVersion requires a LaunchTemplateId or LaunchTemplateName.", error.getMessage());
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals("1", group.getLaunchTemplateVersion());
        assertEquals(3, group.getMaxSize());
    }

    @Test
    void updateAutoScalingGroupRejectsDesiredConfigurationChangeDuringActiveInstanceRefresh() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setDesiredLaunchTemplateId("lt-refresh");
        request.setDesiredLaunchTemplateVersion("2");
        service.startInstanceRefresh(REGION, "test-asg", request);

        AwsException error = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(
                REGION,
                "test-asg",
                null,
                "lt-next",
                null,
                "3",
                null,
                null,
                5,
                null,
                null,
                null,
                null,
                null,
                null,
                null, AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.ACTIVE_INSTANCE_REFRESH_DESIRED_CONFIGURATION_MESSAGE, error.getMessage());
        assertEquals(400, error.getHttpStatus());
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals("lt-refresh", group.getLaunchTemplateId());
        assertEquals("2", group.getLaunchTemplateVersion());
        assertEquals(3, group.getMaxSize());
    }

    @Test
    void describeInstanceRefreshesPaginatesNewestFirst() {
        InstanceRefresh first = service.startInstanceRefresh(REGION, "test-asg", new InstanceRefresh());
        InstanceRefresh second = service.startInstanceRefresh(REGION, "test-asg", new InstanceRefresh());

        AutoScalingService.InstanceRefreshPage firstPage =
                service.describeInstanceRefreshes(REGION, "test-asg", List.of(), 1, null);
        assertEquals(1, firstPage.instanceRefreshes().size());
        assertEquals(second.getInstanceRefreshId(), firstPage.instanceRefreshes().getFirst().getInstanceRefreshId());
        assertEquals("1", firstPage.nextToken());

        AutoScalingService.InstanceRefreshPage secondPage =
                service.describeInstanceRefreshes(REGION, "test-asg", List.of(), 1, firstPage.nextToken());
        assertEquals(1, secondPage.instanceRefreshes().size());
        assertEquals(first.getInstanceRefreshId(), secondPage.instanceRefreshes().getFirst().getInstanceRefreshId());
        assertNull(secondPage.nextToken());
    }

    @Test
    void startInstanceRefreshMarksActiveInstancesForReplacement() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");

        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", new InstanceRefresh());

        assertEquals("InProgress", refresh.getStatus());
        assertEquals("Instance refresh in progress.", refresh.getStatusReason());
        assertEquals(0, refresh.getPercentageComplete());
        assertEquals(1, refresh.getInstancesToUpdate());
        assertNull(refresh.getEndTime());

        AsgInstance instance = service.describeAutoScalingGroups(REGION, List.of("test-asg"))
                .getFirst()
                .getInstances()
                .getFirst();
        assertEquals("Terminating", instance.getLifecycleState());
    }

    @Test
    void startInstanceRefreshRejectsSecondRefreshWhileFirstIsActive() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        service.startInstanceRefresh(REGION, "test-asg", new InstanceRefresh());

        AwsException error = assertThrows(AwsException.class,
                () -> service.startInstanceRefresh(REGION, "test-asg", new InstanceRefresh()));

        assertEquals("InstanceRefreshInProgress", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void startInstanceRefreshSkipMatchingLeavesMatchingInstancesInService() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-matching", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setSkipMatching(true);

        service.startInstanceRefresh(REGION, "test-asg", request);

        AsgInstance instance = service.describeAutoScalingGroups(REGION, List.of("test-asg"))
                .getFirst()
                .getInstances()
                .getFirst();
        assertEquals("InService", instance.getLifecycleState());
    }

    @Test
    void startInstanceRefreshWithLaunchTemplateAliasDoesNotSkipExistingInstances() {
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.setLaunchTemplateVersion("$Latest");
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setSkipMatching(true);

        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", request);

        assertEquals("InProgress", refresh.getStatus());
        assertEquals(1, refresh.getInstancesToUpdate());
        AsgInstance original = group.getInstances().getFirst();
        assertEquals("Terminating", original.getLifecycleState());

        group.getInstances().clear();
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-replacement", "InService", "lt-original", "2");
        refresh.getReplacements().getFirst().setReplacementInstanceId("i-replacement");
        service.completeInstanceRefreshIfSettled(REGION, "test-asg");

        InstanceRefresh completed = service.describeInstanceRefreshes(
                        REGION, "test-asg", List.of(refresh.getInstanceRefreshId()), null, null)
                .instanceRefreshes()
                .getFirst();
        assertEquals("Successful", completed.getStatus());
        assertEquals(0, completed.getInstancesToUpdate());
    }

    @Test
    void startInstanceRefreshWithDefaultLaunchTemplateAliasDoesNotSkipExistingInstances() {
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.setLaunchTemplateVersion("$Default");
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setSkipMatching(true);

        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", request);

        assertEquals("InProgress", refresh.getStatus());
        assertEquals(1, refresh.getInstancesToUpdate());
        AsgInstance original = group.getInstances().getFirst();
        assertEquals("Terminating", original.getLifecycleState());

        group.getInstances().clear();
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-replacement", "InService", "lt-original", "2");
        refresh.getReplacements().getFirst().setReplacementInstanceId("i-replacement");
        service.completeInstanceRefreshIfSettled(REGION, "test-asg");

        InstanceRefresh completed = service.describeInstanceRefreshes(
                        REGION, "test-asg", List.of(refresh.getInstanceRefreshId()), null, null)
                .instanceRefreshes()
                .getFirst();
        assertEquals("Successful", completed.getStatus());
        assertEquals(0, completed.getInstancesToUpdate());
    }

    @Test
    void startInstanceRefreshWithDefaultLaunchTemplateVersionDoesNotSkipExistingInstances() {
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.setLaunchTemplateVersion(null);
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setSkipMatching(true);

        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", request);

        assertEquals("InProgress", refresh.getStatus());
        assertEquals(1, refresh.getInstancesToUpdate());
        AsgInstance original = group.getInstances().getFirst();
        assertEquals("Terminating", original.getLifecycleState());
    }

    @Test
    void startInstanceRefreshWithBlankLaunchTemplateVersionDoesNotSkipExistingInstances() {
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.setLaunchTemplateVersion("");
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setSkipMatching(true);

        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", request);

        assertEquals("InProgress", refresh.getStatus());
        assertEquals(1, refresh.getInstancesToUpdate());
        AsgInstance original = group.getInstances().getFirst();
        assertEquals("Terminating", original.getLifecycleState());
    }

    @Test
    void completeInstanceRefreshMarksSettledReplacementSuccessful() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh request = new InstanceRefresh();
        request.setDesiredLaunchTemplateId("lt-updated");
        request.setDesiredLaunchTemplateVersion("2");
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", request);
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.getInstances().clear();
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-replacement", "InService", "lt-updated", "2");

        refresh.getReplacements().getFirst().setReplacementInstanceId("i-replacement");
        service.completeInstanceRefreshIfSettled(REGION, "test-asg");

        InstanceRefresh completed = service.describeInstanceRefreshes(
                        REGION, "test-asg", List.of(refresh.getInstanceRefreshId()), null, null)
                .instanceRefreshes()
                .getFirst();
        assertEquals("Successful", completed.getStatus());
        assertEquals(100, completed.getPercentageComplete());
        assertEquals(0, completed.getInstancesToUpdate());
        assertNotNull(completed.getEndTime());
    }

    @Test
    void completeInstanceRefreshWaitsForReplacementCapacity() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-original", "InService", "lt-original", "1");
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "test-asg", new InstanceRefresh());
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        group.getInstances().clear();

        service.completeInstanceRefreshIfSettled(REGION, "test-asg");

        InstanceRefresh inProgress = service.describeInstanceRefreshes(
                        REGION, "test-asg", List.of(refresh.getInstanceRefreshId()), null, null)
                .instanceRefreshes()
                .getFirst();
        assertEquals("InProgress", inProgress.getStatus());
        assertEquals(0, inProgress.getPercentageComplete());
        assertEquals(1, inProgress.getInstancesToUpdate());
        assertNull(inProgress.getEndTime());
    }

    @Test
    void deleteAutoScalingGroupWithoutForceRejectsActiveInstances() {
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-active", "InService", "lt-original", "1");

        AwsException error = assertThrows(AwsException.class,
                () -> service.deleteAutoScalingGroup(REGION, "test-asg", false));

        assertEquals("ResourceInUse", error.getErrorCode());
    }

    @Test
    void conditionalSaveDoesNotReinsertDeletedGroup() {
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();

        service.deleteAutoScalingGroup(REGION, "test-asg", false);

        assertFalse(service.saveAutoScalingGroupIfPresent(group));
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("test-asg")).isEmpty());
    }

    @Test
    void forceDeleteAutoScalingGroupTerminatesActiveEc2Instances() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-active", "InService", "lt-original", "1");
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-terminated", "Terminated", "lt-original", "1");

        service.deleteAutoScalingGroup(REGION, "test-asg", true);

        verify(ec2Service).terminateInstances(REGION, List.of("i-active"));
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("test-asg")).isEmpty());
    }

    @Test
    void forceDeleteAutoScalingGroupIgnoresStaleEc2Membership() {
        Ec2Service ec2Service = mock(Ec2Service.class);
        service.ec2Service = ec2Service;
        AutoScalingGroupFixture.addInstance(service, REGION, "test-asg", "i-stale", "InService", "lt-original", "1");
        doThrow(new AwsException("InvalidInstanceID.NotFound", "Instance i-stale was not found.", 400))
                .when(ec2Service)
                .terminateInstances(REGION, List.of("i-stale"));

        service.deleteAutoScalingGroup(REGION, "test-asg", true);

        verify(ec2Service).terminateInstances(REGION, List.of("i-stale"));
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("test-asg")).isEmpty());
    }

    @Test
    void createAutoScalingGroupRejectsMixedInstancesPolicyWithoutLaunchTemplate() {
        MixedInstancesPolicy policy = new MixedInstancesPolicy();
        MixedInstancesPolicy.InstancesDistribution distribution =
                new MixedInstancesPolicy.InstancesDistribution();
        distribution.setOnDemandBaseCapacity(1);
        policy.setInstancesDistribution(distribution);

        AwsException error = assertThrows(AwsException.class,
                () -> createWithMixedInstancesPolicy("mixed-no-lt", policy));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createAutoScalingGroupRejectsMixedInstancesPolicyWithBlankLaunchTemplateIdentifiers() {
        MixedInstancesPolicy policy = new MixedInstancesPolicy();
        MixedInstancesPolicy.LaunchTemplate launchTemplate = new MixedInstancesPolicy.LaunchTemplate();
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                new MixedInstancesPolicy.LaunchTemplateSpecification();
        specification.setLaunchTemplateId("");
        specification.setLaunchTemplateName("  ");
        launchTemplate.setLaunchTemplateSpecification(specification);
        policy.setLaunchTemplate(launchTemplate);

        AwsException error = assertThrows(AwsException.class,
                () -> createWithMixedInstancesPolicy("mixed-blank-lt", policy));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createAutoScalingGroupAcceptsMixedInstancesPolicyWithLaunchTemplate() {
        MixedInstancesPolicy policy = new MixedInstancesPolicy();
        MixedInstancesPolicy.LaunchTemplate launchTemplate = new MixedInstancesPolicy.LaunchTemplate();
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                new MixedInstancesPolicy.LaunchTemplateSpecification();
        specification.setLaunchTemplateId("lt-mixed");
        launchTemplate.setLaunchTemplateSpecification(specification);
        policy.setLaunchTemplate(launchTemplate);

        createWithMixedInstancesPolicy("mixed-with-lt", policy);

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("mixed-with-lt")).getFirst();
        assertEquals("lt-mixed",
                group.getMixedInstancesPolicy().getLaunchTemplate()
                        .getLaunchTemplateSpecification().getLaunchTemplateId());
    }

    private void createWithMixedInstancesPolicy(String name, MixedInstancesPolicy policy) {
        service.createAutoScalingGroup(REGION,
                name,
                null,
                null,
                null,
                null,
                policy,
                0,
                3,
                1,
                300,
                List.of("us-east-1a"),
                List.of(),
                List.of(),
                List.of(),
                "EC2",
                0,
                List.of("Default"),
                Map.of(), Map.of(), AsgOptionalFields.none());
    }

    @Test
    void putLifecycleHookRejectsNameOutsideModeledPattern() {
        AwsException ex = assertThrows(AwsException.class, () -> service.putLifecycleHook(REGION, "test-asg",
                "bad name!", "autoscaling:EC2_INSTANCE_LAUNCHING", null, null, null, null, null));
        assertEquals("ValidationError", ex.getErrorCode());
    }

    @Test
    void deleteLifecycleHookRejectsNameOutsideModeledPattern() {
        assertThrows(AwsException.class,
                () -> service.deleteLifecycleHook(REGION, "test-asg", "bad name!"));
    }

    @Test
    void completeLifecycleActionRejectsNameOutsideModeledPattern() {
        assertThrows(AwsException.class,
                () -> service.completeLifecycleAction(REGION, "test-asg", "bad name!",
                        "i-0123456789abcdef0", "CONTINUE", "12345678-1234-1234-1234-123456789012"));
    }

    @Test
    void completeLifecycleActionRejectsTokenOfWrongLength() {
        service.putLifecycleHook(REGION, "test-asg", "hook-1", "autoscaling:EC2_INSTANCE_LAUNCHING",
                null, null, null, null, null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.completeLifecycleAction(REGION, "test-asg", "hook-1",
                        "i-0123456789abcdef0", "CONTINUE", "not-a-uuid"));
        assertEquals("ValidationError", ex.getErrorCode());
    }

    @Test
    void completeLifecycleActionAllowsNullTokenWhenInstanceIdIdentifiesTheAction() {
        service.putLifecycleHook(REGION, "test-asg", "hook-1", "autoscaling:EC2_INSTANCE_LAUNCHING",
                null, null, null, null, null);

        assertDoesNotThrow(() -> service.completeLifecycleAction(REGION, "test-asg", "hook-1",
                "i-0123456789abcdef0", "CONTINUE", null));
    }

    @Test
    void deleteLifecycleHookByNameRejectsNameOutsideModeledPattern() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.deleteLifecycleHookByName(REGION, "bad name!"));
        assertEquals("ValidationError", ex.getErrorCode());
    }

    @Test
    void deleteLifecycleHookByNameIsIdempotentOnNullPhysicalId() {
        assertDoesNotThrow(() -> service.deleteLifecycleHookByName(REGION, null));
    }

    @Test
    void putLifecycleHookRejectsNullNameWithNotNullMessage() {
        AwsException ex = assertThrows(AwsException.class, () -> service.putLifecycleHook(REGION, "test-asg",
                null, "autoscaling:EC2_INSTANCE_LAUNCHING", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("must not be null"));
    }

    @Test
    void putLifecycleHookRejectsOverlongNameWithLengthMessage() {
        String tooLong = "h".repeat(256);
        AwsException ex = assertThrows(AwsException.class, () -> service.putLifecycleHook(REGION, "test-asg",
                tooLong, "autoscaling:EC2_INSTANCE_LAUNCHING", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("length less than or equal to 255"),
                "expected a length-specific message, got: " + ex.getMessage());
    }

    @Test
    void putLifecycleHookRejectsBadCharsWithPatternMessage() {
        AwsException ex = assertThrows(AwsException.class, () -> service.putLifecycleHook(REGION, "test-asg",
                "bad name!", "autoscaling:EC2_INSTANCE_LAUNCHING", null, null, null, null, null));
        assertTrue(ex.getMessage().contains("regular expression pattern"));
    }

    @Test
    void setInstanceProtectionValidatesEmptyInstanceIdsBeforeGroupLookup() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.setInstanceProtection(REGION, "no-such-group", List.of(), true));
        assertEquals("ValidationError", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("At least one instance ID is required"),
                "a doubly-bad request (unknown group + empty list) must surface the missing-parameter "
                        + "error, not 'Group not found'; got: " + ex.getMessage());
    }

    @Test
    void setInstanceProtectionReportsAllMissingInstanceIds() {
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId("i-real");
        instance.setLifecycleState("InService");
        instance.setHealthStatus("Healthy");
        service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst().getInstances().add(instance);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.setInstanceProtection(REGION, "test-asg",
                        List.of("i-missing-1", "i-missing-2"), true));
        assertTrue(ex.getMessage().contains("i-missing-1") && ex.getMessage().contains("i-missing-2"),
                "expected both missing IDs in the message, got: " + ex.getMessage());
    }

    @Test
    void setInstanceProtectionUsesSetContainmentAgainstDuplicateStoredInstanceIds() {
        AsgInstance dup1 = new AsgInstance();
        dup1.setInstanceId("i-dup");
        dup1.setLifecycleState("InService");
        dup1.setHealthStatus("Healthy");
        AsgInstance dup2 = new AsgInstance();
        dup2.setInstanceId("i-dup");
        dup2.setLifecycleState("InService");
        dup2.setHealthStatus("Healthy");
        List<AsgInstance> instances = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst().getInstances();
        instances.add(dup1);
        instances.add(dup2);

        assertDoesNotThrow(() -> service.setInstanceProtection(REGION, "test-asg", List.of("i-dup"), true));
        assertTrue(instances.stream().allMatch(AsgInstance::isProtectedFromScaleIn));
    }

    @Test
    void setInstanceHealthAcceptsShouldRespectGracePeriodFlag() {
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId("i-health");
        instance.setLifecycleState("InService");
        instance.setHealthStatus("Healthy");
        service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst().getInstances().add(instance);

        assertDoesNotThrow(() -> service.setInstanceHealth(REGION, "i-health", "Unhealthy", true));
        assertEquals("Unhealthy", service.describeAutoScalingInstances(REGION, List.of("i-health"))
                .getFirst().getHealthStatus());
    }

    @Test
    void groupDeletedMidIterationIsSkippedNotReturnedAsNull() {
        // A StorageBackedMap snapshots keys, then reads each value lazily; a group deleted between the
        // two reads comes back as a key with no value. Model that with a key the store lists but cannot get.
        InMemoryStorage<String, AutoScalingGroup> storage = new InMemoryStorage<>() {
            @Override
            public Set<String> keys() {
                Set<String> keys = new LinkedHashSet<>();
                keys.add(AutoScalingService.asgKey(REGION, "deleted-asg"));
                keys.addAll(super.keys());
                return keys;
            }
        };
        StorageBackedMap<AutoScalingGroup> groups = new StorageBackedMap<>(storage);
        groups.putAll(service.groups);
        service.groups = groups;
        service.ec2Service = mock(Ec2Service.class);
        service.attachInstances(REGION, "test-asg", List.of("i-live"));
        service.createOrUpdateTags(REGION, "test-asg", "auto-scaling-group", Map.of("k", "v"), Map.of());

        assertFalse(service.describeAutoScalingGroups(null, null).contains(null));
        assertEquals(List.of("test-asg"), service.describeAutoScalingGroups(REGION, null).stream()
                .map(AutoScalingGroup::getAutoScalingGroupName).toList());
        assertEquals(1, service.describeAutoScalingInstances(REGION, null).size());
        assertEquals(List.of("k"), service.describeTags(REGION, List.of(), null, null).items().stream()
                .map(AutoScalingService.TagDescription::key).toList());

        service.setInstanceHealth(REGION, "i-live", "Unhealthy", false);
        service.terminateInstanceInAutoScalingGroup(REGION, "i-live", false);
        AsgInstance stored = storage.get(AutoScalingService.asgKey(REGION, "test-asg")).orElseThrow()
                .getInstances().getFirst();
        assertEquals("Unhealthy", stored.getHealthStatus());
        assertEquals("Terminating", stored.getLifecycleState());
    }

    private static final class AutoScalingGroupFixture {
        private static void addInstance(AutoScalingService service, String region, String name, String instanceId,
                String lifecycleState, String launchTemplateId, String launchTemplateVersion) {
            AsgInstance instance = new AsgInstance();
            instance.setInstanceId(instanceId);
            instance.setLifecycleState(lifecycleState);
            instance.setHealthStatus("Healthy");
            instance.setLaunchTemplateId(launchTemplateId);
            instance.setLaunchTemplateVersion(launchTemplateVersion);
            service.describeAutoScalingGroups(region, List.of(name))
                    .getFirst()
                    .getInstances()
                    .add(instance);
        }
    }

    // The AWS provider calls SuspendProcesses/ResumeProcesses unconditionally around ASG
    // creation whenever wait_for_capacity_timeout is non-zero (the default), so an
    // unimplemented SuspendProcesses fails ASG creation outright - found crossing
    // terraform-aws-modules/terraform-aws-eks against floci.
    @Test
    void suspendProcessesRecordsNamedProcessesAndResumeClearsThem() {
        service.suspendProcesses(REGION, "test-asg", List.of("Launch", "Terminate"));

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals(List.of("Launch", "Terminate"), group.getSuspendedProcesses());

        service.resumeProcesses(REGION, "test-asg", List.of("Launch"));
        group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals(List.of("Terminate"), group.getSuspendedProcesses());

        service.resumeProcesses(REGION, "test-asg", List.of());
        group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals(List.of(), group.getSuspendedProcesses());
    }

    @Test
    void suspendProcessesWithNoNamesSuspendsEveryScalingProcess() {
        service.suspendProcesses(REGION, "test-asg", List.of());

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertTrue(group.getSuspendedProcesses().contains("Launch"));
        assertTrue(group.getSuspendedProcesses().contains("Terminate"));
        assertTrue(group.getSuspendedProcesses().contains("HealthCheck"));
    }

    @Test
    void attachTrafficSourcesRecordsIdentifierAndTypeAndDetachRemovesIt() {
        service.attachTrafficSources(REGION, "test-asg", List.of(
                new AutoScalingService.TrafficSourceIdentifier("arn:aws:elasticloadbalancing:...:tg/ex", "elbv2")));

        assertEquals(Map.of("arn:aws:elasticloadbalancing:...:tg/ex", "elbv2"),
                service.describeTrafficSources(REGION, "test-asg", null));

        service.detachTrafficSources(REGION, "test-asg", List.of(
                new AutoScalingService.TrafficSourceIdentifier("arn:aws:elasticloadbalancing:...:tg/ex", null)));

        assertEquals(Map.of(), service.describeTrafficSources(REGION, "test-asg", null));
    }

    @Test
    void describeTrafficSourcesFiltersByType() {
        service.attachTrafficSources(REGION, "test-asg", List.of(
                new AutoScalingService.TrafficSourceIdentifier("arn:elbv2-source", "elbv2"),
                new AutoScalingService.TrafficSourceIdentifier("arn:lattice-source", "vpc-lattice")));

        assertEquals(Map.of("arn:elbv2-source", "elbv2"),
                service.describeTrafficSources(REGION, "test-asg", "elbv2"));
    }

    @Test
    void putScheduledUpdateGroupActionRecordsScheduleAndDeleteRemovesIt() {
        service.putScheduledUpdateGroupAction(REGION, "test-asg", "morning",
                null, null, "0 7 * * 1-5", "Europe/Rome", 0, 1, 1);

        List<ScheduledAction> actions = service.describeScheduledActions(REGION, "test-asg", List.of());
        assertEquals(1, actions.size());
        assertEquals("morning", actions.getFirst().getScheduledActionName());
        assertEquals("0 7 * * 1-5", actions.getFirst().getRecurrence());
        assertEquals("Europe/Rome", actions.getFirst().getTimeZone());
        assertEquals(0, actions.getFirst().getMinSize());
        assertEquals(1, actions.getFirst().getMaxSize());
        assertEquals(1, actions.getFirst().getDesiredCapacity());
        assertNotNull(actions.getFirst().getScheduledActionArn());

        service.deleteScheduledAction(REGION, "test-asg", "morning");
        assertEquals(List.of(), service.describeScheduledActions(REGION, "test-asg", List.of()));
    }

    @Test
    void putWarmPoolReplacesConfigurationAndMapsTheClearSentinel() {
        service.putWarmPool(REGION, "test-asg", 2, 1, "Running", true);

        WarmPoolConfiguration pool = service.describeWarmPool(REGION, "test-asg");
        assertEquals(2, pool.getMaxGroupPreparedCapacity());
        assertEquals(1, pool.getMinSize());
        assertEquals("Running", pool.getPoolState());
        assertTrue(pool.isReuseOnScaleIn());

        // A Put that omits fields resets them to the wire model's documented defaults,
        // and -1 is the documented sentinel for clearing MaxGroupPreparedCapacity.
        service.putWarmPool(REGION, "test-asg", -1, null, null, null);
        pool = service.describeWarmPool(REGION, "test-asg");
        assertNull(pool.getMaxGroupPreparedCapacity());
        assertEquals(0, pool.getMinSize());
        assertEquals("Stopped", pool.getPoolState());
        assertFalse(pool.isReuseOnScaleIn());

        service.deleteWarmPool(REGION, "test-asg", false);
        assertNull(service.describeWarmPool(REGION, "test-asg"));
    }

    @Test
    void deleteAutoScalingGroupAlsoRemovesItsScheduledActionsAndWarmPool() {
        service.putScheduledUpdateGroupAction(REGION, "test-asg", "morning",
                null, null, "0 7 * * 1-5", null, 0, 1, 1);
        service.putWarmPool(REGION, "test-asg", null, 1, "Stopped", false);
        service.createAutoScalingGroup("eu-west-1", "test-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("eu-west-1a"), List.of("subnet-12345678"), List.of(), List.of(),
                "EC2", 0, List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        service.putScheduledUpdateGroupAction("eu-west-1", "test-asg", "morning",
                null, null, "0 7 * * 1-5", null, 0, 1, 1);

        service.deleteAutoScalingGroup(REGION, "test-asg", true);

        assertEquals(List.of(), service.describeScheduledActions(REGION, "test-asg", List.of()));
        assertThrows(AwsException.class, () -> service.describeWarmPool(REGION, "test-asg"));
        assertEquals(1, service.describeScheduledActions("eu-west-1", "test-asg", List.of()).size());
    }

    @Test
    void createAutoScalingGroupStoresTheOptionalFieldsAndLeavesUnsetOnesNull() {
        service.createAutoScalingGroup(REGION, "optional-asg", null, null, null, null,
                attributeBasedPolicy(2, 2048),
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("vcpu", true, 604800, 120));

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("optional-asg")).getFirst();

        assertEquals("vcpu", group.getDesiredCapacityType());
        assertEquals(Boolean.TRUE, group.getCapacityRebalance());
        assertEquals(604800, group.getMaxInstanceLifetime());
        assertEquals(120, group.getDefaultInstanceWarmup());
    }

    @Test
    void aFreshAutoScalingGroupCarriesNoInitialiserForTheOptionalFields() {
        AutoScalingGroup group = new AutoScalingGroup();

        assertNull(group.getDesiredCapacityType());
        assertNull(group.getCapacityRebalance());
        assertNull(group.getMaxInstanceLifetime());
        assertNull(group.getDefaultInstanceWarmup());
    }

    @Test
    void createAutoScalingGroupLeavesEveryOptionalFieldNullWhenTheRequestOmitsThem() {
        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();

        assertNull(group.getDesiredCapacityType());
        assertNull(group.getCapacityRebalance());
        assertNull(group.getMaxInstanceLifetime());
        assertNull(group.getDefaultInstanceWarmup());
    }

    @Test
    void updateAutoScalingGroupOverwritesOnlyTheOptionalFieldsTheRequestCarries() {
        service.createAutoScalingGroup(REGION, "partial-update-asg", null, null, null, null,
                attributeBasedPolicy(2, 2048),
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("vcpu", true, 604800, 120));

        service.updateAutoScalingGroup(REGION, "partial-update-asg", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new AsgOptionalFields(null, null, 86400, null));

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("partial-update-asg")).getFirst();

        assertEquals(86400, group.getMaxInstanceLifetime());
        assertEquals("vcpu", group.getDesiredCapacityType());
        assertEquals(Boolean.TRUE, group.getCapacityRebalance());
        assertEquals(120, group.getDefaultInstanceWarmup());
    }

    @Test
    void updateAutoScalingGroupClearsDefaultInstanceWarmupOnTheRemovalSentinel() {
        service.createAutoScalingGroup(REGION, "warmup-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields(null, null, null, 300));

        service.updateAutoScalingGroup(REGION, "warmup-asg", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new AsgOptionalFields(null, null, null, -1));

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("warmup-asg")).getFirst();

        assertNull(group.getDefaultInstanceWarmup());
    }

    @Test
    void createAutoScalingGroupRejectsAnUnknownDesiredCapacityType() {
        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "bad-capacity-type-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("gpu", null, null, null)));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.INVALID_DESIRED_CAPACITY_TYPE_MESSAGE, error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createAutoScalingGroupRejectsAMaxInstanceLifetimeUnderOneDayButAcceptsZero() {
        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "short-lifetime-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields(null, null, 3600, null)));

        assertEquals(AutoScalingService.INVALID_MAX_INSTANCE_LIFETIME_MESSAGE, error.getMessage());

        service.createAutoScalingGroup(REGION, "unbounded-lifetime-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields(null, null, 0, null));

        assertEquals(0, service.describeAutoScalingGroups(REGION, List.of("unbounded-lifetime-asg"))
                .getFirst().getMaxInstanceLifetime());
    }

    @Test
    void createAutoScalingGroupRejectsADefaultInstanceWarmupBelowTheRemovalSentinel() {
        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "bad-warmup-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields(null, null, null, -2)));

        assertEquals(AutoScalingService.INVALID_DEFAULT_INSTANCE_WARMUP_MESSAGE, error.getMessage());
    }

    @Test
    void createAutoScalingGroupRejectsAnOverrideSettingBothInstanceTypeAndInstanceRequirements() {
        MixedInstancesPolicy.InstanceRequirements requirements = new MixedInstancesPolicy.InstanceRequirements();
        MixedInstancesPolicy.IntRange vCpuCount = new MixedInstancesPolicy.IntRange();
        vCpuCount.setMin(2);
        requirements.setVCpuCount(vCpuCount);
        MixedInstancesPolicy.LaunchTemplateOverride override = new MixedInstancesPolicy.LaunchTemplateOverride();
        override.setInstanceType("m6i.large");
        override.setInstanceRequirements(requirements);
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                new MixedInstancesPolicy.LaunchTemplateSpecification();
        specification.setLaunchTemplateId("lt-original");
        MixedInstancesPolicy.LaunchTemplate launchTemplate = new MixedInstancesPolicy.LaunchTemplate();
        launchTemplate.setLaunchTemplateSpecification(specification);
        launchTemplate.setOverrides(List.of(override));
        MixedInstancesPolicy policy = new MixedInstancesPolicy();
        policy.setLaunchTemplate(launchTemplate);

        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "conflicting-override-asg", null, null, null, null, policy,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.INSTANCE_REQUIREMENTS_AND_INSTANCE_TYPE_MESSAGE, error.getMessage());
    }

    @Test
    void createAutoScalingGroupRejectsVcpuDesiredCapacityTypeWhenInstanceTypesAreNamed() {
        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "fixed-type-vcpu-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("vcpu", null, null, null)));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.DESIRED_CAPACITY_TYPE_NEEDS_INSTANCE_REQUIREMENTS_MESSAGE,
                error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void createAutoScalingGroupRejectsMemoryMibDesiredCapacityTypeForOverridesNamingInstanceTypes() {
        MixedInstancesPolicy.LaunchTemplateOverride override = new MixedInstancesPolicy.LaunchTemplateOverride();
        override.setInstanceType("m6i.large");

        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "named-override-memory-asg", null, null, null, null, policyWithOverrides(List.of(override)),
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("memory-mib", null, null, null)));

        assertEquals(AutoScalingService.DESIRED_CAPACITY_TYPE_NEEDS_INSTANCE_REQUIREMENTS_MESSAGE,
                error.getMessage());
    }

    @Test
    void createAutoScalingGroupAcceptsUnitsDesiredCapacityTypeWithoutInstanceRequirements() {
        service.createAutoScalingGroup(REGION, "units-asg", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("units", null, null, null));

        assertEquals("units", service.describeAutoScalingGroups(REGION, List.of("units-asg"))
                .getFirst().getDesiredCapacityType());
    }

    @Test
    void updateAutoScalingGroupRejectsVcpuDesiredCapacityTypeWhenInstanceTypesAreNamed() {
        AwsException error = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(REGION,
                "test-asg", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new AsgOptionalFields("vcpu", null, null, null)));

        assertEquals(AutoScalingService.DESIRED_CAPACITY_TYPE_NEEDS_INSTANCE_REQUIREMENTS_MESSAGE,
                error.getMessage());
        assertNull(service.describeAutoScalingGroups(REGION, List.of("test-asg"))
                .getFirst().getDesiredCapacityType());
    }

    @Test
    void updateAutoScalingGroupAcceptsVcpuWhenTheSameRequestSuppliesInstanceRequirements() {
        service.updateAutoScalingGroup(REGION, "test-asg", null, null, null, null,
                attributeBasedPolicy(4, 8192),
                null, null, null, null, null, null, null, null, null,
                new AsgOptionalFields("vcpu", null, null, null));

        assertEquals("vcpu", service.describeAutoScalingGroups(REGION, List.of("test-asg"))
                .getFirst().getDesiredCapacityType());
    }

    @Test
    void updateAutoScalingGroupRejectsDroppingInstanceRequirementsWhileVcpuStaysInEffect() {
        service.createAutoScalingGroup(REGION, "drop-requirements-asg", null, null, null, null,
                attributeBasedPolicy(2, 2048),
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(),
                new AsgOptionalFields("vcpu", null, null, null));

        AwsException error = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(REGION,
                "drop-requirements-asg", null, "lt-replacement", null, "1", null,
                null, null, null, null, null, null, null, null, null,
                AsgOptionalFields.none()));

        assertEquals(AutoScalingService.DESIRED_CAPACITY_TYPE_NEEDS_INSTANCE_REQUIREMENTS_MESSAGE,
                error.getMessage());
    }

    @Test
    void createAutoScalingGroupRejectsAnOverrideMissingMemoryMiB() {
        MixedInstancesPolicy.InstanceRequirements requirements = new MixedInstancesPolicy.InstanceRequirements();
        requirements.setVCpuCount(intRange(2));
        MixedInstancesPolicy.LaunchTemplateOverride override = new MixedInstancesPolicy.LaunchTemplateOverride();
        override.setInstanceRequirements(requirements);

        AwsException error = assertThrows(AwsException.class, () -> service.createAutoScalingGroup(REGION,
                "partial-requirements-asg", null, null, null, null, policyWithOverrides(List.of(override)),
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none()));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(AutoScalingService.INSTANCE_REQUIREMENTS_MISSING_RANGES_MESSAGE, error.getMessage());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void updateAutoScalingGroupRejectsAnOverrideMissingVCpuCount() {
        MixedInstancesPolicy.InstanceRequirements requirements = new MixedInstancesPolicy.InstanceRequirements();
        requirements.setMemoryMiB(intRange(2048));
        MixedInstancesPolicy.LaunchTemplateOverride override = new MixedInstancesPolicy.LaunchTemplateOverride();
        override.setInstanceRequirements(requirements);

        AwsException error = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(REGION,
                "test-asg", null, null, null, null, policyWithOverrides(List.of(override)),
                null, null, null, null, null, null, null, null, null, AsgOptionalFields.none()));

        assertEquals(AutoScalingService.INSTANCE_REQUIREMENTS_MISSING_RANGES_MESSAGE, error.getMessage());
    }

    @Test
    void createAndUpdateCarryTheOptionalStructuresAndUpdateLeavesUnsetOnesAlone() {
        CapacityReservationSpecification reservation = new CapacityReservationSpecification();
        reservation.setCapacityReservationPreference("capacity-reservations-first");
        reservation.setCapacityReservationResourceGroupArns(List.of("arn:aws:resource-groups:us-east-1:000000000000:group/cr"));
        createWithStructures("structures-asg", new AsgOptionalFields(null, null, null, null,
                "role-arn", new InstanceMaintenancePolicy(90, 120),
                new AvailabilityZoneDistribution("balanced-only"), reservation));

        service.updateAutoScalingGroup(REGION, "structures-asg", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new AsgOptionalFields(null, null, null, null, "other-role-arn", null, null, null));

        AutoScalingGroup group = service.describeAutoScalingGroups(REGION, List.of("structures-asg")).getFirst();
        assertEquals("other-role-arn", group.getServiceLinkedRoleArn());
        assertEquals(90, group.getInstanceMaintenancePolicy().getMinHealthyPercentage());
        assertEquals(120, group.getInstanceMaintenancePolicy().getMaxHealthyPercentage());
        assertEquals("balanced-only", group.getAvailabilityZoneDistribution().getCapacityDistributionStrategy());
        assertEquals("capacity-reservations-first",
                group.getCapacityReservationSpecification().getCapacityReservationPreference());
    }

    @Test
    void instanceMaintenancePolicyRemovalSentinelClearsTheStoredPolicy() {
        createWithStructures("maintenance-asg", new AsgOptionalFields(null, null, null, null,
                null, new InstanceMaintenancePolicy(100, 110), null, null));

        service.updateAutoScalingGroup(REGION, "maintenance-asg", null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new AsgOptionalFields(null, null, null, null, null,
                        new InstanceMaintenancePolicy(-1, -1), null, null));

        assertNull(service.describeAutoScalingGroups(REGION, List.of("maintenance-asg"))
                .getFirst().getInstanceMaintenancePolicy());
    }

    @Test
    void instanceMaintenancePolicyEnforcesTheModelBounds() {
        assertMaintenancePolicyRejected(null, 110, AutoScalingService.INSTANCE_MAINTENANCE_POLICY_BOTH_REQUIRED_MESSAGE);
        assertMaintenancePolicyRejected(-1, 110, AutoScalingService.INSTANCE_MAINTENANCE_POLICY_PARTIAL_CLEAR_MESSAGE);
        assertMaintenancePolicyRejected(101, 150, AutoScalingService.INVALID_MIN_HEALTHY_PERCENTAGE_MESSAGE);
        assertMaintenancePolicyRejected(50, 99, AutoScalingService.INVALID_MAX_HEALTHY_PERCENTAGE_MESSAGE);
        assertMaintenancePolicyRejected(50, 201, AutoScalingService.INVALID_MAX_HEALTHY_PERCENTAGE_MESSAGE);
        assertMaintenancePolicyRejected(0, 101, AutoScalingService.INSTANCE_MAINTENANCE_POLICY_RANGE_MESSAGE);

        createWithStructures("maintenance-bounds-asg", new AsgOptionalFields(null, null, null, null,
                null, new InstanceMaintenancePolicy(0, 100), null, null));
        assertEquals(0, service.describeAutoScalingGroups(REGION, List.of("maintenance-bounds-asg"))
                .getFirst().getInstanceMaintenancePolicy().getMinHealthyPercentage());
    }

    @Test
    void capacityStructuresRejectValuesOutsideTheirEnums() {
        AwsException strategy = assertThrows(AwsException.class, () -> createWithStructures("bad-strategy-asg",
                new AsgOptionalFields(null, null, null, null, null, null,
                        new AvailabilityZoneDistribution("round-robin"), null)));
        assertEquals(AutoScalingService.INVALID_CAPACITY_DISTRIBUTION_STRATEGY_MESSAGE, strategy.getMessage());

        CapacityReservationSpecification badPreference = new CapacityReservationSpecification();
        badPreference.setCapacityReservationPreference("open");
        AwsException preference = assertThrows(AwsException.class, () -> createWithStructures("bad-preference-asg",
                new AsgOptionalFields(null, null, null, null, null, null, null, badPreference)));
        assertEquals(AutoScalingService.INVALID_CAPACITY_RESERVATION_PREFERENCE_MESSAGE, preference.getMessage());
    }

    @Test
    void capacityReservationPreferenceNoneRejectsATarget() {
        CapacityReservationSpecification specification = new CapacityReservationSpecification();
        specification.setCapacityReservationPreference("none");
        specification.setCapacityReservationIds(List.of("cr-0123456789abcdef0"));

        AwsException error = assertThrows(AwsException.class, () -> createWithStructures("none-target-asg",
                new AsgOptionalFields(null, null, null, null, null, null, null, specification)));

        assertEquals(AutoScalingService.CAPACITY_RESERVATION_TARGET_WITH_NONE_MESSAGE, error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ids", "groups", "mixed"})
    void capacityReservationRejectsMultipleTargetsBeforeCreateOrUpdate(String kind) {
        CapacityReservationSpecification specification = new CapacityReservationSpecification();
        specification.setCapacityReservationPreference("capacity-reservations-only");
        if ("ids".equals(kind)) {
            specification.setCapacityReservationIds(List.of("cr-one", "cr-two"));
        } else if ("groups".equals(kind)) {
            specification.setCapacityReservationResourceGroupArns(List.of("group-one", "group-two"));
        } else {
            specification.setCapacityReservationIds(List.of("cr-one"));
            specification.setCapacityReservationResourceGroupArns(List.of("group-one"));
        }
        AsgOptionalFields options = new AsgOptionalFields(null, null, null, null, null, null, null, specification);
        AwsException create = assertThrows(AwsException.class, () -> createWithStructures("multi-target", options));
        assertEquals("ValidationError", create.getErrorCode());
        assertTrue(service.describeAutoScalingGroups(REGION, List.of("multi-target")).isEmpty());
        AutoScalingGroup before = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        int desired = before.getDesiredCapacity();
        AwsException update = assertThrows(AwsException.class, () -> service.updateAutoScalingGroup(REGION,
                "test-asg", null, null, null, null, null, null, null, 2, null, null, null, null, null, null, options));
        assertEquals("ValidationError", update.getErrorCode());
        AutoScalingGroup after = service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst();
        assertEquals(desired, after.getDesiredCapacity());
        assertNull(after.getCapacityReservationSpecification());
    }

    @Test
    void capacityReservationTargetMembersFollowTheirModelShapes() {
        CapacityReservationSpecification badId = new CapacityReservationSpecification();
        badId.setCapacityReservationPreference("capacity-reservations-only");
        badId.setCapacityReservationIds(List.of("cr 0123"));
        AwsException idError = assertThrows(AwsException.class, () -> createWithStructures("bad-id-asg",
                new AsgOptionalFields(null, null, null, null, null, null, null, badId)));
        assertEquals("ValidationError", idError.getErrorCode());

        CapacityReservationSpecification longId = new CapacityReservationSpecification();
        longId.setCapacityReservationPreference("capacity-reservations-only");
        longId.setCapacityReservationIds(List.of("c".repeat(256)));
        assertThrows(AwsException.class, () -> createWithStructures("long-id-asg",
                new AsgOptionalFields(null, null, null, null, null, null, null, longId)));

        CapacityReservationSpecification longArn = new CapacityReservationSpecification();
        longArn.setCapacityReservationPreference("capacity-reservations-only");
        longArn.setCapacityReservationResourceGroupArns(List.of("a".repeat(1601)));
        assertThrows(AwsException.class, () -> createWithStructures("long-arn-asg",
                new AsgOptionalFields(null, null, null, null, null, null, null, longArn)));
    }

    @Test
    void serviceLinkedRoleArnMustBeOneTo1600Characters() {
        AwsException empty = assertThrows(AwsException.class, () -> createWithStructures("empty-role-asg",
                new AsgOptionalFields(null, null, null, null, "", null, null, null)));
        assertEquals(AutoScalingService.INVALID_SERVICE_LINKED_ROLE_ARN_MESSAGE, empty.getMessage());

        AwsException tooLong = assertThrows(AwsException.class, () -> createWithStructures("long-role-asg",
                new AsgOptionalFields(null, null, null, null, "a".repeat(1601), null, null, null)));
        assertEquals(AutoScalingService.INVALID_SERVICE_LINKED_ROLE_ARN_MESSAGE, tooLong.getMessage());
    }

    @Test
    void weightedCapacityMustBeOneTo999AndSetOnEveryOverrideOrNone() {
        AwsException outOfRange = assertThrows(AwsException.class, () -> createWithStructures("weight-range-asg",
                AsgOptionalFields.none(), weightedPolicy("0", "2")));
        assertEquals(AutoScalingService.INVALID_WEIGHTED_CAPACITY_MESSAGE, outOfRange.getMessage());

        AwsException notANumber = assertThrows(AwsException.class, () -> createWithStructures("weight-text-asg",
                AsgOptionalFields.none(), weightedPolicy("two", "2")));
        assertEquals(AutoScalingService.INVALID_WEIGHTED_CAPACITY_MESSAGE, notANumber.getMessage());

        AwsException tooLong = assertThrows(AwsException.class, () -> createWithStructures("weight-long-asg",
                AsgOptionalFields.none(), weightedPolicy("0".repeat(32) + "1", "2")));
        assertEquals(AutoScalingService.INVALID_WEIGHTED_CAPACITY_MESSAGE, tooLong.getMessage());

        AwsException partial = assertThrows(AwsException.class, () -> createWithStructures("weight-partial-asg",
                AsgOptionalFields.none(), weightedPolicy("2", null)));
        assertEquals(AutoScalingService.PARTIAL_WEIGHTED_CAPACITY_MESSAGE, partial.getMessage());

        createWithStructures("weight-ok-asg", AsgOptionalFields.none(), weightedPolicy("1", "999"));
        assertEquals("999", service.describeAutoScalingGroups(REGION, List.of("weight-ok-asg")).getFirst()
                .getMixedInstancesPolicy().getLaunchTemplate().getOverrides().get(1).getWeightedCapacity());
    }

    @Test
    void enableAndDisableMetricsCollectionMaintainEnabledMetrics() {
        service.enableMetricsCollection(REGION, "test-asg", List.of("GroupMinSize", "GroupMaxSize"), "1Minute");
        service.enableMetricsCollection(REGION, "test-asg", List.of("GroupMaxSize", "GroupTotalInstances"), "1Minute");
        assertEquals(List.of("GroupMinSize", "GroupMaxSize", "GroupTotalInstances"), enabledMetrics());

        service.disableMetricsCollection(REGION, "test-asg", List.of("GroupMaxSize"));
        assertEquals(List.of("GroupMinSize", "GroupTotalInstances"), enabledMetrics());

        service.disableMetricsCollection(REGION, "test-asg", List.of());
        assertEquals(List.of(), enabledMetrics());

        service.enableMetricsCollection(REGION, "test-asg", List.of(), "1Minute");
        assertEquals(AutoScalingService.GROUP_METRICS, enabledMetrics());
    }

    @Test
    void enableMetricsCollectionRequiresOneMinuteGranularityAndKnownMetrics() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.enableMetricsCollection(REGION, "test-asg", List.of(), null));
        assertEquals(AutoScalingService.MISSING_GRANULARITY_MESSAGE, missing.getMessage());

        AwsException wrong = assertThrows(AwsException.class,
                () -> service.enableMetricsCollection(REGION, "test-asg", List.of(), "5Minute"));
        assertEquals(AutoScalingService.INVALID_GRANULARITY_MESSAGE, wrong.getMessage());

        AwsException unknown = assertThrows(AwsException.class,
                () -> service.enableMetricsCollection(REGION, "test-asg", List.of("CPUUtilization"), "1Minute"));
        assertEquals("ValidationError", unknown.getErrorCode());
        assertEquals(List.of(), enabledMetrics());
    }

    private List<String> enabledMetrics() {
        return service.describeAutoScalingGroups(REGION, List.of("test-asg")).getFirst().getEnabledMetrics();
    }

    private void assertMaintenancePolicyRejected(Integer min, Integer max, String message) {
        AwsException error = assertThrows(AwsException.class, () -> createWithStructures("maintenance-bad-asg",
                new AsgOptionalFields(null, null, null, null, null,
                        new InstanceMaintenancePolicy(min, max), null, null)));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(message, error.getMessage());
    }

    private void createWithStructures(String name, AsgOptionalFields optionalFields) {
        service.createAutoScalingGroup(REGION, name, null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), optionalFields);
    }

    private void createWithStructures(String name, AsgOptionalFields optionalFields, MixedInstancesPolicy policy) {
        service.createAutoScalingGroup(REGION, name, null, null, null, null, policy,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), optionalFields);
    }

    private static MixedInstancesPolicy weightedPolicy(String firstWeight, String secondWeight) {
        MixedInstancesPolicy.LaunchTemplateOverride first = new MixedInstancesPolicy.LaunchTemplateOverride();
        first.setInstanceType("t3.micro");
        first.setWeightedCapacity(firstWeight);
        MixedInstancesPolicy.LaunchTemplateOverride second = new MixedInstancesPolicy.LaunchTemplateOverride();
        second.setInstanceType("t3.small");
        second.setWeightedCapacity(secondWeight);
        return policyWithOverrides(List.of(first, second));
    }

    private static MixedInstancesPolicy attributeBasedPolicy(int minVCpuCount, int minMemoryMiB) {
        MixedInstancesPolicy.InstanceRequirements requirements = new MixedInstancesPolicy.InstanceRequirements();
        requirements.setVCpuCount(intRange(minVCpuCount));
        requirements.setMemoryMiB(intRange(minMemoryMiB));
        MixedInstancesPolicy.LaunchTemplateOverride override = new MixedInstancesPolicy.LaunchTemplateOverride();
        override.setInstanceRequirements(requirements);
        return policyWithOverrides(List.of(override));
    }

    private static MixedInstancesPolicy policyWithOverrides(
            List<MixedInstancesPolicy.LaunchTemplateOverride> overrides) {
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                new MixedInstancesPolicy.LaunchTemplateSpecification();
        specification.setLaunchTemplateId("lt-original");
        MixedInstancesPolicy.LaunchTemplate launchTemplate = new MixedInstancesPolicy.LaunchTemplate();
        launchTemplate.setLaunchTemplateSpecification(specification);
        launchTemplate.setOverrides(overrides);
        MixedInstancesPolicy policy = new MixedInstancesPolicy();
        policy.setLaunchTemplate(launchTemplate);
        return policy;
    }

    private static MixedInstancesPolicy.IntRange intRange(int min) {
        MixedInstancesPolicy.IntRange range = new MixedInstancesPolicy.IntRange();
        range.setMin(min);
        return range;
    }
}

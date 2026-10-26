package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoScalingPlacementTest {
    private static final String REGION = "us-east-1";

    @Test
    void changedSubnetsTakePrecedenceOverOlderZoneMetadata() {
        Fixture fixture = new Fixture(Map.of("subnet-b", REGION + "b"), List.of("subnet-b"));
        fixture.group.setAvailabilityZones(List.of(REGION + "a"));
        fixture.grow(2);
        assertEquals(Map.of(REGION + "b", 2L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void exhaustedSubnetsDoNotBlockOtherConfiguredZones() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.availableIps.put("subnet-a", 0);
        fixture.grow(2);
        assertEquals(Map.of(REGION + "b", 2L), fixture.counts());
    }

    @Test
    void oneRemainingAddressIsNotSelectedAgainWithinTheSameScaleOut() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.availableIps.put("subnet-a", 1);
        fixture.availableIps.put("subnet-b", 10);
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 1L, REGION + "b", 3L), fixture.counts());
        assertEquals(0, fixture.availableIps.get("subnet-a"));
    }

    @Test
    void failedSaveStillCleansUpSuccessfulAllocationsAndRethrows() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.saveFailure = new IllegalStateException("fixture save failure");
        assertEquals(fixture.saveFailure, assertThrows(IllegalStateException.class, () -> fixture.grow(2)));
        verify(fixture.ec2).terminateInstances(REGION, List.of("i-1", "i-2"));
        assertTrue(fixture.group.getInstances().isEmpty());
    }

    @Test
    void repeatedGrowthBalancesActualZonesAndReportsEachInstancePlacement() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.grow(2);
        assertEquals(Map.of(REGION + "a", 1L, REGION + "b", 1L), fixture.counts());
        fixture.grow(3);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 1L), fixture.counts());
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 2L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void unequalSubnetCountsDoNotWeightOneAvailabilityZoneMoreHeavily() {
        Fixture fixture = new Fixture(Map.of("subnet-a1", REGION + "a", "subnet-a2", REGION + "a",
                "subnet-b", REGION + "b"), List.of("subnet-a1", "subnet-a2", "subnet-b"));
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 2L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void chosenZoneUsesTheSubnetWithTheMostAvailableAddresses() {
        Fixture fixture = new Fixture(Map.of("subnet-small", REGION + "a", "subnet-large", REGION + "a"),
                List.of("subnet-small", "subnet-large"));
        fixture.availableIps.put("subnet-small", 11);
        fixture.availableIps.put("subnet-large", 251);
        fixture.grow(2);
        assertEquals(List.of("subnet-large", "subnet-large"), fixture.instances.values().stream()
                .map(Instance::getSubnetId).toList());
        fixture.availableIps.put("subnet-small", 250);
        fixture.grow(3);
        assertEquals("subnet-small", fixture.instances.get("i-3").getSubnetId());
    }

    @Test
    void singleZoneSubnetsUseTheirActualZoneWithoutAnExplicitZoneList() {
        Fixture fixture = new Fixture(Map.of("subnet-b1", REGION + "b", "subnet-b2", REGION + "b"),
                List.of("subnet-b1", "subnet-b2"));
        fixture.grow(3);
        assertEquals(Map.of(REGION + "b", 3L), fixture.counts());
        fixture.assertReportedZones();
    }

    @Test
    void unmodelledZonesLaunchUnpinnedAndBalanceUnderTheirRequestedZones() {
        Fixture fixture = new Fixture(Map.of(), List.of());
        fixture.group.setAvailabilityZones(List.of(REGION + "d", REGION + "f"));
        fixture.grow(2);
        fixture.grow(3);
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 4L), fixture.counts());
        assertEquals(Map.of(REGION + "d", 2L, REGION + "f", 2L), fixture.group.getInstances().stream()
                .collect(Collectors.groupingBy(instance -> instance.getAvailabilityZone(), Collectors.counting())));
    }

    @Test
    void existingEc2PlacementWinsOverStaleGroupZoneMetadata() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.grow(2);
        fixture.group.getInstances().forEach(instance -> instance.setAvailabilityZone(REGION + "a"));
        fixture.grow(3);
        assertEquals(REGION + "a", fixture.instances.get("i-3").getPlacement().getAvailabilityZone());
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 1L), fixture.counts());
    }

    @Test
    void missingDeclaredSubnetRefusesBeforeAnyAllocation() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a"),
                List.of("subnet-a", "subnet-missing"));
        fixture.grow(2);
        assertTrue(fixture.activities().getFirst().getStatusMessage().contains("subnet-missing"));
        assertEquals(Map.of(), fixture.instances);
        assertEquals(0, fixture.group.getInstances().size());
        verify(fixture.groups, never()).saveAutoScalingGroupIfPresent(any());
    }

    @Test
    void laterAllocationFailureRetainsAlreadyLaunchedGroupMembers() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.failAfter = 1;
        fixture.grow(2);
        assertEquals(1, fixture.instances.size());
        assertEquals(List.of("i-1"), fixture.group.getInstances().stream()
                .map(instance -> instance.getInstanceId()).toList());
        assertTrue(fixture.savedMembers.contains(List.of("i-1")));
        ScalingActivity activity = fixture.activities().getFirst();
        assertEquals("Failed", activity.getStatusCode());
        assertEquals("fixture allocation failure Launching EC2 instance failed.", activity.getStatusMessage());
        assertEquals("Launching a new EC2 instance.  Status Reason: " + activity.getStatusMessage(),
                activity.getDescription());
        assertEquals(100, activity.getProgress());
        assertNotNull(activity.getEndTime());
        fixture.grow(2);
        assertEquals(1, fixture.activities().size());
        fixture.launchFailure = new AwsException("RequestLimitExceeded", "request limit exceeded", 503);
        fixture.grow(2);
        assertEquals(2, fixture.activities().size());
        assertEquals("request limit exceeded Launching EC2 instance failed.",
                fixture.activities().getFirst().getStatusMessage());
        fixture.launchFailure = new AwsException("InsufficientInstanceCapacity", "fixture allocation failure", 400);
        fixture.grow(2);
        assertEquals(3, fixture.activities().size());
        verify(fixture.groups, never()).completeInstanceRefreshIfSettled(any(), any());
        fixture.failAfter = Integer.MAX_VALUE;
        fixture.grow(2);
        assertEquals(2, fixture.allocations);
    }

    @Test
    void deletedGroupAfterPartialFailureTerminatesOnlyItsSuccessfulAllocations() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.instances.put("i-unrelated", new Instance());
        fixture.failAfter = 1;
        fixture.deleteOnFailure = true;
        fixture.grow(2);
        assertTrue(fixture.savedMembers.contains(List.of("i-1")));
        verify(fixture.ec2).terminateInstances(REGION, List.of("i-1"));
        verify(fixture.ec2, times(1)).terminateInstances(eq(REGION), anyList());
        assertTrue(fixture.instances.containsKey("i-unrelated"));
    }

    @Test
    void exhaustedPlacementsLetEc2ReportTheLeastPopulatedSubnetsFailure() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-b", REGION + "b"),
                List.of("subnet-a", "subnet-b"));
        fixture.grow(1);
        fixture.availableIps.put("subnet-a", 0);
        fixture.availableIps.put("subnet-b", 0);
        fixture.grow(2);
        assertEquals("There are not enough free addresses in subnet 'subnet-b' to satisfy the requested "
                        + "number of instances. Launching EC2 instance failed.",
                fixture.activities().getFirst().getStatusMessage());
        assertEquals("An instance was started in response to a desired capacity change.",
                fixture.activities().getFirst().getCause());
        assertEquals(1, fixture.allocations);
        verify(fixture.groups, times(1)).completeInstanceRefreshIfSettled(any(), any());
    }

    @Test
    void ipv6OnlySubnetsDoNotRequireFreeIpv4Addresses() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a", "subnet-v6", REGION + "b"),
                List.of("subnet-a", "subnet-v6"));
        fixture.ipv6Only.add("subnet-v6");
        fixture.availableIps.put("subnet-v6", 0);
        fixture.grow(4);
        assertEquals(Map.of(REGION + "a", 2L, REGION + "b", 2L), fixture.counts());
        assertTrue(fixture.activities().isEmpty());
    }

    @Test
    void unexpectedLaunchFailurePropagatesAfterSavingPartialAllocations() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a"), List.of("subnet-a"));
        fixture.failAfter = 1;
        fixture.launchFailure = new IllegalStateException("unexpected launch failure");
        assertSame(fixture.launchFailure, assertThrows(IllegalStateException.class, () -> fixture.grow(2)));
        assertEquals(List.of(List.of("i-1")), fixture.savedMembers);
        verify(fixture.ec2, never()).terminateInstances(any(), any());
        verify(fixture.groups, never()).completeInstanceRefreshIfSettled(any(), any());
    }

    @Test
    void launchFailureRetainsSaveAndCleanupFailuresAsSuppressedExceptions() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a"), List.of("subnet-a"));
        fixture.failAfter = 1;
        fixture.saveFailure = new IllegalStateException("save failed");
        RuntimeException cleanupFailure = new IllegalStateException("cleanup failed");
        when(fixture.ec2.terminateInstances(REGION, List.of("i-1"))).thenThrow(cleanupFailure);
        assertSame(fixture.launchFailure, assertThrows(AwsException.class, () -> fixture.grow(2)));
        assertEquals(List.of(fixture.saveFailure), List.of(fixture.launchFailure.getSuppressed()));
        assertEquals(List.of(cleanupFailure), List.of(fixture.saveFailure.getSuppressed()));
    }

    @Test
    void deletedGroupCleanupFailurePropagates() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a"), List.of("subnet-a"));
        fixture.groupPresent = false;
        RuntimeException cleanupFailure = new IllegalStateException("cleanup failed");
        when(fixture.ec2.terminateInstances(REGION, List.of("i-1"))).thenThrow(cleanupFailure);
        assertSame(cleanupFailure, assertThrows(IllegalStateException.class, () -> fixture.grow(1)));
    }

    @Test
    void scheduledReconciliationContinuesAfterOneGroupFails() {
        Fixture fixture = new Fixture(Map.of("subnet-a", REGION + "a"), List.of("subnet-a"));
        fixture.group.setDesiredCapacity(1);
        fixture.failAfter = 0;
        AutoScalingGroup healthyGroup = new AutoScalingGroup();
        healthyGroup.setRegion(REGION);
        healthyGroup.setAutoScalingGroupName("healthy-group");
        when(fixture.groups.autoScalingGroupAccountIds()).thenReturn(Set.of("000000000000"));
        when(fixture.groups.describeAutoScalingGroups(null, null)).thenReturn(List.of(fixture.group, healthyGroup));
        when(fixture.groups.saveAutoScalingGroupIfPresent(healthyGroup)).thenReturn(true);
        assertDoesNotThrow(fixture.reconciler::reconcileAll);
        verify(fixture.groups).completeInstanceRefreshIfSettled(REGION, "healthy-group");
        verify(fixture.groups, never()).completeInstanceRefreshIfSettled(REGION, "placement-group");
    }

    private static class Fixture {
        final AutoScalingGroup group = new AutoScalingGroup();
        final Map<String, Instance> instances = new LinkedHashMap<>();
        final Map<String, Integer> availableIps = new LinkedHashMap<>();
        final List<String> ipv6Only = new ArrayList<>();
        final AutoScalingReconciler reconciler;
        final AutoScalingService groups = mock(AutoScalingService.class);
        final AutoScalingService activityStore = new AutoScalingService();
        final Ec2Service ec2 = mock(Ec2Service.class);
        final List<List<String>> savedMembers = new ArrayList<>();
        boolean groupPresent = true;
        boolean deleteOnFailure;
        int allocations;
        int failAfter = Integer.MAX_VALUE;
        RuntimeException saveFailure;
        RuntimeException launchFailure =
                new AwsException("InsufficientInstanceCapacity", "fixture allocation failure", 400);

        Fixture(Map<String, String> zones, List<String> declaredSubnets) {
            reconciler = new AutoScalingReconciler(groups, ec2, mock(ElbV2Service.class));
            doAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return null;
            }).when(groups).reconcileIfPresent(any(), any());
            when(groups.describeScalingActivities(anyString(), anyString())).thenAnswer(call ->
                    activityStore.describeScalingActivities(call.getArgument(0), call.getArgument(1)));
            when(groups.recordActivity(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(call -> activityStore.recordActivity(call.getArgument(0), call.getArgument(1),
                            call.getArgument(2), call.getArgument(3), call.getArgument(4)));
            doAnswer(call -> {
                activityStore.completeActivity(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                return null;
            }).when(groups).completeActivity(anyString(), anyString(), anyString());
            group.setRegion(REGION);
            group.setAutoScalingGroupName("placement-group");
            group.setLaunchConfigurationName("launch");
            group.setSubnetIds(declaredSubnets);
            LaunchConfiguration launch = new LaunchConfiguration();
            launch.setImageId("ami-test");
            launch.setInstanceType("m5.large");
            when(groups.describeLaunchConfigurations(REGION, List.of("launch"))).thenReturn(List.of(launch));
            when(groups.saveAutoScalingGroupIfPresent(group)).thenAnswer(call -> {
                savedMembers.add(group.getInstances().stream().map(instance -> instance.getInstanceId()).toList());
                if (saveFailure != null) {
                    throw saveFailure;
                }
                return groupPresent;
            });
            List<Subnet> subnets = zones.entrySet().stream().map(entry -> {
                Subnet subnet = new Subnet();
                subnet.setSubnetId(entry.getKey());
                subnet.setAvailabilityZone(entry.getValue());
                return subnet;
            }).toList();
            when(ec2.describeSubnets(eq(REGION), anyList(), any())).thenAnswer(call -> {
                subnets.forEach(subnet -> {
                    subnet.setAvailableIpAddressCount(availableIps.getOrDefault(subnet.getSubnetId(), 251));
                    subnet.setCidrBlock(ipv6Only.contains(subnet.getSubnetId()) ? null : "10.0.0.0/24");
                });
                return subnets;
            });
            when(ec2.getInstance(eq(REGION), anyString()))
                    .thenAnswer(call -> Optional.ofNullable(instances.get(call.<String>getArgument(1))));
            when(ec2.isInstanceContainerRunning(anyString())).thenReturn(true);
            when(ec2.describeInstances(eq(REGION), anyList(), any())).thenAnswer(call -> {
                List<String> ids = call.getArgument(1);
                Reservation reservation = new Reservation();
                reservation.setInstances(ids.stream().map(instances::get).toList());
                return List.of(reservation);
            });
            when(ec2.runInstances(any(), any(), any(), anyInt(), anyInt(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(),
                    anyBoolean(), any(), any(), any(), eq(false), isNull(), isNull())).thenAnswer(call -> {
                        if (allocations >= failAfter) {
                            if (deleteOnFailure) {
                                groupPresent = false;
                            }
                            throw launchFailure;
                        }
                        String subnet = call.getArgument(7);
                        String zone = null;
                        if (subnet != null) {
                            if (!ipv6Only.contains(subnet) && availableIps.getOrDefault(subnet, 251) == 0) {
                                throw new AwsException("InsufficientFreeAddressesInSubnet",
                                        "There are not enough free addresses in subnet '" + subnet
                                                + "' to satisfy the requested number of instances.", 400);
                            }
                            zone = zones.get(subnet);
                        }
                        if (zone == null) {
                            zone = REGION + "a";
                        }
                        List<Instance> launched = new ArrayList<>();
                        int count = call.getArgument(3);
                        for (int index = 0; index < count; index++) {
                            Instance instance = new Instance();
                            instance.setInstanceId("i-" + (++allocations));
                            instance.setSubnetId(subnet);
                            instance.setPlacement(new Placement(zone));
                            instance.setState(new InstanceState(0, "pending"));
                            instances.put(instance.getInstanceId(), instance);
                            if (subnet != null) {
                                availableIps.compute(subnet, (key, free) -> (free == null ? 251 : free) - 1);
                            }
                            launched.add(instance);
                        }
                        Reservation reservation = new Reservation();
                        reservation.setInstances(launched);
                        return reservation;
                    });
        }

        void grow(int desired) {
            group.setDesiredCapacity(desired);
            reconciler.reconcile(group);
        }

        List<ScalingActivity> activities() {
            return groups.describeScalingActivities(REGION, group.getAutoScalingGroupName());
        }

        Map<String, Long> counts() {
            return instances.values().stream().collect(Collectors.groupingBy(
                    instance -> instance.getPlacement().getAvailabilityZone(), Collectors.counting()));
        }

        void assertReportedZones() {
            group.getInstances().forEach(instance -> assertEquals(
                    instances.get(instance.getInstanceId()).getPlacement().getAvailabilityZone(),
                    instance.getAvailabilityZone()));
        }
    }
}

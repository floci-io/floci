package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.autoscaling.model.MixedInstancesPolicy;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoScalingReconcilerTest {

    @Test
    void pendingInstancesCountAsActiveCapacity() {
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.getInstances().add(instance("Pending"));
        asg.getInstances().add(instance("InService"));
        asg.getInstances().add(instance("Terminating"));
        asg.getInstances().add(instance("Terminated"));
        asg.getInstances().add(instance("Detached"));

        assertEquals(2, AutoScalingReconciler.activeCapacity(asg));
    }

    @Test
    void stopTerminatesReconcilerThread() throws Exception {
        var asgService = mock(AutoScalingService.class);
        var ec2Service = mock(Ec2Service.class);
        var elbV2Service = mock(ElbV2Service.class);
        var reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        var preexisting = Thread.getAllStackTraces().keySet();

        reconciler.start();
        var reconcilerThread = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "asg-reconciler".equals(t.getName()) && !preexisting.contains(t))
                .findFirst().orElseThrow();

        reconciler.stop();
        reconcilerThread.join(5_000);

        assertFalse(reconcilerThread.isAlive());
    }

    @Test
    void reconcileCompletesSettledInstanceRefreshes() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        when(asgService.saveAutoScalingGroupIfPresent(asg)).thenReturn(true);

        reconciler.reconcile(asg);

        verify(asgService).completeInstanceRefreshIfSettled("us-east-1", "app-asg");
    }

    @Test
    void scaleOutUsesRequestedLaunchTemplateVersionData() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.setLaunchTemplateId("lt-123");
        asg.setLaunchTemplateVersion("1");
        asg.getTags().put("job-id", "2001");
        asg.getTags().put("control-plane-only", "true");
        asg.getTagPropagateAtLaunch().put("job-id", true);
        asg.getTagPropagateAtLaunch().put("control-plane-only", false);

        LaunchTemplate launchTemplate = new LaunchTemplate();
        launchTemplate.setLaunchTemplateId("lt-123");
        LaunchTemplate version = new LaunchTemplate();
        version.setLatestVersionNumber("1");
        version.getData().setImageId("ami-version-1");
        version.getData().setInstanceType("t3.micro");
        version.getData().setIamInstanceProfile(new LaunchTemplateData.IamInstanceProfile(
                "arn:aws:iam::000000000000:instance-profile/app-profile", null));
        when(ec2Service.iamInstanceProfileArn(version.getData()))
                .thenReturn("arn:aws:iam::000000000000:instance-profile/app-profile");
        List<Tag> instanceTags = List.of(new Tag("app.ClusterId", "development"));
        List<Tag> propagatedTags = List.of(new Tag("app.ClusterId", "development"), new Tag("job-id", "2001"));
        version.getData().setTagSpecifications(List.of(
                new LaunchTemplateData.TagSpecification("instance", instanceTags)));
        when(ec2Service.describeLaunchTemplates("us-east-1", List.of("lt-123"), List.of(), Map.of()))
                .thenReturn(List.of(launchTemplate));
        when(ec2Service.describeLaunchTemplateVersions("us-east-1", "lt-123", null, List.of("1")))
                .thenReturn(List.of(version));
        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-launched");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.runInstances(eq("us-east-1"), eq("ami-version-1"), eq("t3.micro"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                anyList(), eq(null), eq("arn:aws:iam::000000000000:instance-profile/app-profile"), eq(null))).thenReturn(reservation);

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-launched", asg.getInstances().getFirst().getInstanceId());
        assertEquals("lt-123", asg.getInstances().getFirst().getLaunchTemplateId());
        assertEquals("1", asg.getInstances().getFirst().getLaunchTemplateVersion());
        ArgumentCaptor<List<Tag>> tags = ArgumentCaptor.captor();
        verify(ec2Service).runInstances(eq("us-east-1"), eq("ami-version-1"), eq("t3.micro"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                tags.capture(), eq(null), eq("arn:aws:iam::000000000000:instance-profile/app-profile"), eq(null));
        assertEquals(propagatedTags.size(), tags.getValue().size());
        assertEquals("app.ClusterId", tags.getValue().get(0).getKey());
        assertEquals("development", tags.getValue().get(0).getValue());
        assertEquals("job-id", tags.getValue().get(1).getKey());
        assertEquals("2001", tags.getValue().get(1).getValue());
    }

    @Test
    void scaleOutTerminatesInstancesWhenGroupDisappears() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = launchTemplateAsg();
        stubLaunchTemplate(ec2Service, "ami-version-1", "t3.micro");
        when(asgService.saveAutoScalingGroupIfPresent(asg)).thenReturn(false);

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-stale");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.runInstances(eq("us-east-1"), eq("ami-version-1"), eq("t3.micro"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null), eq(List.of()),
                eq(null), eq(null), eq(null))).thenReturn(reservation);

        reconciler.reconcile(asg);

        verify(ec2Service).terminateInstances("us-east-1", List.of("i-stale"));
        verify(asgService, never()).completeInstanceRefreshIfSettled("us-east-1", "app-asg");
    }

    @Test
    void scaleOutPersistsInstancesWhenGroupStillExists() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = launchTemplateAsg();
        stubLaunchTemplate(ec2Service, "ami-version-1", "t3.micro");
        when(asgService.saveAutoScalingGroupIfPresent(asg)).thenReturn(true);

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-owned");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.runInstances(eq("us-east-1"), eq("ami-version-1"), eq("t3.micro"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null), eq(List.of()),
                eq(null), eq(null), eq(null))).thenReturn(reservation);

        reconciler.reconcile(asg);

        verify(asgService, times(2)).saveAutoScalingGroupIfPresent(asg);
        verify(ec2Service, never()).terminateInstances("us-east-1", List.of("i-owned"));
    }

    @Test
    void scaleOutForwardsLaunchConfigurationAssociatePublicIpAddressBothDirections() {
        // An LC that explicitly sets false must suppress the public IP even in a
        // MapPublicIpOnLaunch subnet, so false has to reach runInstances as
        // FALSE rather than null (null means "use the subnet default").
        assertLaunchConfigurationForwards(Boolean.FALSE);
        assertLaunchConfigurationForwards(Boolean.TRUE);
        assertLaunchConfigurationForwards(null);
    }

    private void assertLaunchConfigurationForwards(Boolean associatePublicIp) {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);

        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.setLaunchConfigurationName("app-lc");

        LaunchConfiguration lc = new LaunchConfiguration();
        lc.setLaunchConfigurationName("app-lc");
        lc.setImageId("ami-lc");
        lc.setInstanceType("t3.micro");
        lc.setAssociatePublicIpAddress(associatePublicIp);
        when(asgService.describeLaunchConfigurations("us-east-1", List.of("app-lc")))
                .thenReturn(List.of(lc));

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-lc-launched");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.runInstances(eq("us-east-1"), eq("ami-lc"), eq("t3.micro"),
                eq(1), eq(1), eq(null), anyList(), eq(null), eq(null),
                anyList(), eq(null), eq(null), eq(associatePublicIp))).thenReturn(reservation);

        reconciler.reconcile(asg);

        verify(ec2Service).runInstances(eq("us-east-1"), eq("ami-lc"), eq("t3.micro"),
                eq(1), eq(1), eq(null), anyList(), eq(null), eq(null),
                anyList(), eq(null), eq(null), eq(associatePublicIp));
    }

    @Test
    void scaleOutStoresResolvedLaunchTemplateVersionForAliases() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.setLaunchTemplateId("lt-123");
        asg.setLaunchTemplateVersion("$Latest");

        LaunchTemplate launchTemplate = new LaunchTemplate();
        launchTemplate.setLaunchTemplateId("lt-123");
        LaunchTemplate version = new LaunchTemplate();
        version.setLatestVersionNumber("7");
        version.getData().setImageId("ami-version-7");
        version.getData().setInstanceType("t3.micro");
        when(ec2Service.describeLaunchTemplates("us-east-1", List.of("lt-123"), List.of(), Map.of()))
                .thenReturn(List.of(launchTemplate));
        when(ec2Service.describeLaunchTemplateVersions("us-east-1", "lt-123", null, List.of("$Latest")))
                .thenReturn(List.of(version));
        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-launched");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.runInstances(eq("us-east-1"), eq("ami-version-7"), eq("t3.micro"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                eq(List.of()), eq(null), eq(null), eq(null))).thenReturn(reservation);

        reconciler.reconcile(asg);

        assertEquals("$Latest", asg.getLaunchTemplateVersion());
        assertEquals("7", asg.getInstances().getFirst().getLaunchTemplateVersion());
    }

    @Test
    void scaleOutUsesMixedInstancesLaunchTemplateSpecification() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        MixedInstancesPolicy policy = new MixedInstancesPolicy();
        MixedInstancesPolicy.LaunchTemplate launchTemplatePolicy =
                new MixedInstancesPolicy.LaunchTemplate();
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                new MixedInstancesPolicy.LaunchTemplateSpecification();
        specification.setLaunchTemplateId("lt-123");
        specification.setVersion("3");
        launchTemplatePolicy.setLaunchTemplateSpecification(specification);
        MixedInstancesPolicy.LaunchTemplateOverride override =
                new MixedInstancesPolicy.LaunchTemplateOverride();
        override.setInstanceType("t3.small");
        launchTemplatePolicy.setOverrides(List.of(override));
        policy.setLaunchTemplate(launchTemplatePolicy);
        asg.setMixedInstancesPolicy(policy);

        LaunchTemplate launchTemplate = new LaunchTemplate();
        launchTemplate.setLaunchTemplateId("lt-123");
        LaunchTemplate version = new LaunchTemplate();
        version.setLatestVersionNumber("3");
        version.getData().setImageId("ami-version-3");
        version.getData().setInstanceType("t3.micro");
        when(ec2Service.describeLaunchTemplates("us-east-1", List.of("lt-123"), List.of(), Map.of()))
                .thenReturn(List.of(launchTemplate));
        when(ec2Service.describeLaunchTemplateVersions("us-east-1", "lt-123", null, List.of("3")))
                .thenReturn(List.of(version));
        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-launched");
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.runInstances(eq("us-east-1"), eq("ami-version-3"), eq("t3.small"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                eq(List.of()), eq(null), eq(null), eq(null))).thenReturn(reservation);

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-launched", asg.getInstances().getFirst().getInstanceId());
        assertEquals("lt-123", asg.getInstances().getFirst().getLaunchTemplateId());
        assertEquals("3", asg.getInstances().getFirst().getLaunchTemplateVersion());
        assertEquals("t3.small", asg.getInstances().getFirst().getInstanceType());
        verify(ec2Service).runInstances(eq("us-east-1"), eq("ami-version-3"), eq("t3.small"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                eq(List.of()), eq(null), eq(null), eq(null));
    }

    @Test
    void reconcileDeregistersTargetsThatAreNotActiveAsgInstances() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.setTargetGroupARNs(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/app/123"));
        asg.getInstances().add(instance("i-active", "InService"));
        when(ec2Service.isInstanceContainerRunning("i-active")).thenReturn(true);
        when(elbV2Service.describeTargetHealth(asg.getRegion(), asg.getTargetGroupARNs().getFirst(), List.of()))
                .thenReturn(List.of(targetHealth("i-active"), targetHealth("i-stale")));

        reconciler.reconcile(asg);

        ArgumentCaptor<List<TargetDescription>> targets = ArgumentCaptor.captor();
        verify(elbV2Service).deregisterTargets(
                eq(asg.getRegion()),
                eq(asg.getTargetGroupARNs().getFirst()),
                targets.capture());
        assertEquals(1, targets.getValue().size());
        assertEquals("i-stale", targets.getValue().getFirst().getId());
        verify(asgService).saveAutoScalingGroupIfPresent(asg);
        verify(ec2Service, never()).terminateInstances(asg.getRegion(), List.of("i-active"));
    }

    @Test
    void reconcileKeepsPendingInstancesWhileContainerLaunchIsStillInFlight() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.getInstances().add(instance("i-pending", "Pending"));

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-pending");
        ec2Instance.setState(InstanceState.pending());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.describeInstances("us-east-1", List.of("i-pending"), null))
                .thenReturn(List.of(reservation));
        when(ec2Service.isInstanceContainerRunning("i-pending")).thenReturn(false);

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-pending", asg.getInstances().getFirst().getInstanceId());
        verify(ec2Service, never()).runInstances(
                eq("us-east-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconcilePrunesPendingInstancesWhenEc2InstanceIsTerminal() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.getInstances().add(instance("i-dead", "Pending"));

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-dead");
        ec2Instance.setState(InstanceState.terminated());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.describeInstances("us-east-1", List.of("i-dead"), null))
                .thenReturn(List.of(reservation));

        reconciler.reconcile(asg);

        assertEquals(0, asg.getInstances().size());
        verify(asgService).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Removing stale EC2 instance reference(s): [i-dead]"),
                eq("Persisted Auto Scaling state referenced instance containers that are no longer running."),
                eq("Successful"));
    }

    @Test
    void reconcileFailsActiveSsmInvocationsBeforePruningStaleInstance() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        SsmCommandService ssmCommandService = mock(SsmCommandService.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                asgService, ec2Service, elbV2Service, null, ssmCommandService);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.getInstances().add(instance("i-dead", "Pending"));

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-dead");
        ec2Instance.setState(InstanceState.terminated());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.describeInstances("us-east-1", List.of("i-dead"), null))
                .thenReturn(List.of(reservation));
        when(ssmCommandService.failActiveInvocationsForInstances("us-east-1", Set.of("i-dead"), "Undeliverable"))
                .thenReturn(1);

        reconciler.reconcile(asg);

        assertEquals(0, asg.getInstances().size());
        verify(ssmCommandService).failActiveInvocationsForInstances("us-east-1", Set.of("i-dead"), "Undeliverable");
    }

    @Test
    void reconcileDeregistersStaleInstanceFromClassicLoadBalancerBeforePruning() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        ElbClassicService elbClassicService = mock(ElbClassicService.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                asgService, ec2Service, elbV2Service, elbClassicService, null);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.setLoadBalancerNames(List.of("classic-lb"));
        asg.getInstances().add(instance("i-dead", "InService"));
        when(ec2Service.isInstanceContainerRunning("i-dead")).thenReturn(false);

        reconciler.reconcile(asg);

        assertEquals(0, asg.getInstances().size());
        verify(elbClassicService).deregisterInstances(
                eq(asg.getRegion()), eq("classic-lb"), eq(List.of("i-dead")));
    }

    @Test
    void reconcilePromotesPendingAsgInstanceWhenEc2InstanceIsRunning() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.getInstances().add(instance("i-pending", "Pending"));

        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-pending");
        ec2Instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.describeInstances(asg.getRegion(), List.of("i-pending"), null))
                .thenReturn(List.of(reservation));
        when(ec2Service.isInstanceContainerRunning("i-pending")).thenReturn(true);

        reconciler.reconcile(asg);

        assertEquals("InService", asg.getInstances().getFirst().getLifecycleState());
        assertEquals("Healthy", asg.getInstances().getFirst().getHealthStatus());
        verify(asgService).recordActivity(
                eq(asg.getRegion()),
                eq(asg.getAutoScalingGroupName()),
                eq("Launching a new EC2 instance: i-pending"),
                eq("An instance was started in response to a desired capacity change."),
                eq("Successful"));
        verify(asgService, times(2)).saveAutoScalingGroupIfPresent(asg);
    }

    @Test
    void deletedLaunchTemplateDoesNotAbortTheReconcilePass() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        AutoScalingGroup asg = asgWhoseLaunchTemplateLookupFails(ec2Service,
                new AwsException("InvalidLaunchTemplateName.NotFoundException",
                        "The launch template 'app-lt' does not exist.", 400));
        AutoScalingReconciler reconciler =
                new AutoScalingReconciler(asgService, ec2Service, mock(ElbV2Service.class));
        when(asgService.saveAutoScalingGroupIfPresent(asg)).thenReturn(true);

        reconciler.reconcile(asg);

        assertEquals(0, asg.getInstances().size());
        verify(asgService).completeInstanceRefreshIfSettled("us-east-1", "app-asg");
    }

    @Test
    void otherLaunchTemplateErrorsSurfaceRatherThanSilentlySkippingScaling() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        AutoScalingGroup asg = asgWhoseLaunchTemplateLookupFails(ec2Service,
                new AwsException("RequestLimitExceeded", "Request limit exceeded.", 503));
        AutoScalingReconciler reconciler =
                new AutoScalingReconciler(asgService, ec2Service, mock(ElbV2Service.class));

        AwsException thrown = assertThrows(AwsException.class, () -> reconciler.reconcile(asg));

        assertEquals("RequestLimitExceeded", thrown.getErrorCode());
    }

    @Test
    void scaleInTerminationFailureKeepsMembershipAndRecordsFailedActivity() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        stubActivityRecording(asgService);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.setTargetGroupARNs(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/app/123"));
        asg.getInstances().add(instance("i-keep", "InService"));
        when(ec2Service.isInstanceContainerRunning("i-keep")).thenReturn(true);
        when(ec2Service.terminateInstances("us-east-1", List.of("i-keep")))
                .thenThrow(new AwsException("InsufficientInstanceCapacity", "termination refused", 500));

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-keep", asg.getInstances().getFirst().getInstanceId());
        verify(asgService).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Terminating EC2 instance(s): [i-keep]"),
                eq("An instance was terminated in response to a desired capacity change."),
                eq("Failed"));
        verify(asgService).completeActivity("activity-1", "Failed", "termination refused");
        verify(elbV2Service, never()).deregisterTargets(
                eq("us-east-1"), eq(asg.getTargetGroupARNs().getFirst()), anyList());
    }

    @Test
    void repeatedTerminationFailureIsRecordedOnce() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        stubActivityRecording(asgService);
        when(asgService.describeScalingActivities("us-east-1", "app-asg"))
                .thenReturn(List.of(failedActivity("Terminating EC2 instance(s): [i-keep]")));
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.getInstances().add(instance("i-keep", "InService"));
        when(ec2Service.isInstanceContainerRunning("i-keep")).thenReturn(true);
        when(ec2Service.terminateInstances("us-east-1", List.of("i-keep")))
                .thenThrow(new AwsException("InsufficientInstanceCapacity", "termination refused", 500));

        reconciler.reconcile(asg);

        verify(asgService, never()).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Terminating EC2 instance(s): [i-keep]"),
                eq("An instance was terminated in response to a desired capacity change."),
                eq("Failed"));
    }

    @Test
    void scaleInTerminatesAndRemovesTheInstanceOnSuccess() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.setTargetGroupARNs(List.of("arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/app/123"));
        asg.getInstances().add(instance("i-gone", "InService"));
        when(ec2Service.isInstanceContainerRunning("i-gone")).thenReturn(true);

        reconciler.reconcile(asg);

        assertEquals(0, asg.getInstances().size());
        verify(ec2Service).terminateInstances("us-east-1", List.of("i-gone"));
        verify(elbV2Service).deregisterTargets(
                eq("us-east-1"), eq(asg.getTargetGroupARNs().getFirst()), anyList());
        verify(asgService).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Terminating EC2 instance(s): [i-gone]"),
                eq("An instance was terminated in response to a desired capacity change."),
                eq("Successful"));
    }

    @Test
    void terminatingInstanceTerminationFailureKeepsMembershipAndRecordsFailedActivity() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        stubActivityRecording(asgService);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.getInstances().add(instance("i-term", "Terminating"));
        when(ec2Service.terminateInstances("us-east-1", List.of("i-term")))
                .thenThrow(new AwsException("InsufficientInstanceCapacity", "termination refused", 500));
        when(ec2Service.describeInstances("us-east-1", List.of("i-term"), null))
                .thenReturn(List.of(reservation(runningEc2Instance("i-term"))));

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-term", asg.getInstances().getFirst().getInstanceId());
        verify(asgService).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Terminating EC2 instance(s) for refresh: [i-term]"),
                eq("An instance refresh requested replacement of active instances."),
                eq("Failed"));
        verify(asgService).completeActivity("activity-1", "Failed", "termination refused");
        verify(asgService, never()).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Terminating EC2 instance(s) for refresh: [i-term]"),
                eq("An instance refresh requested replacement of active instances."),
                eq("Successful"));
    }

    @Test
    void terminatingInstanceWithMissingEc2RecordIsPrunedAsStale() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);
        asg.getInstances().add(instance("i-term", "Terminating"));
        when(ec2Service.terminateInstances("us-east-1", List.of("i-term")))
                .thenThrow(new AwsException("InvalidInstanceID.NotFound", "not found", 400));
        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId("i-term");
        ec2Instance.setState(InstanceState.terminated());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(ec2Instance));
        when(ec2Service.describeInstances("us-east-1", List.of("i-term"), null))
                .thenReturn(List.of(reservation));

        reconciler.reconcile(asg);

        assertEquals(0, asg.getInstances().size());
        verify(asgService).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Removing stale EC2 instance reference(s): [i-term]"),
                eq("Persisted Auto Scaling state referenced instance containers that are no longer running."),
                eq("Successful"));
        verify(asgService, never()).recordActivity(
                eq("us-east-1"),
                eq("app-asg"),
                eq("Terminating EC2 instance(s) for refresh: [i-term]"),
                eq("An instance refresh requested replacement of active instances."),
                eq("Failed"));
    }

    private static ScalingActivity stubActivityRecording(AutoScalingService asgService) {
        ScalingActivity activity = new ScalingActivity();
        activity.setActivityId("activity-1");
        when(asgService.recordActivity(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(activity);
        return activity;
    }

    private static ScalingActivity failedActivity(String description) {
        ScalingActivity activity = new ScalingActivity();
        activity.setDescription(description);
        activity.setStatusCode("Failed");
        return activity;
    }

    private static Instance runningEc2Instance(String instanceId) {
        Instance ec2Instance = new Instance();
        ec2Instance.setInstanceId(instanceId);
        ec2Instance.setState(InstanceState.running());
        return ec2Instance;
    }

    private static Reservation reservation(Instance instance) {
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        return reservation;
    }

    private static AutoScalingGroup asgWhoseLaunchTemplateLookupFails(Ec2Service ec2Service,
                                                                      AwsException failure) {
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.setLaunchTemplateName("app-lt");
        when(ec2Service.describeLaunchTemplates("us-east-1", List.of(), List.of("app-lt"), Map.of()))
                .thenThrow(failure);
        return asg;
    }

    private static AutoScalingGroup launchTemplateAsg() {
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(1);
        asg.setLaunchTemplateId("lt-123");
        asg.setLaunchTemplateVersion("1");
        return asg;
    }

    private static void stubLaunchTemplate(Ec2Service ec2Service, String imageId, String instanceType) {
        LaunchTemplate launchTemplate = new LaunchTemplate();
        launchTemplate.setLaunchTemplateId("lt-123");
        LaunchTemplate version = new LaunchTemplate();
        version.setLatestVersionNumber("1");
        version.getData().setImageId(imageId);
        version.getData().setInstanceType(instanceType);
        when(ec2Service.describeLaunchTemplates("us-east-1", List.of("lt-123"), List.of(), Map.of()))
                .thenReturn(List.of(launchTemplate));
        when(ec2Service.describeLaunchTemplateVersions("us-east-1", "lt-123", null, List.of("1")))
                .thenReturn(List.of(version));
    }

    private static AsgInstance instance(String lifecycleState) {
        AsgInstance instance = new AsgInstance();
        instance.setLifecycleState(lifecycleState);
        return instance;
    }

    private static AsgInstance instance(String instanceId, String lifecycleState) {
        AsgInstance instance = instance(lifecycleState);
        instance.setInstanceId(instanceId);
        instance.setHealthStatus("Healthy");
        return instance;
    }

    private static TargetHealth targetHealth(String instanceId) {
        TargetDescription target = new TargetDescription();
        target.setId(instanceId);
        TargetHealth health = new TargetHealth();
        health.setTarget(target);
        return health;
    }
}

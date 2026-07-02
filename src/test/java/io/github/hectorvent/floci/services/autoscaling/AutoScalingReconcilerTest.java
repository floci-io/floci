package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.MixedInstancesPolicy;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
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
    void reconcileCompletesSettledInstanceRefreshes() {
        AutoScalingService asgService = mock(AutoScalingService.class);
        Ec2Service ec2Service = mock(Ec2Service.class);
        ElbV2Service elbV2Service = mock(ElbV2Service.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(asgService, ec2Service, elbV2Service);
        AutoScalingGroup asg = new AutoScalingGroup();
        asg.setRegion("us-east-1");
        asg.setAutoScalingGroupName("app-asg");
        asg.setDesiredCapacity(0);

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

        LaunchTemplate launchTemplate = new LaunchTemplate();
        launchTemplate.setLaunchTemplateId("lt-123");
        LaunchTemplate version = new LaunchTemplate();
        version.setLatestVersionNumber("1");
        version.setImageId("ami-version-1");
        version.setInstanceType("t3.micro");
        version.setIamInstanceProfileArn("arn:aws:iam::000000000000:instance-profile/app-profile");
        List<Tag> instanceTags = List.of(new Tag("app.ClusterId", "development"));
        version.setInstanceTags(instanceTags);
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
                eq(instanceTags), eq(null), eq("arn:aws:iam::000000000000:instance-profile/app-profile"))).thenReturn(reservation);

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-launched", asg.getInstances().getFirst().getInstanceId());
        assertEquals("lt-123", asg.getInstances().getFirst().getLaunchTemplateId());
        assertEquals("1", asg.getInstances().getFirst().getLaunchTemplateVersion());
        verify(ec2Service).runInstances(eq("us-east-1"), eq("ami-version-1"), eq("t3.micro"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                eq(instanceTags), eq(null), eq("arn:aws:iam::000000000000:instance-profile/app-profile"));
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
        version.setImageId("ami-version-7");
        version.setInstanceType("t3.micro");
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
                eq(List.of()), eq(null), eq(null))).thenReturn(reservation);

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
        version.setImageId("ami-version-3");
        version.setInstanceType("t3.micro");
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
                eq(List.of()), eq(null), eq(null))).thenReturn(reservation);

        reconciler.reconcile(asg);

        assertEquals(1, asg.getInstances().size());
        assertEquals("i-launched", asg.getInstances().getFirst().getInstanceId());
        assertEquals("lt-123", asg.getInstances().getFirst().getLaunchTemplateId());
        assertEquals("3", asg.getInstances().getFirst().getLaunchTemplateVersion());
        assertEquals("t3.small", asg.getInstances().getFirst().getInstanceType());
        verify(ec2Service).runInstances(eq("us-east-1"), eq("ami-version-3"), eq("t3.small"),
                eq(1), eq(1), eq(null), eq(List.of()), eq(null), eq(null),
                eq(List.of()), eq(null), eq(null));
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
        verify(asgService).saveAutoScalingGroup(asg);
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
                asgService, ec2Service, elbV2Service, ssmCommandService);
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
        verify(asgService, times(2)).saveAutoScalingGroup(asg);
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

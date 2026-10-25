package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.CapacityReservationSpecification;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class AutoScalingReservationOnlyIntegrationTest {
    private static final String ACCOUNT = "753190420009";
    private static final String REGION = "us-east-1";

    @Inject
    Ec2Service ec2;

    @Inject
    AutoScalingService service;

    @Inject
    AutoScalingReconciler reconciler;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateReservationOnlyDoesNotFallBack(boolean available) {
        checkReservationPreference(available, "capacity-reservations-only", null, false, available ? 1 : 0,
                available, null);
    }

    @ParameterizedTest
    @CsvSource({
            "false,none,capacity-reservations-only,false,0,false",
            "true,none,capacity-reservations-only,false,1,true",
            "false,none,capacity-reservations-only,true,0,false",
            "true,none,capacity-reservations-only,true,1,true",
            "true,capacity-reservations-only,none,false,1,false",
            "true,capacity-reservations-only,none,true,1,false",
            "false,capacity-reservations-only,capacity-reservations-first,false,1,false",
            "true,none,capacity-reservations-first,false,1,true",
            "false,capacity-reservations-only,default,false,0,false",
            "true,none,default,false,1,false"
    })
    void groupPreferenceControlsLaunch(boolean available, String templatePreference, String groupPreference,
                                       boolean update, int expectedInstances, boolean expectedReserved) {
        checkReservationPreference(available, templatePreference, groupPreference, update, expectedInstances,
                expectedReserved, null);
    }

    @ParameterizedTest
    @CsvSource({
            "capacity-reservations-only,matching,1,true",
            "capacity-reservations-first,matching,1,true",
            "capacity-reservations-only,inherited-mismatch,0,false",
            "capacity-reservations-first,inherited-mismatch,1,false",
            "capacity-reservations-only,override-template,1,true",
            "none,inherited-mismatch,1,false",
            "capacity-reservations-only,mismatch,0,false",
            "capacity-reservations-first,mismatch,1,false",
            "capacity-reservations-only,missing,0,false",
            "capacity-reservations-first,missing,0,false",
            "capacity-reservations-only,resource-group,0,false",
            "capacity-reservations-first,resource-group,0,false"
    })
    void groupTargetsDoNotConsumeUnrelatedOpenCapacity(String preference, String targets,
                                                       int expectedInstances, boolean expectedReserved) {
        checkReservationPreference(true, "open", preference, false, expectedInstances, expectedReserved, targets);
    }

    private void checkReservationPreference(boolean available, String templatePreference, String groupPreference,
                                            boolean update, int expectedInstances, boolean expectedReserved, String targets) {
        RequestScopes.runAs(ACCOUNT, REGION, () -> {
            CapacityReservation capacity = ec2.createCapacityReservation(REGION, "t3.small", "Linux/UNIX",
                    REGION + "a", null, 1, null, null, null, null, null, null, null, null);
            String groupName = "reservation-only-" + UUID.randomUUID();
            CapacityReservation mismatch = null;
            LaunchTemplate template = null;
            boolean groupCreated = false;
            try {
                if (!available) {
                    ec2.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                }
                LaunchTemplateData data = new LaunchTemplateData();
                data.setImageId("ami-test");
                data.setInstanceType("t3.small");
                LaunchTemplateData.CapacityReservationSpecification specification =
                        new LaunchTemplateData.CapacityReservationSpecification();
                specification.setCapacityReservationPreference(templatePreference);
                data.setCapacityReservationSpecification(specification);
                template = ec2.createLaunchTemplate(REGION, groupName, data, List.of());
                String subnet = ec2.describeSubnets(REGION, List.of(), Map.of()).stream()
                        .filter(value -> (REGION + "a").equals(value.getAvailabilityZone()))
                        .findFirst().orElseThrow().getSubnetId();
                CapacityReservationSpecification groupSpec =
                        new CapacityReservationSpecification();
                groupSpec.setCapacityReservationPreference(groupPreference);
                if (targets != null) {
                    if ("resource-group".equals(targets)) {
                        groupSpec.setCapacityReservationResourceGroupArns(
                                List.of("arn:aws:resource-groups:us-east-1:" + ACCOUNT + ":group/test"));
                    } else if ("missing".equals(targets)) {
                        groupSpec.setCapacityReservationIds(List.of("cr-00000000000000000"));
                    } else {
                        mismatch = ec2.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX",
                                REGION + "a", null, 1, null, null, null, null, null, null, null, null);
                        if ("inherited-mismatch".equals(targets) || "override-template".equals(targets)) {
                            LaunchTemplateData.CapacityReservationTarget templateTarget =
                                    new LaunchTemplateData.CapacityReservationTarget();
                            templateTarget.setCapacityReservationId(mismatch.getCapacityReservationId());
                            specification.setCapacityReservationTarget(templateTarget);
                            ec2.createLaunchTemplateVersion(REGION, template.getLaunchTemplateId(), null,
                                    "1", data, null);
                        }
                        if (!"inherited-mismatch".equals(targets)) {
                            groupSpec.setCapacityReservationIds(List.of(
                                    "matching".equals(targets) || "override-template".equals(targets)
                                            ? capacity.getCapacityReservationId() : mismatch.getCapacityReservationId()));
                        }
                    }
                }
                AsgOptionalFields options = groupPreference == null ? AsgOptionalFields.none()
                        : new AsgOptionalFields(null, null, null, null, null, null, null, groupSpec);
                AutoScalingGroup group = service.createAutoScalingGroup(REGION, groupName, null,
                        template.getLaunchTemplateId(), null, "$Latest", null, 0, 1, update ? 0 : 1, 300,
                        List.of(REGION + "a"), List.of(subnet), List.of(), List.of(), "EC2", 0,
                        List.of("Default"), Map.of(), Map.of(), update ? AsgOptionalFields.none() : options);
                groupCreated = true;
                if (update) {
                    service.updateAutoScalingGroup(REGION, groupName, null, null, null, null, null,
                            null, null, 1, null, null, null, null, null, null, options);
                }
                reconciler.reconcile(group);
                AutoScalingGroup current = service.describeAutoScalingGroups(REGION, List.of(groupName)).getFirst();
                assertEquals(expectedInstances, current.getInstances().size());
                assertEquals(expectedInstances, ec2.describeInstances(REGION, List.of(), Map.of()).stream()
                        .flatMap(value -> value.getInstances().stream())
                        .filter(value -> !"terminated".equals(value.getState().getName())).count());
                if (available) {
                    assertEquals(expectedReserved ? List.of(current.getInstances().getFirst().getInstanceId()) : List.of(),
                            ec2.describeCapacityReservations(REGION, List.of(capacity.getCapacityReservationId()),
                                    Map.of()).getFirst().getInstanceIds());
                }
            } finally {
                if (groupCreated) {
                    service.deleteAutoScalingGroup(REGION, groupName, true);
                }
                if (template != null) {
                    ec2.deleteLaunchTemplate(REGION, template.getLaunchTemplateId(), null);
                }
                if (mismatch != null) {
                    ec2.cancelCapacityReservation(REGION, mismatch.getCapacityReservationId());
                }
                if (available) {
                    ec2.cancelCapacityReservation(REGION, capacity.getCapacityReservationId());
                }
            }
        });
    }
}

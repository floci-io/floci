package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.redshift.RedshiftClient;
import software.amazon.awssdk.services.redshift.model.CreateScheduledActionResponse;
import software.amazon.awssdk.services.redshift.model.RedshiftException;
import software.amazon.awssdk.services.redshift.model.ScheduledAction;
import software.amazon.awssdk.services.redshift.model.ScheduledActionAlreadyExistsException;
import software.amazon.awssdk.services.redshift.model.ScheduledActionNotFoundException;
import software.amazon.awssdk.services.redshift.model.ScheduledActionState;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Scheduled actions that pause and resume a provisioned Redshift cluster, through the AWS SDK for Java v2. */
@DisplayName("Redshift scheduled actions")
class RedshiftScheduledActionTest {

    private static final String OWNER = "111111111111";
    private static final String OTHER = "222222222222";
    private static final String ROLE = "arn:aws:iam::" + OWNER + ":role/redshift-scheduler";
    private static final String SUFFIX = UUID.randomUUID().toString().substring(0, 8);
    private static final String CLUSTER = "sched-c-" + SUFFIX;
    private static final String PAUSE = "sched-pause-" + SUFFIX;
    private static final String RESUME = "sched-resume-" + SUFFIX;

    private static RedshiftClient owner;
    private static RedshiftClient other;

    @BeforeAll
    static void setUp() {
        owner = TestFixtures.redshiftClient(OWNER);
        other = TestFixtures.redshiftClient(OTHER);
        owner.createCluster(r -> r.clusterIdentifier(CLUSTER).nodeType("dc2.large")
                .masterUsername("admin").masterUserPassword("Secret123"));
    }

    @AfterAll
    static void cleanup() {
        for (Runnable step : new Runnable[] {
                () -> owner.deleteScheduledAction(r -> r.scheduledActionName(PAUSE)),
                () -> owner.deleteScheduledAction(r -> r.scheduledActionName(RESUME)),
                () -> owner.deleteCluster(r -> r.clusterIdentifier(CLUSTER).skipFinalClusterSnapshot(true))}) {
            try {
                step.run();
            } catch (Exception ignored) {
                // Best effort: the test may have failed before creating the resource.
            }
        }
        owner.close();
        other.close();
    }

    @Test
    @DisplayName("create, describe, modify and delete a pause and a resume action")
    void lifecycle() {
        CreateScheduledActionResponse created = owner.createScheduledAction(r -> r.scheduledActionName(PAUSE)
                .iamRole(ROLE).schedule("cron(0 22 * * ? *)").scheduledActionDescription("pause at night")
                .targetAction(t -> t.pauseCluster(p -> p.clusterIdentifier(CLUSTER))));
        assertThat(created.state()).isEqualTo(ScheduledActionState.ACTIVE);
        assertThat(created.targetAction().pauseCluster().clusterIdentifier()).isEqualTo(CLUSTER);
        assertThat(created.nextInvocations()).isNotEmpty();

        owner.createScheduledAction(r -> r.scheduledActionName(RESUME).iamRole(ROLE)
                .schedule("cron(0 6 * * ? *)")
                .targetAction(t -> t.resumeCluster(p -> p.clusterIdentifier(CLUSTER))));
        assertThatThrownBy(() -> owner.createScheduledAction(r -> r.scheduledActionName(PAUSE).iamRole(ROLE)
                .schedule("cron(0 22 * * ? *)")
                .targetAction(t -> t.pauseCluster(p -> p.clusterIdentifier(CLUSTER)))))
                .isInstanceOf(ScheduledActionAlreadyExistsException.class);

        owner.modifyScheduledAction(r -> r.scheduledActionName(RESUME).enable(false));

        List<ScheduledAction> disabled = owner.describeScheduledActions(r -> r.active(false)
                .filters(f -> f.name("cluster-identifier").values(CLUSTER))).scheduledActions();
        assertThat(disabled).extracting(ScheduledAction::scheduledActionName).containsExactly(RESUME);
        List<ScheduledAction> enabled = owner.describeScheduledActions(r -> r.active(true)
                .filters(f -> f.name("cluster-identifier").values(CLUSTER))).scheduledActions();
        assertThat(enabled).extracting(ScheduledAction::scheduledActionName).containsExactly(PAUSE);

        owner.deleteScheduledAction(r -> r.scheduledActionName(PAUSE));
        owner.deleteScheduledAction(r -> r.scheduledActionName(RESUME));
        assertThatThrownBy(() -> owner.deleteScheduledAction(r -> r.scheduledActionName(PAUSE)))
                .isInstanceOf(ScheduledActionNotFoundException.class);
    }

    @Test
    @DisplayName("another account does not see or delete the action")
    void actionsAreAccountScoped() {
        String name = "sched-iso-" + SUFFIX;
        owner.createScheduledAction(r -> r.scheduledActionName(name).iamRole(ROLE)
                .schedule("at(2099-01-01T00:00:00)")
                .targetAction(t -> t.pauseCluster(p -> p.clusterIdentifier(CLUSTER))));
        try {
            assertThat(other.describeScheduledActions(r -> r.scheduledActionName(name)).scheduledActions()).isEmpty();
            assertThatThrownBy(() -> other.deleteScheduledAction(r -> r.scheduledActionName(name)))
                    .isInstanceOf(ScheduledActionNotFoundException.class);
        } finally {
            owner.deleteScheduledAction(r -> r.scheduledActionName(name));
        }
    }

    @Test
    @DisplayName("a resize target and a malformed schedule are refused")
    void unsupportedTargetAndInvalidSchedule() {
        assertThatThrownBy(() -> owner.createScheduledAction(r -> r.scheduledActionName("sched-resize-" + SUFFIX)
                .iamRole(ROLE).schedule("cron(0 22 * * ? *)")
                .targetAction(t -> t.resizeCluster(c -> c.clusterIdentifier(CLUSTER).numberOfNodes(2)))))
                .isInstanceOf(RedshiftException.class)
                .hasMessageContaining("ResizeCluster");
        assertThatThrownBy(() -> owner.createScheduledAction(r -> r.scheduledActionName("sched-bad-" + SUFFIX)
                .iamRole(ROLE).schedule("rate(1 hour)")
                .targetAction(t -> t.pauseCluster(p -> p.clusterIdentifier(CLUSTER)))))
                .isInstanceOf(RedshiftException.class)
                .satisfies(e -> assertThat(((RedshiftException) e).awsErrorDetails().errorCode())
                        .isEqualTo("InvalidSchedule"));
    }
}

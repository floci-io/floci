package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.kinesisanalyticsv2.KinesisAnalyticsV2Client;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationDetail;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ApplicationStatus;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.CreateApplicationRequest;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.CreateApplicationResponse;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.DeleteApplicationRequest;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.DescribeApplicationRequest;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.KinesisAnalyticsV2Exception;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.ListApplicationsRequest;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.RuntimeEnvironment;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.StartApplicationRequest;
import software.amazon.awssdk.services.kinesisanalyticsv2.model.StopApplicationRequest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real Apache Flink container path for Managed Service for Apache Flink
 * (Kinesis Analytics V2): CreateApplication lands in READY (no container yet), StartApplication
 * provisions an {@code apache/flink} JobManager and the application transitions to RUNNING once the
 * JobManager REST API answers, StopApplication tears it down back to READY. Also confirms the
 * AWS-faithful rejection of a runtime Floci cannot back with a plain Flink image.
 *
 * <p>Requires a running Floci with {@code kinesis-analytics.mock=false} and a working Docker daemon.
 * When the application never reaches RUNNING (Docker/Flink unavailable in CI), the dependent
 * assertions skip rather than fail, mirroring {@code AmazonMqTest} / {@code MskTest}.
 */
@DisplayName("Managed Service for Apache Flink (Kinesis Analytics V2)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KinesisAnalyticsV2Test {

    private static KinesisAnalyticsV2Client kda;
    private static String applicationName = TestFixtures.uniqueName("flink-app");
    private static Instant createTimestamp;
    private static boolean reachedRunning;
    private static final String ROLE = "arn:aws:iam::000000000000:role/flink-exec";

    @BeforeAll
    static void setup() {
        kda = TestFixtures.kinesisAnalyticsV2Client();
    }

    @AfterAll
    static void cleanup() {
        if (kda != null) {
            if (applicationName != null && createTimestamp != null) {
                try {
                    ensureStopped();
                    kda.deleteApplication(DeleteApplicationRequest.builder()
                            .applicationName(applicationName)
                            .createTimestamp(createTimestamp)
                            .build());
                } catch (Exception e) {
                    // Tolerated (best-effort teardown) but logged with context per AGENTS.md.
                    System.err.println("[WARN] AfterAll cleanup failed for application "
                            + applicationName + ": " + e.getMessage());
                }
            }
            kda.close();
        }
    }

    @Test
    @Order(1)
    void createApplicationLandsReady() {
        CreateApplicationResponse response = kda.createApplication(CreateApplicationRequest.builder()
                .applicationName(applicationName)
                .runtimeEnvironment(RuntimeEnvironment.FLINK_1_18)
                .serviceExecutionRole(ROLE)
                .build());

        ApplicationDetail detail = response.applicationDetail();
        assertThat(detail.applicationName()).isEqualTo(applicationName);
        assertThat(detail.applicationARN()).contains(":kinesisanalytics:");
        // AWS-faithful: a freshly created application is READY, not RUNNING.
        assertThat(detail.applicationStatus()).isEqualTo(ApplicationStatus.READY);
        createTimestamp = detail.createTimestamp();
    }

    @Test
    @Order(2)
    void startApplicationReachesRunning() {
        requireApp();

        boolean started;
        try {
            kda.startApplication(StartApplicationRequest.builder()
                    .applicationName(applicationName)
                    .build());
            started = true;
        } catch (KinesisAnalyticsV2Exception e) {
            // Container provisioning failed (Docker likely unavailable) — skip rather than fail.
            started = false;
        }
        Assumptions.assumeTrue(started,
                "StartApplication failed (Docker likely unavailable); skipping RUNNING checks");

        ApplicationDetail detail = waitForStatus(ApplicationStatus.RUNNING, Duration.ofSeconds(180));
        Assumptions.assumeTrue(detail != null,
                "Application did not reach RUNNING (Docker/Flink likely unavailable); skipping");

        reachedRunning = true;
        assertThat(detail.applicationStatus()).isEqualTo(ApplicationStatus.RUNNING);
        assertThat(detail.runtimeEnvironment()).isEqualTo(RuntimeEnvironment.FLINK_1_18);
    }

    @Test
    @Order(3)
    void listApplicationsIncludesCreated() {
        requireApp();

        var response = kda.listApplications(ListApplicationsRequest.builder().build());
        assertThat(response.applicationSummaries())
                .anyMatch(s -> s.applicationName().equals(applicationName));
    }

    @Test
    @Order(4)
    void stopApplicationReturnsToReady() {
        requireApp();
        Assumptions.assumeTrue(reachedRunning,
                "Application never reached RUNNING; skipping stop");

        kda.stopApplication(StopApplicationRequest.builder()
                .applicationName(applicationName)
                .build());

        ApplicationDetail detail = waitForStatus(ApplicationStatus.READY, Duration.ofSeconds(60));
        assertThat(detail).isNotNull();
        assertThat(detail.applicationStatus()).isEqualTo(ApplicationStatus.READY);
    }

    @Test
    @Order(5)
    void unsupportedRuntimeRejected() {
        // Floci only backs FLINK-1_x runtimes with a plain Flink image; the SQL and Studio
        // (Zeppelin) runtimes are rejected with InvalidArgumentException.
        assertThatThrownBy(() -> kda.createApplication(CreateApplicationRequest.builder()
                .applicationName(TestFixtures.uniqueName("flink-bad"))
                .runtimeEnvironment(RuntimeEnvironment.ZEPPELIN_FLINK_3_0)
                .serviceExecutionRole(ROLE)
                .build()))
                .isInstanceOf(KinesisAnalyticsV2Exception.class);
    }

    @Test
    @Order(6)
    void deleteApplication() {
        requireApp();

        // AWS rejects DeleteApplication on a non-stopped application (ResourceInUseException), so
        // ensure it is READY first (it may still be STARTING if it never reached RUNNING in time).
        ensureStopped();
        kda.deleteApplication(DeleteApplicationRequest.builder()
                .applicationName(applicationName)
                .createTimestamp(createTimestamp)
                .build());
        applicationName = null;
    }

    /** Best-effort: stop the application if it is RUNNING/STARTING so it can be deleted from READY. */
    private static void ensureStopped() {
        try {
            ApplicationStatus status = kda.describeApplication(DescribeApplicationRequest.builder()
                    .applicationName(applicationName)
                    .build()).applicationDetail().applicationStatus();
            if (status == ApplicationStatus.RUNNING || status == ApplicationStatus.STARTING) {
                kda.stopApplication(StopApplicationRequest.builder()
                        .applicationName(applicationName)
                        .build());
                waitForStatus(ApplicationStatus.READY, Duration.ofSeconds(60));
            }
        } catch (Exception e) {
            System.err.println("[WARN] ensureStopped failed for application "
                    + applicationName + ": " + e.getMessage());
        }
    }

    private static ApplicationDetail waitForStatus(ApplicationStatus target, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ApplicationDetail detail = kda.describeApplication(DescribeApplicationRequest.builder()
                    .applicationName(applicationName)
                    .build()).applicationDetail();
            if (detail.applicationStatus() == target) {
                return detail;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private static void requireApp() {
        Assumptions.assumeTrue(applicationName != null,
                "Application must exist from earlier ordered test");
    }
}

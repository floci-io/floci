package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BadRequestException;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.CancelExportJobRequest;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.CreateExportJobRequest;
import software.amazon.awssdk.services.sesv2.model.DataFormat;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.ExportDataSource;
import software.amazon.awssdk.services.sesv2.model.ExportDestination;
import software.amazon.awssdk.services.sesv2.model.ExportSourceType;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.GetExportJobRequest;
import software.amazon.awssdk.services.sesv2.model.GetExportJobResponse;
import software.amazon.awssdk.services.sesv2.model.ListExportJobsRequest;
import software.amazon.awssdk.services.sesv2.model.ListExportJobsResponse;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.MessageInsightsDataSource;
import software.amazon.awssdk.services.sesv2.model.PutAccountVdmAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.VdmAttributes;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for the SES v2 export jobs against a live Floci instance: the Virtual
 * Deliverability Manager gate, which answers BadRequestException here rather than the
 * NotFoundException the other two gated operations use, the refusal of a caller-chosen S3Url, a
 * job followed to completion with its presigned URL and its echoed data source, and the two
 * server-side checks the SDK cannot catch on the client.
 */
@DisplayName("SES v2 Export Jobs")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesExportJobTest {

    private static final String SENDER = "compat-export@example.com";

    private static SesV2Client sesV2;
    private static VdmAttributes originalVdm;
    private static String completedJobId;

    @BeforeAll
    static void setup() {
        sesV2 = TestFixtures.sesV2Client();
        GetAccountResponse account = sesV2.getAccount(GetAccountRequest.builder().build());
        originalVdm = account.vdmAttributes();
        quietly(() -> sesV2.deleteEmailIdentity(
                DeleteEmailIdentityRequest.builder().emailIdentity(SENDER).build()));
        setVdmEnabled(false);
        sesV2.createEmailIdentity(CreateEmailIdentityRequest.builder().emailIdentity(SENDER).build());
    }

    @AfterAll
    static void cleanup() {
        if (sesV2 == null) {
            return;
        }
        quietly(() -> sesV2.deleteEmailIdentity(
                DeleteEmailIdentityRequest.builder().emailIdentity(SENDER).build()));
        quietly(SesExportJobTest::restoreVdm);
        sesV2.close();
    }

    @Test
    @Order(1)
    @DisplayName("the VDM gate is a BadRequest here, not the NotFound the other gated operations use")
    void vdmGateIsABadRequest() {
        assertThatThrownBy(() -> sesV2.createExportJob(insightsExport(null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Virtual Deliverability Manager");
    }

    @Test
    @Order(2)
    @DisplayName("a caller-chosen destination is refused")
    void customDestinationIsRefused() {
        setVdmEnabled(true);

        assertThatThrownBy(() -> sesV2.createExportJob(insightsExport("s3://mine/prefix/")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Providing a custom S3 URL is not supported");
    }

    @Test
    @Order(3)
    @DisplayName("a job runs to completion and carries a presigned URL and its own data source")
    void jobCompletesAndCarriesItsDestination() {
        send();
        completedJobId = sesV2.createExportJob(insightsExport(null)).jobId();
        assertThat(completedJobId).isNotBlank();

        GetExportJobResponse job = awaitTerminal(completedJobId);

        assertThat(job.jobStatusAsString()).isEqualTo("COMPLETED");
        assertThat(job.exportSourceType()).isEqualTo(ExportSourceType.MESSAGE_INSIGHTS);
        assertThat(job.exportDestination().dataFormat()).isEqualTo(DataFormat.CSV);
        assertThat(job.exportDestination().s3Url()).isNotBlank();
        // The union round-trips through the SDK, which is what an integration test asserting raw
        // JSON cannot show.
        assertThat(job.exportDataSource().messageInsightsDataSource()).isNotNull();
        assertThat(job.exportDataSource().metricsDataSource()).isNull();
        assertThat(job.createdTimestamp()).isNotNull();
        assertThat(job.completedTimestamp()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("the list reports the job and paginates")
    void listReportsTheJob() {
        ListExportJobsResponse all = sesV2.listExportJobs(ListExportJobsRequest.builder()
                .exportSourceType(ExportSourceType.MESSAGE_INSIGHTS).build());
        assertThat(all.exportJobs()).isNotEmpty();
        assertThat(all.exportJobs()).allSatisfy(summary ->
                assertThat(summary.exportSourceType()).isEqualTo(ExportSourceType.MESSAGE_INSIGHTS));

        ListExportJobsResponse firstPage = sesV2.listExportJobs(
                ListExportJobsRequest.builder().pageSize(1).build());
        assertThat(firstPage.exportJobs()).hasSize(1);
    }

    @Test
    @Order(5)
    @DisplayName("a finished job cannot be cancelled")
    void finishedJobCannotBeCancelled() {
        assertThatThrownBy(() -> sesV2.cancelExportJob(
                CancelExportJobRequest.builder().jobId(completedJobId).build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Cannot cancel an export job that has a <COMPLETED> status.");
    }

    @Test
    @Order(6)
    @DisplayName("an unknown job id is a BadRequest rather than a NotFound")
    void unknownJobIsABadRequest() {
        assertThatThrownBy(() -> sesV2.getExportJob(
                GetExportJobRequest.builder().jobId("no-such-job").build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid JobId");
    }

    private static CreateExportJobRequest insightsExport(String s3Url) {
        ExportDestination.Builder destination = ExportDestination.builder().dataFormat(DataFormat.CSV);
        if (s3Url != null) {
            destination.s3Url(s3Url);
        }
        return CreateExportJobRequest.builder()
                .exportDataSource(ExportDataSource.builder()
                        .messageInsightsDataSource(MessageInsightsDataSource.builder()
                                .startDate(Instant.now().minus(Duration.ofDays(1)))
                                .endDate(Instant.now().plus(Duration.ofDays(1)))
                                .build())
                        .build())
                .exportDestination(destination.build())
                .build();
    }

    private static GetExportJobResponse awaitTerminal(String jobId) {
        for (int attempt = 0; attempt < 100; attempt++) {
            GetExportJobResponse job = sesV2.getExportJob(
                    GetExportJobRequest.builder().jobId(jobId).build());
            String status = job.jobStatusAsString();
            if (!"PROCESSING".equals(status) && !"CREATED".equals(status)) {
                return job;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the job", e);
            }
        }
        throw new AssertionError("export job " + jobId + " never reached a terminal state");
    }

    private static void send() {
        sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(SENDER)
                .destination(Destination.builder().toAddresses("reader@example.com").build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-export").build())
                                .body(Body.builder()
                                        .text(Content.builder().data("hi").build())
                                        .build())
                                .build())
                        .build())
                .build());
    }

    private static void setVdmEnabled(boolean enabled) {
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(VdmAttributes.builder()
                        .vdmEnabled(enabled ? "ENABLED" : "DISABLED")
                        .build())
                .build());
    }

    private static void restoreVdm() {
        if (originalVdm == null || originalVdm.vdmEnabled() == null) {
            setVdmEnabled(false);
            return;
        }
        sesV2.putAccountVdmAttributes(PutAccountVdmAttributesRequest.builder()
                .vdmAttributes(originalVdm)
                .build());
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            // Best effort teardown: the identity or the VDM state may already be gone.
        }
    }
}

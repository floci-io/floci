package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.BatchGetMetricDataQuery;
import software.amazon.awssdk.services.sesv2.model.BatchGetMetricDataRequest;
import software.amazon.awssdk.services.sesv2.model.BatchGetMetricDataResponse;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.CreateEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.DeleteEmailIdentityRequest;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.GetAccountRequest;
import software.amazon.awssdk.services.sesv2.model.GetAccountResponse;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.Metric;
import software.amazon.awssdk.services.sesv2.model.MetricDataResult;
import software.amazon.awssdk.services.sesv2.model.MetricDimensionName;
import software.amazon.awssdk.services.sesv2.model.MetricNamespace;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.PutAccountVdmAttributesRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;
import software.amazon.awssdk.services.sesv2.model.VdmAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SDK compatibility test for SES v2 BatchGetMetricData against a live Floci instance: the Virtual
 * Deliverability Manager gate, the daily counts a send produces, the shape of an empty series, and
 * the server-side duplicate-identifier check the SDK cannot catch on the client.
 *
 * <p>Every query carries this test's own sender as the EMAIL_IDENTITY dimension, so sends made by
 * other tests in the same region never reach these counts.
 */
@DisplayName("SES v2 BatchGetMetricData")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SesBatchGetMetricDataTest {

    private static final String SENDER = "compat-metrics@example.com";

    private static SesV2Client sesV2;
    private static VdmAttributes originalVdm;

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
        quietly(SesBatchGetMetricDataTest::restoreVdm);
        sesV2.close();
    }

    @Test
    @Order(1)
    @DisplayName("reports the VDM gate before it validates the request")
    void vdmDisabledIsReportedFirst() {
        assertThatThrownBy(() -> sesV2.batchGetMetricData(request(query("gate", Metric.SEND))))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Virtual Deliverability Manager");
    }

    @Test
    @Order(2)
    @DisplayName("counts each outcome once per recipient, in a daily bucket")
    void countsEachOutcome() {
        setVdmEnabled(true);
        send("user@example.com");
        send("bounce@simulator.amazonses.com");
        send("complaint@simulator.amazonses.com");

        BatchGetMetricDataResponse response = sesV2.batchGetMetricData(request(
                query("send", Metric.SEND),
                query("delivery", Metric.DELIVERY),
                query("bounce", Metric.PERMANENT_BOUNCE),
                query("complaint", Metric.COMPLAINT),
                query("delivered", Metric.DELIVERY_COMPLAINT)));

        assertThat(response.errors()).isEmpty();
        assertThat(total(response, "send")).isEqualTo(3L);
        assertThat(total(response, "delivery")).isEqualTo(2L);
        assertThat(total(response, "bounce")).isEqualTo(1L);
        assertThat(total(response, "complaint")).isEqualTo(1L);
        assertThat(total(response, "delivered")).isEqualTo(1L);
        // The SDK deserializes the buckets as instants, which is the shape the wire has to carry.
        assertThat(result(response, "send").timestamps()).isNotEmpty()
                .allSatisfy(timestamp -> assertThat(timestamp).isNotNull());
    }

    @Test
    @Order(3)
    @DisplayName("a metric with no data keeps its members rather than being omitted")
    void emptySeriesKeepsItsMembers() {
        BatchGetMetricDataResponse response = sesV2.batchGetMetricData(request(
                query("open", Metric.OPEN), query("transient", Metric.TRANSIENT_BOUNCE)));

        assertThat(response.results()).hasSize(2);
        assertThat(result(response, "open").timestamps()).isEmpty();
        assertThat(result(response, "open").values()).isEmpty();
        assertThat(result(response, "transient").values()).isEmpty();
        assertThat(response.errors()).isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("TENANT_NAME is accepted although this suite's SDK version lacks the constant")
    void tenantDimensionIsAccepted() {
        // The string-keyed builder sends the wire value directly, which is the point here: the
        // typed enum would prove the SDK knows the constant, not that Floci accepts the key.
        BatchGetMetricDataResponse response = sesV2.batchGetMetricData(
                BatchGetMetricDataRequest.builder()
                        .queries(BatchGetMetricDataQuery.builder()
                                .id("tenant")
                                .namespace(MetricNamespace.VDM)
                                .metric(Metric.SEND)
                                .dimensionsWithStrings(Map.of("TENANT_NAME", "no-such-tenant"))
                                .startDate(Instant.now().minus(Duration.ofDays(1)))
                                .endDate(Instant.now().plus(Duration.ofDays(1)))
                                .build())
                        .build());

        assertThat(response.errors()).isEmpty();
        assertThat(result(response, "tenant").values()).isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("a duplicate query identifier is rejected by the service")
    void duplicateIdentifierIsRejected() {
        assertThatThrownBy(() -> sesV2.batchGetMetricData(
                request(query("same", Metric.SEND), query("same", Metric.DELIVERY))))
                .isInstanceOf(SesV2Exception.class)
                .hasMessageContaining("All queries must have a unique identifier.");
    }

    @Test
    @Order(6)
    @DisplayName("an interval longer than sixty days is rejected")
    void intervalCapIsEnforced() {
        BatchGetMetricDataQuery wide = BatchGetMetricDataQuery.builder()
                .id("wide")
                .namespace(MetricNamespace.VDM)
                .metric(Metric.SEND)
                .startDate(Instant.now().minus(Duration.ofDays(61)))
                .endDate(Instant.now())
                .build();

        assertThatThrownBy(() -> sesV2.batchGetMetricData(
                BatchGetMetricDataRequest.builder().queries(wide).build()))
                .isInstanceOf(SesV2Exception.class)
                .hasMessageContaining("can request at most 60 days");
    }

    private static BatchGetMetricDataRequest request(BatchGetMetricDataQuery... queries) {
        return BatchGetMetricDataRequest.builder().queries(queries).build();
    }

    private static BatchGetMetricDataQuery query(String id, Metric metric) {
        return BatchGetMetricDataQuery.builder()
                .id(id)
                .namespace(MetricNamespace.VDM)
                .metric(metric)
                .dimensions(Map.of(MetricDimensionName.EMAIL_IDENTITY, SENDER))
                .startDate(Instant.now().minus(Duration.ofDays(1)))
                .endDate(Instant.now().plus(Duration.ofDays(1)))
                .build();
    }

    private static MetricDataResult result(BatchGetMetricDataResponse response, String id) {
        return response.results().stream()
                .filter(candidate -> id.equals(candidate.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no result carried the id " + id));
    }

    private static long total(BatchGetMetricDataResponse response, String id) {
        List<Long> values = result(response, id).values();
        return values.stream().mapToLong(Long::longValue).sum();
    }

    private static void send(String destination) {
        sesV2.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(SENDER)
                .destination(Destination.builder().toAddresses(destination).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().data("compat-metrics").build())
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

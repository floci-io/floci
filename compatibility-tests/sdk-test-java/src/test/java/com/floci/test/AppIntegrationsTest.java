package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.appintegrations.AppIntegrationsClient;
import software.amazon.awssdk.services.appintegrations.model.CreateDataIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.CreateEventIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.GetDataIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.GetEventIntegrationResponse;
import software.amazon.awssdk.services.appintegrations.model.ListDataIntegrationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ListEventIntegrationAssociationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ListEventIntegrationsResponse;
import software.amazon.awssdk.services.appintegrations.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Amazon AppIntegrations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppIntegrationsTest {

    private static AppIntegrationsClient appIntegrations;
    private static String eventIntegrationName;
    private static String eventIntegrationArn;
    private static String dataIntegrationName;
    private static String dataIntegrationId;
    private static String dataIntegrationArn;

    @BeforeAll
    static void setup() {
        appIntegrations = TestFixtures.appIntegrationsClient();
        long stamp = System.currentTimeMillis();
        eventIntegrationName = "sdk-test-event-integration-" + stamp;
        dataIntegrationName = "sdk-test-data-integration-" + stamp;
    }

    @AfterAll
    static void cleanup() {
        if (appIntegrations == null) {
            return;
        }
        if (eventIntegrationArn != null) {
            try {
                appIntegrations.deleteEventIntegration(r -> r.name(eventIntegrationName));
            } catch (RuntimeException e) {
                System.out.println("AppIntegrations cleanup skipped the event integration: " + e);
            }
        }
        if (dataIntegrationId != null) {
            try {
                appIntegrations.deleteDataIntegration(r -> r.dataIntegrationIdentifier(dataIntegrationId));
            } catch (RuntimeException e) {
                System.out.println("AppIntegrations cleanup skipped the data integration: " + e);
            }
        }
        appIntegrations.close();
    }

    @Test
    @Order(1)
    void createEventIntegration() {
        CreateEventIntegrationResponse response = appIntegrations.createEventIntegration(r -> r
                .name(eventIntegrationName)
                .description("partner events")
                .eventBridgeBus("sdk-test-bus")
                .eventFilter(f -> f.source("aws.partner/example.com/1234"))
                .tags(Map.of("team", "data")));

        eventIntegrationArn = response.eventIntegrationArn();

        assertThat(eventIntegrationArn)
                .contains(":app-integrations:")
                .contains(":event-integration/" + eventIntegrationName);
    }

    @Test
    @Order(2)
    void getEventIntegration() {
        GetEventIntegrationResponse response = appIntegrations.getEventIntegration(r -> r
                .name(eventIntegrationName));

        assertThat(response.name()).isEqualTo(eventIntegrationName);
        assertThat(response.description()).isEqualTo("partner events");
        assertThat(response.eventIntegrationArn()).isEqualTo(eventIntegrationArn);
        assertThat(response.eventBridgeBus()).isEqualTo("sdk-test-bus");
        assertThat(response.eventFilter().source()).isEqualTo("aws.partner/example.com/1234");
        assertThat(response.tags()).containsEntry("team", "data");
    }

    @Test
    @Order(3)
    void listEventIntegrations() {
        ListEventIntegrationsResponse response = appIntegrations.listEventIntegrations(r -> { });

        assertThat(response.eventIntegrations()).anySatisfy(integration -> {
            assertThat(integration.name()).isEqualTo(eventIntegrationName);
            assertThat(integration.eventIntegrationArn()).isEqualTo(eventIntegrationArn);
            assertThat(integration.eventBridgeBus()).isEqualTo("sdk-test-bus");
        });
    }

    @Test
    @Order(4)
    void updateEventIntegrationDescription() {
        appIntegrations.updateEventIntegration(r -> r
                .name(eventIntegrationName)
                .description("partner events, revised"));

        assertThat(appIntegrations.getEventIntegration(r -> r.name(eventIntegrationName)).description())
                .isEqualTo("partner events, revised");
    }

    /**
     * Associations are written by the consuming service, so a fresh integration reports none.
     * The call still has to deserialize, which is what pins the wire member name.
     */
    @Test
    @Order(5)
    void listEventIntegrationAssociationsIsEmpty() {
        ListEventIntegrationAssociationsResponse response = appIntegrations
                .listEventIntegrationAssociations(r -> r.eventIntegrationName(eventIntegrationName));

        assertThat(response.eventIntegrationAssociations()).isEmpty();
    }

    @Test
    @Order(6)
    void createDataIntegration() {
        CreateDataIntegrationResponse response = appIntegrations.createDataIntegration(r -> r
                .name(dataIntegrationName)
                .description("salesforce pull")
                .kmsKey("arn:aws:kms:us-east-1:000000000000:key/abc")
                .sourceURI("Salesforce://AppFlow/test")
                .scheduleConfig(s -> s
                        .scheduleExpression("rate(1 hour)")
                        .firstExecutionFrom("1439788800000")
                        .object("Account"))
                .fileConfiguration(f -> f.folders(List.of("/home/data")))
                .tags(Map.of("team", "data")));

        dataIntegrationId = response.id();
        dataIntegrationArn = response.arn();

        assertThat(dataIntegrationId).isNotBlank();
        assertThat(dataIntegrationArn)
                .contains(":app-integrations:")
                .contains(":data-integration/" + dataIntegrationId);
        assertThat(response.name()).isEqualTo(dataIntegrationName);
        assertThat(response.kmsKey()).isEqualTo("arn:aws:kms:us-east-1:000000000000:key/abc");
        assertThat(response.sourceURI()).isEqualTo("Salesforce://AppFlow/test");
        assertThat(response.scheduleConfiguration().scheduleExpression()).isEqualTo("rate(1 hour)");
        assertThat(response.fileConfiguration().folders()).containsExactly("/home/data");
        assertThat(response.tags()).containsEntry("team", "data");
    }

    @Test
    @Order(7)
    void getDataIntegrationByIdAndByArn() {
        GetDataIntegrationResponse byId = appIntegrations.getDataIntegration(r -> r
                .identifier(dataIntegrationId));

        assertThat(byId.id()).isEqualTo(dataIntegrationId);
        assertThat(byId.arn()).isEqualTo(dataIntegrationArn);
        assertThat(byId.description()).isEqualTo("salesforce pull");
        assertThat(byId.scheduleConfiguration().firstExecutionFrom()).isEqualTo("1439788800000");
        assertThat(byId.scheduleConfiguration().object()).isEqualTo("Account");

        assertThat(appIntegrations.getDataIntegration(r -> r.identifier(dataIntegrationArn)).id())
                .isEqualTo(dataIntegrationId);
    }

    @Test
    @Order(8)
    void listDataIntegrations() {
        ListDataIntegrationsResponse response = appIntegrations.listDataIntegrations(r -> { });

        assertThat(response.dataIntegrations()).anySatisfy(summary -> {
            assertThat(summary.name()).isEqualTo(dataIntegrationName);
            assertThat(summary.arn()).isEqualTo(dataIntegrationArn);
            assertThat(summary.sourceURI()).isEqualTo("Salesforce://AppFlow/test");
        });
    }

    @Test
    @Order(9)
    void updateDataIntegrationDescription() {
        appIntegrations.updateDataIntegration(r -> r
                .identifier(dataIntegrationId)
                .description("salesforce pull, revised"));

        assertThat(appIntegrations.getDataIntegration(r -> r.identifier(dataIntegrationId)).description())
                .isEqualTo("salesforce pull, revised");
    }

    @Test
    @Order(10)
    void tagRoundTripOnBothIntegrationTypes() {
        for (String arn : List.of(eventIntegrationArn, dataIntegrationArn)) {
            appIntegrations.tagResource(r -> r.resourceArn(arn).tags(Map.of("env", "test")));

            assertThat(appIntegrations.listTagsForResource(r -> r.resourceArn(arn)).tags())
                    .containsEntry("team", "data")
                    .containsEntry("env", "test");

            appIntegrations.untagResource(r -> r.resourceArn(arn).tagKeys("env"));

            assertThat(appIntegrations.listTagsForResource(r -> r.resourceArn(arn)).tags())
                    .containsEntry("team", "data")
                    .doesNotContainKey("env");
        }
    }

    @Test
    @Order(11)
    void deleteBothIntegrationsThenGetThrowsResourceNotFound() {
        appIntegrations.deleteEventIntegration(r -> r.name(eventIntegrationName));
        appIntegrations.deleteDataIntegration(r -> r.dataIntegrationIdentifier(dataIntegrationId));

        assertThatThrownBy(() -> appIntegrations.getEventIntegration(r -> r.name(eventIntegrationName)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> appIntegrations.getDataIntegration(r -> r.identifier(dataIntegrationId)))
                .isInstanceOf(ResourceNotFoundException.class);

        eventIntegrationArn = null;
        dataIntegrationId = null;
    }
}

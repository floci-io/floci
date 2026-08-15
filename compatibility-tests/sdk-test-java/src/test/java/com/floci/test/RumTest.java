package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.rum.RumClient;
import software.amazon.awssdk.services.rum.model.AppMonitorSummary;
import software.amazon.awssdk.services.rum.model.CreateAppMonitorResponse;
import software.amazon.awssdk.services.rum.model.GetAppMonitorResponse;
import software.amazon.awssdk.services.rum.model.ListAppMonitorsResponse;
import software.amazon.awssdk.services.rum.model.ResourceNotFoundException;
import software.amazon.awssdk.services.rum.model.StateEnum;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch RUM")
class RumTest {

    @Test
    void appMonitorLifecycleUsesAwsSdkWireShapes() throws Exception {
        Set<String> cleanupNames = new LinkedHashSet<>();
        String prefix = TestFixtures.uniqueName("rum");
        String firstName = prefix + "-a";
        String secondName = prefix + "-b";

        try (RumClient rum = TestFixtures.rumClient();
                AutoCloseable deleteMonitors = () -> {
                    for (String name : cleanupNames) {
                        rum.deleteAppMonitor(request -> request.name(name));
                    }
                }) {
            cleanupNames.add(firstName);
            CreateAppMonitorResponse first = rum.createAppMonitor(request -> request
                    .name(firstName)
                    .domain("first.example.com")
                    .platform("Android")
                    .tags(Map.of("env1", "test")));
            cleanupNames.add(secondName);
            CreateAppMonitorResponse second = rum.createAppMonitor(request -> request
                    .name(secondName)
                    .domain("second.example.com"));

            assertThat(first.id()).hasSize(36);
            assertThat(second.id()).hasSize(36);

            GetAppMonitorResponse created = rum.getAppMonitor(request -> request.name(firstName));
            assertThat(created.appMonitor().id()).isEqualTo(first.id());
            assertThat(created.appMonitor().name()).isEqualTo(firstName);
            assertThat(created.appMonitor().domain()).isEqualTo("first.example.com");
            assertThat(created.appMonitor().state()).isEqualTo(StateEnum.CREATED);
            assertThat(created.appMonitor().platformAsString()).isEqualTo("Android");
            assertThat(created.appMonitor().tags()).containsEntry("env1", "test");
            assertThat(created.appMonitor().created()).hasSize(19);
            assertThat(created.appMonitor().lastModified()).hasSize(19);
            String createdAt = created.appMonitor().created();

            rum.updateAppMonitor(request -> request
                    .name(firstName)
                    .domainList(List.of("updated.example.com", "localhost"))
                    .cwLogEnabled(true));

            GetAppMonitorResponse updated = rum.getAppMonitor(request -> request.name(firstName));
            assertThat(updated.appMonitor().id()).isEqualTo(first.id());
            assertThat(updated.appMonitor().name()).isEqualTo(firstName);
            assertThat(updated.appMonitor().domain()).isNull();
            assertThat(updated.appMonitor().domainList())
                    .containsExactly("updated.example.com", "localhost");
            assertThat(updated.appMonitor().dataStorage().cwLog().cwLogEnabled()).isTrue();
            assertThat(updated.appMonitor().state()).isEqualTo(StateEnum.CREATED);
            assertThat(updated.appMonitor().platformAsString()).isEqualTo("Android");
            assertThat(updated.appMonitor().tags()).containsEntry("env1", "test");
            assertThat(updated.appMonitor().created()).isEqualTo(createdAt);
            assertThat(updated.appMonitor().lastModified()).hasSize(19);

            ListAppMonitorsResponse firstPage =
                    rum.listAppMonitors(request -> request.maxResults(1));
            assertThat(firstPage.appMonitorSummaries()).hasSize(1);
            assertSummary(firstPage.appMonitorSummaries().get(0),
                    firstName, first.id(), "Android");
            assertThat(firstPage.nextToken()).isNotBlank();

            ListAppMonitorsResponse secondPage = rum.listAppMonitors(request -> request
                    .maxResults(1)
                    .nextToken(firstPage.nextToken()));
            assertThat(secondPage.appMonitorSummaries()).hasSize(1);
            assertSummary(secondPage.appMonitorSummaries().get(0),
                    secondName, second.id(), "Web");
            assertThat(secondPage.nextToken()).isNull();

            rum.deleteAppMonitor(request -> request.name(firstName));
            cleanupNames.remove(firstName);
            assertThatThrownBy(() -> rum.getAppMonitor(request -> request.name(firstName)))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    private static void assertSummary(
            AppMonitorSummary summary, String name, String id, String platform) {
        assertThat(summary.name()).isEqualTo(name);
        assertThat(summary.id()).isEqualTo(id);
        assertThat(summary.created()).hasSize(19);
        assertThat(summary.lastModified()).hasSize(19);
        assertThat(summary.state()).isEqualTo(StateEnum.CREATED);
        assertThat(summary.platformAsString()).isEqualTo(platform);
    }
}

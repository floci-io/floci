package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.OutputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;
import software.amazon.awssdk.services.redshiftdata.RedshiftDataClient;
import software.amazon.awssdk.services.redshiftdata.model.ExecuteStatementResponse;
import software.amazon.awssdk.services.redshiftdata.model.StatusString;
import software.amazon.awssdk.services.redshiftserverless.RedshiftServerlessClient;
import software.amazon.awssdk.services.redshiftserverless.model.ConfigParameter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("Redshift Serverless audit log export")
class RedshiftServerlessAuditLogTest {

    private static final String STREAM = "serverless";

    @Test
    @DisplayName("A namespace exports the logs it selects to its CloudWatch log groups, and stops when it stops selecting them")
    void namespaceExportsItsAuditLogsToCloudWatch() throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "Asserts emulator-local log delivery");

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String namespace = "audit-ns-" + suffix;
        String workgroup = "audit-wg-" + suffix;
        String activityGroup = "/aws/redshift/" + namespace + "/useractivitylog";
        String connectionGroup = "/aws/redshift/" + namespace + "/connectionlog";
        try (RedshiftServerlessClient serverless = TestFixtures.redshiftServerlessClient();
             RedshiftDataClient data = TestFixtures.redshiftDataClient();
             CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                serverless.createNamespace(request -> request.namespaceName(namespace).adminUsername("admin")
                        .adminUserPassword("Secret123!")
                        .logExportsWithStrings("connectionlog", "useractivitylog"));
                serverless.createWorkgroup(request -> request.workgroupName(workgroup).namespaceName(namespace)
                        .configParameters(ConfigParameter.builder().parameterKey("enable_user_activity_logging")
                                .parameterValue("true").build()));

                execute(data, workgroup, "select 'sdk_audit_on_marker'");
                awaitMessage(logs, activityGroup, "sdk_audit_on_marker");
                awaitMessage(logs, connectionGroup, "authenticated");

                serverless.updateNamespace(request -> request.namespaceName(namespace)
                        .logExportsWithStrings(new ArrayList<>(List.of("connectionlog"))));
                execute(data, workgroup, "select 'sdk_audit_off_marker'");
                Thread.sleep(Duration.ofSeconds(3).toMillis());
                assertThat(messages(logs, activityGroup)).noneMatch(line -> line.contains("sdk_audit_off_marker"));
            } finally {
                deleteBestEffort(() -> serverless.deleteWorkgroup(request -> request.workgroupName(workgroup)));
                deleteBestEffort(() -> serverless.deleteNamespace(request -> request.namespaceName(namespace)));
            }
        }
    }

    private static void execute(RedshiftDataClient data, String workgroup, String sql) {
        ExecuteStatementResponse executed = data.executeStatement(request -> request.workgroupName(workgroup).database("dev").sql(sql)
                .waitTimeSeconds(30));
        assertThat(data.describeStatement(request -> request.id(executed.id())).status())
                .isEqualTo(StatusString.FINISHED);
    }

    private static List<String> messages(CloudWatchLogsClient logs, String group) {
        try {
            GetLogEventsResponse response = logs.getLogEvents(request -> request.logGroupName(group)
                    .logStreamName(STREAM).startFromHead(true));
            return response.events().stream().map(OutputLogEvent::message).toList();
        } catch (ResourceNotFoundException notYet) {
            return List.of();
        }
    }

    private static void awaitMessage(CloudWatchLogsClient logs, String group, String text) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        List<String> messages = messages(logs, group);
        while (messages.stream().noneMatch(line -> line.contains(text)) && Instant.now().isBefore(deadline)) {
            Thread.sleep(500);
            messages = messages(logs, group);
        }
        assertThat(messages).anyMatch(line -> line.contains(text));
    }

    private static void deleteBestEffort(Runnable deletion) {
        try {
            deletion.run();
        } catch (RuntimeException ignored) {
            // The test may have failed before creating the resource.
        }
    }
}

package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.LogGroup;
import software.amazon.awssdk.services.cloudwatchlogs.model.ValidationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudWatch Logs deletion protection")
class CloudWatchLogsDeletionProtectionTest {

    @Test
    @DisplayName("defaults off and can be enabled and disabled by name")
    void defaultsOffAndUpdatesByName() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-deletion-protection-default");
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request.logGroupName(groupName));
                assertThat(findLogGroup(logs, groupName))
                        .get()
                        .extracting(LogGroup::deletionProtectionEnabled)
                        .isEqualTo(false);

                logs.putLogGroupDeletionProtection(request -> request
                        .logGroupIdentifier(groupName)
                        .deletionProtectionEnabled(true));
                assertThat(findLogGroup(logs, groupName))
                        .get()
                        .extracting(LogGroup::deletionProtectionEnabled)
                        .isEqualTo(true);

                logs.putLogGroupDeletionProtection(request -> request
                        .logGroupIdentifier(groupName)
                        .deletionProtectionEnabled(false));
                assertThat(findLogGroup(logs, groupName))
                        .get()
                        .extracting(LogGroup::deletionProtectionEnabled)
                        .isEqualTo(false);
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    @Test
    @DisplayName("create-time protection blocks deletion until disabled by ARN")
    void createTimeProtectionBlocksDeletionUntilDisabledByArn() {
        String groupName = "/test/" + TestFixtures.uniqueName("logs-deletion-protection-create");
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            try {
                logs.createLogGroup(request -> request
                        .logGroupName(groupName)
                        .deletionProtectionEnabled(true));
                LogGroup group = findLogGroup(logs, groupName).orElseThrow();
                assertThat(group.deletionProtectionEnabled()).isTrue();

                assertThatThrownBy(() -> logs.deleteLogGroup(request -> request.logGroupName(groupName)))
                        .isInstanceOfSatisfying(ValidationException.class, error -> {
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                            assertThat(error.getMessage()).containsIgnoringCase("deletion protection");
                        });

                logs.putLogGroupDeletionProtection(request -> request
                        .logGroupIdentifier(group.arn())
                        .deletionProtectionEnabled(false));
                logs.deleteLogGroup(request -> request.logGroupName(groupName));

                assertThat(findLogGroup(logs, groupName)).isEmpty();
            } finally {
                deleteIfPresent(logs, groupName);
            }
        }
    }

    private static Optional<LogGroup> findLogGroup(CloudWatchLogsClient logs, String groupName) {
        return logs.describeLogGroups(request -> request.logGroupNamePrefix(groupName))
                .logGroups()
                .stream()
                .filter(group -> groupName.equals(group.logGroupName()))
                .findFirst();
    }

    private static void deleteIfPresent(CloudWatchLogsClient logs, String groupName) {
        if (findLogGroup(logs, groupName).isEmpty()) {
            return;
        }
        logs.putLogGroupDeletionProtection(request -> request
                .logGroupIdentifier(groupName)
                .deletionProtectionEnabled(false));
        logs.deleteLogGroup(request -> request.logGroupName(groupName));
    }
}

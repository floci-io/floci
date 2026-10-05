package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.Tag;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CloudFormationStackTagsUpdateTest {

    private static final String TEMPLATE = """
            {"Resources":{"Queue":{"Type":"AWS::SQS::Queue"}}}
            """;

    private static CloudFormationClient cloudFormation;
    private static String stackName;

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        stackName = TestFixtures.uniqueName("cfn-stack-tags-sdk");
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            try {
                cloudFormation.deleteStack(request -> request.stackName(stackName));
            } finally {
                cloudFormation.close();
            }
        }
    }

    @Test
    void sdkUpdatesStackTags() throws InterruptedException {
        cloudFormation.createStack(request -> request.stackName(stackName)
                .templateBody(TEMPLATE)
                .tags(tag("Env", "before"), tag("Owner", "original")));
        assertThat(awaitStatus("CREATE_COMPLETE")).isEqualTo("CREATE_COMPLETE");
        assertThat(tags()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Env", "before", "Owner", "original"));

        cloudFormation.updateStack(request -> request.stackName(stackName)
                .usePreviousTemplate(true)
                .tags(tag("Env", "after"), tag("Team", "new")));
        assertThat(awaitStatus("UPDATE_COMPLETE")).isEqualTo("UPDATE_COMPLETE");
        assertThat(tags()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Env", "after", "Team", "new"));

        cloudFormation.createChangeSet(request -> request.stackName(stackName)
                .changeSetName("tags-only")
                .changeSetType(ChangeSetType.UPDATE)
                .usePreviousTemplate(true)
                .tags(tag("Env", "final")));
        assertThat(tags()).containsExactlyInAnyOrderEntriesOf(
                Map.of("Env", "after", "Team", "new"));
        cloudFormation.executeChangeSet(request -> request.stackName(stackName)
                .changeSetName("tags-only"));
        assertThat(awaitStatus("UPDATE_COMPLETE")).isEqualTo("UPDATE_COMPLETE");
        assertThat(tags()).containsExactlyInAnyOrderEntriesOf(Map.of("Env", "final"));

        cloudFormation.updateStack(request -> request.stackName(stackName)
                .usePreviousTemplate(true));
        assertThat(awaitStatus("UPDATE_COMPLETE")).isEqualTo("UPDATE_COMPLETE");
        assertThat(tags()).containsExactlyInAnyOrderEntriesOf(Map.of("Env", "final"));
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static Map<String, String> tags() {
        return cloudFormation.describeStacks(request -> request.stackName(stackName))
                .stacks().get(0).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static String awaitStatus(String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Stack stack = cloudFormation.describeStacks(request -> request.stackName(stackName))
                    .stacks().get(0);
            String status = stack.stackStatusAsString();
            if (expected.equals(status)) {
                return status;
            }
            if (status.endsWith("_FAILED") || status.contains("ROLLBACK")) {
                throw new AssertionError(stackName + " reached " + status + ": "
                        + stack.stackStatusReason());
            }
            Thread.sleep(100);
        }
        throw new AssertionError(stackName + " did not reach " + expected + " within 30 seconds");
    }
}

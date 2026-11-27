package io.floci.aws.services.autoscaling;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.Filter;
import software.amazon.awssdk.services.autoscaling.model.Tag;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DescribeAutoScalingGroups Filters (tag:&lt;key&gt;, tag-key, tag-value) and pagination over the
 * filtered set. Groups are read back through the describe call itself.
 */
@QuarkusTest
class AutoScalingDescribeGroupsFilterIntegrationTest {

    // A dedicated account keeps these groups out of every other test's unfiltered describe.
    private static final String ACCOUNT = "595959595959";
    private static final Region REGION = Region.US_EAST_1;

    @Test
    void filtersSelectGroupsByTag() {
        try (AutoScalingClient client = AwsTestClients.create(AutoScalingClient.builder(), ACCOUNT, REGION)) {
            client.createLaunchConfiguration(r -> r.launchConfigurationName("flt-lc")
                    .imageId("ami-12345678").instanceType("t3.micro"));
            createGroup(client, "flt-a", tag("k", "v"), tag("other", "x"));
            createGroup(client, "flt-b", tag("k", "w"));
            createGroup(client, "flt-c");

            assertEquals(List.of("flt-a"), names(client, filter("tag:k", "v")));
            assertEquals(List.of("flt-a", "flt-b"), names(client, filter("tag:k", "v", "w")));
            assertEquals(List.of(), names(client, filter("tag:k", "nope")));
            assertEquals(List.of("flt-a", "flt-b"), names(client, filter("tag-key", "k")));
            assertEquals(List.of("flt-a"), names(client, filter("tag-key", "other")));
            assertEquals(List.of("flt-a"), names(client, filter("tag-value", "x")));
            // Filters are ANDed.
            assertEquals(List.of("flt-a"), names(client, filter("tag:k", "v", "w"), filter("tag-key", "other")));
            assertEquals(List.of(), names(client, filter("tag:k", "v"), filter("tag-value", "w")));
            // Filters combine with AutoScalingGroupNames.
            assertEquals(List.of("flt-b"), client.describeAutoScalingGroups(r -> r
                    .autoScalingGroupNames("flt-b", "flt-c").filters(filter("tag-key", "k")))
                    .autoScalingGroups().stream().map(AutoScalingGroup::autoScalingGroupName).toList());
            // No filter: everything in the account.
            assertEquals(List.of("flt-a", "flt-b", "flt-c"), names(client));
        }
    }

    @Test
    void paginationAppliesAfterFiltering() {
        try (AutoScalingClient client = AwsTestClients.create(AutoScalingClient.builder(), ACCOUNT, Region.US_WEST_2)) {
            client.createLaunchConfiguration(r -> r.launchConfigurationName("page-lc")
                    .imageId("ami-12345678").instanceType("t3.micro"));
            createGroup(client, "page-a", tag("k", "v"));
            createGroup(client, "page-b");
            createGroup(client, "page-c", tag("k", "v"));
            createGroup(client, "page-d", tag("k", "v"));

            List<String> seen = new ArrayList<>();
            String token = null;
            int pages = 0;
            do {
                String next = token;
                DescribeAutoScalingGroupsResponse page = client.describeAutoScalingGroups(r -> r
                        .filters(filter("tag:k", "v")).maxRecords(2).nextToken(next));
                page.autoScalingGroups().forEach(g -> seen.add(g.autoScalingGroupName()));
                token = page.nextToken();
                pages++;
            } while (token != null);
            assertEquals(List.of("page-a", "page-c", "page-d"), seen);
            assertEquals(2, pages);
            assertNull(token);
        }
    }

    @Test
    void tagFilterOnAGroupWithoutThatKeyMatchesNothing() {
        try (AutoScalingClient client = AwsTestClients.create(AutoScalingClient.builder(), ACCOUNT, Region.US_EAST_2)) {
            client.createLaunchConfiguration(r -> r.launchConfigurationName("miss-lc")
                    .imageId("ami-12345678").instanceType("t3.micro"));
            createGroup(client, "miss-a");
            assertEquals(List.of(), names(client, filter("tag:absent", "v")));
            assertEquals(List.of("miss-a"), names(client));
        }
    }

    @Test
    void undecodableNextTokenIsInvalidNextToken() {
        try (AutoScalingClient client = AwsTestClients.create(AutoScalingClient.builder(), ACCOUNT, REGION)) {
            AutoScalingException e = assertThrows(AutoScalingException.class,
                    () -> client.describeAutoScalingGroups(r -> r.nextToken("not-a-token")));
            assertEquals(400, e.statusCode());
            assertEquals("InvalidNextToken", e.awsErrorDetails().errorCode());
            assertEquals("The token 'not-a-token' was invalid", e.awsErrorDetails().errorMessage());
            e = assertThrows(AutoScalingException.class,
                    () -> client.describeAutoScalingGroups(r -> r.maxRecords(101)));
            assertEquals("ValidationError", e.awsErrorDetails().errorCode());
        }
    }

    private static List<String> names(AutoScalingClient client, Filter... filters) {
        return client.describeAutoScalingGroups(r -> r.filters(filters)).autoScalingGroups().stream()
                .map(AutoScalingGroup::autoScalingGroupName).sorted().toList();
    }

    private static Filter filter(String name, String... values) {
        return Filter.builder().name(name).values(values).build();
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).propagateAtLaunch(false).build();
    }

    private static void createGroup(AutoScalingClient client, String name, Tag... tags) {
        client.createAutoScalingGroup(r -> r.autoScalingGroupName(name)
                .launchConfigurationName(name.substring(0, name.indexOf('-')) + "-lc")
                .minSize(0).maxSize(1).desiredCapacity(0)
                .availabilityZones(client.serviceClientConfiguration().region().id() + "a").tags(tags));
    }
}

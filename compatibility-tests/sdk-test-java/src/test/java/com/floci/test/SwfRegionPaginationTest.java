package com.floci.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.swf.SwfClient;
import software.amazon.awssdk.services.swf.model.ActivityType;
import software.amazon.awssdk.services.swf.model.ChildPolicy;
import software.amazon.awssdk.services.swf.model.DescribeDomainRequest;
import software.amazon.awssdk.services.swf.model.DescribeWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.DescribeWorkflowTypeRequest;
import software.amazon.awssdk.services.swf.model.DomainAlreadyExistsException;
import software.amazon.awssdk.services.swf.model.ExecutionTimeFilter;
import software.amazon.awssdk.services.swf.model.ListActivityTypesRequest;
import software.amazon.awssdk.services.swf.model.ListActivityTypesResponse;
import software.amazon.awssdk.services.swf.model.ListDomainsRequest;
import software.amazon.awssdk.services.swf.model.ListDomainsResponse;
import software.amazon.awssdk.services.swf.model.ListOpenWorkflowExecutionsRequest;
import software.amazon.awssdk.services.swf.model.ListWorkflowTypesRequest;
import software.amazon.awssdk.services.swf.model.ListWorkflowTypesResponse;
import software.amazon.awssdk.services.swf.model.RegisterActivityTypeRequest;
import software.amazon.awssdk.services.swf.model.RegisterDomainRequest;
import software.amazon.awssdk.services.swf.model.RegisterWorkflowTypeRequest;
import software.amazon.awssdk.services.swf.model.RegistrationStatus;
import software.amazon.awssdk.services.swf.model.StartWorkflowExecutionRequest;
import software.amazon.awssdk.services.swf.model.SwfException;
import software.amazon.awssdk.services.swf.model.TaskList;
import software.amazon.awssdk.services.swf.model.UnknownResourceException;
import software.amazon.awssdk.services.swf.model.WorkflowExecution;
import software.amazon.awssdk.services.swf.model.WorkflowType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for two AWS behaviours a single-region client cannot exercise:
 * SWF names are unique per <em>region</em>, and the registration lists are paginated.
 *
 * <p>Both were measured against the live service before being asserted here. Region isolation
 * needs two clients pointed at the same emulator with different {@code Region} values, which
 * is exactly what an SDK-level test can express and a raw-HTTP test cannot do naturally.
 */
@DisplayName("SWF cross-region isolation and registration pagination via the AWS SDK")
class SwfRegionPaginationTest {

    private static final String SUFFIX = String.valueOf(System.nanoTime());
    private static final String DOMAIN = "sdk-region-" + SUFFIX;

    /** A fresh domain per test, registered in both regions, so tests never collide. */
    private static String freshDomain(String label) {
        String name = "sdk-" + label + "-" + System.nanoTime();
        east.registerDomain(RegisterDomainRequest.builder()
                .name(name).workflowExecutionRetentionPeriodInDays("1").build());
        return name;
    }
    private static final String TASK_LIST = "sdk-region-tl-" + SUFFIX;

    private static SwfClient east;
    private static SwfClient west;

    @BeforeAll
    static void setUp() {
        east = TestFixtures.swfClient(Region.US_EAST_1);
        west = TestFixtures.swfClient(Region.EU_WEST_1);
    }

    private static void registerWorkflowType(SwfClient swf, String domain, String name) {
        swf.registerWorkflowType(RegisterWorkflowTypeRequest.builder()
                .domain(domain).name(name).version("1.0")
                .defaultTaskList(TaskList.builder().name(TASK_LIST).build())
                .defaultTaskStartToCloseTimeout("300")
                .defaultExecutionStartToCloseTimeout("900")
                .defaultChildPolicy(ChildPolicy.TERMINATE)
                .build());
    }

    @Test
    @DisplayName("the same domain name registers independently in two regions")
    void domainNamesAreUniquePerRegion() {
        east.registerDomain(RegisterDomainRequest.builder()
                .name(DOMAIN).description("east").workflowExecutionRetentionPeriodInDays("1").build());

        // The maintainer's first report: this used to fail as a duplicate.
        west.registerDomain(RegisterDomainRequest.builder()
                .name(DOMAIN).description("west").workflowExecutionRetentionPeriodInDays("2").build());

        var eastDomain = east.describeDomain(DescribeDomainRequest.builder().name(DOMAIN).build());
        var westDomain = west.describeDomain(DescribeDomainRequest.builder().name(DOMAIN).build());

        assertEquals("east", eastDomain.domainInfo().description());
        assertEquals("west", westDomain.domainInfo().description());
        assertEquals("1", eastDomain.configuration().workflowExecutionRetentionPeriodInDays());
        assertEquals("2", westDomain.configuration().workflowExecutionRetentionPeriodInDays());

        // Each ARN names its own region rather than leaking the first registration's.
        assertTrue(eastDomain.domainInfo().arn().contains(":us-east-1:"),
                eastDomain.domainInfo().arn());
        assertTrue(westDomain.domainInfo().arn().contains(":eu-west-1:"),
                westDomain.domainInfo().arn());

        // Re-registering within one region is still a duplicate.
        assertThrows(DomainAlreadyExistsException.class, () ->
                east.registerDomain(RegisterDomainRequest.builder()
                        .name(DOMAIN).workflowExecutionRetentionPeriodInDays("1").build()));
    }

    @Test
    @DisplayName("types and executions do not leak across regions")
    void typesAndExecutionsAreIsolatedPerRegion() {
        String domain = "sdk-isolate-" + System.nanoTime();
        east.registerDomain(RegisterDomainRequest.builder()
                .name(domain).workflowExecutionRetentionPeriodInDays("1").build());
        west.registerDomain(RegisterDomainRequest.builder()
                .name(domain).workflowExecutionRetentionPeriodInDays("1").build());

        registerWorkflowType(east, domain, "EastOnly");

        // Visible in its own region only.
        assertTrue(typeNames(east.listWorkflowTypes(ListWorkflowTypesRequest.builder()
                .domain(domain).registrationStatus(RegistrationStatus.REGISTERED).build()))
                .contains("EastOnly"));
        assertFalse(typeNames(west.listWorkflowTypes(ListWorkflowTypesRequest.builder()
                .domain(domain).registrationStatus(RegistrationStatus.REGISTERED).build()))
                .contains("EastOnly"));
        assertThrows(UnknownResourceException.class, () ->
                west.describeWorkflowType(DescribeWorkflowTypeRequest.builder()
                        .domain(domain)
                        .workflowType(WorkflowType.builder().name("EastOnly").version("1.0").build())
                        .build()));

        // The same workflowId runs independently in each region.
        registerWorkflowType(west, domain, "EastOnly");
        String eastRun = east.startWorkflowExecution(StartWorkflowExecutionRequest.builder()
                        .domain(domain).workflowId("wf-shared")
                        .workflowType(WorkflowType.builder().name("EastOnly").version("1.0").build())
                        .build())
                .runId();
        String westRun = west.startWorkflowExecution(StartWorkflowExecutionRequest.builder()
                        .domain(domain).workflowId("wf-shared")
                        .workflowType(WorkflowType.builder().name("EastOnly").version("1.0").build())
                        .build())
                .runId();
        assertNotEquals(eastRun, westRun);

        List<String> eastOpen = east.listOpenWorkflowExecutions(ListOpenWorkflowExecutionsRequest.builder()
                        .domain(domain)
                        .startTimeFilter(ExecutionTimeFilter.builder()
                                .oldestDate(Instant.EPOCH).build())
                        .build())
                .executionInfos().stream().map(i -> i.execution().runId()).toList();
        assertTrue(eastOpen.contains(eastRun));
        assertFalse(eastOpen.contains(westRun), "a west run must not appear in an east listing");

        // A runId from the other region must not resolve.
        assertThrows(UnknownResourceException.class, () ->
                west.describeWorkflowExecution(DescribeWorkflowExecutionRequest.builder()
                        .domain(domain)
                        .execution(WorkflowExecution.builder()
                                .workflowId("wf-shared").runId(eastRun).build())
                        .build()));
    }

    @Test
    @DisplayName("ListWorkflowTypes honours maximumPageSize and nextPageToken")
    void listWorkflowTypesPaginates() {
        String domain = freshDomain("pagewf");
        for (int i = 0; i < 5; i++) {
            registerWorkflowType(east, domain, "PageWf" + i);
        }

        List<String> seen = new ArrayList<>();
        String token = null;
        int pages = 0;
        do {
            ListWorkflowTypesResponse page = east.listWorkflowTypes(ListWorkflowTypesRequest.builder()
                    .domain(domain)
                    .registrationStatus(RegistrationStatus.REGISTERED)
                    .maximumPageSize(2)
                    .nextPageToken(token)
                    .build());
            assertTrue(page.typeInfos().size() <= 2,
                    "maximumPageSize must cap the page: " + page.typeInfos().size());
            seen.addAll(typeNames(page));
            token = page.nextPageToken();
            pages++;
        } while (token != null && !token.isEmpty() && pages < 10);

        assertTrue(pages > 1, "5 types at page size 2 must span several pages, got " + pages);
        assertNull(emptyToNull(token), "the final page must not carry a continuation token");
        for (int i = 0; i < 5; i++) {
            assertTrue(seen.contains("PageWf" + i), "paging lost PageWf" + i + ": " + seen);
        }
        assertEquals(seen.size(), seen.stream().distinct().count(), "paging repeated an item: " + seen);
    }

    @Test
    @DisplayName("ListActivityTypes and ListDomains paginate the same way")
    void listActivityTypesAndDomainsPaginate() {
        String domain = freshDomain("pageact");
        for (int i = 0; i < 4; i++) {
            east.registerActivityType(RegisterActivityTypeRequest.builder()
                    .domain(domain).name("PageAct" + i).version("1.0")
                    .defaultTaskList(TaskList.builder().name(TASK_LIST).build())
                    .defaultTaskScheduleToStartTimeout("60")
                    .defaultTaskStartToCloseTimeout("60")
                    .defaultTaskScheduleToCloseTimeout("NONE")
                    .defaultTaskHeartbeatTimeout("NONE")
                    .build());
        }

        ListActivityTypesResponse firstActivities = east.listActivityTypes(ListActivityTypesRequest.builder()
                .domain(domain).registrationStatus(RegistrationStatus.REGISTERED)
                .maximumPageSize(3).build());
        assertEquals(3, firstActivities.typeInfos().size());
        assertTrue(firstActivities.nextPageToken() != null && !firstActivities.nextPageToken().isEmpty(),
                "a partial listing must offer a continuation token");

        ListActivityTypesResponse secondActivities = east.listActivityTypes(ListActivityTypesRequest.builder()
                .domain(domain).registrationStatus(RegistrationStatus.REGISTERED)
                .maximumPageSize(3).nextPageToken(firstActivities.nextPageToken()).build());
        List<String> firstNames = firstActivities.typeInfos().stream()
                .map(i -> i.activityType().name()).toList();
        for (var info : secondActivities.typeInfos()) {
            assertFalse(firstNames.contains(info.activityType().name()),
                    "page 2 repeated " + info.activityType().name());
        }

        // ListDomains pages too; at least this domain exists, so page size 1 must cap it.
        ListDomainsResponse domains = east.listDomains(ListDomainsRequest.builder()
                .registrationStatus(RegistrationStatus.REGISTERED).maximumPageSize(1).build());
        assertEquals(1, domains.domainInfos().size());
    }

    @Test
    @DisplayName("an oversized page size and a corrupt token are rejected")
    void paginationInputsAreValidated() {
        String domain = freshDomain("pagevalid");

        // The live service caps maximumPageSize at 1000 rather than clamping silently.
        SwfException tooBig = assertThrows(SwfException.class, () ->
                east.listWorkflowTypes(ListWorkflowTypesRequest.builder()
                        .domain(domain).registrationStatus(RegistrationStatus.REGISTERED)
                        .maximumPageSize(1001).build()));
        assertEquals("ValidationException", tooBig.awsErrorDetails().errorCode());

        SwfException badToken = assertThrows(SwfException.class, () ->
                east.listWorkflowTypes(ListWorkflowTypesRequest.builder()
                        .domain(domain).registrationStatus(RegistrationStatus.REGISTERED)
                        .nextPageToken("not-a-real-token").build()));
        assertEquals("ValidationException", badToken.awsErrorDetails().errorCode());
        assertEquals("Invalid token", badToken.awsErrorDetails().errorMessage());
    }

    private static List<String> typeNames(ListWorkflowTypesResponse response) {
        return response.typeInfos().stream().map(i -> i.workflowType().name()).toList();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}

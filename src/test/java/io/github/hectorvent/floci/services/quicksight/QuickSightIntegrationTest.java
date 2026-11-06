package io.github.hectorvent.floci.services.quicksight;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.quicksight.QuickSightClient;
import software.amazon.awssdk.services.quicksight.model.AccessDeniedException;
import software.amazon.awssdk.services.quicksight.model.DashboardSearchFilter;
import software.amazon.awssdk.services.quicksight.model.GenerateEmbedUrlForRegisteredUserResponse;
import software.amazon.awssdk.services.quicksight.model.InvalidNextTokenException;
import software.amazon.awssdk.services.quicksight.model.InvalidParameterValueException;
import software.amazon.awssdk.services.quicksight.model.ListIngestionsResponse;
import software.amazon.awssdk.services.quicksight.model.PhysicalTable;
import software.amazon.awssdk.services.quicksight.model.ResourceNotFoundException;
import software.amazon.awssdk.services.quicksight.model.ResourcePermission;
import software.amazon.awssdk.services.quicksight.model.SearchDashboardsResponse;
import software.amazon.awssdk.services.quicksight.model.SessionLifetimeInMinutesInvalidException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class QuickSightIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource
    URI endpoint;

    @Test
    void embeddingUsesTheAuthenticatedResourceAccount() throws Exception {
        String account = "111122223333";
        String id = "shared-" + UUID.randomUUID();
        try (QuickSightClient client = AwsTestClients.create(QuickSightClient.builder(), account, Region.EU_WEST_2); HttpClient http = HttpClient.newHttpClient()) {
            String principal = client.registerUser(request -> request.awsAccountId(account).namespace("default")
                    .userName(id).email("reader@example.com").identityType("QUICKSIGHT").userRole("READER")).user().arn();
            try {
                client.createDashboard(request -> request.awsAccountId(account).dashboardId(id).name("Other account")
                        .definition(definition -> definition.sheets(List.of()))
                        .permissions(ResourcePermission.builder().principal(principal).actions("quicksight:DescribeDashboard").build()));
                String url = client.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(account).userArn(principal)
                        .experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(id)))).embedUrl();
                URI generated = URI.create(url);
                URI local = endpoint.resolve(generated.getRawPath() + "?" + generated.getRawQuery());
                HttpResponse<String> content = http.send(HttpRequest.newBuilder(local).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, content.statusCode(), content.body());
                assertTrue(content.body().contains("Other account"));
            } finally {
                client.deleteDashboard(request -> request.awsAccountId(account).dashboardId(id));
            }
        }
    }

    @Test
    void aCallerCannotCreateReadOrDeleteAnotherAccountsDashboard() {
        String owner = "111122223333";
        String id = "isolated-" + UUID.randomUUID();
        try (QuickSightClient caller = client(Region.EU_WEST_2);
                QuickSightClient target = AwsTestClients.create(QuickSightClient.builder(), owner, Region.EU_WEST_2)) {
            target.createDashboard(request -> request.awsAccountId(owner).dashboardId(id).name("Owner")
                    .definition(definition -> definition.sheets(List.of())));
            try {
                AccessDeniedException read = assertThrows(AccessDeniedException.class,
                        () -> caller.describeDashboard(request -> request.awsAccountId(owner).dashboardId(id)));
                assertEquals(401, read.statusCode());
                assertThrows(AccessDeniedException.class,
                        () -> caller.deleteDashboard(request -> request.awsAccountId(owner).dashboardId(id)));
                assertThrows(AccessDeniedException.class,
                        () -> caller.createDashboard(request -> request.awsAccountId(owner).dashboardId(id + "-new")
                                .name("Intruder").definition(definition -> definition.sheets(List.of()))));
                assertEquals("Owner", target.describeDashboard(request -> request.awsAccountId(owner).dashboardId(id)).dashboard().name());
                assertThrows(ResourceNotFoundException.class,
                        () -> target.describeDashboard(request -> request.awsAccountId(owner).dashboardId(id + "-new")));
            } finally {
                target.deleteDashboard(request -> request.awsAccountId(owner).dashboardId(id));
            }
        }
    }

    @Test
    void searchFiltersDirectSharingAndScopesTokensToUserAccountAndRegion() {
        String id = "scoped-" + UUID.randomUUID();
        String otherAccount = "444455556666";
        try (QuickSightClient client = client(Region.EU_WEST_2); QuickSightClient west = client(Region.US_WEST_2);
                QuickSightClient foreign = AwsTestClients.create(QuickSightClient.builder(), otherAccount, Region.EU_WEST_2)) {
            String user = register(client, ACCOUNT, id);
            String otherUser = register(client, ACCOUNT, "other-" + id);
            String foreignUser = register(foreign, otherAccount, id);
            String westUser = register(west, ACCOUNT, id);
            for (String dashboard : List.of(id + "-a", id + "-b")) {
                client.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(dashboard).name("Shared")
                        .definition(definition -> definition.sheets(List.of()))
                        .permissions(ResourcePermission.builder().principal(user).actions("quicksight:DescribeDashboard").build()));
            }
            client.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id).name("Private")
                    .definition(definition -> definition.sheets(List.of()))
                    .permissions(ResourcePermission.builder().principal(user).actions("quicksight:ListDashboards").build()));
            try {
                List<DashboardSearchFilter> filters = filters(user);
                SearchDashboardsResponse page = client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters).maxResults(1));
                assertEquals(id + "-a", page.dashboardSummaryList().getFirst().dashboardId());
                assertNotNull(page.nextToken());
                assertTrue(client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters(otherUser))).dashboardSummaryList().isEmpty());
                assertThrows(InvalidNextTokenException.class, () -> client.searchDashboards(request -> request.awsAccountId(ACCOUNT)
                        .filters(filters(otherUser)).nextToken(page.nextToken())));
                assertThrows(InvalidNextTokenException.class, () -> foreign.searchDashboards(request -> request.awsAccountId(otherAccount)
                        .filters(filters(foreignUser)).nextToken(page.nextToken())));
                assertThrows(InvalidNextTokenException.class, () -> west.searchDashboards(request -> request.awsAccountId(ACCOUNT)
                        .filters(filters(westUser)).nextToken(page.nextToken())));
                assertThrows(ResourceNotFoundException.class, () -> west.describeDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id)));
                assertThrows(ResourceNotFoundException.class, () -> foreign.describeDashboard(request -> request.awsAccountId(otherAccount).dashboardId(id)));
                assertThrows(InvalidParameterValueException.class, () -> client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters).maxResults(0)));
                assertThrows(InvalidParameterValueException.class, () -> client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters).maxResults(101)));
                assertThrows(AccessDeniedException.class, () -> client.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT).userArn(foreignUser)
                        .experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(id)))));
                client.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id + "-a"));
                SearchDashboardsResponse remaining = client.searchDashboards(request -> request.awsAccountId(ACCOUNT)
                        .filters(filters).maxResults(1).nextToken(page.nextToken()));
                assertEquals(List.of(id + "-b"), remaining.dashboardSummaryList().stream().map(value -> value.dashboardId()).toList());
            } finally {
                for (String dashboard : List.of(id, id + "-a", id + "-b")) {
                    try {
                        client.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(dashboard));
                    } catch (ResourceNotFoundException expected) {
                        // The first page's dashboard was deleted to exercise cursor stability.
                    }
                }
            }
        }
    }

    @Test
    void embedPageEscapesMetadataAndChecksOriginAndSessionCookie() throws Exception {
        String id = "html-" + UUID.randomUUID();
        try (QuickSightClient client = client(Region.EU_WEST_2); HttpClient http = HttpClient.newHttpClient()) {
            String user = register(client, ACCOUNT, id);
            client.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id).name("<script>bad()</script> & metadata")
                    .definition(definition -> definition.sheets(List.of()))
                    .permissions(ResourcePermission.builder().principal(user).actions("quicksight:DescribeDashboard").build()));
            try {
                URI generated = URI.create(client.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT).userArn(user)
                        .allowedDomains("https://APP.example.com:443", "https://PORTAL.example.com:443")
                        .experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(id)))).embedUrl());
                URI local = endpoint.resolve(generated.getRawPath() + "?" + generated.getRawQuery());
                assertEquals(403, http.send(HttpRequest.newBuilder(local).header("Referer", "https://other.example/").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                assertEquals(403, http.send(HttpRequest.newBuilder(local).header("Referer", "https://app.example.com:8443/").build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                HttpResponse<String> page = http.send(HttpRequest.newBuilder(local).header("Referer", "https://app.example.com/reports").build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode());
                assertTrue(page.body().contains("&lt;script&gt;bad()&lt;/script&gt; &amp; metadata"));
                assertFalse(page.body().contains("<script>"));
                assertEquals("no-store", page.headers().firstValue("cache-control").orElseThrow());
                assertEquals("frame-ancestors https://app.example.com https://portal.example.com", page.headers().firstValue("content-security-policy").orElseThrow());
                String cookie = page.headers().firstValue("set-cookie").orElseThrow();
                assertTrue(cookie.contains("HttpOnly; Secure; SameSite=None"));
                HttpRequest reload = HttpRequest.newBuilder(endpoint.resolve(generated.getRawPath()))
                        .header("Referer", "https://APP.EXAMPLE.com:443/reports").header("Cookie", cookie.split(";")[0]).build();
                assertEquals(200, http.send(reload, HttpResponse.BodyHandlers.ofString()).statusCode());
            } finally {
                client.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id));
            }
        }
    }

    private QuickSightClient client(Region region) {
        return AwsTestClients.create(QuickSightClient.builder(), ACCOUNT, region);
    }

    private String register(QuickSightClient client, String account, String name) {
        return client.registerUser(request -> request.awsAccountId(account).namespace("default").userName(name)
                .email("reader@example.com").identityType("QUICKSIGHT").userRole("READER")).user().arn();
    }

    private List<DashboardSearchFilter> filters(String principal) {
        return List.of(DashboardSearchFilter.builder().name("QUICKSIGHT_USER").operator("StringEquals").value(principal).build());
    }

    @Test
    void sdkProvisioningSearchPaginationDescriptionsIngestionsAndEmbedding() throws Exception {
        try (QuickSightClient client = client(Region.EU_WEST_2)) {
            String suffix = UUID.randomUUID().toString();
            String user = "reader-" + suffix;
            String dataSet = "data-" + suffix;
            String source = "source-" + suffix;
            String first = "a-" + suffix;
            String second = "b-" + suffix;
            String principal = client.registerUser(request -> request.awsAccountId(ACCOUNT).namespace("default")
                    .userName(user).email(user + "@example.com").identityType("QUICKSIGHT").userRole("READER")).user().arn();
            assertEquals(principal, client.describeUser(request -> request.awsAccountId(ACCOUNT).namespace("default").userName(user)).user().arn());
            String sourceArn = client.createDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(source).name("Reporting source")
                    .type("S3").dataSourceParameters(parameters -> parameters.s3Parameters(s3 -> s3.manifestFileLocation(location -> location.bucket("reports").key("manifest.json"))))).arn();
            String dataSetArn = client.createDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).name("Reporting data")
                    .importMode("SPICE").physicalTableMap(Map.of("table", PhysicalTable.builder()
                            .s3Source(s3 -> s3.dataSourceArn(sourceArn).inputColumns(column -> column.name("value").type("STRING"))).build()))).arn();
            client.createIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).ingestionId("first").ingestionType("FULL_REFRESH"));
            client.createIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).ingestionId("second").ingestionType("INCREMENTAL_REFRESH"));
            assertEquals("COMPLETED", client.describeIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).ingestionId("first")).ingestion().ingestionStatusAsString());
            assertEquals("FULL_REFRESH", client.describeIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).ingestionId("first")).ingestion().requestTypeAsString());
            assertEquals("INCREMENTAL_REFRESH", client.describeIngestion(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).ingestionId("second")).ingestion().requestTypeAsString());
            ListIngestionsResponse firstIngestionPage = client.listIngestions(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet).maxResults(1));
            assertNotNull(firstIngestionPage.nextToken());
            ListIngestionsResponse secondIngestionPage = client.listIngestions(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet)
                    .maxResults(1).nextToken(firstIngestionPage.nextToken()));
            assertEquals(1, secondIngestionPage.ingestions().size());
            assertFalse(firstIngestionPage.ingestions().getFirst().ingestionId().equals(secondIngestionPage.ingestions().getFirst().ingestionId()));
            assertEquals(sourceArn, client.describeDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(source)).dataSource().arn());
            assertEquals(dataSetArn, client.describeDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet)).dataSet().arn());
            assertNotNull(client.listIngestions(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet)).ingestions().getFirst().createdTime());
            List<ResourcePermission> permissions = List.of(ResourcePermission.builder().principal(principal).actions("quicksight:DescribeDashboard").build());
            for (String id : List.of(first, second)) {
                client.createDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(id).name("Dashboard " + id).permissions(permissions)
                        .definition(definition -> definition.dataSetIdentifierDeclarations(declaration -> declaration.identifier("reporting").dataSetArn(dataSetArn))));
            }
            List<DashboardSearchFilter> filters = List.of(DashboardSearchFilter.builder().name("QUICKSIGHT_USER").operator("StringEquals").value(principal).build());
            SearchDashboardsResponse page = client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters).maxResults(1));
            assertEquals(1, page.dashboardSummaryList().size());
            assertNotNull(page.dashboardSummaryList().getFirst().createdTime());
            SearchDashboardsResponse next = client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters).maxResults(1).nextToken(page.nextToken()));
            assertEquals(second, next.dashboardSummaryList().getFirst().dashboardId());
            assertThrows(InvalidNextTokenException.class, () -> client.searchDashboards(request -> request.awsAccountId(ACCOUNT).filters(filters).nextToken("invalid")));
            assertEquals(List.of(dataSetArn), client.describeDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(first)).dashboard().version().dataSetArns());
            for (long lifetime : List.of(14L, 601L, 4294967311L, Long.MAX_VALUE)) {
                SessionLifetimeInMinutesInvalidException error = assertThrows(SessionLifetimeInMinutesInvalidException.class,
                        () -> client.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT).userArn(principal)
                                .sessionLifetimeInMinutes(lifetime).experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(first)))));
                assertEquals(400, error.statusCode());
            }
            GenerateEmbedUrlForRegisteredUserResponse embed = client.generateEmbedUrlForRegisteredUser(request -> request.awsAccountId(ACCOUNT).userArn(principal)
                    .sessionLifetimeInMinutes(180L).experienceConfiguration(experience -> experience.dashboard(dashboard -> dashboard.initialDashboardId(first))));
            URI generated = URI.create(embed.embedUrl());
            URI local = endpoint.resolve(generated.getRawPath() + "?" + generated.getRawQuery());
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<String> content = http.send(HttpRequest.newBuilder(local).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, content.statusCode());
                assertTrue(content.body().contains("Dashboard " + first));
                assertEquals(403, http.send(HttpRequest.newBuilder(local).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            client.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(first));
            client.deleteDashboard(request -> request.awsAccountId(ACCOUNT).dashboardId(second));
            client.deleteDataSet(request -> request.awsAccountId(ACCOUNT).dataSetId(dataSet));
            client.deleteDataSource(request -> request.awsAccountId(ACCOUNT).dataSourceId(source));
        }
    }
}

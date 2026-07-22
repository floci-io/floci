package com.floci.test;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.resourceexplorer2.ResourceExplorer2Client;
import software.amazon.awssdk.services.resourceexplorer2.model.*;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SDK compatibility tests for Resource Explorer 2.
 *
 * <p>Every test deserializes the response through the real AWS SDK and asserts
 * on EVERY field. If the wire format is wrong (wrong casing, wrong structure,
 * missing fields), the SDK will return null for that field and the test fails.
 *
 * <p>This is the safety net that prevents wire format regressions.
 */
@DisplayName("Resource Explorer 2")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResourceExplorer2Test {

    private static ResourceExplorer2Client client;
    // The emulator auto-provisions an index only in the default region (us-east-1). CreateIndex
    // there is a no-op/conflict, so index create+delete is exercised against a second region
    // that starts with no index, matching AWS's one-index-per-region rule.
    private static ResourceExplorer2Client altRegionClient;
    private static S3Client s3;

    private static String defaultViewArn;
    private static String autoProvisionedIndexArn;
    private static String createdViewArn;
    private static String createdIndexArn;

    @BeforeAll
    static void setup() {
        client = TestFixtures.resourceExplorer2Client();
        altRegionClient = TestFixtures.resourceExplorer2Client(Region.US_WEST_2);
        s3 = TestFixtures.s3Client();
    }

    @AfterAll
    static void cleanup() {
        if (client != null) client.close();
        if (altRegionClient != null) altRegionClient.close();
        if (s3 != null) s3.close();
    }

    // -----------------------------------------------------------------------
    // GetIndex — every field must deserialize
    // -----------------------------------------------------------------------

    @Test
    @Order(1)
    void getIndexDeserializesAllFields() {
        GetIndexResponse response = client.getIndex(GetIndexRequest.builder().build());

        // Every field the SDK model exposes must be non-null and correctly typed
        assertThat(response.arn()).as("GetIndex.Arn").isNotBlank();
        assertThat(response.type()).as("GetIndex.Type").isNotNull();
        assertThat(response.type()).isIn(IndexType.LOCAL, IndexType.AGGREGATOR);
        assertThat(response.state()).as("GetIndex.State").isNotNull();
        assertThat(response.state()).isEqualTo(IndexState.ACTIVE);
        assertThat(response.createdAt()).as("GetIndex.CreatedAt").isNotNull();
        assertThat(response.lastUpdatedAt()).as("GetIndex.LastUpdatedAt").isNotNull();
        assertThat(response.tags()).as("GetIndex.Tags").isNotNull();
        assertThat(response.replicatingFrom()).as("GetIndex.ReplicatingFrom").isNotNull();
        assertThat(response.replicatingTo()).as("GetIndex.ReplicatingTo").isNotNull();

        autoProvisionedIndexArn = response.arn();
    }

    // -----------------------------------------------------------------------
    // GetDefaultView
    // -----------------------------------------------------------------------

    @Test
    @Order(2)
    void getDefaultViewReturnsViewArn() {
        GetDefaultViewResponse response = client.getDefaultView(GetDefaultViewRequest.builder().build());
        assertThat(response.viewArn()).as("GetDefaultView.ViewArn").isNotBlank();
        defaultViewArn = response.viewArn();
    }

    // -----------------------------------------------------------------------
    // GetView — every field of the View object
    // -----------------------------------------------------------------------

    @Test
    @Order(3)
    void getViewDeserializesAllFields() {
        GetViewResponse response = client.getView(GetViewRequest.builder()
                .viewArn(defaultViewArn).build());
        View view = response.view();

        assertThat(view).as("GetView.View").isNotNull();
        assertThat(view.viewArn()).as("View.ViewArn").isEqualTo(defaultViewArn);
        assertThat(view.viewName()).as("View.ViewName").isNotBlank();
        assertThat(view.owner()).as("View.Owner").isNotBlank();
        assertThat(view.scope()).as("View.Scope").isNotBlank();
        assertThat(view.lastUpdatedAt()).as("View.LastUpdatedAt").isNotNull();
        assertThat(view.includedProperties()).as("View.IncludedProperties").isNotNull();
        assertThat(view.filters()).as("View.Filters").isNotNull();
        assertThat(view.filters().filterString()).as("View.Filters.FilterString").isNotNull();

        // GetViewResponse has root-level Tags (separate from View)
        assertThat(response.tags()).as("GetViewResponse.Tags").isNotNull();
    }

    // -----------------------------------------------------------------------
    // ListSupportedResourceTypes — field-level assertions
    // -----------------------------------------------------------------------

    @Test
    @Order(4)
    void listSupportedResourceTypesDeserializesAllFields() {
        ListSupportedResourceTypesResponse response = client.listSupportedResourceTypes(
                ListSupportedResourceTypesRequest.builder().maxResults(50).build());

        assertThat(response.resourceTypes()).as("ResourceTypes").isNotEmpty();
        for (var rt : response.resourceTypes()) {
            assertThat(rt.resourceType()).as("SupportedResourceType.ResourceType").isNotBlank();
            assertThat(rt.service()).as("SupportedResourceType.Service").isNotBlank();
            // Verify format: "service:type"
            assertThat(rt.resourceType()).contains(":");
        }
    }

    // -----------------------------------------------------------------------
    // Cross-service setup
    // -----------------------------------------------------------------------

    @Test
    @Order(5)
    void createS3BucketForDiscovery() {
        assumeFalse(TestFixtures.isRealAws(), "Skip resource creation against real AWS");
        String bucketName = TestFixtures.uniqueName("re2");
        s3.createBucket(b -> b.bucket(bucketName));
    }

    // -----------------------------------------------------------------------
    // ListResources — every field of Resource objects
    // -----------------------------------------------------------------------

    @Test
    @Order(10)
    void listResourcesDeserializesAllResourceFields() {
        ListResourcesResponse response = client.listResources(ListResourcesRequest.builder().build());

        assertThat(response.viewArn()).as("ListResources.ViewArn").isNotBlank();
        assertThat(response.resources()).as("ListResources.Resources").isNotNull();

        // Must have at least one resource (the S3 bucket we created)
        assertThat(response.resources()).isNotEmpty();

        for (Resource resource : response.resources()) {
            assertThat(resource.arn()).as("Resource.Arn").isNotBlank();
            assertThat(resource.resourceType()).as("Resource.ResourceType").isNotBlank();
            assertThat(resource.service()).as("Resource.Service").isNotBlank();
            assertThat(resource.region()).as("Resource.Region").isNotBlank();
            assertThat(resource.owningAccountId()).as("Resource.OwningAccountId").isNotBlank();
            assertThat(resource.lastReportedAt()).as("Resource.LastReportedAt").isNotNull();
            assertThat(resource.properties()).as("Resource.Properties").isNotNull();
        }
    }

    @Test
    @Order(11)
    void listResourcesWithFilterReturnsCorrectTypes() {
        ListResourcesResponse response = client.listResources(ListResourcesRequest.builder()
                .filters(SearchFilter.builder().filterString("service:s3").build())
                .build());

        assertThat(response.resources()).isNotEmpty();
        for (Resource resource : response.resources()) {
            assertThat(resource.service()).as("Filtered resource service").isEqualTo("s3");
            assertThat(resource.resourceType()).startsWith("s3:");
        }
    }

    // -----------------------------------------------------------------------
    // Search — Count object structure is critical
    // -----------------------------------------------------------------------

    @Test
    @Order(12)
    void searchCountIsObjectWithTotalResourcesAndComplete() {
        SearchResponse response = client.search(SearchRequest.builder()
                .queryString("service:s3")
                .build());

        // Count must be an object, not an integer
        assertThat(response.count()).as("Search.Count").isNotNull();
        assertThat(response.count().totalResources()).as("Count.TotalResources").isNotNull();
        assertThat(response.count().totalResources()).isGreaterThanOrEqualTo(0L);
        assertThat(response.count().complete()).as("Count.Complete").isNotNull();

        assertThat(response.resources()).as("Search.Resources").isNotNull();
        assertThat(response.viewArn()).as("Search.ViewArn").isNotBlank();
    }

    @Test
    @Order(13)
    void searchCountTotalMatchesActualResourceCount() {
        SearchResponse response = client.search(SearchRequest.builder()
                .queryString("service:s3")
                .build());

        // TotalResources should match the number of matching resources
        assertThat(response.count().totalResources())
                .as("Count.TotalResources matches filtered count")
                .isEqualTo((long) response.resources().size());
    }

    // -----------------------------------------------------------------------
    // CreateView — response shape
    // -----------------------------------------------------------------------

    @Test
    @Order(20)
    void createViewResponseContainsViewObject() {
        assumeFalse(TestFixtures.isRealAws(), "Skip view mutation against real AWS");
        CreateViewResponse response = client.createView(CreateViewRequest.builder()
                .viewName("sdk-compat-view")
                .filters(SearchFilter.builder().filterString("service:s3").build())
                .includedProperties(IncludedProperty.builder().name("tags").build())
                .tags(Map.of("testKey", "testValue"))
                .build());

        View view = response.view();
        assertThat(view).as("CreateView.View").isNotNull();
        assertThat(view.viewArn()).as("View.ViewArn").isNotBlank();
        assertThat(view.owner()).as("View.Owner").isNotBlank();
        assertThat(view.lastUpdatedAt()).as("View.LastUpdatedAt").isNotNull();
        assertThat(view.includedProperties()).as("View.IncludedProperties").isNotEmpty();

        createdViewArn = view.viewArn();
    }

    // -----------------------------------------------------------------------
    // ListViews
    // -----------------------------------------------------------------------

    @Test
    @Order(21)
    void listViewsReturnsArns() {
        ListViewsResponse response = client.listViews(ListViewsRequest.builder().build());
        assertThat(response.views()).as("ListViews.Views").isNotEmpty();
        if (!TestFixtures.isRealAws()) {
            assertThat(response.views()).contains(createdViewArn);
        }
    }

    // -----------------------------------------------------------------------
    // BatchGetView — must have both Views and Errors
    // -----------------------------------------------------------------------

    @Test
    @Order(22)
    void batchGetViewReturnsViewsAndErrorsForMissing() {
        assumeFalse(TestFixtures.isRealAws(), "Depends on created view");
        String bogusArn = "arn:aws:resource-explorer-2:us-east-1:000000000000:view/does-not-exist/00000000-0000-0000-0000-000000000000";

        BatchGetViewResponse response = client.batchGetView(BatchGetViewRequest.builder()
                .viewArns(createdViewArn, bogusArn)
                .build());

        // Found views
        assertThat(response.views()).as("BatchGetView.Views").isNotEmpty();
        assertThat(response.views().get(0).viewArn())
                .as("BatchGetView found view")
                .isEqualTo(createdViewArn);

        // Errors for missing
        assertThat(response.errors()).as("BatchGetView.Errors").isNotEmpty();
        assertThat(response.errors().get(0).viewArn())
                .as("BatchGetView error ViewArn")
                .isEqualTo(bogusArn);
        assertThat(response.errors().get(0).errorMessage())
                .as("BatchGetView error message")
                .isNotBlank();
    }

    // -----------------------------------------------------------------------
    // UpdateView
    // -----------------------------------------------------------------------

    @Test
    @Order(23)
    void updateViewReturnsUpdatedView() {
        assumeFalse(TestFixtures.isRealAws(), "Depends on created view");
        UpdateViewResponse response = client.updateView(UpdateViewRequest.builder()
                .viewArn(createdViewArn)
                .includedProperties(IncludedProperty.builder().name("tags").build())
                .build());

        assertThat(response.view()).as("UpdateView.View").isNotNull();
        assertThat(response.view().viewArn()).isEqualTo(createdViewArn);
        assertThat(response.view().lastUpdatedAt()).as("Updated.LastUpdatedAt").isNotNull();
    }

    // -----------------------------------------------------------------------
    // DeleteView
    // -----------------------------------------------------------------------

    @Test
    @Order(24)
    void deleteViewReturnsViewArn() {
        assumeFalse(TestFixtures.isRealAws(), "Depends on created view");
        DeleteViewResponse response = client.deleteView(DeleteViewRequest.builder()
                .viewArn(createdViewArn).build());
        assertThat(response.viewArn()).as("DeleteView.ViewArn").isEqualTo(createdViewArn);
    }

    // -----------------------------------------------------------------------
    // CreateIndex — response must be trimmed to {Arn, State, CreatedAt}
    // -----------------------------------------------------------------------

    @Test
    @Order(30)
    void createIndexReturnsArnStateCreatedAt() {
        assumeFalse(TestFixtures.isRealAws(), "Skip index mutation against real AWS");
        CreateIndexResponse response = altRegionClient.createIndex(CreateIndexRequest.builder()
                .tags(Map.of("env", "test"))
                .build());

        assertThat(response.arn()).as("CreateIndex.Arn").isNotBlank();
        assertThat(response.state()).as("CreateIndex.State").isNotNull();
        assertThat(response.createdAt()).as("CreateIndex.CreatedAt").isNotNull();

        createdIndexArn = response.arn();
    }

    // -----------------------------------------------------------------------
    // ListIndexes — each item has Arn, Region, Type
    // -----------------------------------------------------------------------

    @Test
    @Order(31)
    void listIndexesDeserializesItemFields() {
        ListIndexesResponse response = client.listIndexes(ListIndexesRequest.builder().build());

        assertThat(response.indexes()).as("ListIndexes.Indexes").isNotEmpty();
        for (var idx : response.indexes()) {
            assertThat(idx.arn()).as("Index.Arn").isNotBlank();
            assertThat(idx.region()).as("Index.Region").isNotBlank();
            assertThat(idx.type()).as("Index.Type").isNotNull();
        }
    }

    // -----------------------------------------------------------------------
    // UpdateIndexType — PascalCase response fields
    // -----------------------------------------------------------------------

    @Test
    @Order(32)
    void updateIndexTypeDeserializesAllFields() {
        assumeFalse(TestFixtures.isRealAws(), "Skip index mutation against real AWS");
        UpdateIndexTypeResponse response = client.updateIndexType(UpdateIndexTypeRequest.builder()
                .arn(autoProvisionedIndexArn)
                .type(IndexType.AGGREGATOR)
                .build());

        assertThat(response.arn()).as("UpdateIndexType.Arn").isNotBlank();
        assertThat(response.state()).as("UpdateIndexType.State").isNotNull();
        assertThat(response.type()).as("UpdateIndexType.Type").isNotNull();
        assertThat(response.lastUpdatedAt()).as("UpdateIndexType.LastUpdatedAt").isNotNull();
    }

    // -----------------------------------------------------------------------
    // DeleteIndex — must include LastUpdatedAt
    // -----------------------------------------------------------------------

    @Test
    @Order(33)
    void deleteIndexReturnsArnLastUpdatedAtState() {
        assumeFalse(TestFixtures.isRealAws(), "Depends on created index");
        DeleteIndexResponse response = altRegionClient.deleteIndex(DeleteIndexRequest.builder()
                .arn(createdIndexArn)
                .build());

        assertThat(response.arn()).as("DeleteIndex.Arn").isEqualTo(createdIndexArn);
        assertThat(response.state()).as("DeleteIndex.State").isNotNull();
        assertThat(response.lastUpdatedAt()).as("DeleteIndex.LastUpdatedAt").isNotNull();
    }

    // -----------------------------------------------------------------------
    // Error handling — invalid input must return proper error, not 500
    // -----------------------------------------------------------------------

    @Test
    @Order(40)
    void getMissingViewReturnsError() {
        // Real AWS: 401 UnauthorizedException (auth checked before existence for wrong-account ARN)
        // Floci: 404 ResourceNotFoundException
        assertThatThrownBy(() -> client.getView(GetViewRequest.builder()
                .viewArn("arn:aws:resource-explorer-2:us-east-1:000000000000:view/nope/00000000-0000-0000-0000-000000000000")
                .build()))
                .isInstanceOf(ResourceExplorer2Exception.class);
    }

    @Test
    @Order(41)
    void deleteMissingIndexReturnsError() {
        // Real AWS: 403 AccessDeniedException (auth checked before existence for wrong-account ARN)
        // Floci: 404 ResourceNotFoundException
        assertThatThrownBy(() -> client.deleteIndex(DeleteIndexRequest.builder()
                .arn("arn:aws:resource-explorer-2:us-east-1:000000000000:index/nonexistent")
                .build()))
                .isInstanceOf(ResourceExplorer2Exception.class);
    }

    // -----------------------------------------------------------------------
    // CLASS 3: Wire Format — Nested Structure Deserialization
    // Drills into nested objects to verify the SDK can parse every level.
    // If any nested field uses wrong casing or wrong structure, the SDK
    // returns null and these tests fail.
    // -----------------------------------------------------------------------

    @Test
    @Order(50)
    void viewFiltersFilterStringDeserializes() {
        assumeFalse(TestFixtures.isRealAws(), "Creates/deletes views");
        // Create view with filter, then get it and drill into nested Filters object
        CreateViewResponse createResp = client.createView(CreateViewRequest.builder()
                .viewName("nested-test-view")
                .filters(SearchFilter.builder().filterString("service:s3").build())
                .includedProperties(IncludedProperty.builder().name("tags").build())
                .build());
        String arn = createResp.view().viewArn();

        GetViewResponse getResp = client.getView(GetViewRequest.builder().viewArn(arn).build());
        View view = getResp.view();

        // Drill into nested Filters.FilterString
        assertThat(view.filters()).as("View.Filters").isNotNull();
        assertThat(view.filters().filterString()).as("Filters.FilterString").isEqualTo("service:s3");

        // Drill into nested IncludedProperties[].Name
        assertThat(view.includedProperties()).as("View.IncludedProperties").hasSize(1);
        assertThat(view.includedProperties().get(0).name())
                .as("IncludedProperty.Name").isEqualTo("tags");

        // Drill into scope
        assertThat(view.scope()).as("View.Scope").contains("arn:aws:iam:");

        // Cleanup
        client.deleteView(DeleteViewRequest.builder().viewArn(arn).build());
    }

    @Test
    @Order(51)
    void resourcePropertiesTagDataDeserializes() {
        // List resources — S3 bucket should have tag Properties
        ListResourcesResponse response = client.listResources(ListResourcesRequest.builder()
                .filters(SearchFilter.builder().filterString("service:s3").build())
                .build());

        assertThat(response.resources()).isNotEmpty();
        Resource s3Resource = response.resources().get(0);

        // Properties is a list of ResourceProperty
        assertThat(s3Resource.properties()).as("Resource.Properties").isNotNull();
        // Note: Properties may be empty if no tags — we just verify the list itself deserializes.
        // The integration test handles tag-specific assertions.
    }

    @Test
    @Order(52)
    void searchCountNestedFieldsDeserialize() {
        SearchResponse response = client.search(SearchRequest.builder()
                .queryString("")
                .maxResults(1)
                .build());

        ResourceCount count = response.count();
        assertThat(count).as("Search.Count object").isNotNull();

        // These would be null if Count was an integer instead of an object
        assertThat(count.totalResources()).as("Count.TotalResources").isNotNull();
        assertThat(count.totalResources()).isGreaterThanOrEqualTo(0L);

        // Complete is a boolean — just verify it deserializes (not null)
        // Value depends on whether there are >1 matching resources
        assertThat(count.complete()).as("Count.Complete").isNotNull();
    }

    @Test
    @Order(53)
    void searchCountCompleteIsTrueWhenAllResultsFit() {
        SearchResponse response = client.search(SearchRequest.builder()
                .queryString("")
                .maxResults(1000)
                .build());

        assertThat(response.count().complete())
                .as("Count.Complete when not paginating").isTrue();
        assertThat(response.nextToken()).as("NextToken absent on last page").isNull();
    }

    @Test
    @Order(54)
    void indexReplicatingFieldsAreEmptyLists() {
        GetIndexResponse response = client.getIndex(GetIndexRequest.builder().build());

        // ReplicatingFrom and ReplicatingTo should deserialize as lists, not null
        assertThat(response.replicatingFrom())
                .as("GetIndex.ReplicatingFrom").isNotNull().isEmpty();
        assertThat(response.replicatingTo())
                .as("GetIndex.ReplicatingTo").isNotNull().isEmpty();
    }

    @Test
    @Order(55)
    void batchGetViewErrorFieldsDeserialize() {
        assumeFalse(TestFixtures.isRealAws(), "Uses floci-specific bogus ARN format");
        String bogus = "arn:aws:resource-explorer-2:us-east-1:000000000000:view/x/00000000-0000-0000-0000-000000000000";

        BatchGetViewResponse response = client.batchGetView(BatchGetViewRequest.builder()
                .viewArns(bogus)
                .build());

        assertThat(response.errors()).as("Errors list").hasSize(1);
        BatchGetViewError error = response.errors().get(0);
        assertThat(error.viewArn()).as("Error.ViewArn").isEqualTo(bogus);
        assertThat(error.errorMessage()).as("Error.ErrorMessage").isNotBlank();
    }

    @Test
    @Order(56)
    void listResourcesPaginationTokenRoundTrips() {
        assumeFalse(TestFixtures.isRealAws(), "Floci-specific pagination behavior");
        // Page 1
        ListResourcesResponse page1 = client.listResources(ListResourcesRequest.builder()
                .maxResults(1)
                .build());
        assertThat(page1.resources()).hasSize(1);
        assertThat(page1.nextToken()).as("Page1 NextToken").isNotNull();

        // Page 2 using token from page 1
        ListResourcesResponse page2 = client.listResources(ListResourcesRequest.builder()
                .maxResults(1)
                .nextToken(page1.nextToken())
                .build());
        assertThat(page2.resources()).hasSize(1);

        // Page 1 and Page 2 should return different resources
        assertThat(page2.resources().get(0).arn())
                .as("Page 2 returns different resource")
                .isNotEqualTo(page1.resources().get(0).arn());
    }

    @Test
    @Order(57)
    void updateViewNestedFieldsDeserialize() {
        assumeFalse(TestFixtures.isRealAws(), "Creates/deletes views");
        // Create, update, verify nested fields roundtrip
        CreateViewResponse created = client.createView(CreateViewRequest.builder()
                .viewName("update-nested-test")
                .build());
        String arn = created.view().viewArn();

        UpdateViewResponse updated = client.updateView(UpdateViewRequest.builder()
                .viewArn(arn)
                .filters(SearchFilter.builder().filterString("region:us-east-1").build())
                .includedProperties(IncludedProperty.builder().name("tags").build())
                .build());

        View view = updated.view();
        assertThat(view.viewArn()).isEqualTo(arn);
        assertThat(view.filters()).as("Updated Filters").isNotNull();
        assertThat(view.filters().filterString())
                .as("Updated FilterString").isEqualTo("region:us-east-1");
        assertThat(view.includedProperties()).hasSize(1);
        assertThat(view.includedProperties().get(0).name()).isEqualTo("tags");

        client.deleteView(DeleteViewRequest.builder().viewArn(arn).build());
    }
}

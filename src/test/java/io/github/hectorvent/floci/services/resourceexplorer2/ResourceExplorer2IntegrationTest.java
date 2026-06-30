package io.github.hectorvent.floci.services.resourceexplorer2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ResourceExplorer2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260528/us-east-1/resource-explorer-2/aws4_request";
    private static final String DYNAMO_TYPE = "application/x-amz-json-1.0";

    private static boolean fixturesProvisioned = false;

    private record IndexRef(String auth, String arn) {}

    private final List<String> viewArnsToCleanup = new ArrayList<>();
    private final List<IndexRef> indexArnsToCleanup = new ArrayList<>();

    private String trackView(String viewArn) {
        viewArnsToCleanup.add(viewArn);
        return viewArn;
    }

    private String trackIndex(String auth, String arn) {
        indexArnsToCleanup.add(new IndexRef(auth, arn));
        return arn;
    }

    @AfterEach
    void cleanupEphemeralResources() {
        // Best-effort teardown — no assertions. Runs even when the test failed,
        // so ephemeral views/indexes never leak into later tests or reruns.
        for (String viewArn : viewArnsToCleanup) {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + viewArn + "\"}")
            .when()
                .post("/DeleteView");
        }
        viewArnsToCleanup.clear();

        for (IndexRef ref : indexArnsToCleanup) {
            given()
                .header("Authorization", ref.auth())
                .contentType("application/json")
                .body("{\"Arn\": \"" + ref.arn() + "\"}")
            .when()
                .post("/DeleteIndex");
        }
        indexArnsToCleanup.clear();
    }

    @BeforeEach
    void provisionFixturesOnce() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        if (fixturesProvisioned) return;

        // Create S3 bucket used by ListResources, DataProvenance, and FilterSemantics groups
        given()
        .when()
            .put("/re2-test-bucket")
        .then()
            .statusCode(200);

        // Create DynamoDB table with tags used by ListResources, DataProvenance, and FilterSemantics groups
        given()
            .header("X-Amz-Target", "DynamoDB_20120810.CreateTable")
            .contentType(DYNAMO_TYPE)
            .body("""
                {
                    "TableName": "re2-test-table",
                    "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}],
                    "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}],
                    "ProvisionedThroughput": {"ReadCapacityUnits": 1, "WriteCapacityUnits": 1},
                    "Tags": [{"Key": "env", "Value": "test"}]
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TableDescription.TableName", equalTo("re2-test-table"));

        // Lambda function (Resource Explorer 2 provider coverage)
        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "re2-test-fn",
                    "Runtime": "python3.11",
                    "Role": "arn:aws:iam::000000000000:role/re2-test-role",
                    "Handler": "index.handler",
                    "Tags": {"env": "test"}
                }
                """)
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        // SNS topic
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "re2-test-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // KMS key
        given()
            .header("X-Amz-Target", "TrentService.CreateKey")
            .contentType("application/x-amz-json-1.1")
            .body("""
                {"Description": "re2-test-key", "KeyUsage": "ENCRYPT_DECRYPT", "KeySpec": "SYMMETRIC_DEFAULT"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // SQS queue
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "re2-test-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // ECR repository
        given()
            .header("X-Amz-Target", "AmazonEC2ContainerRegistry_V20150921.CreateRepository")
            .contentType("application/x-amz-json-1.1")
            .body("""
                {"repositoryName": "re2-test-repo"}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        fixturesProvisioned = true;
    }

    @Nested
    class AutoProvisioning {

        @Test
        void autoProvisionedIndexExists() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
            .when()
                .post("/GetIndex")
            .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("Type", notNullValue())
                .body("State", equalTo("ACTIVE"))
                .body("CreatedAt", notNullValue())
                .body("LastUpdatedAt", notNullValue())
                .body("Tags", notNullValue())
                .body("ReplicatingFrom", notNullValue())
                .body("ReplicatingTo", notNullValue());
        }

        @Test
        void autoProvisionedDefaultViewExists() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
            .when()
                .post("/GetDefaultView")
            .then()
                .statusCode(200)
                .body("ViewArn", notNullValue());
        }
    }

    @Nested
    class ListResources {

        @Test
        void listResourcesWithNoFilterReturnsAll() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources", notNullValue())
                .body("ViewArn", notNullValue());
        }

        @Test
        void listResourcesFilteredByS3ResourceType() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "resourcetype:s3:bucket"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources", notNullValue())
                .body("Resources.findAll { it.ResourceType != 's3:bucket' }.size()", equalTo(0));
        }

        @Test
        void listResourcesFilteredByDynamoDbService() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:dynamodb"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.findAll { it.Service != 'dynamodb' }.size()", equalTo(0));
        }

        @Test
        void listResourcesNegatedServiceExcludesS3() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "-service:s3"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.findAll { it.Service == 's3' }.size()", equalTo(0));
        }

        @Test
        void listResourcesInvalidFilterReturns400() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "hello world"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(400);
        }
    }

    @Nested
    class Search {

        @Test
        void searchWithQueryStringReturnsCountObject() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"QueryString": "service:s3"}
                    """)
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Count.TotalResources", greaterThanOrEqualTo(0))
                .body("Count.Complete", equalTo(true))
                .body("Resources", notNullValue())
                .body("ViewArn", notNullValue());
        }

        @Test
        void searchWithEmptyQueryReturnsAllResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"QueryString": ""}
                    """)
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Count.TotalResources", greaterThanOrEqualTo(0))
                .body("Count.Complete", equalTo(true))
                .body("Resources", notNullValue());
        }
    }

    @Nested
    class ListSupportedResourceTypes {

        @Test
        void listSupportedResourceTypesReturnsKnownTypes() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/ListSupportedResourceTypes")
            .then()
                .statusCode(200)
                .body("ResourceTypes", notNullValue())
                .body("ResourceTypes.size()", greaterThan(0))
                .body("ResourceTypes.Service", hasItems("s3", "rds", "dynamodb", "elasticache", "es", "lambda", "sns", "kms", "sqs", "ecr"));
        }
    }

    @Nested
    class LambdaResources {
        @Test
        void lambdaFunctionSurfacesViaListResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:lambda"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service != 'lambda' }.size()", equalTo(0))
                .body("Resources.findAll { it.ResourceType == 'lambda:function' && it.Region == 'us-east-1' }.size()", greaterThan(0));
        }
    }

    @Nested
    class SnsResources {
        @Test
        void snsTopicSurfacesViaListResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:sns"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service != 'sns' }.size()", equalTo(0))
                .body("Resources.findAll { it.ResourceType == 'sns:topic' && it.Region == 'us-east-1' }.size()", greaterThan(0));
        }
    }

    @Nested
    class KmsResources {
        @Test
        void kmsKeySurfacesViaListResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:kms"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service != 'kms' }.size()", equalTo(0))
                .body("Resources.findAll { it.ResourceType == 'kms:key' && it.Region == 'us-east-1' }.size()", greaterThan(0));
        }
    }

    @Nested
    class SqsResources {
        @Test
        void sqsQueueSurfacesViaListResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:sqs"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service != 'sqs' }.size()", equalTo(0))
                .body("Resources.findAll { it.ResourceType == 'sqs:queue' && it.Region == 'us-east-1' }.size()", greaterThan(0));
        }
    }

    @Nested
    class EcrResources {
        @Test
        void ecrRepositorySurfacesViaListResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:ecr"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service != 'ecr' }.size()", equalTo(0))
                .body("Resources.findAll { it.ResourceType == 'ecr:repository' && it.Region == 'us-east-1' }.size()", greaterThan(0));
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ViewCrud {

        private String viewArn;

        @Test
        @Order(1)
        void createViewWithFilter() {
            viewArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {
                        "ViewName": "test-view",
                        "Filters": {"FilterString": "service:s3"},
                        "IncludedProperties": [{"Name": "tags"}]
                    }
                    """)
            .when()
                .post("/CreateView")
            .then()
                .statusCode(200)
                .body("View.ViewArn", notNullValue())
                .body("View.Filters.FilterString", equalTo("service:s3"))
                .extract().path("View.ViewArn");
        }

        @Test
        @Order(2)
        void getViewReturnsCreatedView() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + viewArn + "\"}")
            .when()
                .post("/GetView")
            .then()
                .statusCode(200)
                .body("View.ViewArn", equalTo(viewArn));
        }

        @Test
        @Order(3)
        void updateView() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {
                        "ViewArn": "%s",
                        "Filters": {"FilterString": "service:dynamodb"},
                        "IncludedProperties": [{"Name": "tags"}]
                    }
                    """.formatted(viewArn))
            .when()
                .post("/UpdateView")
            .then()
                .statusCode(200)
                .body("View.Filters.FilterString", equalTo("service:dynamodb"));
        }

        @Test
        @Order(4)
        void listViewsContainsCreatedView() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/ListViews")
            .then()
                .statusCode(200)
                .body("Views", hasItem(viewArn));
        }

        @Test
        @Order(5)
        void batchGetViewReturnsErrorsForMissingArns() {
            String bogusArn = "arn:aws:resource-explorer-2:us-east-1:000000000000:view/nope/00000000-0000-0000-0000-000000000000";
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArns\": [\"" + viewArn + "\", \"" + bogusArn + "\"]}")
            .when()
                .post("/BatchGetView")
            .then()
                .statusCode(200)
                .body("Views.size()", equalTo(1))
                .body("Views[0].ViewArn", equalTo(viewArn))
                .body("Errors.size()", equalTo(1))
                .body("Errors[0].ViewArn", equalTo(bogusArn))
                .body("Errors[0].ErrorMessage", notNullValue());
        }

        @Test
        @Order(6)
        void associateDefaultView() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + viewArn + "\"}")
            .when()
                .post("/AssociateDefaultView")
            .then()
                .statusCode(200)
                .body("ViewArn", equalTo(viewArn));
        }

        @Test
        @Order(7)
        void getDefaultViewReturnsMostRecent() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
            .when()
                .post("/GetDefaultView")
            .then()
                .statusCode(200)
                .body("ViewArn", equalTo(viewArn));
        }

        @Test
        @Order(8)
        void disassociateDefaultView() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
            .when()
                .post("/DisassociateDefaultView")
            .then()
                .statusCode(200);
        }

        @Test
        @Order(9)
        void deleteView() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + viewArn + "\"}")
            .when()
                .post("/DeleteView")
            .then()
                .statusCode(200)
                .body("ViewArn", equalTo(viewArn));
        }

        @Test
        @Order(10)
        void restoreDefaultViewAfterCrudTests() {
            // Re-associate the auto-provisioned default view so other groups work
            String autoViewArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/ListViews")
            .then()
                .extract().path("Views[0]");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + autoViewArn + "\"}")
            .when()
                .post("/AssociateDefaultView")
            .then()
                .statusCode(200);
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class IndexCrud {

        @Test
        @Order(1)
        void listIndexesContainsAutoProvisionedIndex() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
            .when()
                .post("/ListIndexes")
            .then()
                .statusCode(200)
                .body("Indexes", notNullValue())
                .body("Indexes.size()", greaterThan(0));
        }

        @Test
        @Order(2)
        void updateIndexType() {
            String indexArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
            .when()
                .post("/GetIndex")
            .then()
                .statusCode(200)
                .extract().path("Arn");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Arn\": \"" + indexArn + "\", \"Type\": \"LOCAL\"}")
            .when()
                .post("/UpdateIndexType")
            .then()
                .statusCode(200)
                .body("Arn", equalTo(indexArn))
                .body("Type", equalTo("LOCAL"))
                .body("State", notNullValue())
                .body("LastUpdatedAt", notNullValue());

            // Restore to AGGREGATOR
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Arn\": \"" + indexArn + "\", \"Type\": \"AGGREGATOR\"}")
            .when()
                .post("/UpdateIndexType")
            .then()
                .statusCode(200);
        }
    }

    @Nested
    class ErrorCases {

        @Test
        void getMissingViewReturns404() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"arn:aws:resource-explorer-2:us-east-1:000000000000:view/nonexistent/abc\"}")
            .when()
                .post("/GetView")
            .then()
                .statusCode(404);
        }

        @Test
        void invalidUpdateIndexTypeReturns400Not500() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Arn\": \"some-arn\", \"Type\": \"INVALID_TYPE\"}")
            .when()
                .post("/UpdateIndexType")
            .then()
                .statusCode(400);
        }

        @Test
        void deleteIndexIncludesLastUpdatedAt() {
            String arn = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260528/eu-west-1/resource-explorer-2/aws4_request")
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/CreateIndex")
            .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("State", notNullValue())
                .body("CreatedAt", notNullValue())
                .extract().path("Arn");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Arn\": \"" + arn + "\"}")
            .when()
                .post("/DeleteIndex")
            .then()
                .statusCode(200)
                .body("Arn", equalTo(arn))
                .body("State", notNullValue())
                .body("LastUpdatedAt", notNullValue());
        }
    }

    @Nested
    class ConditionalResponseFields {

        @Test
        void searchCountCompleteIsTrueWhenFewResources() {
            // Complete=true means total matched resources <= 1000 (no cap was applied).
            // With only a handful of test resources, Complete must be TRUE on every page,
            // even when MaxResults=1 causes pagination (NextToken is still emitted).
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"QueryString": "", "MaxResults": 1}
                    """)
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Count.Complete", equalTo(true))
                .body("Count.TotalResources", greaterThan(1))
                .body("NextToken", notNullValue());
        }

        @Test
        void searchCountCompleteIsTrueWhenNotPaginating() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"QueryString": "", "MaxResults": 1000}
                    """)
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Count.Complete", equalTo(true))
                .body("NextToken", nullValue());
        }

        @Test
        void listResourcesNextTokenSuppressedAtMaxResults1000() {
            // First confirm there are multiple pages when MaxResults=1 — this proves
            // NextToken absence at MaxResults=1000 is NOT simply "everything fits on
            // one page" but is the deliberate suppression rule for MaxResults==1000.
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"MaxResults": 1}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(1))
                .body("NextToken", notNullValue());

            // Now verify that MaxResults=1000 suppresses NextToken even though more
            // pages would exist at smaller page sizes. The production rule is:
            // NextToken must be absent on ListResources when MaxResults==1000.
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"MaxResults": 1000}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("NextToken", nullValue());
        }

        @Test
        void listResourcesNextTokenPresentWhenMorePages() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"MaxResults": 1}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(1))
                .body("NextToken", notNullValue());
        }

        @Test
        void propertiesEmptyWhenViewExcludesTags() {
            String noTagsViewArn = trackView(given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"ViewName": "no-tags-view", "IncludedProperties": []}
                    """)
            .when()
                .post("/CreateView")
            .then()
                .statusCode(200)
                .extract().path("View.ViewArn"));

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + noTagsViewArn + "\"}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources[0].get('Properties').size()", equalTo(0));
        }

        @Test
        void propertiesPopulatedWhenViewIncludesTags() {
            // Target the known-tagged fixture table by ARN. A positional Resources[0] is
            // unsafe: the full test suite shares one emulator instance, so other classes'
            // untagged DynamoDB tables land in this same service:dynamodb result set and any
            // of them may sort ahead of the tagged fixture.
            String table = "Resources.find { it.Arn.contains('re2-test-table') }";
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:dynamodb"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body(table, notNullValue())
                .body(table + ".get('Properties').size()", greaterThan(0))
                .body(table + ".get('Properties')[0].Name", equalTo("tags"))
                .body(table + ".get('Properties')[0].Data.size()", greaterThan(0))
                .body(table + ".get('Properties')[0].Data[0].Key", notNullValue())
                .body(table + ".get('Properties')[0].Data[0].Value", notNullValue())
                .body(table + ".get('Properties')[0].LastReportedAt", notNullValue());
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DataProvenance {

        @Test
        @Order(1)
        void s3BucketTagsAppearViaTagFilter() {
            given()
                .contentType("application/xml")
                .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Tagging xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <TagSet>
                        <Tag><Key>env</Key><Value>staging</Value></Tag>
                      </TagSet>
                    </Tagging>
                    """)
            .when()
                .put("/re2-test-bucket?tagging")
            .then()
                .statusCode(anyOf(equalTo(200), equalTo(204)));

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:env=staging"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service == 's3' }.size()", greaterThan(0));
        }

        @Test
        @Order(2)
        void dynamoDbTagsFromCreateTableAppearViaTagFilter() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:env=test"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service == 'dynamodb' }.size()", greaterThan(0));
        }

        @Test
        @Order(3)
        void dynamoDbTagResourceUpdatesResourceExplorer() {
            String tableArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "resourcetype:dynamodb:table"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources[0].Arn");

            given()
                .header("X-Amz-Target", "DynamoDB_20120810.TagResource")
                .contentType(DYNAMO_TYPE)
                .body("""
                    {
                        "ResourceArn": "%s",
                        "Tags": [{"Key": "team", "Value": "platform"}]
                    }
                    """.formatted(tableArn))
            .when()
                .post("/")
            .then()
                .statusCode(200);

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:team=platform"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0));
        }

        @Test
        @Order(4)
        void tagFilterDoesNotMatchUntaggedResources() {
            given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260528/us-east-1/rds/aws4_request")
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateDBInstance")
                .formParam("DBInstanceIdentifier", "re2-untagged-db")
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
            .when()
                .post("/")
            .then()
                .statusCode(200);

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:env=staging service:rds"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(0));
        }

        @Test
        @Order(5)
        void tagAllMatchesOnlyTaggedResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:all"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.get('Properties').size() > 0 }.size()",
                        equalTo((int) given()
                            .header("Authorization", AUTH)
                            .contentType("application/json")
                            .body("{\"Filters\": {\"FilterString\": \"tag:all\"}}")
                            .post("/ListResources")
                            .then().extract().path("Resources.size()")));
        }

        @Test
        @Order(6)
        void tagNoneMatchesOnlyUntaggedResources() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:none"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.get('Properties').size() > 0 }.size()", equalTo(0));
        }

        @Test
        @Order(7)
        void tagKeyFilterMatchesAcrossServices() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag.key:env"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThanOrEqualTo(2))
                .body("Resources.Service", hasItems("s3", "dynamodb"));
        }

        @Test
        @Order(8)
        void tagValueFilterMatchesCorrectValue() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag.value:staging"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.Service == 'dynamodb' }.size()", equalTo(0));
        }

        @Test
        @Order(9)
        void rdsTagsViaResourceGroupsTaggingApi() {
            String rdsArn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "resourcetype:rds:db"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources[0].Arn");

            given()
                .header("X-Amz-Target", "ResourceGroupsTaggingAPI_20170126.TagResources")
                .contentType("application/x-amz-json-1.1")
                .body("""
                    {
                        "ResourceARNList": ["%s"],
                        "Tags": {"rgta-tag": "rgta-value"}
                    }
                    """.formatted(rdsArn))
            .when()
                .post("/")
            .then()
                .statusCode(200);

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "tag:rgta-tag=rgta-value"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources[0].Arn", equalTo(rdsArn));
        }
    }

    @Nested
    class EdgeBoundaryBehavior {

        @Test
        void listResourcesMaxResults1ReturnsSingleResource() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"MaxResults": 1}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(1))
                .body("NextToken", notNullValue());
        }

        @Test
        void listResourcesEmptyResultSet() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:nonexistent"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(0))
                .body("NextToken", nullValue());
        }

        @Test
        void paginationReturnsAllResourcesAcrossPages() {
            int totalCount = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources.size()");

            Set<String> allArns = new HashSet<>();
            String nextToken = null;
            int pages = 0;
            int maxPages = totalCount + 5;

            do {
                String body = nextToken == null
                        ? "{\"MaxResults\": 1}"
                        : "{\"MaxResults\": 1, \"NextToken\": \"" + nextToken + "\"}";

                var response = given()
                    .header("Authorization", AUTH)
                    .contentType("application/json")
                    .body(body)
                .when()
                    .post("/ListResources")
                .then()
                    .statusCode(200)
                    .extract().response();

                List<String> arns = response.path("Resources.Arn");
                allArns.addAll(arns);
                nextToken = response.path("NextToken");
                pages++;
            } while (nextToken != null && pages < maxPages);

            assertEquals(totalCount, allArns.size(), "Paginated results should cover all resources");
        }

        @Test
        void listResourcesMaxResultsGreaterThanTotalReturnsAll() {
            int totalCount = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources.size()");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 999}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(totalCount))
                .body("NextToken", nullValue());
        }

        @Test
        void searchPaginationWithNextToken() {
            String nextToken = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"QueryString": "", "MaxResults": 1}
                    """)
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Count.Complete", equalTo(true))
                .body("Resources.size()", equalTo(1))
                .extract().path("NextToken");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"QueryString\": \"\", \"MaxResults\": 1, \"NextToken\": \"" + nextToken + "\"}")
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(1))
                .body("Count.TotalResources", greaterThan(1));
        }

        @Test
        void listSupportedResourceTypesHasNextTokenWhenNeeded() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"MaxResults": 1}
                    """)
            .when()
                .post("/ListSupportedResourceTypes")
            .then()
                .statusCode(200)
                .body("ResourceTypes.size()", equalTo(1))
                .body("NextToken", notNullValue());
        }

        @Test
        void listViewsHasNextTokenWhenNeeded() {
            String tempView = trackView(given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewName\": \"temp-pagination-view\"}")
            .when()
                .post("/CreateView")
            .then()
                .extract().path("View.ViewArn"));

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1}")
            .when()
                .post("/ListViews")
            .then()
                .statusCode(200)
                .body("Views.size()", equalTo(1))
                .body("NextToken", notNullValue());
        }

        @Test
        void listIndexesHasNextTokenWhenNeeded() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1}")
            .when()
                .post("/ListIndexes")
            .then()
                .statusCode(200)
                .body("Indexes.size()", lessThanOrEqualTo(1));
        }
    }

    @Nested
    class CrossServiceDataConsistency {

        @Test
        void allResourcesHaveRequiredFields() {
            List<Map<String, Object>> resources = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .extract().path("Resources");

            for (var resource : resources) {
                assertNotNull(resource.get("Arn"), "Resource missing Arn: " + resource);
                assertNotNull(resource.get("ResourceType"), "Resource missing ResourceType: " + resource);
                assertNotNull(resource.get("Service"), "Resource missing Service: " + resource);
                assertNotNull(resource.get("Region"), "Resource missing Region: " + resource);
                assertNotNull(resource.get("OwningAccountId"), "Resource missing OwningAccountId: " + resource);
                assertNotNull(resource.get("LastReportedAt"), "Resource missing LastReportedAt: " + resource);
                assertNotNull(resource.get("Properties"), "Resource missing Properties: " + resource);
            }
        }

        @Test
        void resourceTypeFormatIsServiceColonType() {
            List<String> types = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources.ResourceType");

            for (String type : types) {
                assertTrue(type.contains(":"), "ResourceType must be in service:type format, got: " + type);
            }
        }

        @Test
        void serviceFieldMatchesResourceTypePrefix() {
            List<Map<String, Object>> resources = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources");

            for (var resource : resources) {
                String service = (String) resource.get("Service");
                String resourceType = (String) resource.get("ResourceType");
                assertTrue(
                        resourceType.startsWith(service + ":"),
                        "Service '" + service + "' doesn't match ResourceType prefix '" + resourceType + "'");
            }
        }

        @Test
        void arnContainsServiceAndRegion() {
            List<Map<String, Object>> resources = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources");

            for (var resource : resources) {
                String arn = (String) resource.get("Arn");
                String service = (String) resource.get("Service");
                assertTrue(arn.startsWith("arn:aws:"),
                        "ARN must start with arn:aws:, got: " + arn);
                assertTrue(arn.contains(service),
                        "ARN should contain service '" + service + "', got: " + arn);
            }
        }
    }

    @Nested
    class FilterSemantics {

        @Test
        void filterByServiceReturnsOnlyThatService() {
            for (String svc : List.of("s3", "dynamodb", "rds")) {
                List<String> services = given()
                    .header("Authorization", AUTH)
                    .contentType("application/json")
                    .body("{\"Filters\": {\"FilterString\": \"service:" + svc + "\"}}")
                .when()
                    .post("/ListResources")
                .then()
                    .statusCode(200)
                    .extract().path("Resources.Service");

                for (String s : services) {
                    assertEquals(svc, s,
                            "service:" + svc + " filter returned wrong service: " + s);
                }
            }
        }

        @Test
        void filterByResourceTypeIsExact() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "resourcetype:dynamodb:table"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.ResourceType != 'dynamodb:table' }.size()", equalTo(0));
        }

        @Test
        void filterByResourceTypeCommaOr() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "resourcetype:s3:bucket,dynamodb:table"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThanOrEqualTo(2))
                .body("Resources.ResourceType",
                        everyItem(anyOf(equalTo("s3:bucket"), equalTo("dynamodb:table"))));
        }

        @Test
        void filterByRegionExact() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "region:us-east-1"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.findAll { it.Region != 'us-east-1' }.size()", equalTo(0));
        }

        @Test
        void filterByRegionWildcard() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "region:us*"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { !it.Region.startsWith('us') }.size()", equalTo(0));
        }

        @Test
        void negatedFilterExcludesCorrectly() {
            // All three queries must use the same page size. ListResources defaults to a 100-result
            // page; the full suite shares one emulator instance and can hold >100 resources, so an
            // unspecified MaxResults would truncate the s3 and non-s3 counts inconsistently against
            // the MaxResults:1000 total and break the arithmetic.
            int s3Count = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000, \"Filters\": {\"FilterString\": \"service:s3\"}}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources.size()");

            int total = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources.size()");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1000, \"Filters\": {\"FilterString\": \"-service:s3\"}}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(total - s3Count))
                .body("Resources.findAll { it.Service == 's3' }.size()", equalTo(0));
        }

        @Test
        void combinedFiltersAreAnded() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "service:s3 region:us-east-1"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.findAll { it.Service != 's3' }.size()", equalTo(0))
                .body("Resources.findAll { it.Region != 'us-east-1' }.size()", equalTo(0));
        }

        @Test
        void accountIdFilterMatchesCorrectAccount() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"Filters": {"FilterString": "accountid:000000000000"}}
                    """)
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", greaterThan(0))
                .body("Resources.findAll { it.OwningAccountId != '000000000000' }.size()", equalTo(0));
        }

        @Test
        void idFilterMatchesExactArn() {
            String arn = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1}")
            .when()
                .post("/ListResources")
            .then()
                .extract().path("Resources[0].Arn");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Filters\": {\"FilterString\": \"id:" + arn + "\"}}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(1))
                .body("Resources[0].Arn", equalTo(arn));
        }

        @Test
        void searchFreeFormTextNarrowsResults() {
            // AWS Search treats free-form text as a narrowing filter: resources whose
            // attributes don't match the keyword are excluded. A keyword that matches
            // nothing must therefore return zero resources — not the full set.
            int totalCount = given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"QueryString\": \"\", \"MaxResults\": 1000}")
            .when()
                .post("/Search")
            .then()
                .body("Resources.size()", greaterThan(0))
                .extract().path("Resources.size()");

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"QueryString\": \"xyznonexistent\", \"MaxResults\": 1000}")
            .when()
                .post("/Search")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(0))
                .body("Count.TotalResources", equalTo(0));
        }
    }

    @Nested
    class AwsApiFidelity {

        private static String authFor(String region) {
            return "AWS4-HMAC-SHA256 Credential=test/20260528/" + region + "/resource-explorer-2/aws4_request";
        }

        @Test
        void listResourcesWithNoDefaultViewInRegionReturns401() {
            // Only the startup region (us-east-1) has an auto-provisioned default view.
            // A region that never had a default view must return UnauthorizedException (401),
            // not ResourceNotFoundException (404).
            given()
                .header("Authorization", authFor("eu-central-1"))
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(401)
                .body("__type", equalTo("UnauthorizedException"));
        }

        @Test
        void searchWithNoDefaultViewInRegionReturns401() {
            given()
                .header("Authorization", authFor("eu-central-1"))
                .contentType("application/json")
                .body("{\"QueryString\": \"\"}")
            .when()
                .post("/Search")
            .then()
                .statusCode(401)
                .body("__type", equalTo("UnauthorizedException"));
        }

        @Test
        void createViewWithDuplicateNameReturnsConflict() {
            trackView(given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewName\": \"fidelity-dup-view\"}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(200)
                .extract().path("View.ViewArn"));

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewName\": \"fidelity-dup-view\"}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
        }

        @Test
        void createViewWithInvalidNameReturnsValidation() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewName\": \"bad name with spaces!\"}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void createViewWithoutNameReturnsValidation() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"Filters\": {\"FilterString\": \"service:s3\"}}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void createViewWithInvalidIncludedPropertyReturnsValidation() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("""
                    {"ViewName": "fidelity-bad-prop-view",
                     "IncludedProperties": [{"Name": "notavalidproperty"}]}
                    """)
            .when()
                .post("/CreateView")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void associateDefaultViewCrossRegionReturnsValidation() {
            // A view lives in one region; making it the default for a different region is rejected
            // by AWS with a ValidationException (default views are scoped per region).
            String euViewArn = trackView(given()
                .header("Authorization", authFor("eu-central-1"))
                .contentType("application/json")
                .body("{\"ViewName\": \"fidelity-crossregion-view\"}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(200)
                .extract().path("View.ViewArn"));

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + euViewArn + "\"}")
            .when()
                .post("/AssociateDefaultView")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void promotingSecondIndexToAggregatorReturnsConflict() {
            // us-east-1 has the auto-provisioned AGGREGATOR. A LOCAL index promoted in
            // another region must fail with ConflictException — only one aggregator per account.
            String arn = trackIndex(authFor("ap-southeast-2"), given()
                .header("Authorization", authFor("ap-southeast-2"))
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/CreateIndex")
            .then()
                .statusCode(200)
                .extract().path("Arn"));

            given()
                .header("Authorization", authFor("ap-southeast-2"))
                .contentType("application/json")
                .body("{\"Arn\": \"" + arn + "\", \"Type\": \"AGGREGATOR\"}")
            .when()
                .post("/UpdateIndexType")
            .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
        }

        @Test
        void searchWithoutQueryStringReturnsValidation() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/Search")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void maxResultsAboveLimitReturnsValidation() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 1001}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void maxResultsZeroReturnsValidation() {
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"MaxResults\": 0}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
        }

        @Test
        void staleNextTokenBeyondResultSetReturnsEmptyPageNot500() {
            // Base64("999999") — an offset far past any live result set. The result set is
            // queried live and can shrink between paginated calls, so this must not 500.
            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"NextToken\": \"OTk5OTk5\"}")
            .when()
                .post("/ListResources")
            .then()
                .statusCode(200)
                .body("Resources.size()", equalTo(0));
        }

        @Test
        void createIndexReturns200WithCreatingState() {
            // AWS botocore service-2.json: CreateIndex responseCode = 200.
            String arn = trackIndex(authFor("ca-central-1"), given()
                .header("Authorization", authFor("ca-central-1"))
                .contentType("application/json")
                .body("{}")
            .when()
                .post("/CreateIndex")
            .then()
                .statusCode(200)
                .body("Arn", notNullValue())
                .body("State", equalTo("CREATING"))
                .body("CreatedAt", notNullValue())
                .extract().path("Arn"));
        }

        @Test
        void createViewResponseViewNodeHasNoTagsField() {
            // AWS SDK View shape does NOT include Tags — Tags is only on GetViewResponse top-level.
            String viewArn = trackView(given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewName\": \"fidelity-notags-view\", \"Tags\": {\"owner\": \"team-a\"}}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(200)
                .body("View", notNullValue())
                .body("View.Tags", nullValue())
                .extract().path("View.ViewArn"));
        }

        @Test
        void getViewHasTopLevelTagsButViewNodeHasNone() {
            // GetView response: top-level "Tags" present, but "View.Tags" absent.
            String viewArn = trackView(given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewName\": \"fidelity-topleveltags-view\", \"Tags\": {\"env\": \"prod\"}}")
            .when()
                .post("/CreateView")
            .then()
                .statusCode(200)
                .extract().path("View.ViewArn"));

            given()
                .header("Authorization", AUTH)
                .contentType("application/json")
                .body("{\"ViewArn\": \"" + viewArn + "\"}")
            .when()
                .post("/GetView")
            .then()
                .statusCode(200)
                .body("Tags", notNullValue())
                .body("View.Tags", nullValue());
        }
    }
}

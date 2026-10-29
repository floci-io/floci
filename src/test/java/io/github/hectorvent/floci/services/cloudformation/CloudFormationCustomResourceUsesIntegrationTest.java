package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A custom resource keeps the physical id of each resource its definition names, through a
 * {@code Ref} or {@code DependsOn}, so a stack can delete it before them once the template no
 * longer names it; a literal it was given is not kept, though another resource may have that name.
 * An update replaces what it keeps, and a rolled-back update gives back what it kept before.
 *
 * <p>The ServiceToken Lambda is mocked, as in {@code CloudFormationCustomResourceRollbackIntegrationTest},
 * and answers by a PUT of its result to the event's {@code ResponseURL}.
 */
@QuarkusTest
class CloudFormationCustomResourceUsesIntegrationTest {

    private static final String SERVICE_TOKEN = "arn:aws:lambda:us-east-1:000000000000:function:uses-probe";
    private static final String USES = "__FlociUsesResource.";

    @InjectMock
    LambdaService lambdaService;

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubHandler() {
        CustomResourceProbe.stub(lambdaService, SERVICE_TOKEN, "uses-probe");
    }

    @Test
    void aCustomResourceKeepsWhatItsDefinitionNamesAndNotItsLiterals() throws InterruptedException {
        String stack = "cr-uses-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stack, template(stack, "Data", ""));
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        Map<String, String> kept = attributes(stack);
        assertEquals(stack + "-data", kept.get(USES + "Data"), kept::toString);
        assertEquals(queueUrl(stack), kept.get(USES + "Queue"), kept::toString);
        assertFalse(kept.containsValue(stack + ".example.com"), kept::toString);

        updateStack(stack, template(stack, "Archive", ""));
        awaitStackStatus(stackId, "UPDATE_COMPLETE");

        kept = attributes(stack);
        assertEquals(stack + "-archive", kept.get(USES + "Archive"), kept::toString);
        assertNull(kept.get(USES + "Data"), kept::toString);
        deleteStack(stack);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    @Test
    void aRolledBackUpdateGivesACustomResourceBackWhatItKeptBefore() throws InterruptedException {
        String stack = "cr-uses-rollback-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stack, template(stack, "Data", ""));
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String failingBucket = """
                ,
                "Bad": {"Type": "AWS::S3::Bucket", "DependsOn": "Emptier", "Properties": {"BucketName": "bad/name"}}""";
        updateStack(stack, template(stack, "Archive", failingBucket));
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

        Map<String, String> kept = attributes(stack);
        assertEquals(stack + "-data", kept.get(USES + "Data"), kept::toString);
        assertNull(kept.get(USES + "Archive"), kept::toString);
        deleteStack(stack);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    /**
     * An update that changes only a custom resource's {@code DependsOn} does not re-provision it,
     * yet once the update commits it keeps the resource it now depends on.
     */
    @Test
    void aCommittedUpdateKeepsADependsOnAddedAlone() throws InterruptedException {
        String stack = "cr-uses-dependson-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stack, template(stack, "Data", ""));
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        updateStack(stack, dependsOnArchive(template(stack, "Data", extraQueue(stack))));
        awaitStackStatus(stackId, "UPDATE_COMPLETE");

        Map<String, String> kept = attributes(stack);
        assertEquals(stack + "-archive", kept.get(USES + "Archive"), kept::toString);
        assertEquals(stack + "-data", kept.get(USES + "Data"), kept::toString);
        deleteStack(stack);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    /** The same change in an update that rolls back leaves what the custom resource kept before. */
    @Test
    void aRolledBackUpdateKeepsNoDependsOnAddedAlone() throws InterruptedException {
        String stack = "cr-uses-dependson-rollback-" + Long.toString(System.nanoTime(), 36);
        String stackId = createStack(stack, template(stack, "Data", ""));
        awaitStackStatus(stackId, "CREATE_COMPLETE");

        String failingBucket = """
                ,
                "Bad": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "bad/name"}}""";
        updateStack(stack, dependsOnArchive(template(stack, "Data", failingBucket)));
        awaitStackStatus(stackId, "UPDATE_ROLLBACK_COMPLETE");

        Map<String, String> kept = attributes(stack);
        assertNull(kept.get(USES + "Archive"), kept::toString);
        assertEquals(stack + "-data", kept.get(USES + "Data"), kept::toString);
        deleteStack(stack);
        awaitStackStatus(stackId, "DELETE_COMPLETE");
    }

    /** The template with the custom resource depending on the Archive bucket as well as the queue. */
    private static String dependsOnArchive(String template) {
        return template.replace("\"DependsOn\": \"Queue\"", "\"DependsOn\": [\"Queue\", \"Archive\"]");
    }

    /** An unrelated queue an update adds, so the update has something to do besides the DependsOn. */
    private static String extraQueue(String stack) {
        return """
                ,
                "Extra": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%s-extra"}}""".formatted(stack);
    }

    /** Two buckets and a queue, and a custom resource that uses the queue and the bucket named {@code target}. */
    private static String template(String stack, String target, String extraResources) {
        return """
                {
                  "Resources": {
                    "Data": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%1$s-data"}},
                    "Archive": {"Type": "AWS::S3::Bucket", "Properties": {"BucketName": "%1$s-archive"}},
                    "Queue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "%1$s-queue"}},
                    "Emptier": {
                      "Type": "Custom::EmptyBucket",
                      "DependsOn": "Queue",
                      "Properties": {
                        "ServiceToken": "%2$s",
                        "BucketName": {"Ref": "%3$s"},
                        "Domain": "%1$s.example.com"
                      }
                    }%4$s
                  }
                }
                """.formatted(stack, SERVICE_TOKEN, target, extraResources);
    }

    private Map<String, String> attributes(String stack) {
        return cloudFormationService.describeStacks(stack, "us-east-1").getFirst()
                .getResources().get("Emptier").getAttributes();
    }

    private static String queueUrl(String stack) {
        String xml = cfn(Map.of("Action", "DescribeStackResource", "StackName", stack,
                "LogicalResourceId", "Queue"));
        return XmlParser.extractFirst(xml, "PhysicalResourceId", null);
    }

    private static String createStack(String stackName, String template) {
        String xml = cfn(Map.of("Action", "CreateStack", "StackName", stackName, "TemplateBody", template));
        return XmlParser.extractFirst(xml, "StackId", null);
    }

    private static void updateStack(String stackName, String template) {
        cfn(Map.of("Action", "UpdateStack", "StackName", stackName, "TemplateBody", template));
    }

    private static void deleteStack(String stackName) {
        cfn(Map.of("Action", "DeleteStack", "StackName", stackName));
    }

    /** Polls DescribeStacks by stack id and returns its XML once the stack reaches {@code status}. */
    private static String awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = cfn(Map.of("Action", "DescribeStacks", "StackName", stackId));
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return xml;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
        return xml;
    }

    private static String cfn(Map<String, String> params) {
        return given()
                .contentType("application/x-www-form-urlencoded")
                .formParams(params)
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().asString();
    }
}

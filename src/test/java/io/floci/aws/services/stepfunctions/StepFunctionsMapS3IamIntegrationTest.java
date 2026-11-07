package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.floci.aws.testing.StepFunctionsTestClient;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.floci.aws.testing.StepFunctionsTestClient.LAMBDA_TRUST;
import static io.floci.aws.testing.StepFunctionsTestClient.STATES_TRUST;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The S3 calls a Distributed Map makes as its execution role, under IAM enforcement. S3 runs for real, so a test reads
 * what a reader saw from the Map's output and what a writer wrote from the bucket.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StepFunctionsMapS3IamIntegrationTest {
    private static final String ACCOUNT = "000000000473";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READ_ONLY = allow("*", "sns:ListTopics");
    private static final String READER_REFUSED = "States.ItemReaderFailed";
    private static final String WRITER_REFUSED = "States.ResultWriterFailed";
    private static final Map<String, Object> PASS_CHILD = processor(Map.of("StartAt", "Item",
            "States", Map.of("Item", Map.of("Type", "Pass", "End", true))));
    /** Fails the items that say so, for a Map that tolerates it. */
    private static final Map<String, Object> FAILING_CHILD = processor(Map.of("StartAt", "Decide", "States", Map.of(
            "Decide", Map.of("Type", "Choice", "Default", "Ok",
                    "Choices", List.of(Map.of("Variable", "$.fail", "BooleanEquals", true, "Next", "Boom"))),
            "Ok", Map.of("Type", "Pass", "End", true),
            "Boom", Map.of("Type", "Fail", "Error", "Item.Failed", "Cause", "told to"))));
    private static final String TWO_ITEMS = "{\"items\":[{\"fail\":false},{\"fail\":false}]}";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void contentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "LIST_PREFIX", "JSON", "JSONL", "CSV"})
    void anExecutionRoleAllowedToReadRunsEveryChild(String form) throws Exception {
        Reader reader = reader(form);

        String execution = start(machine(readerMap(reader), role(STATES_TRUST, reader.grant())), "{}");
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(reader.items(), MAPPER.readTree(completed.jsonPath().getString("output")).size());
        assertEquals(1, eventsOfType(execution, "MapRunStarted").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "LIST_PREFIX", "JSON", "JSONL", "CSV"})
    void anExecutionRoleNotAllowedToReadIsRefusedAndRunsNoChild(String form) throws Exception {
        Reader reader = reader(form);

        String execution = start(machine(readerMap(reader), role(STATES_TRUST, READ_ONLY)), "{}");
        Response completed = terminal(execution);

        assertFailed(completed, READER_REFUSED);
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains(reader.action()), cause);
        assertTrue(cause.contains(reader.resourceArn()), cause);
        assertEquals(0, eventsOfType(execution, "MapRunStarted").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "LIST_PREFIX", "JSON", "JSONL", "CSV"})
    void theActionOnAnotherResourceDoesNotCount(String form) throws Exception {
        Reader reader = reader(form);

        Response completed = terminal(start(machine(readerMap(reader),
                role(STATES_TRUST, allow(reader.resourceArn() + "-elsewhere", reader.action()))), "{}"));

        assertFailed(completed, READER_REFUSED);
    }

    /** A listing is not a read, as neither is for a direct request: each form needs its own action. */
    @ParameterizedTest
    @CsvSource({"LIST,s3:GetObject", "LIST_PREFIX,s3:GetObject", "JSON,s3:ListBucket", "JSONL,s3:ListBucket",
            "CSV,s3:ListBucket"})
    void theOtherReaderActionDoesNotCount(String form, String otherAction) throws Exception {
        Reader reader = reader(form);
        String everything = "arn:aws:s3:::" + reader.bucket();

        Response completed = terminal(start(machine(readerMap(reader),
                role(STATES_TRUST, policy(List.of(Map.of("Effect", "Allow", "Action", otherAction,
                        "Resource", List.of(everything, everything + "/*")))))), "{}"));

        assertFailed(completed, READER_REFUSED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "LIST_PREFIX", "JSON", "JSONL", "CSV"})
    void aRoleThatDoesNotTrustStepFunctionsCannotRead(String form) throws Exception {
        Reader reader = reader(form);
        String role = role(LAMBDA_TRUST, reader.grant());

        String execution = start(machine(readerMap(reader), role), "{}");
        Response completed = terminal(execution);

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        assertEquals(0, eventsOfType(execution, "MapRunStarted").size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "JSON"})
    void aRoleThatDoesNotExistCannotRead(String form) throws Exception {
        Reader reader = reader(form);
        String role = "arn:aws:iam::" + ACCOUNT + ":role/absent-" + UUID.randomUUID();

        Response completed = terminal(start(machine(readerMap(reader), role), "{}"));

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
    }

    @Test
    void aRoleThatDoesNotTrustStepFunctionsCannotWrite() throws Exception {
        String bucket = bucket();
        String role = role(LAMBDA_TRUST, allow("arn:aws:s3:::" + bucket + "/*", "s3:PutObject"));

        String execution = start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS);
        Response completed = terminal(execution);

        assertFailed(completed, "States.Permissions");
        assertTrue(completed.jsonPath().getString("cause").contains(role), completed.asString());
        assertEquals(1, eventsOfType(execution, "MapRunFailed").size());
        assertEquals(List.of(), keys(bucket));
    }

    @Test
    void aRoleThatDoesNotExistCannotWrite() throws Exception {
        String bucket = bucket();
        String role = "arn:aws:iam::" + ACCOUNT + ":role/absent-" + UUID.randomUUID();

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertFailed(completed, "States.Permissions");
        assertEquals(List.of(), keys(bucket));
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "JSON", "JSONL", "CSV"})
    void aBucketPolicyLetsARoleRead(String form) throws Exception {
        Reader reader = reader(form);
        String role = role(STATES_TRUST, READ_ONLY);
        setBucketPolicy(reader.bucket(), bucketStatement("Allow", role, reader.action(), reader.resourceArn()));

        Response completed = terminal(start(machine(readerMap(reader), role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "JSON", "JSONL", "CSV"})
    void aBucketPolicyDenyStopsARoleReading(String form) throws Exception {
        Reader reader = reader(form);
        String role = role(STATES_TRUST, reader.grant());
        setBucketPolicy(reader.bucket(), bucketStatement("Deny", role, reader.action(), reader.resourceArn()));

        Response completed = terminal(start(machine(readerMap(reader), role), "{}"));

        assertFailed(completed, READER_REFUSED);
        assertTrue(completed.jsonPath().getString("cause").contains("resource-based policy"), completed.asString());
    }

    @Test
    void aBucketPolicyLetsARoleWrite() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, READ_ONLY);
        setBucketPolicy(bucket, bucketStatement("Allow", role, "s3:PutObject", "arn:aws:s3:::" + bucket + "/out/*"));

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(2, keys(bucket).size());
    }

    @Test
    void aBucketPolicyDenyStopsARoleWriting() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/*", "s3:PutObject"));
        setBucketPolicy(bucket, bucketStatement("Deny", role, "s3:PutObject", "arn:aws:s3:::" + bucket + "/*"));

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertFailed(completed, WRITER_REFUSED);
        assertTrue(completed.jsonPath().getString("cause").contains("resource-based policy"), completed.asString());
        assertEquals(List.of(), keys(bucket));
    }

    /** A listing offers {@code s3:prefix} only when it has one, as a direct request does. */
    @Test
    void aGrantConditionedOnThePrefixCoversThatPrefix() throws Exception {
        String bucket = bucket();
        put(bucket, "data/a", "a");
        put(bucket, "other/b", "b");
        String role = role(STATES_TRUST, policy(List.of(Map.of("Effect", "Allow", "Action", "s3:ListBucket",
                "Resource", "arn:aws:s3:::" + bucket,
                "Condition", Map.of("StringEquals", Map.of("s3:prefix", "data/"))))));

        Response granted = terminal(start(machine(listMap(Map.of("Bucket", bucket, "Prefix", "data/")), role), "{}"));
        Response elsewhere = terminal(start(machine(listMap(Map.of("Bucket", bucket, "Prefix", "other/")), role), "{}"));
        Response noPrefix = terminal(start(machine(listMap(Map.of("Bucket", bucket)), role), "{}"));

        assertEquals("SUCCEEDED", granted.jsonPath().getString("status"), granted.asString());
        assertFailed(elsewhere, READER_REFUSED);
        assertFailed(noPrefix, READER_REFUSED);
    }

    /** S3's one namespace reaches a bucket of another partition, whose ARN is the one a direct request is checked on. */
    @ParameterizedTest
    @ValueSource(strings = {"LIST", "JSON"})
    void aBucketReadInAnotherPartitionIsEvaluatedInItsOwn(String form) throws Exception {
        String bucket = bucket(ACCOUNT, "cn-north-1");
        boolean list = "LIST".equals(form);
        String resource = list ? bucket : bucket + "/items.json";
        put(bucket, "items.json", "[1,2]", auth(ACCOUNT, "cn-north-1", "s3"));
        Map<String, Object> map = list ? listMap(Map.of("Bucket", bucket))
                : getMap(Map.of("Bucket", bucket, "Key", "items.json"), "JSON");
        String action = list ? "s3:ListBucket" : "s3:GetObject";

        Response refused = terminal(start(machine(map, role(STATES_TRUST, allow("arn:aws:s3:::" + resource, action))), "{}"));
        Response allowed = terminal(start(machine(map, role(STATES_TRUST, allow("arn:aws-cn:s3:::" + resource, action))), "{}"));

        assertFailed(refused, READER_REFUSED);
        assertTrue(refused.jsonPath().getString("cause").contains("arn:aws-cn:s3:::" + bucket), refused.asString());
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
    }

    /** Evaluated before the Region check, which is then what answers: a role cannot learn where a bucket is. */
    @Test
    void aBucketWrittenInAnotherPartitionIsEvaluatedInItsOwn() throws Exception {
        String bucket = bucket(ACCOUNT, "cn-north-1");
        Map<String, Object> map = writerMap(bucket, "out", PASS_CHILD);

        Response refused = terminal(start(machine(map, role(STATES_TRUST,
                allow("arn:aws:s3:::" + bucket + "/*", "s3:PutObject"))), TWO_ITEMS));
        Response allowed = terminal(start(machine(map, role(STATES_TRUST,
                allow("arn:aws-cn:s3:::" + bucket + "/*", "s3:PutObject"))), TWO_ITEMS));

        assertFailed(refused, WRITER_REFUSED);
        assertTrue(refused.jsonPath().getString("cause").contains("arn:aws-cn:s3:::" + bucket), refused.asString());
        assertFailed(allowed, WRITER_REFUSED);
        assertTrue(allowed.jsonPath().getString("cause").contains("same AWS Region"), allowed.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIST", "JSON"})
    void aMissingBucketIsAnsweredByS3OnceTheRoleIsAllowed(String form) throws Exception {
        String bucket = "sfn-map-absent-" + UUID.randomUUID();
        boolean list = "LIST".equals(form);
        Map<String, Object> map = list ? listMap(Map.of("Bucket", bucket))
                : getMap(Map.of("Bucket", bucket, "Key", "items.json"), "JSON");
        String action = list ? "s3:ListBucket" : "s3:GetObject";
        String arn = "arn:aws:s3:::" + bucket + (list ? "" : "/items.json");

        Response refused = terminal(start(machine(map, role(STATES_TRUST, READ_ONLY)), "{}"));
        Response answered = terminal(start(machine(map, role(STATES_TRUST, allow(arn, action))), "{}"));

        assertFailed(refused, READER_REFUSED);
        assertTrue(refused.jsonPath().getString("cause").contains(action), refused.asString());
        assertFailed(answered, READER_REFUSED);
        assertFalse(answered.jsonPath().getString("cause").contains(action), answered.asString());
    }

    @ParameterizedTest
    @CsvSource({"LIST,'{}'", "JSON,'{\"Key\":\"items.json\"}'"})
    void aRequestNamingNoBucketIsAnsweredByTheReader(String form, String parameters) throws Exception {
        Map<String, Object> request = MAPPER.readValue(parameters, Map.class);
        Map<String, Object> map = "LIST".equals(form) ? listMap(request) : getMap(request, "JSON");

        Response answered = terminal(start(machine(map, role(STATES_TRUST, READ_ONLY)), "{}"));
        Response roleChecked = terminal(start(machine(map, role(LAMBDA_TRUST, READ_ONLY)), "{}"));

        assertFailed(answered, "States.Runtime");
        assertTrue(answered.jsonPath().getString("cause").contains("must include Bucket"), answered.asString());
        assertFailed(roleChecked, "States.Permissions");
    }

    /** Floci reads neither, so there is no S3 call to evaluate and its answer does not depend on the role. */
    @ParameterizedTest
    @ValueSource(strings = {"PARQUET", "MANIFEST"})
    void anInputTypeFlociDoesNotReadKeepsItsAnswer(String inputType) throws Exception {
        String bucket = bucket();
        Map<String, Object> map = getMap(Map.of("Bucket", bucket, "Key", "items"), inputType);

        Response completed = terminal(start(machine(map, role(LAMBDA_TRUST, READ_ONLY)), "{}"));

        assertFailed(completed, READER_REFUSED);
        assertTrue(completed.jsonPath().getString("cause").contains("not yet implemented"), completed.asString());
    }

    @Test
    void anExecutionRoleAllowedToWriteExports() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/out/*", "s3:PutObject"));

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        JsonNode output = MAPPER.readTree(completed.jsonPath().getString("output"));
        String manifest = output.path("ResultWriterDetails").path("Key").asText();
        assertTrue(manifest.startsWith("out/"), manifest);
        List<String> keys = keys(bucket);
        assertEquals(2, keys.size(), keys.toString());
        assertTrue(keys.contains(manifest), keys.toString());
    }

    /** The children have run by then, and the Map run fails as any other failure of its writer does. */
    @Test
    void anExecutionRoleNotAllowedToWriteFailsTheMapAfterItsChildren() throws Exception {
        String bucket = bucket();

        String execution = start(machine(writerMap(bucket, "out", PASS_CHILD), role(STATES_TRUST, READ_ONLY)), TWO_ITEMS);
        Response completed = terminal(execution);

        assertFailed(completed, WRITER_REFUSED);
        String cause = completed.jsonPath().getString("cause");
        assertTrue(cause.contains("s3:PutObject"), cause);
        assertTrue(cause.contains("arn:aws:s3:::" + bucket + "/out/"), cause);
        assertEquals(List.of(), keys(bucket));
        assertEquals(1, eventsOfType(execution, "MapRunFailed").size());
        String mapRun = eventsOfType(execution, "MapRunStarted").getFirst()
                .path("mapRunStartedEventDetails").path("mapRunArn").asText();
        Response described = request("DescribeMapRun", Map.of("mapRunArn", mapRun));
        assertEquals("FAILED", described.jsonPath().getString("status"), described.asString());
        assertEquals(2, described.jsonPath().getInt("itemCounts.succeeded"), described.asString());
    }

    @Test
    void aGrantOnAnotherPrefixDoesNotCoverTheExport() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/elsewhere/*", "s3:PutObject"));

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertFailed(completed, WRITER_REFUSED);
        assertEquals(List.of(), keys(bucket));
    }

    @Test
    void aGrantOnAnotherActionDoesNotCoverTheExport() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/*", "s3:GetObject",
                "s3:ListMultipartUploadParts", "s3:AbortMultipartUpload"));

        assertFailed(terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS)), WRITER_REFUSED);
        assertEquals(List.of(), keys(bucket));
    }

    /** Multipart is not called, and the export reads nothing back: the single write is all the role needs. */
    @Test
    void anExportNeedsOnlyPutObject() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, policy(List.of(
                Map.of("Effect", "Allow", "Action", "s3:PutObject", "Resource", "arn:aws:s3:::" + bucket + "/*"),
                Map.of("Effect", "Deny", "Action",
                        List.of("s3:GetObject", "s3:ListMultipartUploadParts", "s3:AbortMultipartUpload", "s3:ListBucket"),
                        "Resource", "*"))));

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    /** Every key is known up front and evaluated first, so a refusal of the last leaves no partial export. */
    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED_0.json", "manifest.json"})
    void aRefusalOfOneObjectWritesNone(String refused) throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, policy(List.of(
                Map.of("Effect", "Allow", "Action", "s3:PutObject", "Resource", "arn:aws:s3:::" + bucket + "/*"),
                Map.of("Effect", "Deny", "Action", "s3:PutObject", "Resource", "arn:aws:s3:::" + bucket + "/*/" + refused))));

        Response completed = terminal(start(machine(writerMap(bucket, "out", PASS_CHILD), role), TWO_ITEMS));

        assertFailed(completed, WRITER_REFUSED);
        assertTrue(completed.jsonPath().getString("cause").contains(refused), completed.asString());
        assertEquals(List.of(), keys(bucket));
    }

    /** Only what is written is evaluated: the failed file needs a grant when a tolerated child failed, not otherwise. */
    @Test
    void theFailedFileIsEvaluatedOnlyWhenAChildFailed() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, policy(List.of(Map.of("Effect", "Allow", "Action", "s3:PutObject", "Resource",
                List.of("arn:aws:s3:::" + bucket + "/*/SUCCEEDED_0.json", "arn:aws:s3:::" + bucket + "/*/manifest.json")))));
        Map<String, Object> map = writerMap(bucket, "out", FAILING_CHILD);
        map.put("ToleratedFailurePercentage", 100);

        Response noFailure = terminal(start(machine(map, role), TWO_ITEMS));
        assertEquals("SUCCEEDED", noFailure.jsonPath().getString("status"), noFailure.asString());
        int exported = keys(bucket).size();

        Response failure = terminal(start(machine(map, role), "{\"items\":[{\"fail\":true},{\"fail\":false}]}"));

        assertFailed(failure, WRITER_REFUSED);
        assertTrue(failure.jsonPath().getString("cause").contains("FAILED_0.json"), failure.asString());
        assertEquals(exported, keys(bucket).size());
    }

    @Test
    void aFailedFileIsExportedWithItsGrant() throws Exception {
        String bucket = bucket();
        String role = role(STATES_TRUST, allow("arn:aws:s3:::" + bucket + "/out/*", "s3:PutObject"));
        Map<String, Object> map = writerMap(bucket, "out", FAILING_CHILD);
        map.put("ToleratedFailurePercentage", 100);

        Response completed = terminal(start(machine(map, role), "{\"items\":[{\"fail\":true},{\"fail\":false}]}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(3, keys(bucket).size());
    }

    @Test
    void aWriterConfigAloneWritesNothingAndNeedsNothing() throws Exception {
        Map<String, Object> map = mapState(PASS_CHILD);
        map.put("ItemsPath", "$.items");
        map.put("ResultWriter", Map.of("WriterConfig", Map.of("Transformation", "COMPACT", "OutputType", "JSON")));

        Response completed = terminal(start(machine(map, role(LAMBDA_TRUST, READ_ONLY)), TWO_ITEMS));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    @Test
    void aWriterNamingNoBucketIsAnsweredByTheWriter() throws Exception {
        Map<String, Object> map = mapState(PASS_CHILD);
        map.put("ItemsPath", "$.items");
        map.put("ResultWriter", Map.of("Resource", "arn:aws:states:::s3:putObject",
                "Parameters", Map.of("Bucket.$", "$.bucket", "Prefix", "out")));
        String input = "{\"bucket\":\"\",\"items\":[1]}";

        Response answered = terminal(start(machine(map, role(STATES_TRUST, READ_ONLY)), input));
        Response roleChecked = terminal(start(machine(map, role(LAMBDA_TRUST, READ_ONLY)), input));

        assertFailed(answered, WRITER_REFUSED);
        assertTrue(answered.jsonPath().getString("cause").contains("bucket is required"), answered.asString());
        assertFailed(roleChecked, "States.Permissions");
    }

    @Test
    void aMissingBucketIsAnsweredByTheWriterOnceTheRoleIsAllowed() throws Exception {
        String bucket = "sfn-map-absent-" + UUID.randomUUID();
        Map<String, Object> map = writerMap(bucket, "out", PASS_CHILD);

        Response refused = terminal(start(machine(map, role(STATES_TRUST, READ_ONLY)), TWO_ITEMS));
        Response answered = terminal(start(machine(map, role(STATES_TRUST,
                allow("arn:aws:s3:::" + bucket + "/*", "s3:PutObject"))), TWO_ITEMS));

        assertFailed(refused, WRITER_REFUSED);
        assertTrue(refused.jsonPath().getString("cause").contains("s3:PutObject"), refused.asString());
        assertFailed(answered, WRITER_REFUSED);
        assertTrue(answered.jsonPath().getString("cause").contains("Unable to export"), answered.asString());
    }

    /** Retried, the reader is evaluated again and the Map then goes to its Catch, with a second's wait between. */
    @ParameterizedTest
    @CsvSource({"States.ItemReaderFailed,STATES", "States.Permissions,LAMBDA"})
    void aRefusedReaderIsRetriedThenCaught(String error, String trust) throws Exception {
        Reader reader = reader("LIST");
        Map<String, Object> map = readerMap(reader);
        map.put("Retry", List.of(Map.of("ErrorEquals", List.of(error), "IntervalSeconds", 1, "MaxAttempts", 1)));
        map.put("Catch", List.of(Map.of("ErrorEquals", List.of(error), "Next", "Fallback", "ResultPath", "$.caught")));
        map.remove("End");
        map.put("Next", "Done");

        Response completed = terminal(start(machineWithFallback(map,
                role("STATES".equals(trust) ? STATES_TRUST : LAMBDA_TRUST, READ_ONLY)), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        JsonNode caught = MAPPER.readTree(completed.jsonPath().getString("output")).path("caught");
        assertEquals(error, caught.path("Error").asText(), completed.asString());
        JsonNode described = MAPPER.readTree(completed.asString());
        assertTrue(described.path("stopDate").asDouble() - described.path("startDate").asDouble() >= 1,
                completed.asString());
    }

    @Test
    void aRefusedWriterRunsTheMapAgainOnRetryThenIsCaught() throws Exception {
        String bucket = bucket();
        Map<String, Object> map = writerMap(bucket, "out", PASS_CHILD);
        map.put("Retry", List.of(Map.of("ErrorEquals", List.of(WRITER_REFUSED), "IntervalSeconds", 1, "MaxAttempts", 1)));
        map.put("Catch", List.of(Map.of("ErrorEquals", List.of(WRITER_REFUSED), "Next", "Fallback", "ResultPath", "$.caught")));
        map.remove("End");
        map.put("Next", "Done");

        String execution = start(machineWithFallback(map, role(STATES_TRUST, READ_ONLY)), TWO_ITEMS);
        Response completed = terminal(execution);

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(WRITER_REFUSED, MAPPER.readTree(completed.jsonPath().getString("output"))
                .path("caught").path("Error").asText(), completed.asString());
        assertEquals(2, eventsOfType(execution, "MapRunFailed").size());
        assertEquals(List.of(), keys(bucket));
    }

    /** A refused reader ends the Map before its writer has anything to do, whatever the role may write. */
    @Test
    void aRefusedReaderWritesNothing() throws Exception {
        Reader reader = reader("JSON");
        String target = bucket();
        Map<String, Object> map = readerMap(reader);
        map.put("ResultWriter", writer(target, "out"));

        Response completed = terminal(start(machine(map, role(STATES_TRUST,
                allow("arn:aws:s3:::" + target + "/*", "s3:PutObject"))), "{}"));

        assertFailed(completed, READER_REFUSED);
        assertEquals(List.of(), keys(target));
    }

    @Test
    void aReaderAndWriterBothAllowedExportWhatWasRead() throws Exception {
        Reader reader = reader("JSON");
        String target = bucket();
        Map<String, Object> map = readerMap(reader);
        map.put("ResultWriter", writer(target, "out"));
        String role = role(STATES_TRUST, policy(List.of(
                Map.of("Effect", "Allow", "Action", reader.action(), "Resource", reader.resourceArn()),
                Map.of("Effect", "Allow", "Action", "s3:PutObject", "Resource", "arn:aws:s3:::" + target + "/*"))));

        Response completed = terminal(start(machine(map, role), "{}"));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(2, keys(target).size());
    }

    /** What each resolves to is what is evaluated, in both query languages. */
    @ParameterizedTest
    @ValueSource(strings = {"JSONPath", "JSONata"})
    void whatARequestResolvesToIsWhatIsEvaluated(String language) throws Exception {
        Reader reader = reader("JSON");
        String target = bucket();
        boolean jsonata = "JSONata".equals(language);
        Map<String, Object> map = mapState(PASS_CHILD);
        map.put("QueryLanguage", language);
        map.put("ItemReader", Map.of("Resource", "arn:aws:states:::s3:getObject",
                "ReaderConfig", Map.of("InputType", "JSON"),
                jsonata ? "Arguments" : "Parameters", jsonata
                        ? Map.of("Bucket", "{% $states.input.source %}", "Key", "{% $states.input.key %}")
                        : Map.of("Bucket.$", "$.source", "Key.$", "$.key")));
        map.put("ResultWriter", Map.of("Resource", "arn:aws:states:::s3:putObject",
                jsonata ? "Arguments" : "Parameters", jsonata
                        ? Map.of("Bucket", "{% $states.input.target %}", "Prefix", "{% $states.input.prefix %}")
                        : Map.of("Bucket.$", "$.target", "Prefix.$", "$.prefix")));
        String input = MAPPER.writeValueAsString(Map.of("source", reader.bucket(), "key", "items.json",
                "target", target, "prefix", "out"));
        String readGrant = policy(List.of(
                Map.of("Effect", "Allow", "Action", reader.action(), "Resource", reader.resourceArn())));
        String bothGrants = policy(List.of(
                Map.of("Effect", "Allow", "Action", reader.action(), "Resource", reader.resourceArn()),
                Map.of("Effect", "Allow", "Action", "s3:PutObject", "Resource", "arn:aws:s3:::" + target + "/out/*")));

        Response noRead = terminal(start(machine(map, role(STATES_TRUST, READ_ONLY)), input));
        Response noWrite = terminal(start(machine(map, role(STATES_TRUST, readGrant)), input));
        assertFailed(noRead, READER_REFUSED);
        assertFailed(noWrite, WRITER_REFUSED);
        assertEquals(List.of(), keys(target));

        Response allowed = terminal(start(machine(map, role(STATES_TRUST, bothGrants)), input));
        assertEquals("SUCCEEDED", allowed.jsonPath().getString("status"), allowed.asString());
        assertEquals(2, keys(target).size());
    }

    /** A Map's own S3 grant says nothing about its children, whose tasks are evaluated as they always were. */
    @Test
    void aChildTaskIsEvaluatedOnItsOwn() throws Exception {
        Reader reader = reader("JSON");
        Map<String, Object> map = readerMap(reader);
        map.put("ItemProcessor", processor(Map.of("StartAt", "Write", "States", Map.of("Write", Map.of("Type", "Task",
                "Resource", "arn:aws:states:::s3:putObject",
                "Parameters", Map.of("Bucket", reader.bucket(), "Key", "child", "Body", "x"), "End", true)))));

        Response completed = terminal(start(machine(map, role(STATES_TRUST, reader.grant())), "{}"));

        assertFailed(completed, "S3.AccessDeniedException");
    }

    /** No S3 call, nothing to evaluate: neither of these reaches the role at all. */
    @ParameterizedTest
    @ValueSource(strings = {"INLINE", "DISTRIBUTED"})
    void aMapOverItsInputNeedsNothing(String mode) throws Exception {
        Map<String, Object> map = mapState(processor(mode, Map.of("StartAt", "Item",
                "States", Map.of("Item", Map.of("Type", "Pass", "End", true)))));
        map.put("ItemsPath", "$.items");

        Response completed = terminal(start(machine(map, role(LAMBDA_TRUST, READ_ONLY)), TWO_ITEMS));

        assertEquals("SUCCEEDED", completed.jsonPath().getString("status"), completed.asString());
    }

    private Reader reader(String form) {
        String bucket = bucket();
        String arn = "arn:aws:s3:::" + bucket;
        return switch (form) {
            case "LIST" -> {
                put(bucket, "data/a", "a");
                put(bucket, "data/b", "b");
                put(bucket, "other/c", "c");
                yield new Reader(bucket, listMap(Map.of("Bucket", bucket)), "s3:ListBucket", arn, 3);
            }
            case "LIST_PREFIX" -> {
                put(bucket, "data/a", "a");
                put(bucket, "data/b", "b");
                put(bucket, "other/c", "c");
                yield new Reader(bucket, listMap(Map.of("Bucket", bucket, "Prefix", "data/")), "s3:ListBucket", arn, 2);
            }
            case "JSON" -> {
                put(bucket, "items.json", "[1,2]");
                yield new Reader(bucket, getMap(Map.of("Bucket", bucket, "Key", "items.json"), "JSON"),
                        "s3:GetObject", arn + "/items.json", 2);
            }
            case "JSONL" -> {
                put(bucket, "items.jsonl", "{\"a\":1}\n{\"a\":2}\n{\"a\":3}\n");
                yield new Reader(bucket, getMap(Map.of("Bucket", bucket, "Key", "items.jsonl"), "JSONL"),
                        "s3:GetObject", arn + "/items.jsonl", 3);
            }
            case "CSV" -> {
                put(bucket, "items.csv", "h1,h2\na,b\n");
                Map<String, Object> map = mapState(PASS_CHILD);
                map.put("ItemReader", Map.of("Resource", "arn:aws:states:::s3:getObject",
                        "ReaderConfig", Map.of("InputType", "CSV", "CSVHeaderLocation", "FIRST_ROW"),
                        "Parameters", Map.of("Bucket", bucket, "Key", "items.csv")));
                yield new Reader(bucket, map, "s3:GetObject", arn + "/items.csv", 1);
            }
            default -> throw new IllegalArgumentException(form);
        };
    }

    private static Map<String, Object> readerMap(Reader reader) {
        return new LinkedHashMap<>(reader.map());
    }

    private static Map<String, Object> listMap(Map<String, Object> parameters) {
        Map<String, Object> map = mapState(PASS_CHILD);
        map.put("ItemReader", Map.of("Resource", "arn:aws:states:::s3:listObjectsV2", "Parameters", parameters));
        return map;
    }

    private static Map<String, Object> getMap(Map<String, Object> parameters, String inputType) {
        Map<String, Object> map = mapState(PASS_CHILD);
        map.put("ItemReader", Map.of("Resource", "arn:aws:states:::s3:getObject",
                "ReaderConfig", Map.of("InputType", inputType), "Parameters", parameters));
        return map;
    }

    private static Map<String, Object> writerMap(String bucket, String prefix, Map<String, Object> processor) {
        Map<String, Object> map = mapState(processor);
        map.put("ItemsPath", "$.items");
        map.put("ResultWriter", writer(bucket, prefix));
        return map;
    }

    private static Map<String, Object> writer(String bucket, String prefix) {
        return Map.of("Resource", "arn:aws:states:::s3:putObject",
                "Parameters", Map.of("Bucket", bucket, "Prefix", prefix));
    }

    private static Map<String, Object> mapState(Map<String, Object> processor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("Type", "Map");
        map.put("ItemProcessor", processor);
        map.put("End", true);
        return map;
    }

    private static Map<String, Object> processor(Map<String, Object> states) {
        return processor("DISTRIBUTED", states);
    }

    private static Map<String, Object> processor(String mode, Map<String, Object> states) {
        Map<String, Object> processor = new LinkedHashMap<>(states);
        processor.put("ProcessorConfig", "INLINE".equals(mode) ? Map.of("Mode", mode)
                : Map.of("Mode", mode, "ExecutionType", "STANDARD"));
        return processor;
    }

    private static void assertFailed(Response completed, String error) {
        assertEquals("FAILED", completed.jsonPath().getString("status"), completed.asString());
        assertEquals(error, completed.jsonPath().getString("error"), completed.asString());
    }

    private static String allow(String resource, String... actions) {
        return policy(List.of(Map.of("Effect", "Allow", "Action", List.of(actions), "Resource", List.of(resource))));
    }

    private static String policy(List<Map<String, Object>> statements) {
        try {
            return MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bucketStatement(String effect, String principal, String action, String resource) {
        return policy(List.of(Map.of("Effect", effect, "Principal", Map.of("AWS", principal),
                "Action", action, "Resource", resource)));
    }

    private String role(String trust, String policy) {
        return EnforcementFixtures.createRole(cleanup, baseUri.getPort(), ACCOUNT, "/",
                "sfn-map-" + UUID.randomUUID(), trust, policy);
    }

    private String bucket() {
        return bucket(ACCOUNT, REGION);
    }

    private String bucket(String account, String region) {
        String name = "sfn-map-" + UUID.randomUUID();
        String authorization = auth(account, region, "s3");
        given().port(baseUri.getPort()).header("Authorization", authorization)
                .body(REGION.equals(region) ? "" : "<CreateBucketConfiguration><LocationConstraint>" + region
                        + "</LocationConstraint></CreateBucketConfiguration>")
                .put("/" + name).then().statusCode(200);
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), authorization, name));
        return name;
    }

    private void put(String bucket, String key, String body) {
        put(bucket, key, body, auth(ACCOUNT, "s3"));
    }

    private void put(String bucket, String key, String body, String authorization) {
        given().port(baseUri.getPort()).header("Authorization", authorization).body(body)
                .put("/" + bucket + "/" + key).then().statusCode(200);
    }

    private void setBucketPolicy(String bucket, String policy) {
        given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3")).body(policy)
                .queryParam("policy", "").put("/" + bucket).then().statusCode(200);
    }

    private List<String> keys(String bucket) {
        return given().port(baseUri.getPort()).header("Authorization", auth(ACCOUNT, "s3")).queryParam("list-type", "2")
                .get("/" + bucket).then().statusCode(200).extract().xmlPath()
                .getList("ListBucketResult.Contents.Key");
    }

    private String machine(Map<String, Object> map, String role) throws Exception {
        return create(MAPPER.writeValueAsString(Map.of("StartAt", "Run", "States", Map.of("Run", map))), role);
    }

    private String machineWithFallback(Map<String, Object> map, String role) throws Exception {
        return create(MAPPER.writeValueAsString(Map.of("StartAt", "Run", "States", Map.of("Run", map,
                "Fallback", Map.of("Type", "Pass", "End", true),
                "Done", Map.of("Type", "Pass", "End", true)))), role);
    }

    private String create(String definition, String role) {
        return sfn().createMachine(ACCOUNT, "sfn-map-", definition, "STANDARD", role);
    }

    private String start(String machine, String input) {
        return sfn().start(ACCOUNT, machine, input);
    }

    private Response terminal(String execution) throws Exception {
        return sfn().terminal(ACCOUNT, execution, 20);
    }

    private List<JsonNode> eventsOfType(String execution, String type) throws Exception {
        return sfn().eventsOfType(ACCOUNT, execution, type);
    }

    private Response request(String action, Map<String, ?> input) {
        return sfn().request(ACCOUNT, action, input);
    }

    /**
     * @param map the Map state reading it
     * @param items how many items it reads
     */
    private record Reader(String bucket, Map<String, Object> map, String action, String resourceArn, int items) {

        String grant() {
            return allow(resourceArn, action);
        }
    }

    private StepFunctionsTestClient sfn() {
        return new StepFunctionsTestClient(baseUri.getPort(), cleanup);
    }
}

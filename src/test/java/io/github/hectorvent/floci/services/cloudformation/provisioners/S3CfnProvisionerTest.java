package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code AWS::S3::Bucket} and {@code AWS::S3::BucketPolicy}. */
class S3CfnProvisionerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String NO_DUAL_STACK_REGION = "eu-isoe-west-1";

    private S3Service s3;
    private S3CfnProvisioner provisioner;
    private ProvisionContext ctx;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Service.class);
        provisioner = new S3CfnProvisioner(s3);
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(i -> {
            JsonNode node = i.getArgument(0);
            return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(i -> i.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(i -> i.getArgument(0));
        // A bucket carrying a configuration no test declares, so every declared one is a change.
        when(s3.getBucketNotificationConfiguration(anyString())).thenReturn(queueNotification("existing"));
        when(s3.snapshotBucketConfiguration(anyString())).thenReturn("snapshot");
        ctx = new ProvisionContext(engine, REGION, "000000000000", "my-stack");
    }

    private static JsonNode props(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static StackResource resource(String logicalId, String type) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        return r;
    }

    private StackResource provisionBucket(String json) {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        provisioner.provision(r, props(json), ctx);
        return r;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"Rules":[{"ObjectOwnership":"Bogus"}]}                    | The XML you provided was not well-formed or did not validate against our published schema.
            {"Rules":[{}]}                                               | Model validation failed (#/OwnershipControls/Rules/0: required key [ObjectOwnership] not found)
            {"Rules":[{"ObjectOwnership":["BucketOwnerEnforced"]}]}    | Model validation failed (#/OwnershipControls/Rules/0/ObjectOwnership: expected type: String, found: JSONArray)
            {"Rules":[]}                                                 | The XML you provided was not well-formed or did not validate against our published schema.
            {"Rules":"not-an-array"}                                     | Model validation failed (#/OwnershipControls/Rules: expected type: JSONArray, found: String)
            """)
    void invalidOwnershipControlsFailBeforeCreatingTheBucket(String controls, String message) {
        AwsException error = assertThrows(AwsException.class,
                () -> provisionBucket("{\"BucketName\":\"my-bucket\",\"OwnershipControls\":" + controls + "}"));

        assertEquals(message, error.getMessage());
        verify(s3, never()).createBucket(anyString(), anyString());
        verify(s3, never()).putBucketOwnershipControls(anyString(), anyString());
    }

    @Test
    void multipleOwnershipRulesFailBeforeCreatingTheBucket() {
        AwsException error = assertThrows(AwsException.class, () -> provisionBucket("""
                {"BucketName":"my-bucket","OwnershipControls":{"Rules":[
                  {"ObjectOwnership":"ObjectWriter"},
                  {"ObjectOwnership":"BucketOwnerEnforced"}]}}
                """));

        assertEquals("The XML you provided was not well-formed or did not validate against our published schema.",
                error.getMessage());
        verify(s3, never()).createBucket(anyString(), anyString());
        verify(s3, never()).putBucketOwnershipControls(anyString(), anyString());
    }

    @Test
    void refIsTheBucketNameAndGetAttExposesTheDocumentedAttributes() {
        StackResource r = provisionBucket("""
                {"BucketName": "my-bucket"}
                """);

        verify(s3).createBucket("my-bucket", REGION);
        assertEquals("my-bucket", r.getPhysicalId(), "Ref is the bucket name");
        // Every readOnlyProperty in aws-s3-bucket.json, plus BucketName which the template engine
        // resolves for Fn::GetAtt.
        assertEquals(Map.of(
                "Arn", "arn:aws:s3:::my-bucket",
                "DomainName", "my-bucket.s3.amazonaws.com",
                "RegionalDomainName", "my-bucket.s3.us-east-1.amazonaws.com",
                "DualStackDomainName", "my-bucket.s3.dualstack.us-east-1.amazonaws.com",
                // us-east-1 is one of the nine regions that keep the legacy dash form, and AWS's
                // WebsiteURL ends with a slash (the AWS::S3::Bucket schema example).
                "WebsiteURL", "http://my-bucket.s3-website-us-east-1.amazonaws.com/",
                "BucketName", "my-bucket"), r.getAttributes());
    }

    /**
     * The defect Greptile flagged on the migration PR, pre-existing rather than introduced there:
     * an unnamed bucket was renamed on every update, creating a second bucket and orphaning the
     * first with its objects. BucketName is createOnly in aws-s3-bucket.json, so an unchanged
     * template must keep its physical id.
     */
    @Test
    void anUnnamedBucketKeepsItsNameAcrossUpdates() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("my-stack-bucket-abc123def456");
        provisioner.provision(r, props("{}"),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack",
                        "my-stack-bucket-abc123def456"));

        assertEquals("my-stack-bucket-abc123def456", r.getPhysicalId());
        // The bucket already exists under this name. Outside us-east-1 CreateBucket would answer
        // BucketAlreadyOwnedByYou, so the update must not create; it still reconciles configuration.
        verify(s3, never()).createBucket(anyString(), anyString());
        verify(s3).deleteBucketCors("my-stack-bucket-abc123def456");
    }

    @Test
    void anUpdateThatRenamesTheBucketStillCreatesIt() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("my-stack-bucket-abc123def456");
        provisioner.provision(r, props("{\"BucketName\": \"renamed\"}"),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack",
                        "my-stack-bucket-abc123def456"));

        // A replacing update: the derived name differs from the prior id, so the new bucket must
        // be created. This is why the guard asks reusesPriorEntity and not isUpdate.
        assertEquals("renamed", r.getPhysicalId());
        verify(s3).createBucket("renamed", REGION);
    }

    /** ISO-E publishes no S3 dual-stack endpoint, so the attribute is absent rather than invented. */
    @Test
    void aBucketInARegionWithoutS3DualStackHasNoDualStackDomainName() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        provisioner.provision(r, props("{\"BucketName\": \"my-bucket\"}"),
                new ProvisionContext(ctx.engine(), NO_DUAL_STACK_REGION, "000000000000", "my-stack"));

        assertEquals("my-bucket.s3.eu-isoe-west-1.cloud.adc-e.uk", r.getAttributes().get("RegionalDomainName"));
        assertFalse(r.getAttributes().containsKey("DualStackDomainName"));
        assertEquals("arn:aws-iso-e:s3:::my-bucket", r.getAttributes().get("Arn"));
    }

    /**
     * An update arrives with the prior attributes already on the resource, so a dual-stack host
     * recorded before the region's availability was known has to be removed, not just not written.
     */
    @Test
    void anUpdateInARegionWithoutS3DualStackDropsAStaleDualStackDomainName() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("my-bucket");
        r.getAttributes().put("DualStackDomainName", "my-bucket.s3.dualstack.eu-isoe-west-1.cloud.adc-e.uk");
        provisioner.provision(r, props("{\"BucketName\": \"my-bucket\"}"),
                new ProvisionContext(ctx.engine(), NO_DUAL_STACK_REGION, "000000000000", "my-stack", "my-bucket"));

        assertFalse(r.getAttributes().containsKey("DualStackDomainName"), "attributes: " + r.getAttributes());
    }

    @Test
    void anUnnamedBucketGetsALowerCasedStackScopedName() {
        StackResource r = provisionBucket("{}");

        String name = r.getPhysicalId();
        assertTrue(name.startsWith("my-stack-bucket-"), name);
        assertEquals(name.toLowerCase(), name, "S3 bucket names must be lower case");
        assertTrue(name.length() <= 63, "bucket names cap at 63 characters: " + name);
    }

    /**
     * A bucket with no CORS block has its configuration cleared rather than left alone, so an
     * update that removes CorsConfiguration actually removes it from the bucket.
     */
    @Test
    void absentCorsConfigurationClearsAnyExistingRules() {
        provisionBucket("""
                {"BucketName": "b"}
                """);

        verify(s3).deleteBucketCors("b");
        verify(s3, never()).putBucketCors(anyString(), anyString());
    }

    @Test
    void corsRulesBecomeS3CorsConfigurationXml() {
        ArgumentCaptor<String> xml = ArgumentCaptor.forClass(String.class);

        provisionBucket("""
                {
                  "BucketName": "b",
                  "CorsConfiguration": {
                    "CorsRules": [
                      {
                        "Id": "rule-1",
                        "AllowedHeaders": ["*"],
                        "AllowedMethods": ["GET", "PUT"],
                        "AllowedOrigins": ["https://example.com"],
                        "ExposedHeaders": ["ETag"],
                        "MaxAge": "3600"
                      }
                    ]
                  }
                }
                """);

        verify(s3).putBucketCors(anyString(), xml.capture());
        String body = xml.getValue();
        assertTrue(body.contains("<ID>rule-1</ID>"), body);
        assertTrue(body.contains("<AllowedMethod>GET</AllowedMethod>"), body);
        assertTrue(body.contains("<AllowedMethod>PUT</AllowedMethod>"), body);
        assertTrue(body.contains("<AllowedOrigin>https://example.com</AllowedOrigin>"), body);
        // The CFN property is ExposedHeaders but the S3 XML element is ExposeHeader.
        assertTrue(body.contains("<ExposeHeader>ETag</ExposeHeader>"), body);
        assertTrue(body.contains("<MaxAgeSeconds>3600</MaxAgeSeconds>"), body);
    }

    @Test
    void anEmptyCorsRuleListIsTreatedAsNoCors() {
        provisionBucket("""
                {"BucketName": "b", "CorsConfiguration": {"CorsRules": []}}
                """);

        verify(s3).deleteBucketCors("b");
        verify(s3, never()).putBucketCors(anyString(), anyString());
    }

    @Test
    void versioningIsAppliedOnlyWhenAStatusIsGiven() {
        provisionBucket("""
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Enabled"}}
                """);
        verify(s3).putBucketVersioning("b", "Enabled");

        provisionBucket("""
                {"BucketName": "c"}
                """);
        verify(s3, never()).putBucketVersioning("c", null);
    }

    private NotificationConfiguration provisionedNotifications(String json) {
        ArgumentCaptor<NotificationConfiguration> config = ArgumentCaptor.forClass(NotificationConfiguration.class);
        provisionBucket(json);
        verify(s3).putBucketNotificationConfiguration(eq("b"), config.capture());
        return config.getValue();
    }

    /**
     * CloudFormation names one Event per entry and the API takes a list, so each entry is one API
     * configuration. Two entries for the same function stay two configurations.
     */
    @Test
    void notificationEntriesBecomeOneApiConfigurationEachWithTheirFilters() {
        NotificationConfiguration config = provisionedNotifications("""
                {
                  "BucketName": "b",
                  "NotificationConfiguration": {
                    "QueueConfigurations": [
                      {"Event": "s3:ObjectRemoved:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:q"}
                    ],
                    "TopicConfigurations": [
                      {"Event": "s3:ObjectRestore:Completed", "Topic": "arn:aws:sns:us-east-1:000000000000:t"}
                    ],
                    "LambdaConfigurations": [
                      {"Event": "s3:ObjectCreated:*", "Function": "arn:aws:lambda:us-east-1:000000000000:function:f",
                       "Filter": {"S3Key": {"Rules": [{"Name": "prefix", "Value": "images/"},
                                                      {"Name": "suffix", "Value": ".webp"}]}}},
                      {"Event": "s3:ObjectTagging:Put", "Function": "arn:aws:lambda:us-east-1:000000000000:function:f"}
                    ]
                  }
                }
                """);

        assertEquals(List.of(new QueueNotification("", "arn:aws:sqs:us-east-1:000000000000:q",
                List.of("s3:ObjectRemoved:*"), List.of())), config.getQueueConfigurations());
        assertEquals(List.of(new TopicNotification("", "arn:aws:sns:us-east-1:000000000000:t",
                List.of("s3:ObjectRestore:Completed"), List.of())), config.getTopicConfigurations());
        assertEquals(List.of(
                new LambdaNotification("", "arn:aws:lambda:us-east-1:000000000000:function:f",
                        List.of("s3:ObjectCreated:*"),
                        List.of(new FilterRule("prefix", "images/"), new FilterRule("suffix", ".webp"))),
                new LambdaNotification("", "arn:aws:lambda:us-east-1:000000000000:function:f",
                        List.of("s3:ObjectTagging:Put"), List.of())),
                config.getLambdaFunctionConfigurations());
        assertFalse(config.isEventBridgeEnabled());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,false"})
    void eventBridgeConfigurationSetsDeliveryAsDeclared(String declared, boolean enabled) {
        NotificationConfiguration config = provisionedNotifications("""
                {"BucketName": "b",
                 "NotificationConfiguration": {"EventBridgeConfiguration": {"EventBridgeEnabled": %s}}}
                """.formatted(declared));

        assertEquals(enabled, config.isEventBridgeEnabled());
    }

    /**
     * Omitted and explicitly empty differ. An undeclared configuration is left alone, as the other
     * properties here are, so a configuration put on the bucket outside the stack survives an
     * update. A declared empty one is applied, which clears the bucket's.
     */
    @Test
    void anUndeclaredNotificationConfigurationLeavesTheBucketAlone() {
        provisionBucket("""
                {"BucketName": "b"}
                """);

        verify(s3, never()).putBucketNotificationConfiguration(anyString(), any());
    }

    private void updateBucket(String priorProperties, String properties) {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("b");
        provisioner.provision(r, props(properties), new ProvisionContext(ctx.engine(), REGION,
                "000000000000", "my-stack", "b", event -> { }, props(priorProperties)));
    }

    /** AWS clears the configuration when an update removes the property. */
    @Test
    void anUpdateThatRemovesTheNotificationConfigurationClearsIt() {
        ArgumentCaptor<NotificationConfiguration> config = ArgumentCaptor.forClass(NotificationConfiguration.class);

        updateBucket("""
                {"BucketName": "b", "NotificationConfiguration": {"QueueConfigurations": [
                  {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:q"}]}}
                """, """
                {"BucketName": "b"}
                """);

        verify(s3).putBucketNotificationConfiguration(eq("b"), config.capture());
        assertTrue(config.getValue().isEmpty());
    }

    /** An update of a bucket whose template never declared the property leaves its configuration alone. */
    @Test
    void anUpdateOfABucketThatNeverDeclaredTheNotificationConfigurationLeavesItAlone() {
        updateBucket("""
                {"BucketName": "b"}
                """, """
                {"BucketName": "b", "Tags": [{"Key": "k", "Value": "v"}]}
                """);

        verify(s3, never()).putBucketNotificationConfiguration(anyString(), any());
    }

    @Test
    void aDeclaredEmptyNotificationConfigurationClearsTheBucket() {
        NotificationConfiguration config = provisionedNotifications("""
                {"BucketName": "b", "NotificationConfiguration": {}}
                """);

        assertTrue(config.isEmpty());
    }

    /** The registry schema's required keys at each level, reported before S3 is called. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"QueueConfigurations": [{"Queue": "arn:aws:sqs:us-east-1:000000000000:q"}]}                  | #/NotificationConfiguration/QueueConfigurations/0: required key [Event] not found
            {"QueueConfigurations": [{"Event": "s3:ObjectCreated:*"}]}                                     | #/NotificationConfiguration/QueueConfigurations/0: required key [Queue] not found
            {"TopicConfigurations": [{"Event": "s3:ObjectCreated:*"}]}                                     | #/NotificationConfiguration/TopicConfigurations/0: required key [Topic] not found
            {"LambdaConfigurations": [{"Event": "s3:ObjectCreated:*"}]}                                    | #/NotificationConfiguration/LambdaConfigurations/0: required key [Function] not found
            {"LambdaConfigurations": [{"Event": "e", "Function": "f", "Filter": {}}]}                       | #/NotificationConfiguration/LambdaConfigurations/0/Filter: required key [S3Key] not found
            {"LambdaConfigurations": [{"Event": "e", "Function": "f", "Filter": {"S3Key": {}}}]}           | #/NotificationConfiguration/LambdaConfigurations/0/Filter/S3Key: required key [Rules] not found
            {"LambdaConfigurations": [{"Event": "e", "Function": "f", "Filter": {"S3Key": {"Rules": [{"Value": "v"}]}}}]} | #/NotificationConfiguration/LambdaConfigurations/0/Filter/S3Key/Rules/0: required key [Name] not found
            {"LambdaConfigurations": [{"Event": "e", "Function": "f", "Filter": {"S3Key": {"Rules": [{"Name": "prefix"}]}}}]} | #/NotificationConfiguration/LambdaConfigurations/0/Filter/S3Key/Rules/0: required key [Value] not found
            {"EventBridgeConfiguration": {}}                                                               | #/NotificationConfiguration/EventBridgeConfiguration: required key [EventBridgeEnabled] not found
            {"EventBridgeConfiguration": {"EventBridgeEnabled": "yes"}}                                    | #/NotificationConfiguration/EventBridgeConfiguration/EventBridgeEnabled: expected type: Boolean, found: String
            {"QueueConfigurations": {"Event": "e", "Queue": "q"}}                                           | #/NotificationConfiguration/QueueConfigurations: expected type: JSONArray, found: JSONObject
            {"TopicConfigurations": 5}                                                                     | #/NotificationConfiguration/TopicConfigurations: expected type: JSONArray, found: Integer
            {"LambdaConfigurations": true}                                                                 | #/NotificationConfiguration/LambdaConfigurations: expected type: JSONArray, found: Boolean
            {"QueueConfigurations": [{"Event": ["s3:ObjectCreated:*"], "Queue": "q"}]}                      | #/NotificationConfiguration/QueueConfigurations/0/Event: expected type: String, found: JSONArray
            {"EventBridgeConfiguration": {"EventBridgeEnabled": {"Ref": "Flag"}}}                           | #/NotificationConfiguration/EventBridgeConfiguration/EventBridgeEnabled: expected type: Boolean, found: JSONObject
            "not-a-configuration"                                                                          | #/NotificationConfiguration: expected type: JSONObject, found: String
            [{"QueueConfigurations": []}]                                                                  | #/NotificationConfiguration: expected type: JSONObject, found: JSONArray
            {"QueueConfigurations": ["s3:ObjectCreated:*"]}                                                 | #/NotificationConfiguration/QueueConfigurations/0: expected type: JSONObject, found: String
            {"QueueConfigurations": [{"Event": "e", "Queue": "q", "Filter": "prefix"}]}                     | #/NotificationConfiguration/QueueConfigurations/0/Filter: expected type: JSONObject, found: String
            {"QueueConfigurations": [{"Event": "e", "Queue": "q", "Filter": {"S3Key": []}}]}                | #/NotificationConfiguration/QueueConfigurations/0/Filter/S3Key: expected type: JSONObject, found: JSONArray
            {"QueueConfigurations": [{"Event": "e", "Queue": "q", "Filter": {"S3Key": {"Rules": ["x/"]}}}]} | #/NotificationConfiguration/QueueConfigurations/0/Filter/S3Key/Rules/0: expected type: JSONObject, found: String
            {"LambdaConfigurations": [{"Event": "e", "Function": "f", "Filter": {"S3Key": {"Rules": {"Name": "prefix", "Value": "x/"}}}}]} | #/NotificationConfiguration/LambdaConfigurations/0/Filter/S3Key/Rules: expected type: JSONArray, found: JSONObject
            """)
    void aMissingRequiredNotificationKeyFailsTheResource(String notificationConfiguration, String reason) {
        AwsException e = assertThrows(AwsException.class, () -> provisionBucket("""
                {"BucketName": "b", "NotificationConfiguration": %s}
                """.formatted(notificationConfiguration)));

        assertEquals("Model validation failed (" + reason + ")", e.getMessage());
        verify(s3, never()).createBucket(anyString(), anyString());
        verify(s3, never()).putBucketNotificationConfiguration(anyString(), any());
    }

    private static final String REJECTED_QUEUE = """
            "NotificationConfiguration": {"QueueConfigurations": [
              {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:missing"}]}
            """;

    private void rejectDestinations() {
        doThrow(new AwsException("InvalidArgument", "Unable to validate the following destination configurations", 400))
                .when(s3).putBucketNotificationConfiguration(anyString(), any());
    }

    /**
     * A destination S3 cannot validate fails the resource instead of reporting CREATE_COMPLETE, and
     * the bucket the attempt created is deleted rather than orphaned. The resource is left without
     * an id, which is how Cloud Control tells a failed create from a successful one.
     */
    @Test
    void aRejectedDestinationFailsTheCreateAndDeletesTheBucketItCreated() {
        rejectDestinations();
        StackResource r = resource("Bucket", "AWS::S3::Bucket");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "b", "Tags": [{"Key": "k", "Value": "v"}], %s}
                """.formatted(REJECTED_QUEUE)), ctx));

        assertEquals("InvalidArgument", e.getErrorCode());
        verify(s3).createBucket("b", REGION);
        verify(s3).deleteBucket("b");
        verify(s3, never()).putBucketTagging(anyString(), any());
        assertEquals(null, r.getPhysicalId());
    }

    /** CreateBucket also answers in us-east-1 for a bucket the account already owns; that one stays. */
    @Test
    void aRejectedDestinationNeverDeletesABucketTheAttemptDidNotCreate() {
        rejectDestinations();
        when(s3.ownsBucket("b")).thenReturn(true);

        assertThrows(AwsException.class, () -> provisionBucket("""
                {"BucketName": "b", %s}
                """.formatted(REJECTED_QUEUE)));

        verify(s3, never()).deleteBucket(anyString());
    }

    @Test
    void aBucketThatCannotBeDeletedAfterARejectionKeepsTheOriginalFailure() {
        rejectDestinations();
        AwsException deleteFailure = new AwsException("InternalError", "disk", 500);
        doThrow(deleteFailure).when(s3).deleteBucket("b");

        AwsException e = assertThrows(AwsException.class, () -> provisionBucket("""
                {"BucketName": "b", %s}
                """.formatted(REJECTED_QUEUE)));

        assertEquals("InvalidArgument", e.getErrorCode());
        assertEquals(List.of(deleteFailure), List.of(e.getSuppressed()));
    }

    /** An update rejected before any property changed the bucket leaves the bucket as it was. */
    @Test
    void aRejectedUpdateLeavesTheBucketAsItWas() {
        rejectDestinations();
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("b");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "b", %s}
                """.formatted(REJECTED_QUEUE)), new ProvisionContext(ctx.engine(), REGION, "000000000000",
                "my-stack", "b", event -> { }, props("{\"BucketName\": \"b\"}"))));

        verify(s3, never()).deleteBucket(anyString());
        verify(s3, never()).putBucketNotificationConfiguration(anyString(), any(), eq(true));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /** A replacing update creates a new bucket; rejected, that one is deleted and the original stays. */
    @Test
    void aRejectedReplacingUpdateDeletesOnlyTheReplacementBucket() {
        rejectDestinations();
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("old");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "new", %s}
                """.formatted(REJECTED_QUEUE)), new ProvisionContext(ctx.engine(), REGION, "000000000000",
                "my-stack", "old", event -> { }, props("{\"BucketName\": \"old\"}"))));

        verify(s3).createBucket("new", REGION);
        verify(s3).deleteBucket("new");
        verify(s3, never()).deleteBucket("old");
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /** A CreateBucket that is itself rejected created nothing, so there is nothing to delete. */
    @Test
    void aRejectedCreateBucketLeavesNothingToUndo() {
        doThrow(new AwsException("InvalidBucketName", "The specified bucket is not valid.", 400))
                .when(s3).createBucket("new", REGION);
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("old");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "new"}
                """), new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", "old",
                event -> { }, props("{\"BucketName\": \"old\"}"))));

        verify(s3, never()).deleteBucket(anyString());
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void anUpdateFailingModelValidationChangesNothing() {
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("b");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Enabled"},
                 "NotificationConfiguration": {"QueueConfigurations": [{"Event": "s3:ObjectCreated:*"}]}}
                """), new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", "b",
                event -> { }, props("{\"BucketName\": \"b\"}"))));

        verify(s3, never()).putBucketVersioning(anyString(), anyString());
        verify(s3, never()).putBucketNotificationConfiguration(anyString(), any());
    }

    private static NotificationConfiguration queueNotification(String queueName) {
        NotificationConfiguration config = new NotificationConfiguration();
        config.getQueueConfigurations().add(new QueueNotification("", "arn:aws:sqs:us-east-1:000000000000:" + queueName,
                List.of("s3:ObjectCreated:*"), List.of()));
        return config;
    }

    /** A later failure restores all changed bucket settings from the in-place update snapshot. */
    @Test
    void aLaterFailureRestoresTheBucketSnapshot() {
        NotificationConfiguration before = queueNotification("old");
        when(s3.getBucketNotificationConfiguration("b")).thenReturn(before);
        doThrow(new AwsException("MalformedXML", "bad", 400)).when(s3).putBucketVersioning("b", "Bogus");
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("b");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Bogus"},
                 "NotificationConfiguration": {"QueueConfigurations": [
                   {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:new"}]}}
                """), new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", "b",
                event -> { }, props("{\"BucketName\": \"b\"}"))));

        verify(s3).putBucketNotificationConfiguration(eq("b"), any());
        verify(s3).restoreBucketConfiguration("b", "snapshot");
        assertFalse(r.getAttributes().containsKey(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR));
        assertFalse(r.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    @Test
    void aLaterFailureOnCreateDeletesTheBucketItCreated() {
        doThrow(new AwsException("MalformedXML", "bad", 400)).when(s3).putBucketVersioning("b", "Bogus");

        assertThrows(AwsException.class, () -> provisionBucket("""
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Bogus"}}
                """));

        verify(s3).deleteBucket("b");
    }

    /** An update whose own undo fails is reported as a rollback failure, not as rolled back. */
    @Test
    void anUndoThatFailsOnAnUpdateIsReportedAsARollbackFailure() {
        when(s3.getBucketNotificationConfiguration("b")).thenReturn(queueNotification("old"));
        doThrow(new AwsException("MalformedXML", "bad", 400)).when(s3).putBucketVersioning("b", "Bogus");
        doThrow(new AwsException("InternalError", "disk", 500))
                .when(s3).restoreBucketConfiguration("b", "snapshot");
        StackResource r = resource("Bucket", "AWS::S3::Bucket");
        r.setPhysicalId("b");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props("""
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Bogus"},
                 "NotificationConfiguration": {"QueueConfigurations": [
                   {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:new"}]}}
                """), new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", "b",
                event -> { }, props("{\"BucketName\": \"b\"}"))));

        assertEquals("MalformedXML", e.getErrorCode());
        assertEquals("Could not restore bucket b: disk", r.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
    }

    /** Each put sends a fresh s3:TestEvent to every queue and topic, so an unchanged one is not put again. */
    @Test
    void anUnchangedNotificationConfigurationIsNotPutAgain() {
        NotificationConfiguration current = new NotificationConfiguration();
        current.getQueueConfigurations().add(new QueueNotification("", "arn:aws:sqs:us-east-1:000000000000:q",
                List.of("s3:ObjectCreated:*"), List.of()));
        when(s3.getBucketNotificationConfiguration("b")).thenReturn(current);

        updateBucket("""
                {"BucketName": "b", "NotificationConfiguration": {"QueueConfigurations": [
                  {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:q"}]}}
                """, """
                {"BucketName": "b", "Tags": [{"Key": "k", "Value": "v"}],
                 "NotificationConfiguration": {"QueueConfigurations": [
                   {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:q"}]}}
                """);

        verify(s3, never()).putBucketNotificationConfiguration(anyString(), any());
    }

    /** An update that a destination rejects has changed nothing else on the bucket. */
    @Test
    void aRejectedDestinationFailsAnUpdateBeforeAnyOtherPropertyIsApplied() {
        doThrow(new AwsException("InvalidArgument", "Unable to validate the following destination configurations", 400))
                .when(s3).putBucketNotificationConfiguration(eq("b"), any());

        assertThrows(AwsException.class, () -> updateBucket("""
                {"BucketName": "b"}
                """, """
                {"BucketName": "b", "VersioningConfiguration": {"Status": "Enabled"},
                 "NotificationConfiguration": {"QueueConfigurations": [
                   {"Event": "s3:ObjectCreated:*", "Queue": "arn:aws:sqs:us-east-1:000000000000:missing"}]}}
                """));

        verify(s3, never()).putBucketVersioning(anyString(), anyString());
        verify(s3, never()).deleteBucketCors(anyString());
    }

    /**
     * primaryIdentifier in the registry schema is /properties/Bucket, so the bucket name is the
     * physical id. The type has no readOnlyProperties, so it has no Fn::GetAtt and the attribute
     * map stays empty.
     */
    @Test
    void aBucketPolicyIsIdentifiedByItsBucketAndCarriesNoAttributes() {
        StackResource r = resource("Policy", "AWS::S3::BucketPolicy");
        provisioner.provision(r, props("""
                {"Bucket": "b", "PolicyDocument": {"Version": "2012-10-17"}}
                """), ctx);

        assertEquals("b", r.getPhysicalId());
        assertTrue(r.getAttributes().isEmpty());
    }

    /**
     * provision runs again on every UpdateStack. The id follows the bucket, which is create-only,
     * so an unchanged policy keeps its id and does not look like a replaced resource.
     */
    @Test
    void aBucketPolicyKeepsItsIdAcrossUpdates() {
        StackResource r = resource("Policy", "AWS::S3::BucketPolicy");
        r.setPhysicalId("b");
        provisioner.provision(r, props("""
                {"Bucket": "b", "PolicyDocument": {"Version": "2012-10-17"}}
                """),
                new ProvisionContext(ctx.engine(), REGION, "000000000000", "my-stack", "b"));

        assertEquals("b", r.getPhysicalId());
    }

    @Test
    void deletingABucketReachesTheService() {
        provisioner.delete("AWS::S3::Bucket", "b", REGION);

        verify(s3).deleteBucket("b");
        verify(s3, never()).deleteBucketPolicy(anyString());
    }

    @Test
    void deletingABucketPolicyClearsItFromTheBucketAndLeavesTheBucket() {
        provisioner.delete("AWS::S3::BucketPolicy", "b", REGION);

        verify(s3).deleteBucketPolicy("b");
        verify(s3, never()).deleteBucket(anyString());
    }

    /** The bucket in the same stack may be deleted first, leaving no policy to clear. */
    @Test
    void deletingTheBucketPolicyOfAVanishedBucketIsTolerated() {
        doThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404))
                .when(s3).deleteBucketPolicy("gone");

        provisioner.delete("AWS::S3::BucketPolicy", "gone", REGION);

        verify(s3).deleteBucketPolicy("gone");
    }

    @Test
    void aBucketPolicyDeleteThatFailsForAnyOtherReasonPropagates() {
        doThrow(new AwsException("AccessDenied", "nope", 403)).when(s3).deleteBucketPolicy("b");

        assertThrows(AwsException.class, () -> provisioner.delete("AWS::S3::BucketPolicy", "b", REGION));
    }
}

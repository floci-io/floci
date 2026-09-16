package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private Instance<DynamoDbService> dynamoDbServiceInstance;
    private Ec2Service ec2Service;
    private S3Service s3Service;
    private RequestContext requestContext;
    private EmulatorConfig config;
    private IamConditionContextResolver resolver;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        dynamoDbServiceInstance = mock(Instance.class);
        when(dynamoDbServiceInstance.isResolvable()).thenReturn(true);
        when(dynamoDbServiceInstance.get()).thenReturn(dynamoDbService);
        ec2Service = mock(Ec2Service.class);
        Instance<Ec2Service> ec2ServiceInstance = mock(Instance.class);
        when(ec2ServiceInstance.isResolvable()).thenReturn(true);
        when(ec2ServiceInstance.get()).thenReturn(ec2Service);
        s3Service = mock(S3Service.class);
        Instance<S3Service> s3ServiceInstance = mock(Instance.class);
        when(s3ServiceInstance.isResolvable()).thenReturn(true);
        when(s3ServiceInstance.get()).thenReturn(s3Service);
        requestContext = new RequestContext();
        requestContext.setRegion("us-east-1");
        config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        resolver = new IamConditionContextResolver(
                dynamoDbServiceInstance, ec2ServiceInstance, s3ServiceInstance, requestContext, config);
    }

    /** A form request whose body can be read again, as a real request's restored stream can. */
    private static ContainerRequestContext formRequest(String body) {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getMediaType())
                .thenReturn(MediaType.valueOf("application/x-www-form-urlencoded"));
        when(containerRequest.getEntityStream())
                .thenAnswer(invocation -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return containerRequest;
    }

    private static ContainerRequestContext s3Request(String path, String body) {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(containerRequest.getEntityStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return containerRequest;
    }

    private TableDefinition fgacTable() {
        TableDefinition table = new TableDefinition();
        table.setTableName("FgacTable");
        table.setKeySchema(List.of(new KeySchemaElement("PK", "HASH")));
        return table;
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, List<String>> conditions =
                resolver.resolve("s3", "s3:ListBucket", containerRequest);

        assertEquals(List.of("my_namespace/table/"), conditions.get("s3:prefix"));
        assertEquals(List.of("/"), conditions.get("s3:delimiter"));
        assertEquals(List.of("100"), conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void resolveReturnsNullForUnsupportedServiceOrAction() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest));
        assertNull(resolver.resolve("s3", "s3:GetObject", containerRequest));
    }

    @Test
    void resolvesDynamoDbLeadingKeysForGetItem() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
        assertFalse(conditions.containsKey("dynamodb:Select"));
    }

    @Test
    void omitsLeadingKeysWhenTheTableIsUnknown() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"MissingTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.findTable(eq("MissingTable"), anyString()))
                .thenReturn(Optional.empty());

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertFalse(conditions.containsKey("dynamodb:LeadingKeys"));
        // Attribute names do not depend on the key schema, so they are still populated.
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
    }

    @Test
    void carriesSelectForQuery() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable",
                 "KeyConditionExpression":"PK = :v",
                 "ExpressionAttributeValues":{":v":{"S":"USER_alice"}},
                 "Select":"COUNT"}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:Query", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("COUNT"), conditions.get("dynamodb:Select"));
    }

    @Test
    void returnsNullWhenNoBodyWasBuffered() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(null);

        assertNull(resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest));
    }

    @Test
    void resolvesBatchGetItemAcrossEveryRequestedKey() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{"FgacTable":{"Keys":[
                   {"PK":{"S":"USER_alice"}},{"PK":{"S":"USER_bob"}}]}}}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertEquals(List.of("USER_alice", "USER_bob"), conditions.get("dynamodb:LeadingKeys"));
    }

    @Test
    void omitsLeadingKeysForAMultiTableBatch() {
        // A batch spanning two tables has no single key schema: applying table A's partition
        // key name to table B's items would be wrong, so no leading keys are produced.
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{
                   "FgacTable":{"Keys":[{"PK":{"S":"USER_alice"}}]},
                   "OtherTable":{"Keys":[{"PK":{"S":"USER_bob"}}]}}}"""));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertFalse(conditions.containsKey("dynamodb:LeadingKeys"));
    }

    // ── aws:RequestTag and aws:ResourceTag ─────────────────────────────────────

    @Test
    void putBucketTaggingResolvesRequestTagsFromTheXmlBody() {
        ContainerRequestContext containerRequest = s3Request("/my-bucket", "<Tagging><TagSet>"
                + "<Tag><Key>Team</Key><Value>payments</Value></Tag>"
                + "<Tag><Key>Env</Key><Value>prod</Value></Tag>"
                + "</TagSet></Tagging>");

        Map<String, List<String>> conditions =
                resolver.resolve("s3", "s3:PutBucketTagging", containerRequest);

        assertEquals(List.of("payments"), conditions.get("aws:RequestTag/Team"));
        assertEquals(List.of("prod"), conditions.get("aws:RequestTag/Env"));
    }

    @Test
    void putBucketTaggingRestoresTheBodyForTheController() throws Exception {
        String body = "<Tagging><TagSet><Tag><Key>Team</Key><Value>payments</Value></Tag></TagSet></Tagging>";
        ContainerRequestContext containerRequest = s3Request("/my-bucket", body);

        resolver.resolve("s3", "s3:PutBucketTagging", containerRequest);

        var restored = org.mockito.ArgumentCaptor.forClass(java.io.InputStream.class);
        verify(containerRequest).setEntityStream(restored.capture());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), restored.getValue().readAllBytes());
    }

    @Test
    void bucketActionsResolveResourceTagsFromTheBucketsCurrentTags() {
        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "alice"));

        for (String action : List.of("s3:GetBucketTagging", "s3:DeleteBucketTagging", "s3:DeleteBucket")) {
            Map<String, List<String>> conditions =
                    resolver.resolve("s3", action, s3Request("/my-bucket", ""));
            assertEquals(List.of("alice"), conditions.get("aws:ResourceTag/Owner"), action);
        }
    }

    @Test
    void bucketActionsOfferNoResourceTagsWhenTheBucketLookupFails() {
        when(s3Service.getBucketTagging(anyString()))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        assertNull(resolver.resolve("s3", "s3:GetBucketTagging", s3Request("/missing-bucket", "")));
    }

    @Test
    void runInstancesResolvesRequestTagsFromEveryTagSpecification() {
        ContainerRequestContext containerRequest = formRequest("Action=RunInstances"
                + "&TagSpecification.1.ResourceType=instance"
                + "&TagSpecification.1.Tag.1.Key=Team&TagSpecification.1.Tag.1.Value=payments"
                + "&TagSpecification.2.ResourceType=volume"
                + "&TagSpecification.2.Tag.1.Key=Env&TagSpecification.2.Tag.1.Value=prod");

        Map<String, List<String>> conditions =
                resolver.resolve("ec2", "ec2:RunInstances", containerRequest);

        assertEquals(List.of("payments"), conditions.get("aws:RequestTag/Team"));
        assertEquals(List.of("prod"), conditions.get("aws:RequestTag/Env"));
        assertFalse(conditions.containsKey("aws:ResourceTag/Team"));
    }

    @Test
    void createTagsResolvesBothTheRequestedTagsAndTheResourcesCurrentTags() {
        when(ec2Service.resourceTags("i-0123456789")).thenReturn(List.of(new Tag("Owner", "alice")));
        ContainerRequestContext containerRequest = formRequest(
                "Action=CreateTags&ResourceId.1=i-0123456789&Tag.1.Key=Env&Tag.1.Value=prod");

        Map<String, List<String>> conditions =
                resolver.resolve("ec2", "ec2:CreateTags", containerRequest);

        assertEquals(List.of("prod"), conditions.get("aws:RequestTag/Env"));
        assertEquals(List.of("alice"), conditions.get("aws:ResourceTag/Owner"));
    }

    @Test
    void instanceActionsResolveResourceTagsFromTheFirstNamedInstance() {
        when(ec2Service.resourceTags("i-0123456789")).thenReturn(List.of(new Tag("Team", "payments")));

        for (String action : List.of("ec2:TerminateInstances", "ec2:DescribeInstances")) {
            Map<String, List<String>> conditions = resolver.resolve("ec2", action,
                    formRequest("Action=X&InstanceId.1=i-0123456789&InstanceId.2=i-9999999999"));
            assertEquals(List.of("payments"), conditions.get("aws:ResourceTag/Team"), action);
        }
        assertEquals(List.of("payments"),
                resolver.resolve("ec2", "ec2:DeleteTags",
                        formRequest("Action=DeleteTags&ResourceId.1=i-0123456789&Tag.1.Key=Team"))
                        .get("aws:ResourceTag/Team"));
    }

    @Test
    void ec2ContextIsAbsentWhenNoResourceIsNamedOrTheActionIsNotCovered() {
        assertNull(resolver.resolve("ec2", "ec2:DescribeInstances", formRequest("Action=DescribeInstances")));
        assertNull(resolver.resolve("ec2", "ec2:DescribeVpcs", formRequest("Action=DescribeVpcs")));
        assertNull(resolver.resolve("ec2", "ec2:CreateTags",
                formRequest("Action=CreateTags&ResourceId.1=i-0123456789")));
    }

    @Test
    void formParametersAreBufferedOncePerRequest() {
        ContainerRequestContext containerRequest = formRequest("Action=TerminateInstances&InstanceId.1=i-1");
        when(ec2Service.resourceTags("i-1")).thenReturn(List.of(new Tag("Team", "payments")));

        resolver.resolve("ec2", "ec2:TerminateInstances", containerRequest);

        verify(containerRequest).setEntityStream(any());
        verify(containerRequest).setProperty(eq("floci.bufferedFormBody"), any());
    }

    // ── The resolved context drives the real evaluator ─────────────────────────

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void resourceTagConditionAllowsATaggedInstanceAndDeniesAnUntaggedOne() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        String body = "Action=TerminateInstances&InstanceId.1=i-0123456789";

        when(ec2Service.resourceTags("i-0123456789")).thenReturn(List.of(new Tag("Team", "payments")));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy),
                "ec2:TerminateInstances", "*",
                resolver.resolve("ec2", "ec2:TerminateInstances", formRequest(body))));

        when(ec2Service.resourceTags("i-0123456789")).thenReturn(List.of(new Tag("Team", "engineering")));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy),
                "ec2:TerminateInstances", "*",
                resolver.resolve("ec2", "ec2:TerminateInstances", formRequest(body))));
    }

    @Test
    void requestTagConditionAllowsTheDemandedTagValueAndDeniesAnotherOne() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:CreateTags","Resource":"*",
                   "Condition":{"StringEquals":{"aws:RequestTag/CostCenter":"1234"}}}
                ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy), "ec2:CreateTags", "*",
                resolver.resolve("ec2", "ec2:CreateTags", formRequest(
                        "Action=CreateTags&ResourceId.1=i-1&Tag.1.Key=CostCenter&Tag.1.Value=1234"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy), "ec2:CreateTags", "*",
                resolver.resolve("ec2", "ec2:CreateTags", formRequest(
                        "Action=CreateTags&ResourceId.1=i-1&Tag.1.Key=CostCenter&Tag.1.Value=9999"))));
    }

    @Test
    void resourceTagConditionOnABucketFollowsItsOwnerTag() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetBucketTagging","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"alice"}}}
                ]}""";

        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "alice"));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy), "s3:GetBucketTagging", "*",
                resolver.resolve("s3", "s3:GetBucketTagging", s3Request("/my-bucket", ""))));

        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "bob"));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy), "s3:GetBucketTagging", "*",
                resolver.resolve("s3", "s3:GetBucketTagging", s3Request("/my-bucket", ""))));
    }

    // ── Every target of a multi-resource request is evaluated ──────────────────

    @Test
    void remainingTargetsCarryEachLaterResourcesOwnTags() {
        when(ec2Service.resourceTags("i-1")).thenReturn(List.of(new Tag("Team", "payments")));
        when(ec2Service.resourceTags("i-2")).thenReturn(List.of(new Tag("Team", "engineering")));
        when(ec2Service.resourceTags("i-3")).thenReturn(List.of());
        ContainerRequestContext containerRequest = formRequest(
                "Action=TerminateInstances&InstanceId.1=i-1&InstanceId.2=i-2&InstanceId.3=i-3");

        List<Map<String, List<String>>> remaining =
                resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances", containerRequest);

        assertEquals(2, remaining.size());
        assertEquals(List.of("engineering"), remaining.get(0).get("aws:ResourceTag/Team"));
        // An untagged target still gets its own, empty, context so a tag condition fails on it.
        assertEquals(Map.of(), remaining.get(1));
    }

    @Test
    void remainingTargetsOfCreateTagsRepeatTheRequestedTagsPerResource() {
        when(ec2Service.resourceTags("vpc-2")).thenReturn(List.of(new Tag("Owner", "bob")));
        ContainerRequestContext containerRequest = formRequest(
                "Action=CreateTags&ResourceId.1=vpc-1&ResourceId.2=vpc-2&Tag.1.Key=Env&Tag.1.Value=prod");

        List<Map<String, List<String>>> remaining =
                resolver.resolveRemainingTargets("ec2", "ec2:CreateTags", containerRequest);

        assertEquals(1, remaining.size());
        assertEquals(List.of("prod"), remaining.get(0).get("aws:RequestTag/Env"));
        assertEquals(List.of("bob"), remaining.get(0).get("aws:ResourceTag/Owner"));
    }

    @Test
    void singleTargetRequestsAndOtherServicesHaveNoRemainingTargets() {
        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-1")));
        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:RunInstances",
                formRequest("Action=RunInstances&TagSpecification.1.ResourceType=instance")));
        assertEquals(List.of(), resolver.resolveRemainingTargets("s3", "s3:DeleteBucket",
                s3Request("/my-bucket", "")));
    }

    @Test
    void policyMustHoldForEveryTargetNotJustTheFirst() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        String body = "Action=TerminateInstances&InstanceId.1=i-1&InstanceId.2=i-2";
        when(ec2Service.resourceTags("i-1")).thenReturn(List.of(new Tag("Team", "payments")));

        when(ec2Service.resourceTags("i-2")).thenReturn(List.of(new Tag("Team", "engineering")));
        assertEquals(Decision.DENY, decisionForEveryTarget(policy, "ec2:TerminateInstances", formRequest(body)));

        when(ec2Service.resourceTags("i-2")).thenReturn(List.of(new Tag("Team", "payments")));
        assertEquals(Decision.ALLOW, decisionForEveryTarget(policy, "ec2:TerminateInstances", formRequest(body)));
    }

    @Test
    void aRepeatedNumberedParameterKeepsItsFirstValueLikeTheHandlers() {
        when(ec2Service.resourceTags("i-first")).thenReturn(List.of(new Tag("Team", "engineering")));
        when(ec2Service.resourceTags("i-second")).thenReturn(List.of(new Tag("Team", "payments")));

        Map<String, List<String>> conditions = resolver.resolve("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-first&InstanceId.1=i-second"));

        assertEquals(List.of("engineering"), conditions.get("aws:ResourceTag/Team"));
    }

    @Test
    void remainingTargetsStopAtTheFirstNumberingGapLikeTheHandlers() {
        when(ec2Service.resourceTags("i-2")).thenReturn(List.of(new Tag("Team", "payments")));

        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.2=i-2")));
        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-1&InstanceId.3=i-3")));
    }

    @Test
    void aRepeatedKeyCannotSwapAnAllowedResourceInFrontOfTheDeniedOne() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        when(ec2Service.resourceTags("i-denied")).thenReturn(List.of(new Tag("Team", "engineering")));
        when(ec2Service.resourceTags("i-allowed")).thenReturn(List.of(new Tag("Team", "payments")));

        // The handler terminates i-denied, the first value, so that is what must be authorized.
        assertEquals(Decision.DENY, decisionForEveryTarget(policy, "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-denied&InstanceId.1=i-allowed")));
    }

    private Decision decisionForEveryTarget(String policy, String action, ContainerRequestContext request) {
        Decision first = evaluator.simulateCustomPolicy(List.of(policy), action, "*",
                resolver.resolve("ec2", action, request));
        if (first == Decision.DENY) {
            return first;
        }
        for (Map<String, List<String>> target : resolver.resolveRemainingTargets("ec2", action, request)) {
            if (evaluator.simulateCustomPolicy(List.of(policy), action, "*", target) == Decision.DENY) {
                return Decision.DENY;
            }
        }
        return Decision.ALLOW;
    }
}

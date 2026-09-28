package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.resource.SupportedResourceType;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.DynamoDbCfnProvisioner;
import io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.RecordingDynamoDbBackend.Invocation;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iot.IotMqttBrokerService;
import io.github.hectorvent.floci.services.iot.IotPublishEventRecorder;
import io.github.hectorvent.floci.services.iot.IotService;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The facade Java consumers of DynamoDB reach the engine through: it hands out the selected
 * capabilities, scopes to the ambient account, and each consumer gives it the account and region
 * it means. Every case runs against a recording backend, so nothing here touches native state.
 */
class DynamoDbFacadeTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String OWNER_ACCOUNT = "111122223333";
    private static final String OTHER_REGION = "eu-west-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RecordingDynamoDbBackend backend = new RecordingDynamoDbBackend();
    private final DynamoDbFacade facade =
            new DynamoDbFacade(backend, backend, new RegionResolver(DEFAULT_REGION, DEFAULT_ACCOUNT));

    @Test
    void scopeIsTheAmbientAccountInTheGivenRegion() {
        assertEquals(new Scope(DEFAULT_ACCOUNT, OTHER_REGION), facade.scope(OTHER_REGION));
    }

    @Test
    void resourcesAreListedForTheAmbientAccount() {
        assertEquals(List.of(RecordingDynamoDbBackend.RESOURCE), facade.getResources());
        assertEquals(List.of(new Invocation("resources", DEFAULT_ACCOUNT, null)), backend.invocations());
    }

    @Test
    void supportedResourceTypeIsTheDynamoDbTable() {
        assertEquals(Set.of(new SupportedResourceType("dynamodb:table", "dynamodb", true)),
                facade.getSupportedResourceTypes());
    }

    @Test
    void capabilitiesAreTheSelectedOnes() {
        RecordingDynamoDbBackend items = new RecordingDynamoDbBackend();
        RecordingDynamoDbBackend tables = new RecordingDynamoDbBackend();
        DynamoDbFacade split = new DynamoDbFacade(items, tables, new RegionResolver(DEFAULT_REGION, DEFAULT_ACCOUNT));

        assertSame(items, split.items());
        assertSame(tables, split.tables());
    }

    @Test
    void cloudFormationTableUsesTheStackRegion() throws Exception {
        DynamoDbCfnProvisioner provisioner = new DynamoDbCfnProvisioner(facade);
        StackResource table = cfnResource("Orders", "AWS::DynamoDB::Table");

        provisioner.provision(table, mapper.readTree("{\"TableName\":\"orders\"}"),
                cfnContext(DEFAULT_ACCOUNT, OTHER_REGION));
        provisioner.delete("AWS::DynamoDB::Table", "orders", OTHER_REGION);

        assertEquals(List.of(
                new Invocation("createTable", DEFAULT_ACCOUNT, OTHER_REGION),
                new Invocation("listTagsOfResource", DEFAULT_ACCOUNT, OTHER_REGION),
                new Invocation("deleteTable", DEFAULT_ACCOUNT, OTHER_REGION)), backend.invocations());
    }

    @Test
    void cloudFormationProvisionsUnderTheStackAccountRatherThanTheAmbientOne() throws Exception {
        DynamoDbCfnProvisioner provisioner = new DynamoDbCfnProvisioner(facade);
        ProvisionContext stack = cfnContext(OWNER_ACCOUNT, OTHER_REGION);

        provisioner.provision(cfnResource("Orders", "AWS::DynamoDB::GlobalTable"), mapper.readTree("""
                {"TableName": "orders", "Replicas": [{"Region": "us-west-2"}]}
                """), stack);
        provisioner.provision(cfnResource("OrdersReplica", "Custom::DynamoDBReplica"), mapper.readTree("""
                {"TableName": "orders", "Region": "ap-south-1"}
                """), stack);

        assertEquals(List.of(
                new Invocation("createTable", OWNER_ACCOUNT, OTHER_REGION),
                new Invocation("listTagsOfResource", OWNER_ACCOUNT, OTHER_REGION),
                new Invocation("describeTable", OWNER_ACCOUNT, OTHER_REGION),
                new Invocation("ensureGlobalTable", OWNER_ACCOUNT, OTHER_REGION),
                new Invocation("applyReplicaUpdates", OWNER_ACCOUNT, OTHER_REGION),
                new Invocation("applyReplicaUpdates", OWNER_ACCOUNT, OTHER_REGION)), backend.invocations());
    }

    @Test
    void iotDynamoDbRuleActionWritesAsTheRuleOwnerInTheRuleRegion() throws Exception {
        // The rule is created by OWNER_ACCOUNT in OTHER_REGION, then fires with no request account,
        // as an MQTT publish does: the action must write as the rule's owner, not the ambient default.
        IotService iot = iotService(new RegionResolver(OTHER_REGION, OWNER_ACCOUNT));
        iot.createTopicRule("toTable", mapper.readTree("""
                {"sql": "SELECT * FROM 'devices/+/metrics'",
                 "actions": [{"dynamoDBv2": {"putItem": {"tableName": "metrics"},
                              "roleArn": "arn:aws:iam::111122223333:role/rule"}}]}
                """), OTHER_REGION);

        iot.publish("devices/d1/metrics", "{\"t\":1}".getBytes(StandardCharsets.UTF_8), false, 0, null, "sensor-1");

        assertEquals(List.of(new Invocation("putItem", OWNER_ACCOUNT, OTHER_REGION)), backend.invocations());
    }

    @Test
    @SuppressWarnings("unchecked")
    void iamConditionContextFindsTheTableInTheRequestRegion() throws Exception {
        Instance<DynamoDbFacade> dynamoDb = mock(Instance.class);
        when(dynamoDb.isResolvable()).thenReturn(true);
        when(dynamoDb.get()).thenReturn(facade);
        RequestContext requestContext = new RequestContext();
        requestContext.setRegion(OTHER_REGION);
        IamConditionContextResolver resolver = new IamConditionContextResolver(dynamoDb,
                mock(Instance.class), mock(Instance.class), requestContext, mock(EmulatorConfig.class));
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        JsonNode body = mapper.readTree("""
                {"TableName": "recorded-table", "Key": {"id": {"S": "USER_alice"}}}
                """);
        when(request.getProperty("floci.bufferedJsonBody")).thenReturn(body);

        Map<String, List<String>> conditions = resolver.resolve("dynamodb", "dynamodb:GetItem", request);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of(new Invocation("findTable", DEFAULT_ACCOUNT, OTHER_REGION)), backend.invocations());
    }

    private StackResource cfnResource(String logicalId, String type) {
        StackResource resource = new StackResource();
        resource.setLogicalId(logicalId);
        resource.setResourceType(type);
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionContext cfnContext(String accountId, String region) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, region, accountId, "stack");
    }

    private IotService iotService(RegionResolver regionResolver) {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultRegion()).thenReturn(DEFAULT_REGION);
        when(config.defaultAccountId()).thenReturn(DEFAULT_ACCOUNT);
        when(config.services().iot().ruleSqlStrict()).thenReturn(false);
        return new IotService(storageFactory, config, regionResolver, mapper, new IotPublishEventRecorder(),
                mock(IotMqttBrokerService.class), mock(SqsService.class), mock(SnsService.class),
                mock(S3Service.class), mock(KinesisService.class), facade, mock(LambdaService.class),
                mock(FirehoseService.class), mock(CloudWatchLogsService.class),
                mock(FlociCertificateAuthority.class), new IamPolicyEvaluator(mapper));
    }
}

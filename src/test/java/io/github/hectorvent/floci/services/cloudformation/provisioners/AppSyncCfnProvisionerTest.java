package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.ApiKey;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.FunctionConfiguration;
import io.github.hectorvent.floci.services.appsync.model.GraphqlApi;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatus;
import io.github.hectorvent.floci.services.appsync.model.SchemaCreationStatusType;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The AppSync CFN provisioner in isolation, with only AppSyncService mocked. */
class AppSyncCfnProvisionerTest {

    private static final String API_ID = "abcd1234";

    private final AppSyncService appSync = mock(AppSyncService.class);
    private final AppSyncCfnProvisioner provisioner = new AppSyncCfnProvisioner(appSync);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Identity resolution: these templates carry no intrinsics, and resolveNode's contract for
        // an intrinsic-free node is to hand it back unchanged.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null || node.isNull() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "eu-west-1", "000000000000", "my-stack");
    }

    private ProvisionContext updateCtx(String priorPhysicalId) {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorPhysicalId);
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private GraphqlApi api() {
        GraphqlApi api = new GraphqlApi();
        api.setApiId(API_ID);
        api.setName("account-api");
        api.setArn("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID);
        api.setUris(Map.of(
                "GRAPHQL", "http://localhost:4566/v1/apis/" + API_ID + "/graphql",
                "REALTIME", "ws://localhost:4566/v1/apis/" + API_ID + "/graphql/realtime"));
        return api;
    }

    private void schemaCompletesImmediately() {
        SchemaCreationStatus success = new SchemaCreationStatus();
        success.setStatus(SchemaCreationStatusType.SUCCESS);
        when(appSync.getSchemaCreationStatus(API_ID)).thenReturn(success);
    }

    /** The request the provisioner handed CreateGraphqlApi. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedApiRequest() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(appSync).createGraphqlApi(captor.capture(), eq("eu-west-1"));
        return captor.getValue();
    }

    // ── GraphQLApi ───────────────────────────────────────────────────────────

    @Test
    void graphqlApiSetsPhysicalIdAndGetAttAttributes() {
        when(appSync.createGraphqlApi(any(), eq("eu-west-1"))).thenReturn(api());
        StackResource r = resource("AWS::AppSync::GraphQLApi", "GraphQlApi");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "account-api")
                .put("AuthenticationType", "API_KEY")
                .put("XrayEnabled", false);

        provisioner.provision(r, props, ctx());

        assertEquals(API_ID, r.getPhysicalId());
        assertEquals(API_ID, r.getAttributes().get("ApiId"));
        assertEquals("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID, r.getAttributes().get("Arn"));
        assertEquals("http://localhost:4566/v1/apis/" + API_ID + "/graphql",
                r.getAttributes().get("GraphQLUrl"));
        assertEquals("ws://localhost:4566/v1/apis/" + API_ID + "/graphql/realtime",
                r.getAttributes().get("RealtimeUrl"));
        // The DNS attributes are the endpoints' hosts, which is what a custom domain aliases.
        assertEquals("localhost", r.getAttributes().get("GraphQLDns"));
        assertEquals("localhost", r.getAttributes().get("RealtimeDns"));
    }

    @Test
    void graphqlApiLowerCamelisesPropertiesForTheApi() {
        when(appSync.createGraphqlApi(any(), eq("eu-west-1"))).thenReturn(api());
        StackResource r = resource("AWS::AppSync::GraphQLApi", "GraphQlApi");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "account-api")
                .put("AuthenticationType", "API_KEY")
                .put("XrayEnabled", false);
        ObjectNode logConfig = props.putObject("LogConfig");
        logConfig.put("CloudWatchLogsRoleArn", "arn:aws:iam::000000000000:role/logs");
        logConfig.put("FieldLogLevel", "ALL");
        ObjectNode oidc = props.putArray("AdditionalAuthenticationProviders").addObject();
        oidc.put("AuthenticationType", "OPENID_CONNECT");
        oidc.putObject("OpenIDConnectConfig").put("Issuer", "https://issuer").put("ClientId", "cid");

        provisioner.provision(r, props, ctx());

        Map<String, Object> request = capturedApiRequest();
        assertEquals("account-api", request.get("name"));
        assertEquals("API_KEY", request.get("authenticationType"));
        assertEquals(Boolean.FALSE, request.get("xrayEnabled"));
        Map<?, ?> resolvedLog = (Map<?, ?>) request.get("logConfig");
        assertEquals("arn:aws:iam::000000000000:role/logs", resolvedLog.get("cloudWatchLogsRoleArn"));
        assertEquals("ALL", resolvedLog.get("fieldLogLevel"));
        Map<?, ?> provider = (Map<?, ?>) ((List<?>) request.get("additionalAuthenticationProviders")).get(0);
        assertEquals("OPENID_CONNECT", provider.get("authenticationType"));
        // AWS keeps the acronym capitalised in this member name; the model's field is openIDConnectConfig.
        assertEquals("https://issuer", ((Map<?, ?>) provider.get("openIDConnectConfig")).get("issuer"));
    }

    @Test
    void graphqlApiEnvironmentVariablesGoThroughTheirOwnCall() {
        when(appSync.createGraphqlApi(any(), eq("eu-west-1"))).thenReturn(api());
        StackResource r = resource("AWS::AppSync::GraphQLApi", "GraphQlApi");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "account-api")
                .put("AuthenticationType", "API_KEY");
        props.putObject("EnvironmentVariables").put("STRIPE_PRODUCT_SMALL_ID", "prod_1");

        provisioner.provision(r, props, ctx());

        // CreateGraphqlApi has no environment-variable member: it is PutGraphqlApiEnvironmentVariables.
        assertFalse(capturedApiRequest().containsKey("environmentVariables"));
        verify(appSync).putEnvironmentVariables(API_ID, Map.of("STRIPE_PRODUCT_SMALL_ID", "prod_1"));
    }

    @Test
    void graphqlApiUpdateUpdatesInPlace() {
        when(appSync.updateGraphqlApi(eq(API_ID), any(), eq("eu-west-1"))).thenReturn(api());
        StackResource r = resource("AWS::AppSync::GraphQLApi", "GraphQlApi");
        ObjectNode props = mapper.createObjectNode()
                .put("Name", "account-api")
                .put("AuthenticationType", "AWS_IAM");

        provisioner.provision(r, props, updateCtx(API_ID));

        verify(appSync).updateGraphqlApi(eq(API_ID), any(), eq("eu-west-1"));
        verify(appSync, never()).createGraphqlApi(any(), anyString());
        assertEquals(API_ID, r.getPhysicalId());
    }

    // ── GraphQLSchema ────────────────────────────────────────────────────────

    @Test
    void schemaWaitsForCompilationBeforeCompleting() {
        SchemaCreationStatus processing = new SchemaCreationStatus();
        processing.setStatus(SchemaCreationStatusType.PROCESSING);
        SchemaCreationStatus success = new SchemaCreationStatus();
        success.setStatus(SchemaCreationStatusType.SUCCESS);
        when(appSync.getSchemaCreationStatus(API_ID)).thenReturn(processing, processing, success);
        StackResource r = resource("AWS::AppSync::GraphQLSchema", "GraphQlSchema");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Definition", "type Query { ping: String }");

        provisioner.provision(r, props, ctx());

        verify(appSync).startSchemaCreation(API_ID, "type Query { ping: String }");
        // Every AppSync call for the API is refused with a 409 while the schema is PROCESSING, so a
        // provision that returned early would fail the data sources and resolvers behind it.
        verify(appSync, org.mockito.Mockito.times(3)).getSchemaCreationStatus(API_ID);
        assertEquals(API_ID + "GraphQLSchema", r.getPhysicalId());
        assertEquals(API_ID, r.getAttributes().get("ApiId"));
    }

    @Test
    void schemaCompilationFailureFailsTheResource() {
        SchemaCreationStatus failed = new SchemaCreationStatus();
        failed.setStatus(SchemaCreationStatusType.FAILED);
        failed.setDetails("Unknown type Widget");
        when(appSync.getSchemaCreationStatus(API_ID)).thenReturn(failed);
        StackResource r = resource("AWS::AppSync::GraphQLSchema", "GraphQlSchema");
        ObjectNode props = mapper.createObjectNode().put("ApiId", API_ID).put("Definition", "type Q { a: Widget }");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertTrue(e.getMessage().contains("Unknown type Widget"), e.getMessage());
    }

    @Test
    void schemaFromS3IsRefusedRatherThanSilentlyEmpty() {
        StackResource r = resource("AWS::AppSync::GraphQLSchema", "GraphQlSchema");
        ObjectNode props = mapper.createObjectNode().put("ApiId", API_ID);
        props.putObject("DefinitionS3Location").put("Bucket", "b").put("Key", "schema.graphql");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertTrue(e.getMessage().contains("DefinitionS3Location"), e.getMessage());
        verify(appSync, never()).startSchemaCreation(anyString(), anyString());
    }

    @Test
    void schemaWithoutApiIdIsRefused() {
        StackResource r = resource("AWS::AppSync::GraphQLSchema", "GraphQlSchema");
        ObjectNode props = mapper.createObjectNode().put("Definition", "type Query { ping: String }");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        verifyNoInteractions(appSync);
    }

    // ── DataSource ───────────────────────────────────────────────────────────

    @Test
    void dataSourceKeepsAwsSpellingOfDynamodbConfig() {
        DataSource ds = new DataSource();
        ds.setName("publicApiAuthKeysTable");
        ds.setDataSourceArn("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID
                + "/datasources/publicApiAuthKeysTable");
        when(appSync.createDataSource(eq(API_ID), any(), eq("eu-west-1"))).thenReturn(ds);
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDsAuthKeys");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Name", "publicApiAuthKeysTable")
                .put("Type", "AMAZON_DYNAMODB")
                .put("ServiceRoleArn", "arn:aws:iam::000000000000:role/ds");
        props.putObject("DynamoDBConfig")
                .put("AwsRegion", "eu-west-1")
                .put("TableName", "qip-local-public-api-auth-keys")
                .put("UseCallerCredentials", false);

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(appSync).createDataSource(eq(API_ID), captor.capture(), eq("eu-west-1"));
        Map<String, Object> request = captor.getValue();
        // dynamoDBConfig — what the plain lower-camel rule would produce — is silently ignored by
        // the service, leaving a DynamoDB data source with no table.
        assertNull(request.get("dynamoDBConfig"));
        Map<?, ?> dynamodb = (Map<?, ?>) request.get("dynamodbConfig");
        assertEquals("qip-local-public-api-auth-keys", dynamodb.get("tableName"));
        assertEquals("eu-west-1", dynamodb.get("awsRegion"));
        assertEquals(Boolean.FALSE, dynamodb.get("useCallerCredentials"));
        assertEquals("arn:aws:iam::000000000000:role/ds", request.get("serviceRoleArn"));
        // The ApiId is a path parameter, not a member of the request body.
        assertFalse(request.containsKey("apiId"));

        assertEquals(ds.getDataSourceArn(), r.getPhysicalId());
        assertEquals(ds.getDataSourceArn(), r.getAttributes().get("DataSourceArn"));
        assertEquals("publicApiAuthKeysTable", r.getAttributes().get("Name"));
        assertEquals(API_ID, r.getAttributes().get("ApiId"));
    }

    @Test
    void dataSourceCamelisesNestedRelationalConfig() {
        DataSource ds = new DataSource();
        ds.setName("accountDB");
        ds.setDataSourceArn("arn:ds");
        when(appSync.createDataSource(eq(API_ID), any(), eq("eu-west-1"))).thenReturn(ds);
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDsAccountDb");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Name", "accountDB")
                .put("Type", "RELATIONAL_DATABASE");
        ObjectNode relational = props.putObject("RelationalDatabaseConfig");
        relational.put("RelationalDatabaseSourceType", "RDS_HTTP_ENDPOINT");
        relational.putObject("RdsHttpEndpointConfig")
                .put("AwsRegion", "eu-west-1")
                .put("DbClusterIdentifier", "ls-db-cluster")
                .put("DatabaseName", "accountdb")
                .put("AwsSecretStoreArn", "arn:aws:secretsmanager:eu-west-1:000000000000:secret:db");

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(appSync).createDataSource(eq(API_ID), captor.capture(), eq("eu-west-1"));
        Map<?, ?> config = (Map<?, ?>) captor.getValue().get("relationalDatabaseConfig");
        assertEquals("RDS_HTTP_ENDPOINT", config.get("relationalDatabaseSourceType"));
        Map<?, ?> rds = (Map<?, ?>) config.get("rdsHttpEndpointConfig");
        assertEquals("ls-db-cluster", rds.get("dbClusterIdentifier"));
        assertEquals("accountdb", rds.get("databaseName"));
        assertEquals("arn:aws:secretsmanager:eu-west-1:000000000000:secret:db", rds.get("awsSecretStoreArn"));
    }

    @Test
    void dataSourceUpdateReusesTheNameItAlreadyHas() {
        DataSource ds = new DataSource();
        ds.setName("accountDB");
        ds.setDataSourceArn("arn:ds");
        when(appSync.updateDataSource(eq(API_ID), eq("accountDB"), any())).thenReturn(ds);
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDsAccountDb");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("Name", "accountDB");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Name", "accountDB")
                .put("Type", "RELATIONAL_DATABASE");

        provisioner.provision(r, props, updateCtx("arn:ds"));

        verify(appSync).updateDataSource(eq(API_ID), eq("accountDB"), any());
        verify(appSync, never()).createDataSource(anyString(), any(), anyString());
    }

    @Test
    void dataSourceMovedToAnotherApiIsCreatedNotUpdated() {
        DataSource ds = new DataSource();
        ds.setName("accountDB");
        ds.setDataSourceArn("arn:ds:new");
        when(appSync.createDataSource(eq("other-api"), any(), eq("eu-west-1"))).thenReturn(ds);
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDsAccountDb");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("Name", "accountDB");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", "other-api")
                .put("Name", "accountDB")
                .put("Type", "RELATIONAL_DATABASE");

        provisioner.provision(r, props, updateCtx("arn:ds"));

        // The entity the resource pointed at lives in an API this resource no longer names, so
        // updating it would mutate a data source belonging to a different API.
        verify(appSync).createDataSource(eq("other-api"), any(), eq("eu-west-1"));
        verify(appSync, never()).updateDataSource(anyString(), anyString(), any());
    }

    // ── FunctionConfiguration ────────────────────────────────────────────────

    @Test
    void functionPublishesFunctionIdAndCarriesTheJsRuntime() {
        FunctionConfiguration fn = new FunctionConfiguration();
        fn.setFunctionId("fn123");
        fn.setName("Query_getMessages_0");
        fn.setDataSourceName("accountDB");
        fn.setFunctionArn("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID + "/functions/fn123");
        when(appSync.createFunction(eq(API_ID), any(), eq("eu-west-1"))).thenReturn(fn);
        StackResource r = resource("AWS::AppSync::FunctionConfiguration", "GraphQlFnGetMessages");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Name", "Query_getMessages_0")
                .put("DataSourceName", "accountDB")
                .put("FunctionVersion", "2018-05-29")
                .put("Code", "export function request() { return {}; }");
        props.putObject("Runtime").put("Name", "APPSYNC_JS").put("RuntimeVersion", "1.0.0");

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(appSync).createFunction(eq(API_ID), captor.capture(), eq("eu-west-1"));
        Map<String, Object> request = captor.getValue();
        assertEquals("2018-05-29", request.get("functionVersion"));
        assertEquals("export function request() { return {}; }", request.get("code"));
        assertEquals("APPSYNC_JS", ((Map<?, ?>) request.get("runtime")).get("name"));

        assertEquals(fn.getFunctionArn(), r.getPhysicalId());
        // The pipeline resolvers reference their functions by Fn::GetAtt FunctionId.
        assertEquals("fn123", r.getAttributes().get("FunctionId"));
        assertEquals("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID + "/functions/fn123",
                r.getAttributes().get("FunctionArn"));
        assertEquals("accountDB", r.getAttributes().get("DataSourceName"));
    }

    @Test
    void functionUpdateAddressesTheRecordedFunctionId() {
        FunctionConfiguration fn = new FunctionConfiguration();
        fn.setFunctionId("fn123");
        fn.setName("Query_getMessages_0");
        fn.setFunctionArn("arn:fn");
        when(appSync.updateFunction(eq(API_ID), eq("fn123"), any())).thenReturn(fn);
        StackResource r = resource("AWS::AppSync::FunctionConfiguration", "GraphQlFnGetMessages");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("FunctionId", "fn123");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Name", "Query_getMessages_0")
                .put("DataSourceName", "accountDB");

        provisioner.provision(r, props, updateCtx("arn:fn"));

        // The function id is not derivable from the ARN-shaped physical id alone in the general
        // case, so it has to come off the attributes recorded at create time.
        verify(appSync).updateFunction(eq(API_ID), eq("fn123"), any());
        verify(appSync, never()).createFunction(anyString(), any(), anyString());
    }

    // ── Resolver ─────────────────────────────────────────────────────────────

    @Test
    void pipelineResolverIsProvisionedWithoutADataSource() {
        Resolver resolver = new Resolver();
        resolver.setResolverArn("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID
                + "/types/Query/resolvers/getMessages");
        when(appSync.createResolver(eq(API_ID), any(), eq("eu-west-1"))).thenReturn(resolver);
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolverQuerygetMessages");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("TypeName", "Query")
                .put("FieldName", "getMessages")
                .put("Kind", "PIPELINE")
                .put("Code", "export function request() { return {}; }");
        props.putObject("Runtime").put("Name", "APPSYNC_JS").put("RuntimeVersion", "1.0.0");
        props.putObject("PipelineConfig").putArray("Functions").add("fn123");

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(appSync).createResolver(eq(API_ID), captor.capture(), eq("eu-west-1"));
        Map<String, Object> request = captor.getValue();
        assertEquals("PIPELINE", request.get("kind"));
        assertNull(request.get("dataSourceName"));
        assertEquals(List.of("fn123"), ((Map<?, ?>) request.get("pipelineConfig")).get("functions"));

        assertEquals(resolver.getResolverArn(), r.getPhysicalId());
        assertEquals("Query", r.getAttributes().get("TypeName"));
        assertEquals("getMessages", r.getAttributes().get("FieldName"));
    }

    @Test
    void resolverUpdateAddressesTypeAndField() {
        Resolver resolver = new Resolver();
        resolver.setResolverArn("arn:resolver");
        when(appSync.updateResolver(eq(API_ID), eq("Query"), eq("getMessages"), any())).thenReturn(resolver);
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolverQuerygetMessages");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("TypeName", "Query");
        r.getAttributes().put("FieldName", "getMessages");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("TypeName", "Query")
                .put("FieldName", "getMessages")
                .put("Kind", "PIPELINE");

        provisioner.provision(r, props, updateCtx("arn:resolver"));

        verify(appSync).updateResolver(eq(API_ID), eq("Query"), eq("getMessages"), any());
        verify(appSync, never()).createResolver(anyString(), any(), anyString());
    }

    @Test
    void resolverOnADifferentFieldIsCreatedNotUpdated() {
        Resolver resolver = new Resolver();
        resolver.setResolverArn("arn:resolver:new");
        when(appSync.createResolver(eq(API_ID), any(), eq("eu-west-1"))).thenReturn(resolver);
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolverQuerygetMessages");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("TypeName", "Query");
        r.getAttributes().put("FieldName", "getMessages");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("TypeName", "Query")
                .put("FieldName", "getUsers")
                .put("Kind", "PIPELINE");

        provisioner.provision(r, props, updateCtx("arn:resolver"));

        // TypeName and FieldName are create-only: a resolver on a new field is a new resolver.
        verify(appSync).createResolver(eq(API_ID), any(), eq("eu-west-1"));
        verify(appSync, never()).updateResolver(anyString(), anyString(), anyString(), any());
    }

    @Test
    void resolverWithoutTypeOrFieldIsRefused() {
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolver");
        ObjectNode props = mapper.createObjectNode().put("ApiId", API_ID).put("TypeName", "Query");

        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        verify(appSync, never()).createResolver(anyString(), any(), anyString());
    }

    @Test
    void resolverCodeFromS3IsRefused() {
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolver");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("TypeName", "Query")
                .put("FieldName", "getMessages")
                .put("CodeS3Location", "s3://bucket/resolver.js");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));

        assertTrue(e.getMessage().contains("CodeS3Location"), e.getMessage());
    }

    // ── ApiKey ───────────────────────────────────────────────────────────────

    @Test
    void apiKeyPublishesTheKeyAndItsId() {
        ApiKey key = new ApiKey();
        key.setId("da2-abcdefghijklmnopqrstuvwxyz");
        key.setApiId(API_ID);
        when(appSync.createApiKey(eq(API_ID), any())).thenReturn(key);
        StackResource r = resource("AWS::AppSync::ApiKey", "GraphQlApiqvalia");
        ObjectNode props = mapper.createObjectNode()
                .put("ApiId", API_ID)
                .put("Description", "qvalia")
                .put("Expires", 1820570400L);

        provisioner.provision(r, props, ctx());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(appSync).createApiKey(eq(API_ID), captor.capture());
        // Expires must stay a number: the service parses it as an epoch second.
        assertEquals(1820570400L, captor.getValue().get("expires"));
        assertEquals("qvalia", captor.getValue().get("description"));

        assertEquals("da2-abcdefghijklmnopqrstuvwxyz", r.getPhysicalId());
        assertEquals("da2-abcdefghijklmnopqrstuvwxyz", r.getAttributes().get("ApiKey"));
        assertEquals("da2-abcdefghijklmnopqrstuvwxyz", r.getAttributes().get("ApiKeyId"));
        assertEquals("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID
                + "/apikeys/da2-abcdefghijklmnopqrstuvwxyz", r.getAttributes().get("Arn"));
    }

    // ── Deletes ──────────────────────────────────────────────────────────────

    @Test
    void deleteApiDeletesByPhysicalId() {
        StackResource r = resource("AWS::AppSync::GraphQLApi", "GraphQlApi");
        r.setPhysicalId(API_ID);

        provisioner.delete(r, "eu-west-1");

        verify(appSync).deleteGraphqlApi(API_ID);
    }

    @Test
    void deleteDataSourceUsesTheRecordedApiIdAndName() {
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDsAccountDb");
        r.setPhysicalId("arn:aws:appsync:eu-west-1:000000000000:apis/" + API_ID + "/datasources/accountDB");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("Name", "accountDB");

        provisioner.delete(r, "eu-west-1");

        // DeleteDataSource takes the api id and the name; the ARN that is the physical id is not it.
        verify(appSync).deleteDataSource(API_ID, "accountDB");
    }

    @Test
    void deleteResolverUsesTheRecordedTypeAndField() {
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolver");
        r.setPhysicalId("arn:resolver");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("TypeName", "Query");
        r.getAttributes().put("FieldName", "getMessages");

        provisioner.delete(r, "eu-west-1");

        verify(appSync).deleteResolver(API_ID, "Query", "getMessages");
    }

    @Test
    void deleteFunctionUsesTheRecordedFunctionId() {
        StackResource r = resource("AWS::AppSync::FunctionConfiguration", "GraphQlFn");
        r.setPhysicalId("arn:fn");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("FunctionId", "fn123");

        provisioner.delete(r, "eu-west-1");

        verify(appSync).deleteFunction(API_ID, "fn123");
    }

    @Test
    void deleteSchemaTouchesNothingBecauseItGoesWithItsApi() {
        StackResource r = resource("AWS::AppSync::GraphQLSchema", "GraphQlSchema");
        r.setPhysicalId(API_ID + "GraphQLSchema");
        r.getAttributes().put("ApiId", API_ID);

        provisioner.delete(r, "eu-west-1");

        verifyNoInteractions(appSync);
    }

    @Test
    void deleteToleratesAnAlreadyGoneResolver() {
        doThrow(new AwsException("NotFoundException", "Resolver not found", 404))
                .when(appSync).deleteResolver(API_ID, "Query", "getMessages");
        StackResource r = resource("AWS::AppSync::Resolver", "GraphQlResolver");
        r.setPhysicalId("arn:resolver");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("TypeName", "Query");
        r.getAttributes().put("FieldName", "getMessages");

        // A DeleteStack retry must not fail on a resolver the first attempt already removed.
        provisioner.delete(r, "eu-west-1");
    }

    @Test
    void deletePropagatesAFailureThatIsNotAlreadyGone() {
        doThrow(new AwsException("ConcurrentModificationException", "Schema is being modified", 409))
                .when(appSync).deleteDataSource(API_ID, "accountDB");
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDs");
        r.setPhysicalId("arn:ds");
        r.getAttributes().put("ApiId", API_ID);
        r.getAttributes().put("Name", "accountDB");

        // Anything but "already gone" has to reach the stack as DELETE_FAILED.
        assertThrows(AwsException.class, () -> provisioner.delete(r, "eu-west-1"));
    }

    @Test
    void deleteWithoutRecordedIdentityDoesNotThrow() {
        StackResource r = resource("AWS::AppSync::DataSource", "GraphQlDs");
        r.setPhysicalId("arn:ds");

        provisioner.delete(r, "eu-west-1");

        verifyNoInteractions(appSync);
    }

    @Test
    void resourceTypesCoverTheSixAppSyncTypes() {
        assertEquals(Set.of("AWS::AppSync::GraphQLApi", "AWS::AppSync::GraphQLSchema",
                        "AWS::AppSync::DataSource", "AWS::AppSync::FunctionConfiguration",
                        "AWS::AppSync::Resolver", "AWS::AppSync::ApiKey"),
                provisioner.resourceTypes());
    }
}

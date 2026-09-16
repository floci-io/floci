package io.github.hectorvent.floci.services.appsync.graphql.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlEngine;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.AppSyncDataSourceRoleAuthorizer;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.DynamoDbDataSourceInvoker;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.NoneDataSourceInvoker;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.ResolverKind;
import io.github.hectorvent.floci.services.appsync.model.ResolverRuntimeName;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.model.ConditionalCheckFailedException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;

class UnitResolverRuntimeTest {

    private static final String REQ_NONE = "{\"version\":\"2018-05-29\",\"payload\":{\"hello\":\"$ctx.args.name\"}}";
    private static final String RESP_PASS = "$util.toJson($ctx.result)";

    private final ObjectMapper mapper = new ObjectMapper();
    private final DynamoDbService dynamoDb = mock(DynamoDbService.class);
    private final UnitResolverRuntime runtime = new UnitResolverRuntime(new AppSyncVtlEngine(vtlConfig()), mapper,
            new NoneDataSourceInvoker(), new DynamoDbDataSourceInvoker(
                    dynamoDb, mapper, mock(AppSyncDataSourceRoleAuthorizer.class)));

    private final GraphQlRequestContext rc = new GraphQlRequestContext("api", "000000000000", "us-east-1",
            "API Key Authorization", null, Map.of("x-api-key", "da2-x"), Map.of("v", 1));

    /** The sandbox limits the engine reads at construction; generous values keep them out of the way here. */
    private static EmulatorConfig vtlConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().appsync().vtlMaxLoops()).thenReturn(10_000);
        when(config.services().appsync().vtlMaxOutputChars()).thenReturn(1_000_000);
        when(config.services().appsync().vtlTimeoutMillis()).thenReturn(5_000L);
        return config;
    }

    private static DataSource none() {
        DataSource ds = new DataSource();
        ds.setName("none");
        ds.setType(DataSourceType.NONE);
        return ds;
    }

    private static DataSource dynamo() {
        DataSource ds = new DataSource();
        ds.setName("todos");
        ds.setType(DataSourceType.AMAZON_DYNAMODB);
        ds.setDynamodbConfig(Map.of("tableName", "Todos"));
        return ds;
    }

    private static Resolver resolver(String request, String response) {
        Resolver r = new Resolver();
        r.setTypeName("Query");
        r.setFieldName("f");
        r.setDataSourceName("ds");
        r.setKind(ResolverKind.UNIT);
        r.setRequestMappingTemplate(request);
        r.setResponseMappingTemplate(response);
        return r;
    }

    private FieldOutcome run(DataSource ds, Resolver resolver, Map<String, Object> args) {
        return runtime.execute(new ResolverInvocation(rc, resolver, ds, args, Map.of(), Map.of("fieldName", "f")));
    }

    @Test
    void noneRoundTripUsesArgsAliasAndInfo() {
        FieldOutcome out = run(none(), resolver(REQ_NONE, RESP_PASS), Map.of("name", "bob"));
        assertFalse(out.isError());
        assertEquals(Map.of("hello", "bob"), out.data());
        assertTrue(out.appended().isEmpty());
    }

    @Test
    void templateVersionMustBe20180529() {
        FieldOutcome old = run(none(), resolver("{\"version\":\"2017-02-28\",\"payload\":{}}", RESP_PASS), Map.of());
        assertEquals("MappingTemplate", old.error().errorType());
        assertTrue(old.error().message().contains("2017-02-28"));

        FieldOutcome missing = run(none(), resolver("{\"payload\":{}}", RESP_PASS), Map.of());
        assertEquals("MappingTemplate", missing.error().errorType());
        assertTrue(missing.error().message().contains("required"));
    }

    @Test
    void bothTemplatesAreRequired() {
        assertEquals("MappingTemplate", run(none(), resolver(null, RESP_PASS), Map.of()).error().errorType());
        assertEquals("MappingTemplate", run(none(), resolver(REQ_NONE, "  "), Map.of()).error().errorType());
    }

    @Test
    void invalidJsonDuplicateKeysAndTrailingContentAreMappingTemplateErrors() {
        for (String bad : List.of("{\"version\":\"2018-05-29\",", "{\"version\":\"2018-05-29\",\"version\":\"x\"}",
                "{\"version\":\"2018-05-29\"} trailing", "[1,2]", "\"just a string\"")) {
            FieldOutcome out = run(none(), resolver(bad, RESP_PASS), Map.of());
            assertTrue(out.isError(), bad);
            assertEquals("MappingTemplate", out.error().errorType(), bad);
        }
        FieldOutcome resp = run(none(), resolver(REQ_NONE, "{\"a\":1,\"a\":2}"), Map.of());
        assertEquals("MappingTemplate", resp.error().errorType());
    }

    @Test
    void returnDirectiveShortCircuitsWithAnyValueType() {
        assertEquals("early", run(none(), resolver("#return(\"early\")", RESP_PASS), Map.of()).data());
        assertEquals(Map.of("id", "123"), run(none(), resolver("#return({\"id\":\"123\"})", RESP_PASS), Map.of()).data());
        FieldOutcome nul = run(none(), resolver("#return", RESP_PASS), Map.of());
        assertFalse(nul.isError());
        assertNull(nul.data());
        assertEquals("stop", run(none(), resolver(
                "#foreach($i in [1,2,3])#if($i==2)#return(\"stop\")#end#end{\"version\":\"2018-05-29\"}", RESP_PASS),
                Map.of()).data());
        // response-side #return returns the raw value without JSON parsing
        assertEquals("raw", run(none(), resolver(REQ_NONE, "#return(\"raw\")"), Map.of()).data());
    }

    @Test
    void stashIsSharedBetweenTemplatesAndAppendedErrorsAreOrdered() {
        String request = "$util.qr($ctx.stash.put(\"k\", \"v\"))$util.appendError(\"first\", \"A\")" + REQ_NONE;
        String response = "$util.appendError(\"second\", \"B\")$util.toJson($ctx.stash.k)";
        FieldOutcome out = run(none(), resolver(request, response), Map.of());
        assertFalse(out.isError());
        assertEquals("v", out.data());
        assertEquals(List.of("A", "B"), out.appended().stream().map(FieldError::errorType).toList());
        assertEquals(List.of("first", "second"), out.appended().stream().map(FieldError::message).toList());
    }

    @Test
    void utilErrorIsTerminalAndCarriesDataAndErrorInfo() {
        FieldOutcome out = run(none(), resolver(REQ_NONE,
                "$util.error(\"nope\", \"Custom\", {\"id\": \"1\"}, {\"code\": 7})"), Map.of());
        assertTrue(out.isError());
        assertEquals("Custom", out.error().errorType());
        assertEquals("nope", out.error().message());
        assertEquals(Map.of("id", "1"), out.error().data());
        assertEquals(Map.of("code", 7), out.error().errorInfo());
        assertNull(out.data());
        assertEquals("Unknown", run(none(), resolver("$util.error(\"x\")", RESP_PASS), Map.of()).error().errorType());
        assertEquals("Unauthorized", run(none(), resolver("$util.unauthorized()", RESP_PASS), Map.of()).error().errorType());
    }

    @Test
    void dataSourceErrorIsHandledByTheResponseTemplate() throws Exception {
        JsonNode existing = mapper.readTree("{\"id\":{\"S\":\"1\"},\"title\":{\"S\":\"old\"}}");
        doThrow(new ConditionalCheckFailedException(existing))
                .when(dynamoDb).putItem(any(), any(), any(), any(), any(), any(), any());
        String request = "{\"version\":\"2018-05-29\",\"operation\":\"PutItem\",\"key\":{\"id\":{\"S\":\"1\"}}," +
                "\"condition\":{\"expression\":\"attribute_not_exists(id)\"}}";

        // ignored: template output is the data, no error surfaces
        FieldOutcome ignored = run(dynamo(), resolver(request, "$util.toJson($ctx.result)"), Map.of());
        assertFalse(ignored.isError());
        assertTrue(ignored.appended().isEmpty());
        assertEquals(Map.of("id", "1", "title", "old"), ignored.data());

        // re-raised with $util.error
        FieldOutcome raised = run(dynamo(), resolver(request,
                "#if($ctx.error)$util.error($ctx.error.message, $ctx.error.type)#end$util.toJson($ctx.result)"), Map.of());
        assertTrue(raised.isError());
        assertEquals("DynamoDB:ConditionalCheckFailedException", raised.error().errorType());
        assertEquals("The conditional request failed", raised.error().message());

        // appended while returning data
        FieldOutcome appended = run(dynamo(), resolver(request,
                "#if($ctx.error)$util.appendError($ctx.error.message, $ctx.error.type)#end{\"id\":\"fallback\"}"), Map.of());
        assertFalse(appended.isError());
        assertEquals(Map.of("id", "fallback"), appended.data());
        assertEquals("DynamoDB:ConditionalCheckFailedException", appended.appended().get(0).errorType());
    }

    @Test
    void unimplementedDataSourceOperationIsTerminalEvenIfTheTemplateIgnoresErrors() {
        String request = "{\"version\":\"2018-05-29\",\"operation\":\"UpdateItem\",\"key\":{\"id\":{\"S\":\"1\"}}}";
        FieldOutcome out = run(dynamo(), resolver(request, "$util.toJson($ctx.result)"), Map.of());
        assertTrue(out.isError());
        assertEquals("UnsupportedOperation", out.error().errorType());
        assertTrue(out.error().message().contains("UpdateItem"));
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void blankResponseIsNullData() {
        FieldOutcome out = run(none(), resolver(REQ_NONE, "#if(false)x#end"), Map.of());
        assertFalse(out.isError());
        assertNull(out.data());
    }

    @Test
    void invalidDataSourceDocumentsAreMappingTemplateErrors() {
        FieldOutcome out = run(dynamo(), resolver("{\"version\":\"2018-05-29\",\"operation\":\"GetItem\"}", RESP_PASS), Map.of());
        assertEquals("MappingTemplate", out.error().errorType());
        assertTrue(out.error().message().contains("key"));
        FieldOutcome noneOp = run(none(), resolver("{\"version\":\"2018-05-29\",\"operation\":\"Invoke\"}", RESP_PASS), Map.of());
        assertEquals("MappingTemplate", noneOp.error().errorType());
        verifyNoInteractions(dynamoDb);
    }

    @Test
    void guardsForUnsupportedResolverShapes() {
        Resolver pipeline = resolver(REQ_NONE, RESP_PASS);
        pipeline.setKind(ResolverKind.PIPELINE);
        assertEquals("UnsupportedOperation", run(none(), pipeline, Map.of()).error().errorType());

        Resolver js = resolver(null, null);
        Resolver.ResolverRuntime rt = new Resolver.ResolverRuntime();
        rt.setName(ResolverRuntimeName.APPSYNC_JS);
        js.setRuntime(rt);
        js.setCode("export function request() {}");
        assertEquals("UnsupportedOperation", run(none(), js, Map.of()).error().errorType());

        assertEquals("InternalFailure", run(null, resolver(REQ_NONE, RESP_PASS), Map.of()).error().errorType());

        DataSource lambda = new DataSource();
        lambda.setName("fn");
        lambda.setType(DataSourceType.AWS_LAMBDA);
        FieldOutcome out = run(lambda, resolver(REQ_NONE, RESP_PASS), Map.of());
        assertEquals("UnsupportedOperation", out.error().errorType());
        assertTrue(out.error().message().contains("AWS_LAMBDA"));
    }

    @Test
    void velocityFailuresAreMappingTemplateErrors() {
        FieldOutcome out = run(none(), resolver("#foreach($x in $ctx.args.list)#end#if(", RESP_PASS), Map.of());
        assertEquals("MappingTemplate", out.error().errorType());
    }
}

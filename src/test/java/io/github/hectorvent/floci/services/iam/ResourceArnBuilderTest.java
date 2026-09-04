package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceArnBuilderTest {

    private ResourceArnBuilder builder;
    private ContainerRequestContext ctx;
    private UriInfo uriInfo;
    private MultivaluedMap<String, String> queryParams;
    private Map<String, Object> contextProperties;

    @BeforeEach
    void setUp() {
        builder = new ResourceArnBuilder(new ObjectMapper());
        ctx = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        queryParams = new MultivaluedHashMap<>();
        contextProperties = new HashMap<>();

        when(ctx.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(uriInfo.getQueryParameters()).thenReturn(queryParams);

        when(ctx.getProperty(any())).thenAnswer(inv -> contextProperties.get(inv.getArgument(0)));
        doAnswer(inv -> {
            contextProperties.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setProperty(any(), any());
    }

    private void setJsonBody(String json) {
        contextProperties.clear();
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    private void setFormBody(String form) {
        contextProperties.clear();
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        when(ctx.getMediaType()).thenReturn(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        when(ctx.getEntityStream()).thenReturn(in);
        doAnswer(inv -> {
            InputStream newIn = inv.getArgument(0);
            when(ctx.getEntityStream()).thenReturn(newIn);
            return null;
        }).when(ctx).setEntityStream(any(InputStream.class));
    }

    private void setEcsTarget(String operation) {
        when(ctx.getHeaderString("X-Amz-Target"))
                .thenReturn("AmazonEC2ContainerServiceV20141113." + operation);
    }

    // ── STS ─────────────────────────────────────────────────────────────────────

    @Test
    void stsBuildsExactRoleArnFromFormBodyAndPreservesStream() throws IOException {
        String form = "Action=AssumeRole&RoleArn=arn%3Aaws%3Aiam%3A%3A222222222222%3Arole%2Fapp%2Ftarget"
                + "&RoleSessionName=test";
        setFormBody(form);

        String arn = builder.build("sts", ctx, "us-east-1", "111111111111");

        assertEquals("arn:aws:iam::222222222222:role/app/target", arn);
        assertEquals(form, new String(ctx.getEntityStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void stsReturnsWildcardForMissingOrInvalidRoleArn() {
        setFormBody("Action=GetCallerIdentity");
        assertEquals("*", builder.build("sts", ctx, "us-east-1", "111111111111"));

        setFormBody("Action=AssumeRole&RoleArn=arn%3Aaws%3As3%3A%3A%3Anot-a-role");
        assertEquals("*", builder.build("sts", ctx, "us-east-1", "111111111111"));
    }

    // ── DynamoDB ─────────────────────────────────────────────────────────────────

    @Test
    void dynamoDbBuildsArnFromShortTableName() {
        setJsonBody("{\"TableName\":\"FgacTable\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);
    }

    @Test
    void dynamoDbReturnsExactArnIfTableNameIsAlreadyArn() {
        String fullArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"TableName\":\"" + fullArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromResourceArn() {
        String tagArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        setJsonBody("{\"ResourceArn\":\"" + tagArn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"dev\"}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(tagArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromTableArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/ExportTable";
        setJsonBody("{\"TableArn\":\"" + exportArn + "\",\"S3Bucket\":\"my-bucket\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromStreamArn() {
        String streamArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/stream/2026-09-01T00:00:00.000";
        setJsonBody("{\"StreamArn\":\"" + streamArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(streamArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromExportArn() {
        String exportArn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable/export/01693526400000-abcd";
        setJsonBody("{\"ExportArn\":\"" + exportArn + "\"}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(exportArn, arn);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"MyTable\":{\"Keys\":[]}}}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableBatchRequest() {
        setJsonBody("{\"RequestItems\":{\"TableA\":{\"Keys\":[]},\"TableB\":{\"Keys\":[]}}}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromSingleTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"MyTable\"}},{\"Delete\":{\"TableName\":\"MyTable\"}}]}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/MyTable", arn);
    }

    @Test
    void dynamoDbBuildsArnsFromMultiTableTransactRequest() {
        setJsonBody("{\"TransactItems\":[{\"Put\":{\"TableName\":\"TableA\"}},{\"Delete\":{\"TableName\":\"TableB\"}},{\"Get\":{\"TableName\":\"TableA\"}}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnFromPartiQLStatement() {
        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"UsersTable\\\" WHERE id = '1'\"}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/UsersTable"), arns);

        setJsonBody("{\"Statement\":\"INSERT INTO OrdersTable VALUE {'id': '1'}\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/OrdersTable"), arns);

        setJsonBody("{\"Statement\":\"UPDATE \\\"ProductsTable\\\" SET price=10 WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/ProductsTable"), arns);

        setJsonBody("{\"Statement\":\"DELETE FROM \\\"LogsTable\\\" WHERE id='1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/LogsTable"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM \\\"Table With Spaces & Special:Chars\\\" WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table With Spaces & Special:Chars"), arns);

        setJsonBody("{\"Statement\":\"SELECT * FROM 'Table.With.Single.Quotes#1' WHERE id = '1'\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/Table.With.Single.Quotes#1"), arns);

        setJsonBody("{\"Statement\":\"SELECT \\\"FROM TableEvil\\\" FROM \\\"TableA\\\" WHERE pk = 1\"}");
        arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of("arn:aws:dynamodb:us-east-1:000000000000:table/TableA"), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLBatchStatements() {
        setJsonBody("{\"Statements\":[{\"Statement\":\"SELECT * FROM \\\"TableA\\\" WHERE id = '1'\"},{\"Statement\":\"SELECT * FROM TableB WHERE id = '2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbBuildsArnsFromPartiQLTransactStatements() {
        setJsonBody("{\"TransactStatements\":[{\"Statement\":\"INSERT INTO \\\"TableA\\\" VALUE {'id': '1'}\"},{\"Statement\":\"UPDATE \\\"TableB\\\" SET x=1 WHERE id='2'\"}]}");
        List<String> arns = builder.buildResources("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals(List.of(
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableA",
                "arn:aws:dynamodb:us-east-1:000000000000:table/TableB"
        ), arns);
    }

    @Test
    void dynamoDbReturnsWildcardWhenNoTableSpecified() {
        setJsonBody("{}");
        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("*", arn);
    }

    @Test
    void entityStreamRemainsReadableAfterResourceArnBuilding() throws IOException {
        String json = "{\"TableName\":\"FgacTable\",\"Item\":{\"PK\":{\"S\":\"USER_1\"}}}";
        setJsonBody(json);

        String arn = builder.build("dynamodb", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable", arn);

        InputStream entityStream = ctx.getEntityStream();
        assertNotNull(entityStream);
        byte[] readBack = entityStream.readAllBytes();
        assertEquals(json, new String(readBack, StandardCharsets.UTF_8));
    }

    // ── Kinesis ──────────────────────────────────────────────────────────────────

    @Test
    void kinesisBuildsArnFromStreamName() {
        setJsonBody("{\"StreamName\":\"order-events\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:kinesis:us-east-1:000000000000:stream/order-events", arn);
    }

    @Test
    void kinesisBuildsArnFromStreamArn() {
        String fullArn = "arn:aws:kinesis:us-east-1:000000000000:stream/order-events";
        setJsonBody("{\"StreamARN\":\"" + fullArn + "\"}");
        String arn = builder.build("kinesis", ctx, "us-east-1", "000000000000");
        assertEquals(fullArn, arn);
    }

    // ── Secrets Manager ──────────────────────────────────────────────────────────

    @Test
    void secretsManagerBuildsArnFromSecretId() {
        setJsonBody("{\"SecretId\":\"app/prod/database\"}");
        String arn = builder.build("secretsmanager", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:secretsmanager:us-east-1:000000000000:secret:app/prod/database", arn);
    }

    // ── SSM ──────────────────────────────────────────────────────────────────────

    @Test
    void ssmBuildsArnFromNameWithLeadingSlash() {
        setJsonBody("{\"Name\":\"/config/env\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config/env", arn);
    }

    @Test
    void ssmBuildsArnFromNameWithoutLeadingSlash() {
        setJsonBody("{\"Name\":\"config-key\"}");
        String arn = builder.build("ssm", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:ssm:us-east-1:000000000000:parameter/config-key", arn);
    }

    // ── SQS ─────────────────────────────────────────────────────────────────────

    @Test
    void sqsBuildsArnFromJsonQueueName() {
        setJsonBody("{\"QueueName\":\"order-queue\"}");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:order-queue", arn);
    }

    @Test
    void sqsBuildsArnFromQueryParam() {
        queryParams.putSingle("QueueUrl", "http://localhost:4566/000000000000/my-queue");
        String arn = builder.build("sqs", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:sqs:us-east-1:000000000000:my-queue", arn);
    }

    // ── SNS ─────────────────────────────────────────────────────────────────────

    @Test
    void snsBuildsArnFromJsonTopicArn() {
        String topicArn = "arn:aws:sns:us-east-1:000000000000:alerts";
        setJsonBody("{\"TopicArn\":\"" + topicArn + "\"}");
        String arn = builder.build("sns", ctx, "us-east-1", "000000000000");
        assertEquals(topicArn, arn);
    }

    // ── S3 ──────────────────────────────────────────────────────────────────────

    @Test
    void s3BuildsBucketArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket", arn);
    }

    @Test
    void s3BuildsObjectArn() {
        when(uriInfo.getPath()).thenReturn("/my-bucket/folder/file.json");
        String arn = builder.build("s3", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:s3:::my-bucket/folder/file.json", arn);
    }

    // ── Lambda ──────────────────────────────────────────────────────────────────

    @Test
    void lambdaBuildsFunctionArn() {
        when(uriInfo.getPath()).thenReturn("/2015-03-31/functions/my-function/invocations");
        String arn = builder.build("lambda", ctx, "us-east-1", "000000000000");
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-function", arn);
    }

    // ── ECS ─────────────────────────────────────────────────────────────────────

    @Test
    void ecsDescribeServicesBuildsEveryShortNameInDefaultClusterAndPreservesBody() throws IOException {
        String body = "{\"services\":[\"api\",\"worker\"]}";
        setEcsTarget("DescribeServices");
        setJsonBody(body);

        List<String> resources = builder.buildResources("ecs", ctx, "us-east-1", "000000000000");

        assertEquals(List.of(
                "arn:aws:ecs:us-east-1:000000000000:service/default/api",
                "arn:aws:ecs:us-east-1:000000000000:service/default/worker"), resources);
        assertEquals(body, new String(ctx.getEntityStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void ecsDescribeServicesUsesClusterArnAndPreservesExplicitForeignServiceArn() {
        setEcsTarget("DescribeServices");
        String clusterArn = "arn:aws:ecs:eu-west-1:222222222222:cluster/production";
        setJsonBody("{\"cluster\":\"" + clusterArn + "\",\"services\":[\"billing\"]}");

        assertEquals(List.of("arn:aws:ecs:eu-west-1:222222222222:service/production/billing"),
                builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));

        String foreignArn = "arn:aws:ecs:eu-west-1:333333333333:service/production/foreign";
        setJsonBody("{\"cluster\":\"production\",\"services\":[\"" + foreignArn + "\"]}");
        assertEquals(List.of(foreignArn),
                builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void ecsFullServiceArnInDefaultClusterRemainsExact() {
        setEcsTarget("DescribeServices");
        String foreignDefaultArn = "arn:aws:ecs:eu-west-1:333333333333:service/default/foreign";
        setJsonBody("{\"services\":[\"" + foreignDefaultArn + "\"]}");

        assertEquals(List.of(foreignDefaultArn),
                builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void ecsUpdateServiceBuildsShortAndFullServiceReferences() {
        setEcsTarget("UpdateService");
        setJsonBody("{\"cluster\":\"blue\",\"service\":\"api\",\"desiredCount\":2}");
        assertEquals(List.of("arn:aws:ecs:us-east-1:000000000000:service/blue/api"),
                builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));

        String serviceArn = "arn:aws:ecs:eu-west-1:222222222222:service/production/api";
        String clusterArn = "arn:aws:ecs:eu-west-1:222222222222:cluster/production";
        setJsonBody("{\"cluster\":\"" + clusterArn + "\",\"service\":\"" + serviceArn + "\"}");
        assertEquals(List.of(serviceArn),
                builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));
    }

    @Test
    void ecsMalformedMixedAndMismatchedTargetsAreRejected() {
        setEcsTarget("DescribeServices");
        setJsonBody("{\"cluster\":\"blue\",\"services\":[\"api\",7]}");
        assertEcsInvalidRequest();

        setJsonBody("{\"cluster\":\"blue\",\"services\":[]}");
        assertEcsInvalidRequest();

        setJsonBody("{\"cluster\":\" blue\",\"services\":[\"api\"]}");
        assertEcsInvalidRequest();

        setJsonBody("{\"cluster\":\"blue\",\"services\":[\" api\"]}");
        assertEcsInvalidRequest();

        setJsonBody("{\"cluster\":\"blue\",\"services\":[\"arn:aws:ecs:us-east-1:000000000000:service/green/api\"]}");
        assertEcsInvalidRequest();

        setJsonBody("{\"cluster\":\"arn:aws:ecs:eu-west-1:222222222222:cluster/blue\",\"service\":\"arn:aws:ecs:eu-west-1:333333333333:service/blue/api\"}");
        setEcsTarget("UpdateService");
        assertEcsInvalidRequest();

        setEcsTarget("DescribeServices");
        setJsonBody("{\"services\":[\"arn:aws:ecs:us-east-1:000000000000:service/blue/api\"]}");
        assertEcsInvalidRequest();
    }

    private void assertEcsInvalidRequest() {
        AwsException error = assertThrows(AwsException.class,
                () -> builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void ecsUnsupportedOperationFallsBackToWildcardAndPreservesBody() throws IOException {
        String body = "{\"cluster\":\"blue\"}";
        setEcsTarget("ListClusters");
        setJsonBody(body);

        assertEquals(List.of("*"), builder.buildResources("ecs", ctx, "us-east-1", "000000000000"));
        assertEquals(body, new String(ctx.getEntityStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}

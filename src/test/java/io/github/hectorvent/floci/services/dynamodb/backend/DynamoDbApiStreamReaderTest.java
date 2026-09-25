package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Cursor;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Position;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.RecordsPage;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Shard;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.Stream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DynamoDB Streams reader over the raw seam, driven by a scripted engine. */
class DynamoDbApiStreamReaderTest {

    private static final String STREAM_ARN =
            "arn:aws:dynamodb:eu-west-1:333333333333:table/orders/stream/2026-09-25T00:00:00.000";
    private static final Stream STREAM = Stream.of(STREAM_ARN);
    private static final String ERROR_PREFIX = "com.amazonaws.dynamodb.v20120810#";

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Call> calls = new ArrayList<>();

    private interface Script {
        Reply answer(Call call) throws Exception;
    }

    private DynamoDbStreamReader reader(Script script) {
        return new DynamoDbApiStreamReader(call -> {
            calls.add(call);
            return script.answer(call);
        }, CheckpointLifetime.PROCESS);
    }

    private Reply ok(String json) throws IOException {
        return new Reply(200, mapper.readTree(json), Map.of());
    }

    private JsonNode json(String text) throws IOException {
        return mapper.readTree(text);
    }

    private static String recordJson(String sequenceNumber) {
        return "{\"eventName\":\"INSERT\",\"dynamodb\":{\"SequenceNumber\":\"" + sequenceNumber + "\"}}";
    }

    private static Shard shard(String shardId, String parentShardId) {
        return new Shard(shardId, parentShardId, null, null);
    }

    private Reply trimmed() throws IOException {
        return new Reply(400, json("{\"__type\":\"" + ERROR_PREFIX
                + "TrimmedDataAccessException\",\"message\":\"Trimmed\"}"), Map.of());
    }

    private long trimHorizonIteratorCalls() {
        return calls.stream()
                .filter(call -> "GetShardIterator".equals(call.action()))
                .filter(call -> "TRIM_HORIZON".equals(call.body().path("ShardIteratorType").asText()))
                .count();
    }

    @Test
    void streamOfScopesToTheStreamOwner() {
        assertEquals(new Scope("333333333333", "eu-west-1"), STREAM.scope());
        assertEquals(STREAM_ARN, STREAM.arn());
    }

    @Test
    void shardsFollowsLastEvaluatedShardIdAcrossPages() throws Exception {
        DynamoDbStreamReader reader = reader(call -> call.body().has("ExclusiveStartShardId")
                ? ok("""
                        {"StreamDescription":{"Shards":[{"ShardId":"child","ParentShardId":"parent",
                          "SequenceNumberRange":{"StartingSequenceNumber":"5","EndingSequenceNumber":"9"}}],
                          "LastEvaluatedShardId":null}}""")
                : ok("""
                        {"StreamDescription":{"Shards":[{"ShardId":"parent",
                          "SequenceNumberRange":{"StartingSequenceNumber":"1"}}],
                          "LastEvaluatedShardId":"parent"}}"""));

        List<Shard> shards = reader.shards(STREAM);

        assertEquals(List.of(new Shard("parent", null, "1", null), new Shard("child", "parent", "5", "9")), shards);
        assertEquals(json("{\"StreamArn\":\"" + STREAM_ARN + "\"}"), calls.get(0).body());
        assertEquals(json("{\"StreamArn\":\"" + STREAM_ARN + "\",\"ExclusiveStartShardId\":\"parent\"}"),
                calls.get(1).body());
        assertEquals(2, calls.size());
    }

    @Test
    void describeStreamSendsTheLimitAndReadsAMissingLastEvaluatedShardIdAsNull() throws Exception {
        DynamoDbStreamReader reader = reader(call -> ok("{\"StreamDescription\":{\"Shards\":[]}}"));

        DynamoDbStreamReader.ShardsPage page = reader.describeStream(STREAM, "s0", 2);

        assertEquals(json("{\"StreamArn\":\"" + STREAM_ARN + "\",\"ExclusiveStartShardId\":\"s0\",\"Limit\":2}"),
                calls.get(0).body());
        assertSame(STREAM, page.stream());
        assertTrue(page.shards().isEmpty());
        assertNull(page.lastEvaluatedShardId());
    }

    @Test
    void readableHoldsAChildUntilItsParentIsFinished() {
        List<Shard> shards = List.of(shard("parent", null), shard("child", "parent"),
                shard("orphan", "trimmed-away"), shard("grandchild", "child"));

        assertEquals(List.of(shard("parent", null), shard("orphan", "trimmed-away")),
                DynamoDbStreamReader.readable(shards, Set.of()));
        assertEquals(List.of(shard("child", "parent"), shard("orphan", "trimmed-away")),
                DynamoDbStreamReader.readable(shards, Set.of("parent")));
        assertEquals(List.of(shard("orphan", "trimmed-away"), shard("grandchild", "child")),
                DynamoDbStreamReader.readable(shards, Set.of("parent", "child")));
    }

    @Test
    void anOpenEmptyPageKeepsItsCursorWhileAClosedShardEndsWithNone() throws Exception {
        DynamoDbStreamReader reader = reader(call -> switch (call.body().path("ShardIterator").asText()) {
            case "open" -> ok("{\"Records\":[],\"NextShardIterator\":\"open-2\"}");
            case "closed" -> ok("{\"Records\":[],\"NextShardIterator\":null}");
            default -> ok("{\"Records\":[]}");
        });

        RecordsPage open = reader.getRecords(new Cursor(STREAM, "s1", "open"), 10);
        RecordsPage closed = reader.getRecords(new Cursor(STREAM, "s1", "closed"), 10);
        RecordsPage missing = reader.getRecords(new Cursor(STREAM, "s1", "missing"), 10);

        assertTrue(open.records().isEmpty());
        assertEquals(new Cursor(STREAM, "s1", "open-2"), open.nextCursor());
        assertTrue(closed.records().isEmpty());
        assertNull(closed.nextCursor());
        assertNull(missing.nextCursor());
    }

    @Test
    void errorRepliesBecomeAwsExceptionsWithTheShortCode() {
        DynamoDbStreamReader reader = reader(call -> switch (call.body().path("ShardIterator").asText()) {
            case "expired" -> new Reply(400, json("{\"__type\":\"" + ERROR_PREFIX
                    + "ExpiredIteratorException\",\"message\":\"Iterator expired\"}"), Map.of());
            default -> new Reply(400, json("{\"__type\":\"" + ERROR_PREFIX
                    + "TrimmedDataAccessException\",\"Message\":\"Trimmed\"}"), Map.of());
        });

        AwsException expired = assertThrows(AwsException.class,
                () -> reader.getRecords(new Cursor(STREAM, "s1", "expired"), 10));
        AwsException trimmed = assertThrows(AwsException.class,
                () -> reader.getRecords(new Cursor(STREAM, "s1", "trimmed"), 10));

        assertEquals("ExpiredIteratorException", expired.getErrorCode());
        assertEquals("Iterator expired", expired.getMessage());
        assertEquals(400, expired.getHttpStatus());
        assertEquals("TrimmedDataAccessException", trimmed.getErrorCode());
        assertEquals("Trimmed", trimmed.getMessage());
        assertEquals(400, trimmed.getHttpStatus());
    }

    @Test
    void anErrorReplyWithoutATypeIsAnInternalServerError() {
        DynamoDbStreamReader reader = reader(call -> new Reply(500, null, Map.of()));

        AwsException error = assertThrows(AwsException.class, () -> reader.describeStream(STREAM, null, null));

        assertEquals("InternalServerError", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
    }

    @Test
    void aThrownAwsExceptionPropagatesUnchanged() {
        AwsException thrown = new AwsException("ResourceNotFoundException", "Stream not found", 400);
        DynamoDbStreamReader reader = reader(call -> {
            throw thrown;
        });

        assertSame(thrown, assertThrows(AwsException.class, () -> reader.describeStream(STREAM, null, null)));
    }

    @Test
    void aCheckedExceptionBecomesAnIllegalStateException() {
        IOException thrown = new IOException("boom");
        DynamoDbStreamReader reader = reader(call -> {
            throw thrown;
        });

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> reader.describeStream(STREAM, null, null));
        assertSame(thrown, error.getCause());
    }

    @Test
    void everyCallRunsUnderTheStreamScopeOnTheStreamsApi() throws Exception {
        DynamoDbStreamReader reader = reader(call -> switch (call.action()) {
            case "DescribeStream" -> ok("{\"StreamDescription\":{\"Shards\":[]}}");
            case "GetShardIterator" -> ok("{\"ShardIterator\":\"it\"}");
            default -> ok("{\"Records\":[],\"NextShardIterator\":\"it\"}");
        });

        reader.describeStream(STREAM, null, null);
        Cursor cursor = reader.getShardIterator(STREAM, "s1", Position.LATEST, null);
        reader.getRecords(cursor, 10);

        assertEquals(List.of("DescribeStream", "GetShardIterator", "GetRecords"),
                calls.stream().map(Call::action).toList());
        for (Call call : calls) {
            assertEquals(new Scope("333333333333", "eu-west-1"), call.scope());
            assertEquals(Api.DYNAMODB_STREAMS, call.api());
        }
    }

    @Test
    void getShardIteratorSendsTheSequenceNumberOnlyWhenGiven() throws Exception {
        DynamoDbStreamReader reader = reader(call -> ok("{\"ShardIterator\":\"it-" + calls.size() + "\"}"));

        Cursor fromStart = reader.getShardIterator(STREAM, "s1", Position.TRIM_HORIZON, null);
        Cursor afterFive = reader.getShardIterator(STREAM, "s1", Position.AFTER_SEQUENCE_NUMBER, "5");

        assertEquals(json("{\"StreamArn\":\"" + STREAM_ARN
                + "\",\"ShardId\":\"s1\",\"ShardIteratorType\":\"TRIM_HORIZON\"}"), calls.get(0).body());
        assertEquals(json("{\"StreamArn\":\"" + STREAM_ARN
                + "\",\"ShardId\":\"s1\",\"ShardIteratorType\":\"AFTER_SEQUENCE_NUMBER\",\"SequenceNumber\":\"5\"}"),
                calls.get(1).body());
        assertEquals(new Cursor(STREAM, "s1", "it-1"), fromStart);
        assertEquals(new Cursor(STREAM, "s1", "it-2"), afterFive);
    }

    @Test
    void repeatReadsOfOneCursorSendIdenticalRequestsAndLeaveTheRecordUntouched() throws Exception {
        Reply reply = ok("{\"Records\":[" + recordJson("7") + "],\"NextShardIterator\":\"next\"}");
        DynamoDbStreamReader reader = reader(call -> reply);
        Cursor cursor = new Cursor(STREAM, "s1", "it");

        RecordsPage first = reader.getRecords(cursor, 10);
        RecordsPage second = reader.getRecords(cursor, 10);

        assertEquals(json("{\"ShardIterator\":\"it\",\"Limit\":10}"), calls.get(0).body());
        assertEquals(calls.get(0).body(), calls.get(1).body());
        assertEquals("7", first.records().get(0).sequenceNumber());
        assertEquals(json(recordJson("7")), first.records().get(0).awsRecord());
        assertEquals(json(recordJson("7")), second.records().get(0).awsRecord());
        assertEquals(new Cursor(STREAM, "s1", "next"), first.nextCursor());
    }

    @Test
    void newestSequenceNumbersRestartsAShardWhoseOldestRecordsWereTrimmedMeanwhile() throws Exception {
        DynamoDbStreamReader reader = reader(call -> switch (call.action()) {
            case "DescribeStream" -> ok("{\"StreamDescription\":{\"Shards\":[{\"ShardId\":\"s1\"}]}}");
            case "GetShardIterator" -> ok("{\"ShardIterator\":\"s1-" + trimHorizonIteratorCalls() + "\"}");
            default -> switch (call.body().path("ShardIterator").asText()) {
                case "s1-1" -> trimmed();
                case "s1-2" -> ok("{\"Records\":[" + recordJson("5") + "," + recordJson("6")
                        + "],\"NextShardIterator\":\"s1-3\"}");
                default -> ok("{\"Records\":[],\"NextShardIterator\":\"idle\"}");
            };
        });

        Map<String, String> newest = reader.newestSequenceNumbers(STREAM);

        assertEquals(Map.of("s1", "6"), newest);
        assertEquals(2, trimHorizonIteratorCalls());
    }

    @Test
    void newestSequenceNumbersGivesUpAfterRepeatedTrims() {
        DynamoDbStreamReader reader = reader(call -> switch (call.action()) {
            case "DescribeStream" -> ok("{\"StreamDescription\":{\"Shards\":[{\"ShardId\":\"s1\"}]}}");
            case "GetShardIterator" -> ok("{\"ShardIterator\":\"it\"}");
            default -> trimmed();
        });

        AwsException error = assertThrows(AwsException.class, () -> reader.newestSequenceNumbers(STREAM));

        assertEquals("TrimmedDataAccessException", error.getErrorCode());
        assertEquals(4, trimHorizonIteratorCalls());
    }

    @Test
    void newestSequenceNumbersScansEveryShardFromTrimHorizon() throws Exception {
        DynamoDbStreamReader reader = reader(call -> switch (call.action()) {
            case "DescribeStream" -> ok("""
                    {"StreamDescription":{"Shards":[{"ShardId":"s1"},{"ShardId":"s2","ParentShardId":"s1"},
                      {"ShardId":"s3"}]}}""");
            case "GetShardIterator" -> ok("{\"ShardIterator\":\"" + call.body().path("ShardId").asText() + "-0\"}");
            default -> switch (call.body().path("ShardIterator").asText()) {
                case "s1-0" -> ok("{\"Records\":[" + recordJson("1") + "," + recordJson("2")
                        + "],\"NextShardIterator\":\"s1-1\"}");
                case "s1-1" -> ok("{\"Records\":[" + recordJson("3") + "],\"NextShardIterator\":\"s1-2\"}");
                case "s2-0" -> ok("{\"Records\":[" + recordJson("10") + "],\"NextShardIterator\":null}");
                default -> ok("{\"Records\":[],\"NextShardIterator\":\"idle\"}");
            };
        });

        Map<String, String> newest = reader.newestSequenceNumbers(STREAM);

        assertEquals(Map.of("s1", "3", "s2", "10"), newest);
        assertFalse(calls.stream().anyMatch(call -> "idle".equals(call.body().path("ShardIterator").asText())));
        for (Call call : calls) {
            if ("GetShardIterator".equals(call.action())) {
                assertEquals("TRIM_HORIZON", call.body().path("ShardIteratorType").asText());
            } else if ("GetRecords".equals(call.action())) {
                assertEquals(1000, call.body().path("Limit").asInt());
            }
        }
    }

    @Test
    void checkpointLifetimeEchoesTheConstructorValue() {
        DynamoDbOperations unused = call -> {
            throw new AssertionError("no call expected");
        };

        assertEquals(CheckpointLifetime.PROCESS,
                new DynamoDbApiStreamReader(unused, CheckpointLifetime.PROCESS).checkpointLifetime());
        assertEquals(CheckpointLifetime.STREAM,
                new DynamoDbApiStreamReader(unused, CheckpointLifetime.STREAM).checkpointLifetime());
    }
}

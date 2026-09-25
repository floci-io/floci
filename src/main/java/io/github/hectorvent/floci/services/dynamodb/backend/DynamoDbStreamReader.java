package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How Java consumers read DynamoDB Streams, whatever engine serves them. {@link Record#awsRecord()}
 * is the AWS {@code Records[]} element exactly as returned and is never mutated, so a consumer
 * copies it before changing it. A shard is read completely when {@link #getRecords} returns no
 * records and no next cursor. An engine behind this reader must return records whenever the shard
 * holds any after the cursor, so an empty page with a next cursor means the cursor is at the tip of
 * an open shard. The native engine and DynamoDB Local behave this way; real AWS does not guarantee it.
 */
public interface DynamoDbStreamReader {

    /** The most records one GetRecords call returns, as AWS allows. */
    int MAX_RECORDS_PER_READ = 1000;

    enum Position { TRIM_HORIZON, LATEST, AT_SEQUENCE_NUMBER, AFTER_SEQUENCE_NUMBER }

    /** How long committed progress stays valid: for this process only, or as long as the stream. */
    enum CheckpointLifetime { PROCESS, STREAM }

    record Stream(DynamoDbOperations.Scope scope, String arn) {

        /** The stream under its owner's account and region, read off the ARN. */
        public static Stream of(String streamArn) {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(streamArn);
            return new Stream(new DynamoDbOperations.Scope(parsed.accountId(), parsed.region()), streamArn);
        }
    }

    record Shard(String shardId, String parentShardId, String startingSequenceNumber, String endingSequenceNumber) {}

    record ShardsPage(Stream stream, List<Shard> shards, String lastEvaluatedShardId) {}

    record Cursor(Stream stream, String shardId, String token) {}

    record Record(String sequenceNumber, JsonNode awsRecord) {}

    record RecordsPage(List<Record> records, Cursor nextCursor) {

        /** The shard was read to its end. */
        public boolean closed() {
            return records.isEmpty() && nextCursor == null;
        }
    }

    ShardsPage describeStream(Stream stream, String exclusiveStartShardId, Integer limit);

    Cursor getShardIterator(Stream stream, String shardId, Position position, String sequenceNumber);

    RecordsPage getRecords(Cursor cursor, int limit);

    CheckpointLifetime checkpointLifetime();

    /**
     * Up to {@code limit} records after {@code committedSequence}, or from the trim horizon when
     * nothing is committed, filled from as many reads as it needs. It stops early at the tip of an
     * open shard or the end of a closed one. A trim on a later read returns what was already read.
     */
    default RecordsPage readAfter(Stream stream, String shardId, String committedSequence, int limit) {
        Cursor cursor = committedSequence == null
                ? getShardIterator(stream, shardId, Position.TRIM_HORIZON, null)
                : getShardIterator(stream, shardId, Position.AFTER_SEQUENCE_NUMBER, committedSequence);
        List<Record> records = new ArrayList<>();
        RecordsPage page;
        do {
            try {
                page = getRecords(cursor, Math.min(limit - records.size(), MAX_RECORDS_PER_READ));
            } catch (AwsException e) {
                if (!"TrimmedDataAccessException".equals(e.getErrorCode()) || records.isEmpty()) {
                    throw e;
                }
                // The records already read are gone from the stream, so a later read being trimmed
                // must not drop them; the next read after them surfaces the trim to the caller.
                return new RecordsPage(List.copyOf(records), cursor);
            }
            records.addAll(page.records());
            cursor = page.nextCursor();
        } while (records.size() < limit && !page.records().isEmpty() && cursor != null);
        return new RecordsPage(List.copyOf(records), cursor);
    }

    /** Every shard of the stream, following {@code LastEvaluatedShardId} to the last page. */
    default List<Shard> shards(Stream stream) {
        List<Shard> shards = new ArrayList<>();
        String exclusiveStartShardId = null;
        do {
            ShardsPage page = describeStream(stream, exclusiveStartShardId, null);
            shards.addAll(page.shards());
            exclusiveStartShardId = page.lastEvaluatedShardId();
        } while (exclusiveStartShardId != null);
        return shards;
    }

    /**
     * The shards a consumer may read now, in input order: not finished, and whose parent is absent,
     * no longer listed because it was trimmed away, or finished.
     */
    static List<Shard> readable(List<Shard> shards, Set<String> finishedShardIds) {
        Set<String> listed = new HashSet<>();
        for (Shard shard : shards) {
            listed.add(shard.shardId());
        }
        List<Shard> readable = new ArrayList<>();
        for (Shard shard : shards) {
            if (finishedShardIds.contains(shard.shardId())) {
                continue;
            }
            String parent = shard.parentShardId();
            if (parent == null || !listed.contains(parent) || finishedShardIds.contains(parent)) {
                readable.add(shard);
            }
        }
        return readable;
    }

    /**
     * The newest retained sequence number of each shard that holds records, so a LATEST start can
     * be pinned as committed progress.
     */
    default Map<String, String> newestSequenceNumbers(Stream stream) {
        // ponytail: a tail scan instead of holding a LATEST iterator keeps consumer progress
        // sequence-only; it costs one pass over the retained records once per consumer start, and
        // relies on the engine requirement above that an empty page means the tip of the shard.
        Map<String, String> newest = new LinkedHashMap<>();
        for (Shard shard : shards(stream)) {
            Cursor cursor = getShardIterator(stream, shard.shardId(), Position.TRIM_HORIZON, null);
            int reopenings = 0;
            while (cursor != null) {
                RecordsPage page;
                try {
                    page = getRecords(cursor, MAX_RECORDS_PER_READ);
                } catch (AwsException e) {
                    if (!"TrimmedDataAccessException".equals(e.getErrorCode()) || reopenings == 3) {
                        throw e;
                    }
                    // The oldest records were trimmed between opening the iterator and reading it,
                    // so the scan restarts at the new trim horizon.
                    reopenings++;
                    cursor = getShardIterator(stream, shard.shardId(), Position.TRIM_HORIZON, null);
                    continue;
                }
                if (page.records().isEmpty()) {
                    break;
                }
                newest.put(shard.shardId(), page.records().get(page.records().size() - 1).sequenceNumber());
                cursor = page.nextCursor();
            }
        }
        return newest;
    }
}

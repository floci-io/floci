package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.kinesis.model.KinesisConsumer;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Verifies that {@code putRecordForAccount} resolves, persists, and legacy-migrates against the SPECIFIED
 * account rather than the ambient/default one, so an out-of-request-scope producer (TTL sweep, CDC
 * forwarding) never writes into another tenant's same-named stream and keeps working after an upgrade
 * from unprefixed (pre-multi-account) storage. Real {@link KinesisService} over in-memory account-aware
 * storage (no Vert.x → runs in the offline sandbox).
 */
class KinesisServiceAccountTest {

    private static final String KEY_S = "us-east-1::s";
    // jsr310 module registered so cloning a shard's Instant (creationTimestamp) round-trips.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private KinesisService serviceOver(AccountAwareStorageBackend<KinesisStream> streams) {
        return new KinesisService(streams, AccountAwareStorageBackend.inMemory("000000000000"),
                mock(RegionResolver.class));
    }

    private static int recordCount(KinesisStream stream) {
        return stream.getShards().stream().mapToInt(KinesisShard::recordCount).sum();
    }

    private KinesisStream cloneStream(KinesisStream template) {
        try {
            return mapper.treeToValue(mapper.valueToTree(template), KinesisStream.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void putRecordForAccountResolvesAgainstThatAccountNotDefault() {
        KinesisService svc = serviceOver(AccountAwareStorageBackend.inMemory("000000000000"));
        svc.createStream("s", 1, "us-east-1"); // created in the ambient/default account (000000000000)
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);

        assertNotNull(svc.putRecord("s", data, "pk", "us-east-1"));
        assertNotNull(svc.putRecordForAccount(null, "s", data, "pk", "us-east-1"));
        assertNotNull(svc.putRecordForAccount("000000000000", "s", data, "pk", "us-east-1"));

        AwsException ex = assertThrows(AwsException.class,
                () -> svc.putRecordForAccount("111111111111", "s", data, "pk", "us-east-1"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void putRecordForAccountPersistsToThatAccountPartition() {
        AccountAwareStorageBackend<KinesisStream> streams = AccountAwareStorageBackend.inMemory("000000000000");
        KinesisService svc = serviceOver(streams);
        svc.createStream("s", 1, "us-east-1"); // in the default account
        // Seed an INDEPENDENT same-named stream in account 111 (deep copy so the append can't leak back).
        KinesisStream in111 = cloneStream(streams.getForAccount("000000000000", KEY_S).orElseThrow());
        streams.putForAccount("111111111111", KEY_S, in111);

        svc.putRecordForAccount("111111111111", "s", "x".getBytes(StandardCharsets.UTF_8), "pk", "us-east-1");

        // Persisted into account 111's partition only; the default partition is untouched. (A regression
        // that used the ambient put would land the record in the default account here.)
        assertEquals(1, recordCount(streams.getForAccount("111111111111", KEY_S).orElseThrow()));
        assertEquals(0, recordCount(streams.getForAccount("000000000000", KEY_S).orElseThrow()));
    }

    @Test
    void putRecordForAccountMigratesLegacyUnprefixedStream() {
        // Upgrade scenario: a pre-multi-account (unprefixed) stream owned by account 111 exists in the
        // raw delegate. An out-of-request-scope forward for 111 must migrate + use it, not drop as RNF.
        InMemoryStorage<String, KinesisStream> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<KinesisStream> streams = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        KinesisService svc = serviceOver(streams);

        // Build a shard-carrying stream (via createStream on a throwaway key) and place a copy under the
        // UNPREFIXED legacy key, owned (by ARN) by account 111.
        KinesisStream legacy = cloneStream(svc.createStream("tmpl", 1, "us-east-1"));
        legacy.setStreamArn("arn:aws:kinesis:us-east-1:111111111111:stream/s2");
        raw.put("us-east-1::s2", legacy); // unprefixed = legacy pre-multi-account data

        // Migrates on write and appends; no ResourceNotFoundException.
        assertNotNull(svc.putRecordForAccount("111111111111", "s2", "x".getBytes(StandardCharsets.UTF_8),
                "pk", "us-east-1"));
        // The record is now in account 111's partition (migrated).
        assertEquals(1, recordCount(streams.getForAccount("111111111111", "us-east-1::s2").orElseThrow()));
    }
}

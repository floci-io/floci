package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Write-Ahead Log storage: in-memory reads with append-only binary WAL for durability.
 * Periodic compaction writes a full snapshot and truncates the WAL, and is skipped while
 * nothing has changed since the last snapshot.
 * On startup: load snapshot, then replay WAL entries after snapshot.
 *
 * Binary WAL entry format:
 *   PUT:    [0x01] [4-byte key length] [key bytes] [4-byte value length] [value bytes]
 *   DELETE: [0x02] [4-byte key length] [key bytes]
 *   BATCH:  [0x03] [4-byte map length] [CBOR map bytes]
 *   DELETE_BATCH: [0x04] [4-byte array length] [CBOR key array bytes]
 *
 * Key and value bytes are serialized via Jackson CBOR (compact binary format).
 * Snapshot files use indented JSON for debuggability.
 */
public class WalStorage<K, V> implements StorageBackend<K, V> {

    private static final Logger LOG = Logger.getLogger(WalStorage.class);

    static final byte OP_PUT = 0x01;
    static final byte OP_DELETE = 0x02;
    static final byte OP_BATCH = 0x03;
    static final byte OP_DELETE_BATCH = 0x04;

    private volatile ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final Path snapshotPath;
    private final Path walPath;
    private final ObjectMapper snapshotMapper;
    private final ObjectMapper walMapper;
    private final TypeReference<Map<K, V>> typeReference;
    private final ReentrantReadWriteLock compactionLock = new ReentrantReadWriteLock();
    private final ScheduledExecutorService scheduler;
    private final ChannelSync channelSync;
    private volatile DataOutputStream walWriter;
    private volatile IOException walFailure;
    // Mutations and compaction hold the write lock; idle stores need no new snapshot.
    private volatile boolean dirty;

    @FunctionalInterface
    interface ChannelSync {
        void force(FileChannel channel) throws IOException;
    }

    public WalStorage(Path snapshotPath, Path walPath, TypeReference<Map<K, V>> typeReference,
                      long compactionIntervalMs) {
        this(snapshotPath, walPath, typeReference, compactionIntervalMs, channel -> channel.force(true));
    }

    WalStorage(Path snapshotPath, Path walPath, TypeReference<Map<K, V>> typeReference,
               long compactionIntervalMs, ChannelSync channelSync) {
        this.channelSync = channelSync;
        this.snapshotPath = snapshotPath;
        this.walPath = walPath;
        this.typeReference = typeReference;

        this.snapshotMapper = new ObjectMapper();
        this.snapshotMapper.registerModule(new JavaTimeModule());
        this.snapshotMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.snapshotMapper.enable(SerializationFeature.INDENT_OUTPUT);

        this.walMapper = new ObjectMapper(new CBORFactory());
        this.walMapper.registerModule(new JavaTimeModule());
        this.walMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.scheduler = StorageFiles.scheduleEvery("wal-storage-compaction", compactionIntervalMs, this::compact);
    }

    @Override
    public void put(K key, V value) {
        byte[] record = encodePuts(Map.of(key, value));
        compactionLock.writeLock().lock();
        try {
            commitBufferedRecord(record);
            store.put(key, value);
            dirty = true;
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    /** Encodes every entry before publishing or appending any of the batch. */
    @Override
    public void putAll(Map<K, V> entries) {
        Map<K, V> replacements = Map.copyOf(entries);
        if (replacements.isEmpty()) {
            return;
        }
        byte[] records = encodePuts(replacements);
        compactionLock.writeLock().lock();
        try {
            commitBufferedRecord(records);
            store.putAll(replacements);
            dirty = true;
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    /** A batch is one length-delimited CBOR map, applied only after the entire frame decodes. */
    @Override
    public void putAllAtomically(Map<K, V> entries) {
        Map<K, V> replacements = Map.copyOf(entries);
        if (replacements.isEmpty()) {
            return;
        }
        byte[] payload;
        try {
            payload = walMapper.writeValueAsBytes(replacements);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to encode atomic WAL batch", exception);
        }
        commitBatch(OP_BATCH, payload, replacements, Set.of());
    }

    @Override
    public void deleteAllAtomically(Set<K> keys) {
        Set<K> deletions = Set.copyOf(keys);
        if (deletions.isEmpty()) {
            return;
        }
        byte[] payload;
        try {
            payload = walMapper.writeValueAsBytes(deletions);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to encode atomic WAL deletions", exception);
        }
        commitBatch(OP_DELETE_BATCH, payload, Map.of(), deletions);
    }

    private void commitBatch(byte operation, byte[] payload, Map<K, V> replacements, Set<K> deletions) {
        compactionLock.writeLock().lock();
        try {
            ConcurrentHashMap<K, V> candidate = new ConcurrentHashMap<>(store);
            candidate.putAll(replacements);
            candidate.keySet().removeAll(deletions);
            ByteBuffer record = ByteBuffer.allocate(1 + Integer.BYTES + payload.length);
            record.put(operation).putInt(payload.length).put(payload).flip();
            commitRecord(record);
            store = candidate;
            dirty = true;
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(K key) {
        byte[] record = encodeDelete(Objects.requireNonNull(key));
        compactionLock.writeLock().lock();
        try {
            commitBufferedRecord(record);
            store.remove(key);
            dirty = true;
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    @Override
    public List<V> scan(Predicate<K> keyFilter) {
        return store.entrySet().stream()
                .filter(e -> keyFilter.test(e.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Set<K> keys() {
        return Set.copyOf(store.keySet());
    }

    @Override
    public void flush() {
        compact();
    }

    @Override
    public void load() {
        if (Files.exists(snapshotPath)) {
            try {
                Map<K, V> data = snapshotMapper.readValue(snapshotPath.toFile(), typeReference);
                store.clear();
                store.putAll(data);
                LOG.infov("Loaded {0} entries from snapshot {1}", store.size(), snapshotPath);
            } catch (IOException e) {
                StorageQuarantine.quarantine(snapshotPath, e, LOG);
            }
        }

        if (Files.exists(walPath)) {
            int replayed = replayWal();
            LOG.infov("Replayed {0} WAL entries from {1}", replayed, walPath);
            if (replayed > 0) {
                // The snapshot is behind the log; the next compaction folds the log in.
                dirty = true;
            }
        }

        openWalWriter();
    }

    @Override
    public void clear() {
        compactionLock.writeLock().lock();
        try {
            store.clear();
            closeWalWriter();
            try {
                Files.deleteIfExists(walPath);
                Files.deleteIfExists(snapshotPath);
                dirty = false;
                walFailure = null;
            } catch (IOException e) {
                LOG.errorv(e, "Failed to delete WAL/snapshot files");
                // A snapshot that survived must be overwritten by the next compaction.
                dirty = true;
            }
            openWalWriter();
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    public void shutdown() {
        StorageFiles.stop(scheduler);
        compactionLock.writeLock().lock();
        try {
            compact();
            closeWalWriter();
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    /**
     * Reads the store a snapshot and its log hold, replaying the log over the snapshot, and
     * leaves both files as they are: nothing is compacted or deleted.
     */
    static <K, V> Map<K, V> readContents(Path snapshotPath, Path walPath, TypeReference<Map<K, V>> typeReference) {
        WalStorage<K, V> reader = new WalStorage<>(snapshotPath, walPath, typeReference, Long.MAX_VALUE);
        boolean hadLog = Files.exists(walPath);
        try {
            reader.load();
            return new HashMap<>(reader.store);
        } finally {
            StorageFiles.stop(reader.scheduler);
            reader.closeWalWriter();
            if (!hadLog) {
                // load() opens the log for appends, creating it; this read must leave no file behind.
                try {
                    Files.deleteIfExists(walPath);
                } catch (IOException e) {
                    LOG.warnv(e, "Failed to remove the empty log {0} a read created", walPath);
                }
            }
        }
    }

    private void compact() {
        compactionLock.writeLock().lock();
        try {
            if (!dirty || walFailure != null) {
                return;
            }
            StorageFiles.writeAtomically(snapshotPath, tempFile -> snapshotMapper.writeValue(tempFile.toFile(), store));
            dirty = false;

            closeWalWriter();
            try {
                Files.deleteIfExists(walPath);
            } finally {
                // Reopen even when the delete failed, so later records are still appended.
                openWalWriter();
            }

            LOG.debugv("Compacted {0} entries to snapshot, WAL truncated", store.size());
        } catch (IOException e) {
            LOG.errorv(e, "Failed to compact WAL storage");
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    private void commitBufferedRecord(byte[] record) {
        ensureWriterOpen();
        long offset;
        try {
            BasicFileAttributes attributes = Files.readAttributes(walPath, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("WAL path is not a regular file: " + walPath);
            }
            offset = attributes.size();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to inspect WAL at " + walPath, exception);
        }
        DataOutputStream writer = walWriter;
        try {
            writer.write(record);
            writer.flush();
        } catch (IOException exception) {
            rollbackBufferedRecord(writer, offset, exception);
            throw new UncheckedIOException("Failed to append WAL record to " + walPath, exception);
        }
    }

    private void rollbackBufferedRecord(DataOutputStream writer, long offset, IOException exception) {
        walWriter = null;
        try {
            // Closing may flush pending bytes, so it must happen before truncating the failed append.
            writer.close();
        } catch (IOException closeFailure) {
            addSuppressedFailure(exception, closeFailure);
            walFailure = closeFailure;
        }
        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
            channel.truncate(offset);
        } catch (IOException rollbackFailure) {
            addSuppressedFailure(exception, rollbackFailure);
            walFailure = rollbackFailure;
        }
        if (walFailure == null) {
            try {
                walWriter = newWalWriter();
            } catch (IOException reopenFailure) {
                addSuppressedFailure(exception, reopenFailure);
                walFailure = reopenFailure;
            }
        }
    }

    private static void addSuppressedFailure(IOException exception, IOException additionalFailure) {
        if (exception != additionalFailure) {
            exception.addSuppressed(additionalFailure);
        }
    }

    private void ensureWriterOpen() {
        if (walFailure != null) {
            throw new IllegalStateException("WAL recovery is required after an uncertain write", walFailure);
        }
        if (walWriter == null) {
            throw new IllegalStateException("WAL writer is not open");
        }
    }

    private void commitRecord(ByteBuffer record) {
        ensureWriterOpen();
        try {
            walWriter.flush();
        } catch (IOException exception) {
            walFailure = exception;
            closeWalWriter();
            throw new UncheckedIOException("Failed to flush WAL writer at " + walPath, exception);
        }
        try {
            FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE);
            try {
                long offset = channel.size();
                channel.position(offset);
                try {
                    while (record.hasRemaining()) {
                        channel.write(record);
                    }
                    channelSync.force(channel);
                } catch (IOException exception) {
                    try {
                        channel.truncate(offset);
                        channelSync.force(channel);
                    } catch (IOException rollbackFailure) {
                        addSuppressedFailure(exception, rollbackFailure);
                        walFailure = rollbackFailure;
                        closeWalWriter();
                    }
                    throw exception;
                }
            } finally {
                try {
                    channel.close();
                } catch (IOException exception) {
                    LOG.warnv(exception, "Failed to close WAL commit channel for {0}", walPath);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to commit WAL record to " + walPath, exception);
        }
    }

    private byte[] encodePuts(Map<K, V> entries) {
        try {
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            DataOutputStream records = new DataOutputStream(batch);
            for (Map.Entry<K, V> entry : entries.entrySet()) {
                byte[] key = walMapper.writeValueAsBytes(entry.getKey());
                byte[] value = walMapper.writeValueAsBytes(entry.getValue());
                records.writeByte(OP_PUT);
                records.writeInt(key.length);
                records.write(key);
                records.writeInt(value.length);
                records.write(value);
            }
            records.flush();
            return batch.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to encode PUT WAL entries", exception);
        }
    }

    private byte[] encodeDelete(K key) {
        try {
            byte[] encodedKey = walMapper.writeValueAsBytes(key);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream record = new DataOutputStream(bytes);
            record.writeByte(OP_DELETE);
            record.writeInt(encodedKey.length);
            record.write(encodedKey);
            record.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to encode DELETE WAL entry", exception);
        }
    }

    /**
     * Replays the log on top of the snapshot and returns the number of records applied.
     * <p>
     * Replay stops at the first record that cannot be framed or decoded: a tail torn by a crash,
     * a length header that is negative or points past the end of the file, an unknown op byte, or
     * a payload CBOR cannot read. The store is never failed by such a record; the bytes from that
     * record onwards are cut off before the writer reopens, so records appended afterwards sit
     * behind a valid record and are replayed on the next load instead of being lost behind the
     * garbage. A read error that is not a framing problem leaves the file alone.
     */
    @SuppressWarnings("unchecked")
    private int replayWal() {
        int replayed = 0;
        long fileSize;
        try {
            fileSize = Files.size(walPath);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to read the size of WAL {0}", walPath);
            return 0;
        }
        long consumed = 0;
        boolean cutTail = true;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(walPath)))) {
            while (consumed < fileSize) {
                int op = in.readByte();
                byte[] keyBytes = readFrame(in, fileSize - consumed - 1);
                if (keyBytes == null) {
                    break;
                }
                long recordLength = 1 + 4 + keyBytes.length;
                if (op == OP_BATCH) {
                    Map<K, V> batch = walMapper.readValue(keyBytes, typeReference);
                    if (batch == null || batch.entrySet().stream()
                            .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                        LOG.warnv("Invalid WAL batch at offset {0} in {1}, stopping replay", consumed, walPath);
                        break;
                    }
                    store.putAll(batch);
                } else if (op == OP_DELETE_BATCH) {
                    List<K> keys = walMapper.readValue(keyBytes, walMapper.getTypeFactory().constructCollectionType(
                            List.class, walMapper.constructType(typeReference.getType()).getKeyType()));
                    if (keys == null || keys.stream().anyMatch(key -> key == null)) {
                        LOG.warnv("Invalid WAL deletion batch at offset {0} in {1}, stopping replay", consumed, walPath);
                        break;
                    }
                    keys.forEach(store::remove);
                } else if (op == OP_PUT) {
                    byte[] valueBytes = readFrame(in, fileSize - consumed - recordLength);
                    if (valueBytes == null) {
                        break;
                    }
                    recordLength += 4 + valueBytes.length;
                    K key = (K) walMapper.readValue(keyBytes, Object.class);
                    V value = walMapper.readValue(valueBytes,
                            walMapper.constructType(typeReference.getType()).getContentType());
                    store.put(key, value);
                } else if (op == OP_DELETE) {
                    K key = (K) walMapper.readValue(keyBytes, Object.class);
                    store.remove(key);
                } else {
                    LOG.warnv("Unknown WAL op byte {0} at offset {1} in {2}, stopping replay",
                            op, consumed, walPath);
                    break;
                }
                consumed += recordLength;
                replayed++;
            }
        } catch (EOFException | JsonProcessingException e) {
            LOG.warnv("WAL {0} is unreadable from offset {1}: {2}", walPath, consumed, e.getMessage());
        } catch (IOException e) {
            cutTail = false;
            LOG.errorv(e, "Failed to replay WAL from {0} (replayed {1} entries before error)",
                    walPath, replayed);
        }
        if (cutTail && consumed < fileSize) {
            cutWalTail(consumed, fileSize);
        }
        return replayed;
    }

    /**
     * Reads one length-prefixed frame, or returns {@code null} when the length is negative or
     * larger than the bytes left in the file, which marks the record as torn or corrupt.
     */
    private static byte[] readFrame(DataInputStream in, long bytesLeft) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > bytesLeft - 4) {
            return null;
        }
        byte[] bytes = in.readNBytes(length);
        return bytes.length == length ? bytes : null;
    }

    private void cutWalTail(long lastGoodOffset, long fileSize) {
        try (FileChannel channel = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
            channel.truncate(lastGoodOffset);
            LOG.warnv("Cut {0} unreadable bytes from the end of WAL {1} at offset {2}; "
                    + "the entries in them are lost, entries appended from now on are replayed on the next load",
                    fileSize - lastGoodOffset, walPath, lastGoodOffset);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to cut the unreadable tail of WAL {0}; entries appended after it "
                    + "will not be replayed until the next compaction", walPath);
        }
    }

    private void openWalWriter() {
        if (walFailure != null) {
            return;
        }
        try {
            walWriter = newWalWriter();
        } catch (IOException e) {
            LOG.errorv(e, "Failed to open WAL writer at {0}", walPath);
        }
    }

    private DataOutputStream newWalWriter() throws IOException {
        Files.createDirectories(walPath.getParent());
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(walPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
    }

    private void closeWalWriter() {
        if (walWriter != null) {
            try {
                walWriter.close();
            } catch (IOException e) {
                LOG.errorv(e, "Failed to close WAL writer");
            }
            walWriter = null;
        }
    }
}

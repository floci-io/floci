package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
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
 *
 * Key and value bytes are serialized via Jackson CBOR (compact binary format).
 * Snapshot files use indented JSON for debuggability.
 */
public class WalStorage<K, V> implements StorageBackend<K, V> {

    private static final Logger LOG = Logger.getLogger(WalStorage.class);

    static final byte OP_PUT = 0x01;
    static final byte OP_DELETE = 0x02;

    private final ConcurrentHashMap<K, V> store = new ConcurrentHashMap<>();
    private final Path snapshotPath;
    private final Path walPath;
    private final ObjectMapper snapshotMapper;
    private final ObjectMapper walMapper;
    private final TypeReference<Map<K, V>> typeReference;
    private final ReentrantReadWriteLock compactionLock = new ReentrantReadWriteLock();
    private final ScheduledExecutorService scheduler;
    private volatile DataOutputStream walWriter;
    // Set under the read lock by every mutation and cleared under the write lock by compaction,
    // so an idle store does not rewrite its snapshot on every compaction tick.
    private volatile boolean dirty;

    public WalStorage(Path snapshotPath, Path walPath, TypeReference<Map<K, V>> typeReference,
                      long compactionIntervalMs) {
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

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wal-storage-compaction");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::compact, compactionIntervalMs, compactionIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    @Override
    public void put(K key, V value) {
        compactionLock.readLock().lock();
        try {
            store.put(key, value);
            dirty = true;
            appendPut(key, value);
        } finally {
            compactionLock.readLock().unlock();
        }
    }

    /**
     * Appends the whole batch as one contiguous run of WAL records with a single flush, so a
     * batch costs one write instead of one per entry. An empty batch touches nothing.
     */
    @Override
    public void putAll(Map<K, V> entries) {
        if (entries.isEmpty()) {
            return;
        }
        compactionLock.readLock().lock();
        try {
            store.putAll(entries);
            dirty = true;
            appendPuts(entries);
        } finally {
            compactionLock.readLock().unlock();
        }
    }

    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }

    @Override
    public void delete(K key) {
        compactionLock.readLock().lock();
        try {
            store.remove(key);
            dirty = true;
            appendDelete(key);
        } finally {
            compactionLock.readLock().unlock();
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
        return Collections.unmodifiableSet(store.keySet());
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
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        compact();
        closeWalWriter();
    }

    private void compact() {
        compactionLock.writeLock().lock();
        try {
            if (!dirty) {
                return;
            }
            Files.createDirectories(snapshotPath.getParent());
            Path tempFile = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            snapshotMapper.writeValue(tempFile.toFile(), store);
            Files.move(tempFile, snapshotPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            dirty = false;

            closeWalWriter();
            Files.deleteIfExists(walPath);
            openWalWriter();

            LOG.debugv("Compacted {0} entries to snapshot, WAL truncated", store.size());
        } catch (IOException e) {
            LOG.errorv(e, "Failed to compact WAL storage");
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    private void appendPut(K key, V value) {
        DataOutputStream out = walWriter;
        if (out == null) return;
        try {
            byte[] keyBytes = walMapper.writeValueAsBytes(key);
            byte[] valueBytes = walMapper.writeValueAsBytes(value);
            synchronized (out) {
                writePutRecord(out, keyBytes, valueBytes);
                out.flush();
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to append PUT WAL entry");
        }
    }

    private void appendPuts(Map<K, V> entries) {
        DataOutputStream out = walWriter;
        if (out == null) {
            return;
        }
        try {
            // Encode the whole batch off the shared writer so the lock is held for one write.
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            DataOutputStream records = new DataOutputStream(batch);
            for (Map.Entry<K, V> entry : entries.entrySet()) {
                writePutRecord(records, walMapper.writeValueAsBytes(entry.getKey()),
                        walMapper.writeValueAsBytes(entry.getValue()));
            }
            records.flush();
            synchronized (out) {
                batch.writeTo(out);
                out.flush();
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to append {0} PUT WAL entries", entries.size());
        }
    }

    private static void writePutRecord(DataOutputStream out, byte[] keyBytes, byte[] valueBytes)
            throws IOException {
        out.writeByte(OP_PUT);
        out.writeInt(keyBytes.length);
        out.write(keyBytes);
        out.writeInt(valueBytes.length);
        out.write(valueBytes);
    }

    private void appendDelete(K key) {
        DataOutputStream out = walWriter;
        if (out == null) return;
        try {
            byte[] keyBytes = walMapper.writeValueAsBytes(key);
            synchronized (out) {
                out.writeByte(OP_DELETE);
                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.flush();
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to append DELETE WAL entry");
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
                if (op == OP_PUT) {
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
        try {
            Files.createDirectories(walPath.getParent());
            walWriter = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(walPath,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
        } catch (IOException e) {
            LOG.errorv(e, "Failed to open WAL writer at {0}", walPath);
        }
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

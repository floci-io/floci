package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * Periodic compaction writes a full snapshot and truncates the WAL.
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
            appendPut(key, value);
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
                LOG.errorv(e, "Failed to load snapshot from {0}", snapshotPath);
            }
        }

        if (Files.exists(walPath)) {
            int replayed = replayWal();
            LOG.infov("Replayed {0} WAL entries from {1}", replayed, walPath);
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
            } catch (IOException e) {
                LOG.errorv(e, "Failed to delete WAL/snapshot files");
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
            Files.createDirectories(snapshotPath.getParent());
            Path tempFile = snapshotPath.resolveSibling(snapshotPath.getFileName() + ".tmp");
            snapshotMapper.writeValue(tempFile.toFile(), store);
            Files.move(tempFile, snapshotPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

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
                out.writeByte(OP_PUT);
                out.writeInt(keyBytes.length);
                out.write(keyBytes);
                out.writeInt(valueBytes.length);
                out.write(valueBytes);
                out.flush();
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to append PUT WAL entry");
        }
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

    @SuppressWarnings("unchecked")
    private int replayWal() {
        int replayed = 0;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(walPath)))) {
            while (true) {
                int op;
                try {
                    op = in.readByte();
                } catch (EOFException e) {
                    break;
                }

                int keyLen = in.readInt();
                byte[] keyBytes = in.readNBytes(keyLen);
                if (keyBytes.length < keyLen) break; // truncated entry

                if (op == OP_PUT) {
                    int valueLen = in.readInt();
                    byte[] valueBytes = in.readNBytes(valueLen);
                    if (valueBytes.length < valueLen) break; // truncated entry

                    K key = (K) walMapper.readValue(keyBytes, Object.class);
                    V value = walMapper.readValue(valueBytes,
                            walMapper.constructType(typeReference.getType()).getContentType());
                    store.put(key, value);
                    replayed++;
                } else if (op == OP_DELETE) {
                    K key = (K) walMapper.readValue(keyBytes, Object.class);
                    store.remove(key);
                    replayed++;
                } else {
                    LOG.errorv("Unknown WAL op byte: {0}, stopping replay", op);
                    break;
                }
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to replay WAL from {0} (replayed {1} entries before error)",
                    walPath, replayed);
        }
        return replayed;
    }

    private void openWalWriter() {
        try {
            Files.createDirectories(walPath.getParent());
            walWriter = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(walPath,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND)));
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

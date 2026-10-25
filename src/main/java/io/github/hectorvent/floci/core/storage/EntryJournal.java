package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The append-only journal of a {@link PartitionedFileStorage} in journal mode: one record per
 * changed map entry, so a change costs the size of that entry, not of the value it belongs to.
 *
 * <p>Record format, every string UTF-8 and every value Jackson CBOR, each length-prefixed with a
 * 4-byte length:
 * <pre>
 *   PUT_ENTRY:    [0x01] [key] [entry key] [entry value]
 *   REMOVE_ENTRY: [0x02] [key] [entry key]
 *   PUT:          [0x03] [key] [whole map]
 *   DELETE:       [0x04] [key]
 * </pre>
 *
 * <p>Replay stops at the first record that cannot be framed or decoded (a tail torn by a crash)
 * and cuts the file there, so records appended afterwards are not hidden behind the garbage.
 *
 * @param <E> the type of one map entry's value
 */
final class EntryJournal<E> {

    private static final Logger LOG = Logger.getLogger(EntryJournal.class);

    static final byte OP_PUT_ENTRY = 0x01;
    static final byte OP_REMOVE_ENTRY = 0x02;
    static final byte OP_PUT = 0x03;
    static final byte OP_DELETE = 0x04;

    /** One replayed change. */
    sealed interface Change<E> {
        String key();
    }

    record PutEntry<E>(String key, String entryKey, E value) implements Change<E> {}

    record RemoveEntry<E>(String key, String entryKey) implements Change<E> {}

    record Put<E>(String key, Map<String, E> value) implements Change<E> {}

    record Delete<E>(String key) implements Change<E> {}

    private final Path path;
    private final ObjectMapper mapper;
    private final JavaType entryType;
    private final JavaType entriesType;
    private volatile DataOutputStream writer;
    // Runs just before a record is written to the file. Tests use it to make a write fail midway.
    private volatile WriteHook beforeWrite = file -> { };

    /** A step run against the journal file that may fail like a write to it. */
    interface WriteHook {
        void run(Path file) throws IOException;
    }

    EntryJournal(Path path, JavaType entryType, JavaType entriesType) {
        this.path = path;
        this.entryType = entryType;
        this.entriesType = entriesType;
        this.mapper = new ObjectMapper(new CBORFactory());
        this.mapper.registerModule(new JavaTimeModule());
    }

    // Each append returns whether its record reached the journal file.

    boolean putEntry(String key, String entryKey, E value) {
        return append(OP_PUT_ENTRY, key, entryKey, value);
    }

    boolean removeEntry(String key, String entryKey) {
        return append(OP_REMOVE_ENTRY, key, entryKey, null);
    }

    boolean put(String key, Map<String, E> value) {
        return append(OP_PUT, key, null, value);
    }

    boolean delete(String key) {
        return append(OP_DELETE, key, null, null);
    }

    /** Whether the journal holds any record. */
    boolean isEmpty() {
        try {
            return !Files.exists(path) || Files.size(path) == 0;
        } catch (IOException e) {
            LOG.warnv(e, "Failed to read the size of {0}", path);
            return false;
        }
    }

    /**
     * Applies every readable record in order and returns how many were applied. A torn or
     * undecodable tail ends replay and is cut off.
     *
     * @throws IOException when the file could not be read to its end: records past the failure
     *                     were not applied and are kept, so the caller must not empty the journal
     */
    int replay(Consumer<Change<E>> apply) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        if (Files.isDirectory(path)) {
            // Holds no records; the first append fails and compaction replaces it with a file.
            LOG.warnv("{0} is a directory, not a journal", path);
            return 0;
        }
        long fileSize = Files.size(path);
        int replayed = 0;
        long consumed = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            while (consumed < fileSize) {
                long[] position = {consumed + 1};
                byte op = in.readByte();
                String key = readString(in, fileSize, position);
                Change<E> change = switch (op) {
                    case OP_PUT_ENTRY -> {
                        String entryKey = readString(in, fileSize, position);
                        byte[] value = readFrame(in, fileSize, position);
                        E decoded = value == null ? null : mapper.readValue(value, entryType);
                        yield decoded == null || entryKey == null || key == null
                                ? null : new PutEntry<>(key, entryKey, decoded);
                    }
                    case OP_REMOVE_ENTRY -> {
                        String entryKey = readString(in, fileSize, position);
                        yield entryKey == null || key == null ? null : new RemoveEntry<>(key, entryKey);
                    }
                    case OP_PUT -> {
                        byte[] value = readFrame(in, fileSize, position);
                        Map<String, E> decoded = value == null ? null : mapper.readValue(value, entriesType);
                        // A null map, or one holding a null key or value, cannot be stored: the
                        // record is as unreadable as a torn one.
                        yield decoded == null || key == null || hasNull(decoded) ? null : new Put<>(key, decoded);
                    }
                    case OP_DELETE -> key == null ? null : new Delete<>(key);
                    default -> null;
                };
                if (change == null) {
                    LOG.warnv("Unreadable record at offset {0} of {1}, stopping replay", consumed, path);
                    break;
                }
                apply.accept(change);
                consumed = position[0];
                replayed++;
            }
        } catch (EOFException | JsonProcessingException e) {
            LOG.warnv("{0} is unreadable from offset {1}: {2}", path, consumed, e.getMessage());
        }
        if (consumed < fileSize) {
            cutTail(consumed, fileSize);
        }
        return replayed;
    }

    void open() {
        close();
        try {
            Files.createDirectories(path.getParent());
            writer = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)));
        } catch (IOException e) {
            LOG.errorv(e, "Failed to open journal {0}", path);
        }
    }

    void close() {
        DataOutputStream out = writer;
        writer = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                LOG.errorv(e, "Failed to close journal {0}", path);
            }
        }
    }

    /** Drops every record; the caller holds off appends while it does. */
    void truncate() throws IOException {
        close();
        try {
            Files.deleteIfExists(path);
        } finally {
            // Reopen even when the delete failed, so later records are still appended.
            open();
        }
    }

    void deleteFile() {
        close();
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to delete journal {0}", path);
        }
    }

    void setBeforeWrite(WriteHook hook) {
        this.beforeWrite = hook;
    }

    private boolean append(byte op, String key, String entryKey, Object value) {
        DataOutputStream out = writer;
        if (out == null) {
            LOG.errorv("Journal {0} is not open; a change to {1} was not journaled", path, key);
            return false;
        }
        try {
            // Encode off the shared writer so the lock is held for one write.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream record = new DataOutputStream(bytes);
            record.writeByte(op);
            writeFrame(record, key.getBytes(StandardCharsets.UTF_8));
            if (entryKey != null) {
                writeFrame(record, entryKey.getBytes(StandardCharsets.UTF_8));
            }
            if (op == OP_PUT_ENTRY || op == OP_PUT) {
                writeFrame(record, mapper.writeValueAsBytes(value));
            }
            record.flush();
            synchronized (out) {
                if (writer != out) {
                    LOG.errorv("Journal {0} was closed; a change to {1} was not journaled", path, key);
                    return false;
                }
                try {
                    beforeWrite.run(path);
                    bytes.writeTo(out);
                    out.flush();
                } catch (IOException e) {
                    // Part of the record may be on disk. A later record appended behind it would be
                    // cut off with it on replay, so stop appending until compaction replaces the file.
                    writer = null;
                    closeQuietly(out);
                    throw e;
                }
            }
            return true;
        } catch (IOException e) {
            LOG.errorv(e, "Failed to append to journal {0}; it takes no more records until it is compacted", path);
            return false;
        }
    }

    private void closeQuietly(DataOutputStream out) {
        try {
            out.close();
        } catch (IOException e) {
            LOG.warnv(e, "Failed to close journal {0} after a failed append", path);
        }
    }

    private static void writeFrame(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static boolean hasNull(Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                return true;
            }
        }
        return false;
    }

    /** Reads one frame as strict UTF-8, or {@code null} when it is torn or not valid UTF-8. */
    private static String readString(DataInputStream in, long fileSize, long[] position) throws IOException {
        byte[] bytes = readFrame(in, fileSize, position);
        if (bytes == null) {
            return null;
        }
        try {
            // A lenient decode would swap bad bytes for U+FFFD and apply the change to another key.
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Reads one length-prefixed frame, or {@code null} when its length runs past the file. */
    private static byte[] readFrame(DataInputStream in, long fileSize, long[] position) throws IOException {
        if (fileSize - position[0] < 4) {
            return null;
        }
        int length = in.readInt();
        if (length < 0 || length > fileSize - position[0] - 4) {
            return null;
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            return null;
        }
        position[0] += 4 + length;
        return bytes;
    }

    private void cutTail(long lastGoodOffset, long fileSize) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(lastGoodOffset);
            LOG.warnv("Cut {0} unreadable bytes from the end of {1} at offset {2}; the records in them are lost",
                    fileSize - lastGoodOffset, path, lastGoodOffset);
        } catch (IOException e) {
            LOG.errorv(e, "Failed to cut the unreadable tail of {0}", path);
        }
    }
}

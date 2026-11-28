package io.floci.aws.services.rds.integration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The messages of PostgreSQL's {@code pgoutput} logical decoding plugin, protocol version 1, as
 * {@code pg_logical_slot_peek_binary_changes} returns them: one message per row. Values are in their text form,
 * since the binary option is not asked for. Messages Floci has no use for, such as an origin or a type, are read
 * as {@link Other}.
 */
final class PgOutputMessages {

    private PgOutputMessages() {}

    sealed interface Message permits Begin, Commit, Relation, Insert, Update, Delete, Truncate, Other {}

    record Begin() implements Message {}

    /** The end of a transaction; {@code endLsn} is where a slot advanced past it resumes. */
    record Commit(String endLsn) implements Message {}

    /** A table's schema, name and column names, in the order a tuple carries its values. */
    record Relation(int relationId, String schema, String table, List<String> columns) implements Message {}

    record Insert(int relationId, Tuple row) implements Message {}

    /**
     * An updated row. {@code oldRow} is the old primary key when the key changed, or the whole old row under
     * {@code REPLICA IDENTITY FULL}; it is null otherwise.
     */
    record Update(int relationId, Tuple oldRow, Tuple row) implements Message {}

    /** A deleted row, given by its old primary key, or by its whole old row under {@code REPLICA IDENTITY FULL}. */
    record Delete(int relationId, Tuple oldRow) implements Message {}

    record Truncate(List<Integer> relationIds) implements Message {}

    record Other(char type) implements Message {}

    /** A row's values, in the order of its relation's columns. */
    record Tuple(List<Value> values) {}

    /**
     * One value of a row: SQL NULL, its text, or {@link Kind#UNCHANGED} for a TOASTed value the change did not touch,
     * which PostgreSQL leaves out of an update.
     */
    record Value(Kind kind, String text) {
        enum Kind { NULL, UNCHANGED, TEXT }

        static final Value NULL = new Value(Kind.NULL, null);
        static final Value UNCHANGED = new Value(Kind.UNCHANGED, null);
    }

    static Message decode(byte[] data) {
        ByteBuffer in = ByteBuffer.wrap(data);
        char type = (char) in.get();
        return switch (type) {
            case 'B' -> new Begin();
            case 'C' -> {
                in.get();
                in.getLong();
                yield new Commit(lsn(in.getLong()));
            }
            case 'R' -> relation(in);
            case 'I' -> {
                int relationId = in.getInt();
                expect(in, 'N');
                yield new Insert(relationId, tuple(in));
            }
            case 'U' -> update(in);
            case 'D' -> {
                int relationId = in.getInt();
                char kind = (char) in.get();
                if (kind != 'K' && kind != 'O') {
                    throw new IllegalArgumentException("Unexpected tuple kind " + kind + " in a pgoutput delete");
                }
                yield new Delete(relationId, tuple(in));
            }
            case 'T' -> {
                int count = in.getInt();
                in.get();
                List<Integer> relationIds = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    relationIds.add(in.getInt());
                }
                yield new Truncate(relationIds);
            }
            default -> new Other(type);
        };
    }

    /** An LSN in the {@code X/Y} form PostgreSQL prints and parses. */
    static String lsn(long value) {
        return (Long.toHexString(value >>> 32) + "/" + Long.toHexString(value & 0xFFFFFFFFL)).toUpperCase(Locale.ROOT);
    }

    private static Relation relation(ByteBuffer in) {
        int relationId = in.getInt();
        String schema = string(in);
        String table = string(in);
        in.get();
        int count = in.getShort();
        List<String> columns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            in.get();
            columns.add(string(in));
            in.getInt();
            in.getInt();
        }
        return new Relation(relationId, schema.isEmpty() ? "pg_catalog" : schema, table, columns);
    }

    private static Update update(ByteBuffer in) {
        int relationId = in.getInt();
        char kind = (char) in.get();
        Tuple oldRow = null;
        if (kind == 'K' || kind == 'O') {
            oldRow = tuple(in);
            kind = (char) in.get();
        }
        if (kind != 'N') {
            throw new IllegalArgumentException("Unexpected tuple kind " + kind + " in a pgoutput update");
        }
        return new Update(relationId, oldRow, tuple(in));
    }

    private static Tuple tuple(ByteBuffer in) {
        int count = in.getShort();
        List<Value> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            char kind = (char) in.get();
            values.add(switch (kind) {
                case 'n' -> Value.NULL;
                case 'u' -> Value.UNCHANGED;
                case 't' -> {
                    byte[] text = new byte[in.getInt()];
                    in.get(text);
                    yield new Value(Value.Kind.TEXT, new String(text, StandardCharsets.UTF_8));
                }
                default -> throw new IllegalArgumentException("Unexpected value kind " + kind + " in a pgoutput tuple");
            });
        }
        return new Tuple(values);
    }

    private static void expect(ByteBuffer in, char expected) {
        char kind = (char) in.get();
        if (kind != expected) {
            throw new IllegalArgumentException("Expected tuple kind " + expected + " but read " + kind);
        }
    }

    private static String string(ByteBuffer in) {
        int start = in.position();
        while (in.get() != 0) {
            // Reads up to and past the terminating NUL.
        }
        return new String(in.array(), start, in.position() - start - 1, StandardCharsets.UTF_8);
    }
}

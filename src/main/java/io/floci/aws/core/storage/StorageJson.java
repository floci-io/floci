package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The JSON form the file storages keep records in: Java time types, written as ISO-8601 text rather than numbers.
 * Their mappers are built here, and so are copies of a record through that form.
 */
public final class StorageJson {

    private static final ObjectMapper COPIES = mapper();

    private StorageJson() {
    }

    /** A mapper that reads and writes records as the file storages do. */
    public static ObjectMapper mapper() {
        return configure(new ObjectMapper());
    }

    /** As above, over {@code factory}, as the write-ahead log's CBOR mapper is. */
    static ObjectMapper mapper(JsonFactory factory) {
        return configure(new ObjectMapper(factory));
    }

    /**
     * A copy of {@code record} through its stored form: a record of its own, as storage would read it back, so
     * changing one doesn't change the other.
     */
    @SuppressWarnings("unchecked")
    public static <V> V copy(V record) {
        return (V) COPIES.convertValue(record, record.getClass());
    }

    private static ObjectMapper configure(ObjectMapper mapper) {
        return mapper.registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

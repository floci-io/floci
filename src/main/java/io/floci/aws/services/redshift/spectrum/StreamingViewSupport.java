package io.floci.aws.services.redshift.spectrum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * What the streaming materialized views over Kinesis streams and over MSK topics share: the locks that serialize
 * the work on one source (a stream or a topic), the drop of a view and its staging table, and the SQL helpers.
 * A source is named by its external schema and its name, which the two kinds of binding spell differently.
 */
final class StreamingViewSupport<B extends SchemaBinding> {

    /** The schema an unqualified view name is created in. */
    static final String DEFAULT_VIEW_SCHEMA = "public";

    private static final int BATCH_BYTES = 4 * 1024 * 1024;

    private final SchemaBindingRegistry<B> registry;
    private final Function<B, String> viewSchema;
    private final Function<B, String> viewName;
    private final Function<B, String> sourceSchema;
    private final Function<B, String> sourceName;
    private final Map<String, Object> sourceLocks = new ConcurrentHashMap<>();

    StreamingViewSupport(SchemaBindingRegistry<B> registry, Function<B, String> viewSchema,
                         Function<B, String> viewName, Function<B, String> sourceSchema,
                         Function<B, String> sourceName) {
        this.registry = registry;
        this.viewSchema = viewSchema;
        this.viewName = viewName;
        this.sourceSchema = sourceSchema;
        this.sourceName = sourceName;
    }

    /** The lock of one source: its refreshes share a staging table, so they and its creates and drops take turns. */
    Object lock(String accountId, String clusterKey, String database, String schema, String name) {
        return sourceLocks.computeIfAbsent(String.join("\u001f", accountId, clusterKey, database, schema, name),
                key -> new Object());
    }

    Object lock(B binding) {
        return lock(binding.accountId(), binding.clusterKey(), binding.databaseName(), sourceSchema.apply(binding),
                sourceName.apply(binding));
    }

    boolean readsSameSource(B first, B second) {
        return sourceSchema.apply(first).equals(sourceSchema.apply(second))
                && sourceName.apply(first).equals(sourceName.apply(second));
    }

    void unbind(B view) {
        registry.unbind(view.accountId(), view.clusterKey(), view.databaseName(), view.bindingName());
    }

    /** Unbinds a view captured earlier, unless a view of the same name over another source has replaced it. */
    void unbindIfCurrent(B captured) {
        synchronized (lock(captured)) {
            registry.find(captured.accountId(), captured.clusterKey(), captured.databaseName(),
                    captured.bindingName()).filter(current -> readsSameSource(current, captured))
                    .ifPresent(this::unbind);
        }
    }

    /** The SQL that drops a view's table, for the statement that drops a schema it reads from. */
    static String dropSql(String viewSchema, String viewName, boolean ifExists, boolean cascade) {
        return "DROP TABLE " + (ifExists ? "IF EXISTS " : "") + table(viewSchema, viewName)
                + (cascade ? " CASCADE" : " RESTRICT");
    }

    /**
     * Drops a view's table and its binding, and the source's staging table once no other view reads the source.
     * Without that, a plain DROP SCHEMA of the source's schema would keep failing on the leftover table.
     */
    void drop(B named, boolean ifExists, boolean cascade, BackendSql backend) {
        synchronized (lock(named)) {
            // the binding read before the lock may be gone or replaced by a view of the same name since
            Optional<B> registered = registry.find(named.accountId(), named.clusterKey(), named.databaseName(),
                    named.bindingName());
            if (registered.isEmpty() || !readsSameSource(registered.get(), named)) {
                if (ifExists) {
                    return;
                }
                throw new SpectrumSqlException("42P01", "materialized view \"" + viewSchema.apply(named) + "."
                        + viewName.apply(named) + "\" does not exist");
            }
            B view = registered.get();
            boolean lastReader = registry.list(view.accountId(), view.clusterKey(), view.databaseName()).stream()
                    .noneMatch(other -> !other.bindingName().equals(view.bindingName())
                            && readsSameSource(other, view));
            String sql = dropSql(viewSchema.apply(view), viewName.apply(view), ifExists, cascade);
            if (lastReader) {
                sql += "; DROP TABLE IF EXISTS " + table(sourceSchema.apply(view), sourceName.apply(view));
            }
            backend.execute(sql);
            unbind(view);
        }
    }

    /** Splits records so no statement carries more than about {@value #BATCH_BYTES} bytes of record data. */
    static <R> List<List<R>> batches(List<R> records, ToLongFunction<R> size) {
        List<List<R>> batches = new ArrayList<>();
        List<R> batch = new ArrayList<>();
        long bytes = 0;
        for (R record : records) {
            long recordBytes = size.applyAsLong(record);
            if (!batch.isEmpty() && bytes + recordBytes > BATCH_BYTES) {
                batches.add(batch);
                batch = new ArrayList<>();
                bytes = 0;
            }
            batch.add(record);
            bytes += recordBytes;
        }
        batches.add(batch);
        return batches;
    }

    static String schemaOrDefault(String schema) {
        return schema == null ? DEFAULT_VIEW_SCHEMA : schema;
    }

    static String table(String schema, String name) {
        return ExternalTableMaterializer.quote(schema) + "." + ExternalTableMaterializer.quote(name);
    }
}

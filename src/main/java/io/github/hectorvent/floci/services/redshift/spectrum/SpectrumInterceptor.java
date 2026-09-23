package io.github.hectorvent.floci.services.redshift.spectrum;

import jakarta.enterprise.context.ApplicationScoped;

import java.net.Socket;
import java.util.Optional;

@ApplicationScoped
public final class SpectrumInterceptor {

    private final SpectrumCatalog catalog;
    private final SpectrumStatementParser statementParser;
    private final SpectrumQueryClassifier queryClassifier;
    private final SpectrumQueryRewriter queryRewriter;
    private final SpectrumS3Reader reader;
    private final SpectrumMaterializer materializer;

    public SpectrumInterceptor(SpectrumCatalog catalog, SpectrumStatementParser statementParser,
                               SpectrumQueryClassifier queryClassifier, SpectrumQueryRewriter queryRewriter,
                               SpectrumS3Reader reader, SpectrumMaterializer materializer) {
        this.catalog = catalog;
        this.statementParser = statementParser;
        this.queryClassifier = queryClassifier;
        this.queryRewriter = queryRewriter;
        this.reader = reader;
        this.materializer = materializer;
    }

    public Decision intercept(String sql, String accountId, String databaseName, Socket backend) {
        Plan plan = plan(sql, accountId, databaseName);
        return execute(plan, accountId, databaseName, backend);
    }

    public Plan plan(String sql, String accountId, String databaseName) {
        Optional<SpectrumStatement> statement = statementParser.parse(sql);
        if (statement.isPresent()) {
            return new Plan.Ddl(statement.get());
        }

        Optional<SpectrumQuery> query = queryClassifier.classify(sql, 0);
        if (query.isEmpty() || query.get().schemaName() == null) {
            return new Plan.Forward();
        }
        SpectrumQuery externalQuery = query.get();
        Optional<SpectrumExternalTable> table = catalog.table(
                accountId, databaseName, externalQuery.schemaName(), externalQuery.tableName());
        if (table.isEmpty()) {
            return new Plan.Forward();
        }
        SpectrumExternalSchema schema = catalog.schema(accountId, databaseName, externalQuery.schemaName())
                .orElseThrow(() -> new SpectrumSqlException("0A000", "External schema is not defined"));
        return new Plan.Query(externalQuery, table.get(), schema, materializer.nextIdentifier());
    }

    public Decision execute(Plan plan, String accountId, String databaseName, Socket backend) {
        switch (plan) {
            case Plan.Ddl(SpectrumStatement parsed) -> {
                switch (parsed) {
                case SpectrumStatement.CreateSchema schema -> catalog.createSchema(new SpectrumExternalSchema(
                        accountId, schema.databaseName(), schema.schemaName(),
                        "s3://spectrum/" + schema.schemaName() + "/", schema.iamRoleArn()));
                case SpectrumStatement.CreateTable table -> catalog.createTable(new SpectrumExternalTable(
                        accountId, databaseName, table.schemaName(), table.tableName(), table.columns(),
                        table.location(), table.delimiter(), table.quote(), table.escape(), table.nullValue(),
                        table.headerLines()));
                }
                return new Decision.Handled();
            }
            case Plan.Query(SpectrumQuery query, SpectrumExternalTable table, SpectrumExternalSchema schema, String identifier) -> {
                SpectrumMaterializer.Materialization materialized = materializer.materialize(backend, table, schema, reader, identifier);
                return new Decision.Rewritten(queryRewriter.rewrite(query, materialized.identifier()), materialized);
            }
            case Plan.Forward ignored -> {
                return new Decision.Forward();
            }
        }
    }

    /**
     * Drops the temp table a {@link Decision.Rewritten} materialized, once the client has finished
     * reading its rows (or failed while doing so).
     * <p>
     * This eager cleanup is used by execution paths that manage the query round-trip explicitly, such
     * as the Extended Query path in {@code ExtendedSpectrumExchange}. Under the Simple Query protocol,
     * the temp table is session-scoped (via PostgreSQL {@code CREATE TEMP TABLE}) and cleaned up when
     * the connection terminates, avoiding race conditions with the streaming backend-to-client pump.
     */
    public void cleanup(Socket backend, SpectrumMaterializer.Materialization materialization) {
        materializer.cleanup(backend, materialization);
    }

    public sealed interface Plan permits Plan.Ddl, Plan.Forward, Plan.Query {
        record Ddl(SpectrumStatement statement) implements Plan {
        }

        record Forward() implements Plan {
        }

        record Query(SpectrumQuery query, SpectrumExternalTable table, SpectrumExternalSchema schema,
                     String identifier) implements Plan {
        }
    }

    public sealed interface Decision permits Decision.Handled, Decision.Forward, Decision.Rewritten {
        record Handled() implements Decision {
        }

        record Forward() implements Decision {
        }

        /**
         * A Spectrum query rewritten into an executable SQL statement querying a temporary table.
         * The materialized table is dropped eagerly via {@link SpectrumInterceptor#cleanup} on the
         * Extended Query protocol, or released at session termination on the Simple Query protocol.
         */
        record Rewritten(String sql, SpectrumMaterializer.Materialization materialization) implements Decision {
        }
    }
}

package io.github.hectorvent.floci.services.redshift.spectrum;

import java.net.Socket;
import java.util.Optional;
import jakarta.enterprise.context.ApplicationScoped;

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
        Optional<SpectrumStatement> statement = statementParser.parse(sql);
        if (statement.isPresent()) {
            SpectrumStatement parsed = statement.get();
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

        Optional<SpectrumQuery> query = queryClassifier.classify(sql, 0);
        if (query.isEmpty() || query.get().schemaName() == null) {
            return new Decision.Forward();
        }
        SpectrumQuery externalQuery = query.get();
        Optional<SpectrumExternalTable> table = catalog.table(
                accountId, databaseName, externalQuery.schemaName(), externalQuery.tableName());
        if (table.isEmpty()) {
            return new Decision.Forward();
        }
        SpectrumExternalSchema schema = catalog.schema(accountId, databaseName, externalQuery.schemaName())
                .orElseThrow(() -> new SpectrumSqlException("0A000", "External schema is not defined"));
        SpectrumMaterializer.Materialization materialized = materializer.materialize(
                backend, table.get(), schema, reader);
        return new Decision.Rewritten(queryRewriter.rewrite(externalQuery, materialized.identifier()), materialized);
    }

    public sealed interface Decision permits Decision.Handled, Decision.Forward, Decision.Rewritten {
        record Handled() implements Decision {
        }

        record Forward() implements Decision {
        }

        record Rewritten(String sql, SpectrumMaterializer.Materialization materialization) implements Decision {
        }
    }
}

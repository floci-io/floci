package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class SpectrumInterceptor {
    private final SpectrumStatementParser legacyParser;
    private final ExternalStatementParser parser;
    private final SpectrumQueryClassifier classifier;
    private final SpectrumGlueCsvAdapter csvAdapter;
    private final SpectrumMaterializer spectrumMaterializer;
    private final SpectrumS3Reader spectrumReader;
    private final ExternalSchemaService service;
    private final EmulatorConfig config;

    public SpectrumInterceptor(SpectrumStatementParser legacyParser, ExternalStatementParser parser,
                               SpectrumQueryClassifier classifier, SpectrumGlueCsvAdapter csvAdapter,
                               SpectrumMaterializer spectrumMaterializer, SpectrumS3Reader spectrumReader,
                               ExternalSchemaService service, EmulatorConfig config) {
        this.legacyParser = legacyParser;
        this.parser = parser;
        this.classifier = classifier;
        this.csvAdapter = csvAdapter;
        this.spectrumMaterializer = spectrumMaterializer;
        this.spectrumReader = spectrumReader;
        this.service = service;
        this.config = config;
    }

    public Decision intercept(String sql, SpectrumSession session, BackendSql backend) {
        return execute(plan(sql, session), session, backend);
    }

    public Plan plan(String sql, SpectrumSession session) {
        if (!config.services().redshift().spectrumEnabled()) {
            return new Plan.Forward();
        }
        Optional<ExternalStatement> statement = parseStatement(sql);
        if (statement.isPresent()) {
            return new Plan.Ddl(statement.get());
        }
        service.rejectExternalWrites(sql, session);
        if (service.touchesCatalogViews(sql)) {
            return new Plan.Refresh();
        }
        List<ExternalReferenceScanner.Reference> references = service.referencesIn(sql, session);
        if (references.isEmpty()) {
            return new Plan.Forward();
        }
        Optional<SpectrumQuery> query = classifier.classify(sql, 0);
        if (references.size() == 1 && query.isPresent()) {
            ExternalReferenceScanner.Reference reference = references.getFirst();
            if (query.get().tableName().equalsIgnoreCase(reference.table())
                    && (query.get().schemaName() == null || query.get().schemaName().equalsIgnoreCase(reference.schema()))) {
                Optional<ExternalSchemaService.BoundGlueTable> resolved = service.resolveGlueTable(reference, session);
                if (resolved.isPresent()) {
                    ExternalSchemaService.BoundGlueTable glueTable = resolved.get();
                    Optional<SpectrumGlueCsvAdapter.CsvTable> csvTable = csvAdapter.adapt(
                            glueTable.binding(), glueTable.table(), session.accountId(), session.databaseName());
                    if (csvTable.isPresent()) {
                        return new Plan.PhaseOneQuery(query.get(), csvTable.get(), spectrumMaterializer.nextIdentifier());
                    }
                }
            }
        }
        return new Plan.Load(references);
    }

    public Decision execute(Plan plan, SpectrumSession session, BackendSql backend) {
        return switch (plan) {
            case Plan.Ddl ddl -> service.execute(ddl.statement(), session, backend)
                    .<Decision>map(Decision.Handled::new).orElseGet(Decision.Forward::new);
            case Plan.Load load -> {
                service.loadReferences(load.references(), session, backend);
                yield new Decision.Forward();
            }
            case Plan.PhaseOneQuery phaseOne -> {
                SpectrumGlueCsvAdapter.CsvTable csvTable = phaseOne.table();
                SpectrumMaterializer.Materialization materialization = spectrumMaterializer.materialize(
                        backend, session, csvTable.schema(), csvTable.table(), spectrumReader, phaseOne.identifier());
                yield new Decision.Rewritten(SpectrumQueryRewriter.rewrite(phaseOne.query(), phaseOne.identifier()), materialization);
            }
            case Plan.Refresh ignored -> {
                service.refreshMetadata(session, backend);
                yield new Decision.Forward();
            }
            case Plan.Forward ignored -> new Decision.Forward();
        };
    }

    public void forgetCluster(String accountId, String clusterKey) {
        service.forgetCluster(accountId, clusterKey);
    }

    public void cleanup(BackendSql backend, SpectrumMaterializer.Materialization materialization) {
        spectrumMaterializer.cleanup(backend, materialization);
    }

    private Optional<ExternalStatement> parseStatement(String sql) {
        try {
            Optional<SpectrumStatement> legacy = legacyParser.parse(sql);
            if (legacy.isPresent()) {
                return Optional.of(toExternalStatement(legacy.get(), sql));
            }
        } catch (SpectrumSqlException legacyFailure) {
            try {
                Optional<ExternalStatement> extended = parser.parse(sql);
                if (extended.isPresent()) {
                    return extended;
                }
            } catch (SpectrumSqlException extendedFailure) {
                legacyFailure.addSuppressed(extendedFailure);
            }
            throw legacyFailure;
        }
        return parser.parse(sql);
    }

    private static ExternalStatement toExternalStatement(SpectrumStatement statement, String sql) {
        return switch (statement) {
            case SpectrumStatement.CreateSchema create -> new ExternalStatement.CreateSchema(
                    create.schemaName(), create.databaseName(), create.iamRoleArn(),
                    sql.toLowerCase(Locale.ROOT).contains("create external database if not exists"));
            case SpectrumStatement.CreateTable create -> new ExternalStatement.CreateTable(
                    create.schemaName(), create.tableName(),
                    create.columns().stream().map(column -> new ExternalStatement.ColumnDefinition(
                            column.name(), column.type().postgresType())).toList(), List.of(),
                    ExternalStatement.TableFormat.TEXTFILE, create.location(), String.valueOf(create.delimiter()), null,
                    Map.of("field.delim", String.valueOf(create.delimiter()),
                            "serialization.format", String.valueOf(create.delimiter()),
                            "quoteChar", String.valueOf(create.quote()),
                            "escapeChar", String.valueOf(create.escape()),
                            "serialization.null.format", create.nullValue(),
                            "skip.header.line.count", Integer.toString(create.headerLines())));
        };
    }

    public sealed interface Plan permits Plan.Ddl, Plan.Load, Plan.PhaseOneQuery, Plan.Refresh, Plan.Forward {
        record Ddl(ExternalStatement statement) implements Plan { }
        record Load(List<ExternalReferenceScanner.Reference> references) implements Plan { }
        record PhaseOneQuery(SpectrumQuery query, SpectrumGlueCsvAdapter.CsvTable table, String identifier) implements Plan { }
        record Refresh() implements Plan { }
        record Forward() implements Plan { }
    }

    public sealed interface Decision permits Decision.Handled, Decision.Forward, Decision.Rewritten {
        record Handled(String commandTag) implements Decision { }
        record Forward() implements Decision { }
        record Rewritten(String sql, SpectrumMaterializer.Materialization materialization) implements Decision { }
    }
}

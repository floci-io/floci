package io.github.hectorvent.floci.services.redshift.spectrum;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SpectrumInterceptor {
    private final ExternalStatementParser parser;
    private final ExternalSchemaService service;
    private final EmulatorConfig config;

    public SpectrumInterceptor(ExternalStatementParser parser, ExternalSchemaService service, EmulatorConfig config) {
        this.parser = parser;
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
        Optional<ExternalStatement> statement = parser.parse(sql);
        if (statement.isPresent()) {
            return new Plan.Ddl(statement.get());
        }
        service.rejectExternalWrites(sql, session);
        if (service.touchesCatalogViews(sql)) {
            return new Plan.Refresh();
        }
        List<ExternalReferenceScanner.Reference> references = service.referencesIn(sql, session);
        return references.isEmpty() ? new Plan.Forward() : new Plan.Load(references);
    }

    public Decision execute(Plan plan, SpectrumSession session, BackendSql backend) {
        return switch (plan) {
            case Plan.Ddl ddl -> service.execute(ddl.statement(), session, backend)
                    .<Decision>map(Decision.Handled::new).orElseGet(Decision.Forward::new);
            case Plan.Load load -> {
                service.loadReferences(load.references(), session, backend);
                yield new Decision.Forward();
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

    public sealed interface Plan permits Plan.Ddl, Plan.Load, Plan.Refresh, Plan.Forward {
        record Ddl(ExternalStatement statement) implements Plan { }
        record Load(List<ExternalReferenceScanner.Reference> references) implements Plan { }
        record Refresh() implements Plan { }
        record Forward() implements Plan { }
    }

    public sealed interface Decision permits Decision.Handled, Decision.Forward {
        record Handled(String commandTag) implements Decision { }
        record Forward() implements Decision { }
    }
}

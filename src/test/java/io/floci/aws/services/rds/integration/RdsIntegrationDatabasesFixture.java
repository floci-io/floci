package io.floci.aws.services.rds.integration;

import io.floci.aws.services.rds.model.Integration;
import io.floci.aws.services.redshift.RedshiftService;
import io.floci.aws.services.redshift.spectrum.ExternalStatement;
import io.floci.aws.services.redshift.spectrum.SpectrumSession;
import io.floci.aws.services.redshiftserverless.RedshiftServerlessService;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared mocks, integration and statement helpers for {@link RdsIntegrationDatabases} tests. */
final class RdsIntegrationDatabasesFixture {

    static final String ACCOUNT = "000000000000";
    static final String INTEGRATION_ID = "4b2f2f9c-7d1e-4f55-9a77-1a2b3c4d5e6f";

    final RdsIntegrationService integrations;
    final RdsIntegrationLoader loader;
    final RedshiftService redshiftService;
    final RedshiftServerlessService serverless;
    final RdsIntegrationDatabases databases;
    final Integration integration;

    RdsIntegrationDatabasesFixture() {
        integrations = mock(RdsIntegrationService.class);
        loader = mock(RdsIntegrationLoader.class);
        redshiftService = mock(RedshiftService.class);
        serverless = mock(RedshiftServerlessService.class);
        databases = new RdsIntegrationDatabases(integrations, loader, redshiftService, serverless);
        integration = new Integration();
        integration.setIntegrationArn("arn:aws:rds:us-east-1:000000000000:integration:" + INTEGRATION_ID);
        integration.setTargetArn("arn:aws:redshift:us-east-1:000000000000:cluster:orders-dw");
        integration.setDataFilter("include: orders.*.*");
        integration.setAccountId(ACCOUNT);
        integration.setRegion("us-east-1");
        when(integrations.findById(anyString())).thenReturn(Optional.empty());
        when(integrations.findById(INTEGRATION_ID)).thenReturn(Optional.of(integration));
        when(integrations.addDestination(any(), anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            Integration updated = new Integration(integration);
            updated.getDestinations().removeIf(d -> invocation.getArgument(2).equals(d.getSourceDatabase()));
            updated.getDestinations().add(new Integration.Destination(invocation.getArgument(1),
                    invocation.getArgument(2), Integration.Destination.STATE_SYNCING, null, "load-1"));
            return updated;
        });
        when(integrations.reserveDestination(any(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Integration updated = new Integration(integration);
            updated.getDestinations().removeIf(d -> invocation.getArgument(2).equals(d.getSourceDatabase()));
            updated.getDestinations().add(new Integration.Destination(invocation.getArgument(1),
                    invocation.getArgument(2), Integration.Destination.STATE_CREATING, null, "load-1"));
            return updated;
        });
        when(integrations.startDestinationSync(any(), anyString(), any())).thenReturn(integration);
        when(redshiftService.clusterRegion(ACCOUNT, "orders-dw")).thenReturn(Optional.of("us-east-1"));
    }

    static SpectrumSession session(String clusterIdentifier) {
        return new SpectrumSession(ACCOUNT, ACCOUNT + ":" + clusterIdentifier, "dev", List.of(), false);
    }

    static ExternalStatement.CreateIntegrationDatabase statement(String sourceDatabase) {
        return statement("orders_dw", sourceDatabase);
    }

    static ExternalStatement.CreateIntegrationDatabase statement(String databaseName, String sourceDatabase) {
        return new ExternalStatement.CreateIntegrationDatabase(databaseName, INTEGRATION_ID, sourceDatabase);
    }
}

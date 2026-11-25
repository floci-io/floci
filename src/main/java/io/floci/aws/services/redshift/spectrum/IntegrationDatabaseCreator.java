package io.floci.aws.services.redshift.spectrum;

/**
 * Runs {@code CREATE DATABASE ... FROM INTEGRATION} for the integration the statement names: checks that the
 * integration replicates into the session's cluster or workgroup, creates the database on {@code backend}, and
 * starts copying the source into it. It fails with a {@link SpectrumSqlException} carrying the SQLSTATE the
 * client sees.
 */
public interface IntegrationDatabaseCreator {

    void create(ExternalStatement.CreateIntegrationDatabase statement, SpectrumSession session, BackendSql backend);
}

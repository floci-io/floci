package io.floci.aws.testing;

import io.floci.aws.services.dsql.ExternalPostgresResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SidecarContainersProfile}'s namespace and port with service settings its own classes
 * cannot take, for the container-backed classes that each rely on one of them. Quarkus builds the
 * application once per distinct profile class, and keeps every one it built for the rest of the
 * test JVM, so they share this one rather than each declaring their own.
 *
 * <p>The overrides, on top of the sidecar settings, and who relies on them:
 * <ul>
 *   <li>{@code floci.services.s3.enforce-auth=true} makes the S3 transfers a cluster runs, the
 *   floci-duck transfer of a Spectrum cold load and an UNLOAD, present the role's signed session
 *   instead of the account-scoped development credentials.
 *   {@code RedshiftSpectrumS3AuthEnforcedIntegrationTest} and
 *   {@code RedshiftUnloadParquetS3AuthIntegrationTest} rely on it.</li>
 *   <li>{@code floci.services.redshift.spectrum-lake-formation-enforcement=true} lets a Spectrum
 *   query read an external table only when Lake Formation lets the external schema's role select
 *   from it. {@code RedshiftSpectrumLakeFormationIntegrationTest} relies on it.</li>
 *   <li>{@link ExternalPostgresResource} starts a PostgreSQL server before Floci boots and makes it
 *   the DSQL backend, with {@code floci.services.dsql.mock=false}, so every cluster's database is
 *   on that one server instead of in a container of its own.
 *   {@code DsqlExternalBackendDockerNightlyIntegrationTest} relies on it. A server that does not start fails
 *   only that class: the resource records why, and the application boots anyway.</li>
 * </ul>
 *
 * <p>Each is inert where no class relies on it. Lake Formation leaves a table to IAM while the
 * account's Data Lake settings leave new tables to IAM, as AWS's defaults do, so the Spectrum
 * query of the S3 class passes it. The Lake Formation class takes new tables away from IAM to
 * check its grants, and puts AWS's defaults back once it is done. Its role may read the bucket it
 * queries, so the signed session it gets under S3 authentication reads the table as before. The
 * UNLOAD class reads no external table. The Redshift classes create no DSQL cluster, and the DSQL
 * class sends no S3 request and runs no Spectrum query. The DSQL classes on
 * {@link SidecarContainersProfile} run each cluster in a container of its own, the default
 * backend, which is why the external one is here rather than there.
 *
 * <p>A class joins if every setting here is either one it relies on or one that never reaches its
 * requests, and if any setting it adds never reaches the requests of a class already here.
 */
public class SidecarServiceSettingsProfile extends SidecarContainersProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(containerOverrides());
        overrides.put("floci.services.s3.enforce-auth", "true");
        overrides.put("floci.services.redshift.spectrum-lake-formation-enforcement", "true");
        return overrides;
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(ExternalPostgresResource.class));
    }
}

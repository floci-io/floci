package io.floci.aws.services.glue;

import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.glue.model.Connection;
import io.floci.aws.services.redshift.RedshiftJdbcTargets;
import io.floci.aws.services.redshift.RedshiftJdbcUrl;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GlueJobConnectionResolverTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=ASIAGLUE123/20261128/us-east-1/glue/aws4_request";
    private static final String CLUSTER_URL =
            "jdbc:redshift://orders.abc123.us-east-1.redshift.amazonaws.com:5439/dev";
    private static final String WORKGROUP_URL =
            "jdbc:redshift://analytics.000000000000.us-east-1.redshift-serverless.amazonaws.com:5439/dev?tcpKeepAlive=true";

    private final RedshiftJdbcTargets redshiftTargets = mock(RedshiftJdbcTargets.class);
    private final LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
    private final RegionResolver regionResolver = mock(RegionResolver.class);
    private final AccountResolver accountResolver = new AccountResolver("000000000000");

    private GlueJobConnectionResolver resolver() {
        when(awsEnv.flociEndpoint()).thenReturn("http://host.docker.internal:4566");
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getRegion()).thenReturn("us-east-1");
        return new GlueJobConnectionResolver(redshiftTargets, awsEnv, regionResolver, accountResolver);
    }

    private static Connection connection(String jdbcUrl) {
        Connection connection = new Connection();
        connection.setName("rs-conn");
        connection.setConnectionType("JDBC");
        Map<String, String> props = new LinkedHashMap<>();
        props.put("JDBC_CONNECTION_URL", jdbcUrl);
        props.put("USERNAME", "admin");
        props.put("PASSWORD", "Secret123");
        connection.setConnectionProperties(props);
        return connection;
    }

    @Test
    void leavesConnectionUnchangedForCallersOutsideActiveGlueJobs() {
        GlueJobConnectionResolver resolver = resolver();
        Connection stored = connection(CLUSTER_URL);

        Connection result = resolver.resolveForCaller(stored, AUTH, "us-east-1");

        assertSame(stored, result);
        verifyNoInteractions(redshiftTargets);
    }

    @Test
    void rewritesClusterUrlForActiveGlueJobSessionWithoutMutatingStoredConnection() {
        GlueJobConnectionResolver resolver = resolver();
        resolver.registerSession("ASIAGLUE123");
        when(redshiftTargets.resolve(eq("000000000000"), eq("us-east-1"), any(RedshiftJdbcUrl.class)))
                .thenReturn(new RedshiftJdbcTargets.Backend("cluster orders", "127.0.0.1", 54321, "dev", "admin"));
        Connection stored = connection(CLUSTER_URL);

        Connection resolved = resolver.resolveForCaller(stored, AUTH, "us-east-1");

        assertEquals("jdbc:redshift://host.docker.internal:54321/dev?ssl=false",
                resolved.getConnectionProperties().get("JDBC_CONNECTION_URL"));
        assertEquals(CLUSTER_URL, stored.getConnectionProperties().get("JDBC_CONNECTION_URL"));

        resolver.unregisterSession("ASIAGLUE123");
        assertSame(stored, resolver.resolveForCaller(stored, AUTH, "us-east-1"));
    }

    @Test
    void preservesExistingQueryParametersWhenRewritingServerlessUrl() {
        GlueJobConnectionResolver resolver = resolver();
        resolver.registerSession("ASIAGLUE123");
        when(redshiftTargets.resolve(eq("000000000000"), eq("us-east-1"), any(RedshiftJdbcUrl.class)))
                .thenReturn(new RedshiftJdbcTargets.Backend("workgroup analytics", "127.0.0.1", 55432, "dev", "admin"));
        Connection stored = connection(WORKGROUP_URL);

        Connection resolved = resolver.resolveForCaller(stored, AUTH, "us-east-1");

        assertEquals("jdbc:redshift://host.docker.internal:55432/dev?tcpKeepAlive=true&ssl=false",
                resolved.getConnectionProperties().get("JDBC_CONNECTION_URL"));
    }

    @Test
    void doesNotDuplicateSslParameterWhenAlreadyPresent() {
        assertEquals("jdbc:redshift://floci:54321/dev?ssl=true",
                GlueJobConnectionResolver.containerJdbcUrl("jdbc:redshift://x:5439/dev?ssl=true",
                        "floci", 54321, "dev"));
    }

    @Test
    void leavesNonRedshiftAndUnresolvableConnectionsUnchanged() {
        GlueJobConnectionResolver resolver = resolver();
        resolver.registerSession("ASIAGLUE123");
        Connection postgres = connection("jdbc:postgresql://db.example.com:5432/app");
        assertSame(postgres, resolver.resolveForCaller(postgres, AUTH, "us-east-1"));

        when(redshiftTargets.resolve(eq("000000000000"), eq("us-east-1"), any(RedshiftJdbcUrl.class)))
                .thenThrow(new AwsException("ClusterNotFound", "Cluster not found", 404));
        Connection missing = connection(CLUSTER_URL);
        assertSame(missing, resolver.resolveForCaller(missing, AUTH, "us-east-1"));
    }
}

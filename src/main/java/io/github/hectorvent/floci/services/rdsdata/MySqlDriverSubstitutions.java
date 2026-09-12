package io.github.hectorvent.floci.services.rdsdata;

import com.mysql.cj.Messages;
import com.mysql.cj.conf.ConnectionUrl;
import com.mysql.cj.exceptions.ExceptionFactory;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.ha.LoadBalancedConnection;
import com.mysql.cj.jdbc.ha.ReplicationConnection;
import com.mysql.cj.protocol.a.NativePacketPayload;
import com.mysql.cj.telemetry.TelemetryHandler;
import com.mysql.cj.telemetry.TelemetrySpan;
import com.mysql.cj.telemetry.TelemetrySpanName;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.sql.SQLException;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Native image substitutions for MySQL Connector/J, mirroring the quarkus-jdbc-mysql extension.
 * The OCI and OpenTelemetry references point at libraries that are not on the classpath, and the
 * native build links every reachable class at build time. JMX registration, Kerberos and LDAP SASL
 * authentication cannot be used against the databases Floci provisions, and leaving them reachable
 * pulls the JMX server, GSS and SASL parts of the JDK into the image. Floci only opens single-host
 * connections, so the failover, load-balanced and replication URL forms are cut as well.
 */
final class MySqlDriverSubstitutions {

    static final String UNAVAILABLE = " is not available in the native image";

    private MySqlDriverSubstitutions() {
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.ConnectionGroupManager")
final class Target_com_mysql_cj_jdbc_ConnectionGroupManager {

    @Substitute
    public static void registerJmx() throws SQLException {
        throw new IllegalStateException("JMX" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.jmx.LoadBalanceConnectionGroupManager")
final class Target_com_mysql_cj_jdbc_jmx_LoadBalanceConnectionGroupManager {

    @Substitute
    public synchronized void registerJmx() throws SQLException {
        throw new IllegalStateException("JMX" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.jmx.ReplicationGroupManager")
final class Target_com_mysql_cj_jdbc_jmx_ReplicationGroupManager {

    @Substitute
    public synchronized void registerJmx() throws SQLException {
        throw new IllegalStateException("JMX" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.ha.ReplicationConnectionGroupManager")
final class Target_com_mysql_cj_jdbc_ha_ReplicationConnectionGroupManager {

    @Substitute
    public static void registerJmx() throws SQLException {
        throw new IllegalStateException("JMX" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.protocol.a.authentication.AuthenticationKerberosClient")
final class Target_com_mysql_cj_protocol_a_authentication_AuthenticationKerberosClient {

    @Substitute
    public boolean nextAuthenticationStep(NativePacketPayload fromServer, List<NativePacketPayload> toServer) {
        throw ExceptionFactory.createException("Kerberos authentication" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.protocol.a.authentication.AuthenticationLdapSaslClientPlugin")
final class Target_com_mysql_cj_protocol_a_authentication_AuthenticationLdapSaslClientPlugin {

    @Substitute
    public boolean nextAuthenticationStep(NativePacketPayload fromServer, List<NativePacketPayload> toServer) {
        throw ExceptionFactory.createException("LDAP SASL authentication" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.protocol.a.authentication.AuthenticationOciClient")
final class Target_com_mysql_cj_protocol_a_authentication_AuthenticationOciClient {

    @Substitute
    private void loadOciConfig() {
        throw ExceptionFactory.createException("OCI authentication" + MySqlDriverSubstitutions.UNAVAILABLE);
    }

    @Substitute
    private void initializePrivateKey() {
        throw ExceptionFactory.createException("OCI authentication" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.otel.OpenTelemetryHandler", onlyWith = OpenTelemetryUnavailable.class)
final class Target_com_mysql_cj_otel_OpenTelemetryHandler implements TelemetryHandler {

    @Substitute
    Target_com_mysql_cj_otel_OpenTelemetryHandler() {
        throw ExceptionFactory.createException(Messages.getString("Connection.OtelApiNotFound"));
    }

    @Override
    @Substitute
    public TelemetrySpan startSpan(TelemetrySpanName spanName, Object... args) {
        return null;
    }

    @Override
    @Substitute
    public void addLinkTarget(TelemetrySpan span) {
    }

    @Override
    @Substitute
    public void removeLinkTarget(TelemetrySpan span) {
    }

    @Override
    @Substitute
    public void propagateContext(BiConsumer<String, String> traceparentConsumer) {
    }
}

final class OpenTelemetryUnavailable implements BooleanSupplier {

    @Override
    public boolean getAsBoolean() {
        try {
            Class.forName("io.opentelemetry.api.GlobalOpenTelemetry");
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.ha.FailoverConnectionProxy")
final class Target_com_mysql_cj_jdbc_ha_FailoverConnectionProxy {

    @Substitute
    public static JdbcConnection createProxyInstance(ConnectionUrl connectionUrl) throws SQLException {
        throw new SQLException("Failover connection URLs are" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.ha.LoadBalancedConnectionProxy")
final class Target_com_mysql_cj_jdbc_ha_LoadBalancedConnectionProxy {

    @Substitute
    public static LoadBalancedConnection createProxyInstance(ConnectionUrl connectionUrl) throws SQLException {
        throw new SQLException("Load-balanced connection URLs are" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

@TargetClass(className = "com.mysql.cj.jdbc.ha.ReplicationConnectionProxy")
final class Target_com_mysql_cj_jdbc_ha_ReplicationConnectionProxy {

    @Substitute
    public static ReplicationConnection createProxyInstance(ConnectionUrl connectionUrl) throws SQLException {
        throw new SQLException("Replication connection URLs are" + MySqlDriverSubstitutions.UNAVAILABLE);
    }
}

package io.github.hectorvent.floci.services.rdsdata;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.postgresql.core.PGStream;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

/**
 * Native image substitution for the PostgreSQL driver. GSS authentication cannot be used against
 * the databases Floci provisions, and leaving it reachable pulls the JDK GSS stack into the image.
 */
final class PostgresDriverSubstitutions {

    private PostgresDriverSubstitutions() {
    }
}

@TargetClass(className = "org.postgresql.gss.MakeGSS")
final class Target_org_postgresql_gss_MakeGSS {

    @Substitute
    public static void authenticate(boolean encrypted, PGStream pgStream, String host, String user, char[] password,
                                    String jaasApplicationName, String kerberosServerName, boolean useSpnego,
                                    boolean jaasLogin, boolean logServerErrorDetail, boolean gssUseDefaultCreds)
            throws PSQLException {
        throw new PSQLException("GSS authentication is not available in the native image",
                PSQLState.CONNECTION_REJECTED);
    }
}

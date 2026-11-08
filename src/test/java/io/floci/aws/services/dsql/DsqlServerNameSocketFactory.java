package io.floci.aws.services.dsql;

import org.postgresql.ssl.NonValidatingFactory;

import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Properties;

/**
 * pgjdbc's {@link NonValidatingFactory}, naming a DSQL cluster as the TLS server whatever address
 * pgjdbc dialled, as a client that resolves the cluster's endpoint does. pgjdbc builds it from the
 * connection's properties, which carry the name in {@link #SERVER_NAME}.
 */
public final class DsqlServerNameSocketFactory extends NonValidatingFactory {

    public static final String SERVER_NAME = "dsqlServerName";

    private final String serverName;

    public DsqlServerNameSocketFactory(Properties info) throws GeneralSecurityException {
        super(null);
        this.serverName = info.getProperty(SERVER_NAME);
    }

    @Override
    public Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
        return super.createSocket(socket, serverName, port, autoClose);
    }
}

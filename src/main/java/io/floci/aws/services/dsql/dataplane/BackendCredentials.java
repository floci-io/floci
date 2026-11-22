package io.floci.aws.services.dsql.dataplane;

/** The passwords the database expects of the roles Floci connects as. */
public interface BackendCredentials {

    /** A database that trusts its connections wants no password. */
    BackendCredentials TRUST = new BackendCredentials() {
        @Override
        public String passwordFor(String backendRole) {
            return null;
        }

        @Override
        public String verifierFor(String backendRole) {
            return null;
        }
    };

    /**
     * Whether the database stores only SCRAM-SHA-256 verifiers, so a login answers SCRAM alone and
     * refuses a cleartext or MD5 request as a downgrade.
     */
    default boolean scramOnly() {
        return false;
    }

    /** The password Floci logs in with as a backend role, or null when the database wants none. */
    String passwordFor(String backendRole);

    /**
     * The SCRAM-SHA-256 verifier of {@link #passwordFor}, as PostgreSQL stores it, for setting the
     * password without the password itself reaching the server's logs; null when it wants none.
     */
    String verifierFor(String backendRole);
}

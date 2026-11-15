package io.floci.aws.services.dsql.dataplane;

/** The passwords the database expects of the roles Floci connects as. */
public interface BackendCredentials {

    /** A database that trusts its connections wants no password. */
    BackendCredentials TRUST = role -> null;

    /** The password of a backend role, or null when the database wants none. */
    String passwordFor(String backendRole);
}

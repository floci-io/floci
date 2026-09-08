package io.github.hectorvent.floci.services.rds.proxy;

@FunctionalInterface
public interface PasswordValidator {

    AuthResult validate(String username, String password);

    /**
     * REJECT           : refuse the client.
     * PASSTHROUGH      : the proxy does not vouch for this user; forward the
     *                    client's own credentials so the backend enforces them
     *                    (legacy non-master behaviour).
     * MASTER_EQUIVALENT: the proxy has validated the client; open the backend
     *                    connection as the cluster master.
     */
    enum AuthResult { REJECT, PASSTHROUGH, MASTER_EQUIVALENT }
}

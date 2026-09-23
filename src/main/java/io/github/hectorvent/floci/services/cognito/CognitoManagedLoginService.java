package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoAuthorizationCode;
import io.github.hectorvent.floci.services.cognito.model.CognitoManagedLoginSession;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Managed login for a pool's own users: signs a user in with their password, keeps the browser's
 * session, and issues the authorization codes that {@code /oauth2/token} redeems.
 * {@link CognitoFederationService} does the same for users of an external identity provider.
 */
@ApplicationScoped
public class CognitoManagedLoginService {

    /** AWS: "The authorization code is valid for five minutes." */
    private static final Duration AUTHORIZATION_CODE_LIFETIME = Duration.ofMinutes(5);
    /** How long AWS keeps a managed login session. */
    static final Duration SESSION_LIFETIME = Duration.ofHours(1);

    private static final Logger LOG = Logger.getLogger(CognitoManagedLoginService.class);

    private final CognitoService cognitoService;
    private final CognitoFederationStateStore stateStore;
    private final Clock clock;

    @Inject
    public CognitoManagedLoginService(CognitoService cognitoService, CognitoFederationStateStore stateStore,
                                      Clock clock) {
        this.cognitoService = cognitoService;
        this.stateStore = stateStore;
        this.clock = clock;
    }

    /**
     * Signs a user in to the client's pool with their password and opens a session for them.
     *
     * @return the session's id, the value of the browser's session cookie
     * @throws AwsException when the user cannot sign in, with the reason in its message
     */
    public String signIn(UserPoolClient client, String username, String password) {
        CognitoUser user = cognitoService.authenticateManagedLogin(client, username, password);
        return stateStore.putSession(new CognitoManagedLoginSession(
                client.getUserPoolId(), user.getUsername(), clock.instant().plus(SESSION_LIFETIME)));
    }

    /**
     * Issues an authorization code to the user of the browser's session, or nothing when there is
     * no session for the client's pool. Every pool's session travels in the same cookie on Floci's
     * own host, so a session of one pool must never sign its user in to another.
     */
    public Optional<String> issueAuthorizationCode(String sessionId, UserPoolClient client, String redirectUri,
                                                   List<String> scopes, String nonce, String codeChallenge) {
        Optional<CognitoManagedLoginSession> session = stateStore.findSession(sessionId)
                .filter(candidate -> candidate.userPoolId().equals(client.getUserPoolId()));
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (!canStillSignIn(session.get())) {
            stateStore.deleteSession(sessionId);
            return Optional.empty();
        }
        CognitoAuthorizationCode code = new CognitoAuthorizationCode(client.getUserPoolId(), client.getClientId(),
                session.get().username(), redirectUri, scopes, nonce, codeChallenge,
                clock.instant().plus(AUTHORIZATION_CODE_LIFETIME));
        return Optional.of(stateStore.putAuthorizationCode(code));
    }

    /**
     * Ends the browser's session in the pool, and says whether its cookie can go too: not while it
     * holds a live session of another pool, which is not this pool's to end.
     */
    public boolean signOut(String sessionId, String userPoolId) {
        Optional<CognitoManagedLoginSession> session = stateStore.findSession(sessionId);
        if (session.isPresent() && !session.get().userPoolId().equals(userPoolId)) {
            return false;
        }
        stateStore.deleteSession(sessionId);
        return true;
    }

    /** A session outlives neither its user nor AdminDisableUser, after which the user cannot sign in. */
    private boolean canStillSignIn(CognitoManagedLoginSession session) {
        try {
            return cognitoService.adminGetUser(session.userPoolId(), session.username()).isEnabled();
        } catch (AwsException e) {
            LOG.debugv("Ending the managed login session of {0} in pool {1}: {2}",
                    session.username(), session.userPoolId(), e.getMessage());
            return false;
        }
    }
}

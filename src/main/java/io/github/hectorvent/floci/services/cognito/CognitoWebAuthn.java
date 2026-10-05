package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.AttestedCredentialDataConverter;
import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.credential.CredentialRecordImpl;
import com.webauthn4j.data.AuthenticationData;
import com.webauthn4j.data.AuthenticationParameters;
import com.webauthn4j.data.AuthenticationRequest;
import com.webauthn4j.data.AuthenticatorTransport;
import com.webauthn4j.data.PublicKeyCredentialParameters;
import com.webauthn4j.data.PublicKeyCredentialType;
import com.webauthn4j.data.RegistrationData;
import com.webauthn4j.data.RegistrationParameters;
import com.webauthn4j.data.RegistrationRequest;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.COSEKey;
import com.webauthn4j.data.attestation.authenticator.Curve;
import com.webauthn4j.data.attestation.statement.AttestationStatement;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.client.CollectedClientData;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs;
import com.webauthn4j.data.extension.client.AuthenticationExtensionsClientOutputs;
import com.webauthn4j.server.ServerProperty;
import com.webauthn4j.verifier.exception.BadChallengeException;
import com.webauthn4j.verifier.exception.BadOriginException;
import com.webauthn4j.verifier.exception.BadRpIdException;
import com.webauthn4j.verifier.exception.NotAllowedAlgorithmException;
import com.webauthn4j.verifier.exception.UserNotVerifiedException;
import com.webauthn4j.verifier.attestation.statement.androidkey.AndroidKeyAttestationStatementVerifier;
import com.webauthn4j.verifier.attestation.statement.apple.AppleAnonymousAttestationStatementVerifier;
import com.webauthn4j.verifier.attestation.statement.none.NoneAttestationStatementVerifier;
import com.webauthn4j.verifier.attestation.statement.packed.PackedAttestationStatementVerifier;
import com.webauthn4j.verifier.attestation.statement.tpm.TPMAttestationStatementVerifier;
import com.webauthn4j.verifier.attestation.statement.u2f.FIDOU2FAttestationStatementVerifier;
import com.webauthn4j.verifier.attestation.trustworthiness.certpath.NullCertPathTrustworthinessVerifier;
import com.webauthn4j.verifier.attestation.trustworthiness.self.NullSelfAttestationTrustworthinessVerifier;
import com.webauthn4j.verifier.exception.VerificationException;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.WebAuthnCredential;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passkey registration and sign-in checks for Cognito user pools, on webauthn4j.
 *
 * <p>Cognito accepts ES256 and RS256 passkeys and does not enforce attestation, so every
 * attestation format but the retired Android SafetyNet one is accepted once its statement's
 * signature checks out, without a trust check of its certificates: {@code none} and {@code packed}
 * self attestation included. The origin of a ceremony must be the relying party ID or one of its
 * subdomains, over HTTPS, or HTTP for a local page.
 *
 * <p>webauthn4j binds its data types with Jackson, so the native image registers their full
 * hierarchies (the {@code quarkus.index-dependency.webauthn4j} entry puts them in the index),
 * as Quarkus's own WebAuthn extension does.
 */
@RegisterForReflection(registerFullHierarchy = true, targets = {
        AttestationObject.class,
        AttestationStatement.class,
        AttestedCredentialData.class,
        AuthenticatorData.class,
        COSEKey.class,
        CollectedClientData.class,
        Curve.class,
        AuthenticationExtensionsAuthenticatorOutputs.class,
        AuthenticationExtensionsClientOutputs.class
}, classNames = {
        "com.webauthn4j.converter.jackson.serializer.cbor.CredentialProtectionPolicySerializer",
        "com.webauthn4j.converter.jackson.deserializer.cbor.CredentialProtectionPolicyDeserializer"
})
final class CognitoWebAuthn {

    private static final Logger LOG = Logger.getLogger(CognitoWebAuthn.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The most passkeys a user can register, as AWS documents. */
    static final int MAX_CREDENTIALS_PER_USER = 20;
    /** The {@code timeout} of AWS's creation options. */
    static final long REGISTRATION_TIMEOUT_MILLIS = 60_000L;
    private static final Duration REGISTRATION_CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    private static final List<PublicKeyCredentialParameters> SUPPORTED_ALGORITHMS = List.of(
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            new PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256));

    private final ObjectConverter objectConverter = new ObjectConverter();
    // Every format's statement signature is checked; no format's certificate chain is, since Cognito
    // does not enforce attestation.
    private final WebAuthnManager manager = new WebAuthnManager(List.of(
            new NoneAttestationStatementVerifier(),
            new PackedAttestationStatementVerifier(),
            new FIDOU2FAttestationStatementVerifier(),
            new AndroidKeyAttestationStatementVerifier(),
            new TPMAttestationStatementVerifier(),
            new AppleAnonymousAttestationStatementVerifier()),
            new NullCertPathTrustworthinessVerifier(), new NullSelfAttestationTrustworthinessVerifier(),
            objectConverter);
    private final AttestedCredentialDataConverter attestedCredentialDataConverter =
            new AttestedCredentialDataConverter(objectConverter);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ConcurrentHashMap<String, PendingRegistration> pendingRegistrations = new ConcurrentHashMap<>();

    private record PendingRegistration(byte[] challenge, String clientId, String relyingPartyId,
                                       boolean userVerificationRequired, Instant expiresAt) {}

    /** A verified sign-in: the new signature counter and whether the authenticator verified the user. */
    record Assertion(WebAuthnCredential credential, long signCount, boolean userVerified) {}

    CognitoWebAuthn(Clock clock) {
        this.clock = clock;
    }

    /**
     * Issues the {@code CredentialCreationOptions} of StartWebAuthnRegistration and remembers its
     * challenge for this user and app client. A later call replaces an earlier, unfinished one.
     */
    Map<String, Object> startRegistration(String poolId, String username, String userHandle, String clientId,
                                          String relyingPartyId, String userVerification,
                                          List<WebAuthnCredential> registered) {
        byte[] challenge = newChallenge();
        pendingRegistrations.put(registrationKey(poolId, username), new PendingRegistration(challenge, clientId,
                relyingPartyId, "required".equals(userVerification),
                clock.instant().plus(REGISTRATION_CHALLENGE_LIFETIME)));

        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("requireResidentKey", true);
        selection.put("residentKey", "required");
        selection.put("userVerification", userVerification);
        List<Map<String, Object>> exclude = new ArrayList<>();
        for (WebAuthnCredential credential : registered) {
            exclude.add(Map.of("id", credential.getCredentialId(), "type", "public-key"));
        }
        List<Map<String, Object>> algorithms = new ArrayList<>();
        for (PublicKeyCredentialParameters parameters : SUPPORTED_ALGORITHMS) {
            algorithms.add(Map.of("alg", parameters.getAlg().getValue(), "type", "public-key"));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("authenticatorSelection", selection);
        options.put("challenge", base64Url(challenge));
        options.put("excludeCredentials", exclude);
        options.put("pubKeyCredParams", algorithms);
        options.put("rp", Map.of("id", relyingPartyId, "name", relyingPartyId));
        options.put("timeout", REGISTRATION_TIMEOUT_MILLIS);
        options.put("user", Map.of(
                "displayName", username,
                "id", base64Url(userHandle.getBytes(StandardCharsets.UTF_8)),
                "name", username));
        return options;
    }

    /**
     * Verifies a {@code RegistrationResponseJSON} against the user's pending registration, which it
     * consumes, and returns the passkey to store.
     */
    WebAuthnCredential completeRegistration(String poolId, String username, String clientId, JsonNode credential) {
        PendingRegistration pending = pendingRegistrations.remove(registrationKey(poolId, username));
        if (pending == null || clock.instant().isAfter(pending.expiresAt())) {
            throw new AwsException("WebAuthnChallengeNotFoundException",
                    "The passkey registration challenge was not found or has expired.", 400);
        }
        if (!pending.clientId().equals(clientId)) {
            throw new AwsException("WebAuthnClientMismatchException",
                    "The access token is for a different app client than the one that started the registration.",
                    400);
        }
        JsonNode response = credential.path("response");
        byte[] clientDataJson = requiredBytes(response, "clientDataJSON");
        byte[] attestationObject = requiredBytes(response, "attestationObject");
        Set<String> transports = new LinkedHashSet<>();
        response.path("transports").forEach(transport -> transports.add(transport.asText()));

        RegistrationData data;
        try {
            data = manager.verify(new RegistrationRequest(attestationObject, clientDataJson, transports),
                    new RegistrationParameters(serverProperty(pending.relyingPartyId(), pending.challenge()),
                            SUPPORTED_ALGORITHMS, pending.userVerificationRequired(), true));
        } catch (DataConversionException e) {
            throw new AwsException("InvalidParameterException", "The passkey credential is malformed.", 400);
        } catch (BadOriginException e) {
            throw new AwsException("WebAuthnOriginNotAllowedException",
                    "The passkey credential's origin does not match the relying party ID "
                            + pending.relyingPartyId() + ".", 400);
        } catch (BadRpIdException e) {
            throw new AwsException("WebAuthnRelyingPartyMismatchException",
                    "The passkey credential is for a different relying party than " + pending.relyingPartyId() + ".",
                    400);
        } catch (BadChallengeException e) {
            throw new AwsException("WebAuthnChallengeNotFoundException",
                    "The passkey credential does not answer the registration challenge.", 400);
        } catch (NotAllowedAlgorithmException | UserNotVerifiedException e) {
            throw new AwsException("WebAuthnCredentialNotSupportedException",
                    "The passkey credential is not supported: " + e.getMessage(), 400);
        } catch (VerificationException e) {
            LOG.debugv(e, "Passkey registration failed verification for {0} in pool {1}", username, poolId);
            throw new AwsException("InvalidParameterException",
                    "The passkey credential failed verification: " + e.getMessage(), 400);
        }

        AuthenticatorData<?> authenticatorData = data.getAttestationObject().getAuthenticatorData();
        AttestedCredentialData attested = authenticatorData.getAttestedCredentialData();
        WebAuthnCredential stored = new WebAuthnCredential();
        stored.setCredentialId(base64Url(attested.getCredentialId()));
        stored.setAttestedCredentialData(base64Url(attestedCredentialDataConverter.convert(attested)));
        stored.setSignCount(authenticatorData.getSignCount());
        stored.setUserVerified(authenticatorData.isFlagUV());
        stored.setBackupEligible(authenticatorData.isFlagBE());
        stored.setBackupState(authenticatorData.isFlagBS());
        List<String> storedTransports = new ArrayList<>();
        if (data.getTransports() != null) {
            for (AuthenticatorTransport transport : data.getTransports()) {
                storedTransports.add(transport.getValue());
            }
        }
        stored.setTransports(storedTransports);
        String attachment = credential.path("authenticatorAttachment").asText(null);
        stored.setAuthenticatorAttachment(attachment);
        // AWS generates the name; Floci has no catalog of authenticator models to name a passkey after.
        stored.setFriendlyCredentialName("cross-platform".equals(attachment) ? "Roaming passkey" : "Passkey");
        stored.setRelyingPartyId(pending.relyingPartyId());
        stored.setCreatedAtMillis(clock.millis());
        return stored;
    }

    /** A fresh sign-in challenge for a {@code WEB_AUTHN} USER_AUTH challenge. */
    byte[] newChallenge() {
        byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        return challenge;
    }

    /**
     * The {@code CREDENTIAL_REQUEST_OPTIONS} challenge parameter: a {@code PublicKeyCredentialRequestOptionsJSON}
     * that allows the user's registered passkeys.
     */
    String requestOptions(String relyingPartyId, String userVerification, List<WebAuthnCredential> registered,
                          byte[] challenge, long timeoutMillis) {
        List<Map<String, Object>> allow = new ArrayList<>();
        for (WebAuthnCredential credential : registered) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("id", credential.getCredentialId());
            descriptor.put("type", "public-key");
            if (!credential.getTransports().isEmpty()) {
                descriptor.put("transports", credential.getTransports());
            }
            allow.add(descriptor);
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("challenge", base64Url(challenge));
        options.put("timeout", timeoutMillis);
        options.put("rpId", relyingPartyId);
        options.put("allowCredentials", allow);
        options.put("userVerification", userVerification);
        try {
            return MAPPER.writeValueAsString(options);
        } catch (JsonProcessingException e) {
            throw new AwsException("InternalErrorException", "Failed to build the passkey request options", 500);
        }
    }

    /**
     * Verifies an {@code AuthenticationResponseJSON} against the challenge of a {@code WEB_AUTHN}
     * USER_AUTH challenge and the user's registered passkeys. Any failure is a failed sign-in.
     */
    Assertion verifyAssertion(String relyingPartyId, boolean userVerificationRequired, byte[] challenge,
                              List<WebAuthnCredential> registered, String credentialJson) {
        JsonNode credential;
        try {
            credential = MAPPER.readTree(credentialJson);
        } catch (JsonProcessingException e) {
            throw new AwsException("InvalidParameterException", "CREDENTIAL is not valid JSON", 400);
        }
        if (credential == null || !credential.isObject()) {
            throw new AwsException("InvalidParameterException", "CREDENTIAL is not valid JSON", 400);
        }
        String credentialId = credential.path("rawId").asText(credential.path("id").asText(""));
        WebAuthnCredential stored = null;
        for (WebAuthnCredential candidate : registered) {
            if (candidate.getCredentialId().equals(credentialId)) {
                stored = candidate;
                break;
            }
        }
        if (stored == null) {
            throw new AwsException("NotAuthorizedException", "The passkey is not registered for this user.", 400);
        }
        JsonNode response = credential.path("response");
        byte[] userHandle = response.hasNonNull("userHandle") ? decode(response.path("userHandle").asText()) : null;
        AuthenticationRequest request = new AuthenticationRequest(decode(credentialId), userHandle,
                requiredBytes(response, "authenticatorData"), requiredBytes(response, "clientDataJSON"), null,
                requiredBytes(response, "signature"));
        AttestedCredentialData attested = attestedCredentialDataConverter.convert(
                decode(stored.getAttestedCredentialData()));
        CredentialRecordImpl record = new CredentialRecordImpl(new NoneAttestationStatement(),
                stored.isUserVerified(), stored.getBackupEligible(), stored.getBackupState(), stored.getSignCount(),
                attested, null, null, null, null);
        AuthenticationData data;
        try {
            data = manager.verify(request, new AuthenticationParameters(serverProperty(relyingPartyId, challenge),
                    record, List.of(attested.getCredentialId()), userVerificationRequired, true));
        } catch (DataConversionException e) {
            throw new AwsException("InvalidParameterException", "CREDENTIAL is malformed", 400);
        } catch (VerificationException e) {
            LOG.debugv(e, "Passkey sign-in failed verification for credential {0}", credentialId);
            throw new AwsException("NotAuthorizedException", "Failed to verify the passkey: " + e.getMessage(), 400);
        }
        AuthenticatorData<?> authenticatorData = data.getAuthenticatorData();
        return new Assertion(stored, authenticatorData.getSignCount(), authenticatorData.isFlagUV());
    }

    private static ServerProperty serverProperty(String relyingPartyId, byte[] challenge) {
        return ServerProperty.builder()
                .originPredicate(origin -> originMatches(origin, relyingPartyId))
                .rpId(relyingPartyId)
                .challenge(new DefaultChallenge(challenge))
                .build();
    }

    /** The relying party ID or a subdomain of it, over HTTPS, or over HTTP as a local page uses. */
    static boolean originMatches(Origin origin, String relyingPartyId) {
        String scheme = origin.getScheme();
        String host = origin.getHost();
        if (host == null || !("https".equals(scheme) || "http".equals(scheme))) {
            return false;
        }
        return host.equals(relyingPartyId) || host.endsWith("." + relyingPartyId);
    }

    private static String registrationKey(String poolId, String username) {
        return poolId + "::" + username;
    }

    private static byte[] requiredBytes(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isEmpty()) {
            throw new AwsException("InvalidParameterException", "The passkey credential has no " + field, 400);
        }
        return decode(value);
    }

    private static byte[] decode(String base64Url) {
        try {
            return Base64.getUrlDecoder().decode(base64Url);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "The passkey credential is not base64url encoded",
                    400);
        }
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

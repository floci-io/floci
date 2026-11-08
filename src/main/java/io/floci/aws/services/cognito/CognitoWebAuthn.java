package io.floci.aws.services.cognito;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.DomainNames;
import io.floci.aws.core.common.PublicKeys;
import io.floci.aws.services.cognito.model.WebAuthnCredential;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passkey registration and sign-in checks for Cognito user pools.
 *
 * <p>Cognito accepts ES256 and RS256 passkeys and does not enforce attestation, so a registration's
 * attestation statement is never verified, whatever its format: the passkey comes from the
 * authenticator data alone. The origin of a ceremony must be the relying party ID or one of its
 * subdomains, over HTTPS, or HTTP for a local page.
 */
final class CognitoWebAuthn {

    private static final Logger LOG = Logger.getLogger(CognitoWebAuthn.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    /** The most passkeys a user can register, as AWS documents. */
    static final int MAX_CREDENTIALS_PER_USER = 20;
    /** The {@code timeout} of AWS's creation options. */
    static final long REGISTRATION_TIMEOUT_MILLIS = 60_000L;
    private static final Duration REGISTRATION_CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    /** The COSE algorithms Cognito recognizes: ES256 and RS256. */
    private static final int ES256 = -7;
    private static final int RS256 = -257;
    private static final List<Integer> SUPPORTED_ALGORITHMS = List.of(ES256, RS256);
    // COSE key labels: the key type and algorithm, then the EC2 or RSA key members.
    private static final String COSE_KEY_TYPE = "1";
    private static final String COSE_ALGORITHM = "3";
    private static final String COSE_EC2_CURVE = "-1";
    private static final String COSE_EC2_X = "-2";
    private static final String COSE_EC2_Y = "-3";
    private static final String COSE_RSA_MODULUS = "-1";
    private static final String COSE_RSA_EXPONENT = "-2";
    private static final int COSE_KEY_TYPE_EC2 = 2;
    private static final int COSE_KEY_TYPE_RSA = 3;
    private static final int COSE_CURVE_P256 = 1;
    // Authenticator data flags.
    private static final int FLAG_USER_PRESENT = 0x01;
    private static final int FLAG_USER_VERIFIED = 0x04;
    private static final int FLAG_BACKUP_ELIGIBLE = 0x08;
    private static final int FLAG_BACKUP_STATE = 0x10;
    private static final int FLAG_ATTESTED_CREDENTIAL_DATA = 0x40;
    /** The relying party ID hash, the flags and the signature counter. */
    private static final int AUTHENTICATOR_DATA_HEADER_LENGTH = 37;

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ConcurrentHashMap<String, PendingRegistration> pendingRegistrations = new ConcurrentHashMap<>();

    private record PendingRegistration(byte[] challenge, String clientId, String relyingPartyId,
                                       boolean userVerificationRequired, Instant expiresAt) {}

    /** A verified sign-in: the new signature counter and whether the authenticator verified the user. */
    record Assertion(WebAuthnCredential credential, long signCount, boolean userVerified) {}

    /** The authenticator data of a ceremony; {@code attested} is set when it carries a new passkey. */
    private record AuthenticatorData(byte[] relyingPartyIdHash, int flags, long signCount,
                                     AttestedCredential attested) {
        boolean flag(int mask) {
            return (flags & mask) != 0;
        }
    }

    /**
     * A passkey's attested credential data: its ID and COSE public key, and {@code encoded}, the
     * WebAuthn binary form of the three (AAGUID, ID and key) that a passkey is stored as.
     */
    private record AttestedCredential(byte[] credentialId, JsonNode publicKey, byte[] encoded) {}

    /** Why a ceremony failed. A registration answers each with its own error, a sign-in with NotAuthorized. */
    private enum Failure { MALFORMED, ORIGIN, RELYING_PARTY, CHALLENGE, NOT_SUPPORTED, INVALID }

    private static final class CeremonyException extends Exception {
        private final Failure failure;

        CeremonyException(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }
    }

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
        for (int algorithm : SUPPORTED_ALGORITHMS) {
            algorithms.add(Map.of("alg", algorithm, "type", "public-key"));
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("authenticatorSelection", selection);
        options.put("challenge", Base64Url.encode(challenge));
        options.put("excludeCredentials", exclude);
        options.put("pubKeyCredParams", algorithms);
        options.put("rp", Map.of("id", relyingPartyId, "name", relyingPartyId));
        options.put("timeout", REGISTRATION_TIMEOUT_MILLIS);
        options.put("user", Map.of(
                "displayName", username,
                "id", Base64Url.encode(userHandle),
                "name", username));
        return options;
    }

    /**
     * Verifies a {@code RegistrationResponseJSON} against the user's pending registration, which it
     * consumes, and returns the passkey to store.
     */
    WebAuthnCredential completeRegistration(String poolId, String username, String clientId, JsonNode credential) {
        String key = registrationKey(poolId, username);
        PendingRegistration pending = pendingRegistrations.get(key);
        if (pending == null || clock.instant().isAfter(pending.expiresAt())) {
            throw new AwsException("WebAuthnChallengeNotFoundException",
                    "The passkey registration challenge was not found or has expired.", 400);
        }
        // Another client's attempt leaves the registration for the client that started it.
        if (!pending.clientId().equals(clientId)) {
            throw new AwsException("WebAuthnClientMismatchException",
                    "The access token is for a different app client than the one that started the registration.",
                    400);
        }
        if (!pendingRegistrations.remove(key, pending)) {
            throw new AwsException("WebAuthnChallengeNotFoundException",
                    "The passkey registration challenge was not found or has expired.", 400);
        }
        JsonNode response = credential.path("response");
        byte[] clientDataJson = requiredBytes(response, "clientDataJSON");
        byte[] attestationObject = requiredBytes(response, "attestationObject");

        AuthenticatorData authenticatorData;
        try {
            authenticatorData = verifyRegistration(pending, clientDataJson, attestationObject);
        } catch (CeremonyException e) {
            LOG.debugv(e, "Passkey registration failed verification for {0} in pool {1}", username, poolId);
            throw registrationError(e, pending.relyingPartyId());
        }

        AttestedCredential attested = authenticatorData.attested();
        WebAuthnCredential stored = new WebAuthnCredential();
        stored.setCredentialId(Base64Url.encode(attested.credentialId()));
        stored.setAttestedCredentialData(Base64Url.encode(attested.encoded()));
        stored.setSignCount(authenticatorData.signCount());
        stored.setUserVerified(authenticatorData.flag(FLAG_USER_VERIFIED));
        stored.setBackupEligible(authenticatorData.flag(FLAG_BACKUP_ELIGIBLE));
        stored.setBackupState(authenticatorData.flag(FLAG_BACKUP_STATE));
        Set<String> transports = new LinkedHashSet<>();
        response.path("transports").forEach(transport -> transports.add(transport.asText()));
        stored.setTransports(new ArrayList<>(transports));
        String attachment = credential.path("authenticatorAttachment").asText(null);
        stored.setAuthenticatorAttachment(attachment);
        // AWS generates the name; Floci has no catalog of authenticator models to name a passkey after.
        stored.setFriendlyCredentialName("cross-platform".equals(attachment) ? "Roaming passkey" : "Passkey");
        stored.setRelyingPartyId(pending.relyingPartyId());
        stored.setCreatedAtMillis(clock.millis());
        return stored;
    }

    private static AuthenticatorData verifyRegistration(PendingRegistration pending, byte[] clientDataJson,
                                                        byte[] attestationObject) throws CeremonyException {
        verifyClientData(clientDataJson, "webauthn.create", pending.challenge(), pending.relyingPartyId());
        JsonNode attestation = decodeCbor(attestationObject, "attestationObject");
        if (!attestation.path("fmt").isTextual()) {
            throw new CeremonyException(Failure.MALFORMED, "attestationObject has no fmt");
        }
        AuthenticatorData authenticatorData = parseAuthenticatorData(cborBytes(attestation, "authData"));
        verifyAuthenticatorData(authenticatorData, pending.relyingPartyId(), pending.userVerificationRequired());
        if (authenticatorData.attested() == null) {
            throw new CeremonyException(Failure.INVALID, "the authenticator data has no attested credential data");
        }
        // Refuses a key of an algorithm Cognito does not recognize before it is stored.
        publicKey(authenticatorData.attested().publicKey());
        return authenticatorData;
    }

    private static AwsException registrationError(CeremonyException e, String relyingPartyId) {
        return switch (e.failure) {
            case MALFORMED -> new AwsException("InvalidParameterException",
                    "The passkey credential is malformed.", 400);
            case ORIGIN -> new AwsException("WebAuthnOriginNotAllowedException",
                    "The passkey credential's origin does not match the relying party ID " + relyingPartyId + ".",
                    400);
            case RELYING_PARTY -> new AwsException("WebAuthnRelyingPartyMismatchException",
                    "The passkey credential is for a different relying party than " + relyingPartyId + ".", 400);
            case CHALLENGE -> new AwsException("WebAuthnChallengeNotFoundException",
                    "The passkey credential does not answer the registration challenge.", 400);
            case NOT_SUPPORTED -> new AwsException("WebAuthnCredentialNotSupportedException",
                    "The passkey credential is not supported: " + e.getMessage(), 400);
            case INVALID -> new AwsException("InvalidParameterException",
                    "The passkey credential failed verification: " + e.getMessage(), 400);
        };
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
        options.put("challenge", Base64Url.encode(challenge));
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
        byte[] authenticatorData = requiredBytes(response, "authenticatorData");
        byte[] clientDataJson = requiredBytes(response, "clientDataJSON");
        byte[] signature = requiredBytes(response, "signature");
        AuthenticatorData verified;
        try {
            verified = verifySignIn(relyingPartyId, userVerificationRequired, challenge, stored, authenticatorData,
                    clientDataJson, signature);
        } catch (CeremonyException e) {
            if (e.failure == Failure.MALFORMED) {
                throw new AwsException("InvalidParameterException", "CREDENTIAL is malformed", 400);
            }
            LOG.debugv(e, "Passkey sign-in failed verification for credential {0}", credentialId);
            throw new AwsException("NotAuthorizedException", "Failed to verify the passkey: " + e.getMessage(), 400);
        }
        return new Assertion(stored, verified.signCount(), verified.flag(FLAG_USER_VERIFIED));
    }

    private static AuthenticatorData verifySignIn(String relyingPartyId, boolean userVerificationRequired,
                                                  byte[] challenge, WebAuthnCredential stored,
                                                  byte[] authenticatorData, byte[] clientDataJson,
                                                  byte[] signature) throws CeremonyException {
        verifyClientData(clientDataJson, "webauthn.get", challenge, relyingPartyId);
        AuthenticatorData parsed = parseAuthenticatorData(authenticatorData);
        verifyAuthenticatorData(parsed, relyingPartyId, userVerificationRequired);
        JsonNode publicKey = readAttestedCredential(decode(stored.getAttestedCredentialData()), 0).publicKey();
        if (!signatureMatches(publicKey, authenticatorData, Digests.sha256(clientDataJson), signature)) {
            throw new CeremonyException(Failure.INVALID, "the signature does not match the passkey");
        }
        // A counter that does not move past the stored one suggests a cloned authenticator; one that
        // stays at zero on both sides is an authenticator that keeps no counter.
        if ((parsed.signCount() != 0 || stored.getSignCount() != 0) && parsed.signCount() <= stored.getSignCount()) {
            throw new CeremonyException(Failure.INVALID, "the signature counter did not increase");
        }
        return parsed;
    }

    /** Checks a ceremony's {@code clientDataJSON}: its type, the challenge it answers and its origin. */
    private static void verifyClientData(byte[] clientDataJson, String type, byte[] challenge, String relyingPartyId)
            throws CeremonyException {
        JsonNode clientData;
        try {
            clientData = MAPPER.readTree(clientDataJson);
        } catch (IOException e) {
            throw new CeremonyException(Failure.MALFORMED, "clientDataJSON is not valid JSON");
        }
        if (clientData == null || !clientData.isObject()) {
            throw new CeremonyException(Failure.MALFORMED, "clientDataJSON is not a JSON object");
        }
        if (!type.equals(clientData.path("type").asText())) {
            throw new CeremonyException(Failure.INVALID, "clientDataJSON is not of type " + type);
        }
        if (!Base64Url.encode(challenge).equals(clientData.path("challenge").asText())) {
            throw new CeremonyException(Failure.CHALLENGE, "the credential does not answer the challenge");
        }
        if (!originMatches(clientData.path("origin").asText(""), relyingPartyId)) {
            throw new CeremonyException(Failure.ORIGIN, "the origin is not allowed for " + relyingPartyId);
        }
    }

    private static void verifyAuthenticatorData(AuthenticatorData authenticatorData, String relyingPartyId,
                                                boolean userVerificationRequired) throws CeremonyException {
        if (!MessageDigest.isEqual(authenticatorData.relyingPartyIdHash(),
                Digests.sha256(relyingPartyId.getBytes(StandardCharsets.UTF_8)))) {
            throw new CeremonyException(Failure.RELYING_PARTY, "the authenticator data is for another relying party");
        }
        if (!authenticatorData.flag(FLAG_USER_PRESENT)) {
            throw new CeremonyException(Failure.INVALID, "the authenticator did not check for user presence");
        }
        if (userVerificationRequired && !authenticatorData.flag(FLAG_USER_VERIFIED)) {
            throw new CeremonyException(Failure.NOT_SUPPORTED, "the authenticator did not verify the user");
        }
    }

    /**
     * Splits authenticator data: the relying party ID hash, the flags, the signature counter, and the
     * attested credential data when its flag is set. Extensions after them are not read.
     */
    private static AuthenticatorData parseAuthenticatorData(byte[] data) throws CeremonyException {
        if (data.length < AUTHENTICATOR_DATA_HEADER_LENGTH) {
            throw new CeremonyException(Failure.MALFORMED, "the authenticator data is too short");
        }
        int flags = data[32] & 0xFF;
        long signCount = ByteBuffer.wrap(data, 33, 4).getInt() & 0xFFFFFFFFL;
        AttestedCredential attested = (flags & FLAG_ATTESTED_CREDENTIAL_DATA) != 0
                ? readAttestedCredential(data, AUTHENTICATOR_DATA_HEADER_LENGTH)
                : null;
        return new AuthenticatorData(Arrays.copyOfRange(data, 0, 32), flags, signCount, attested);
    }

    /**
     * Reads attested credential data starting at {@code offset}: a 16-byte AAGUID, the credential ID
     * behind its 2-byte length, then the COSE public key, after which extensions may follow.
     */
    private static AttestedCredential readAttestedCredential(byte[] data, int offset) throws CeremonyException {
        int idOffset = offset + 18;
        if (data.length < idOffset) {
            throw new CeremonyException(Failure.MALFORMED, "the attested credential data is too short");
        }
        int idLength = ((data[offset + 16] & 0xFF) << 8) | (data[offset + 17] & 0xFF);
        int keyOffset = idOffset + idLength;
        if (data.length <= keyOffset) {
            throw new CeremonyException(Failure.MALFORMED, "the attested credential data has no public key");
        }
        JsonNode publicKey;
        int keyLength;
        try (JsonParser parser = CBOR_MAPPER.createParser(Arrays.copyOfRange(data, keyOffset, data.length))) {
            publicKey = CBOR_MAPPER.readTree(parser);
            keyLength = (int) parser.currentLocation().getByteOffset();
        } catch (IOException e) {
            throw new CeremonyException(Failure.MALFORMED, "the passkey's public key is not valid CBOR");
        }
        if (publicKey == null || !publicKey.isObject()) {
            throw new CeremonyException(Failure.MALFORMED, "the passkey's public key is not a COSE key");
        }
        return new AttestedCredential(Arrays.copyOfRange(data, idOffset, keyOffset), publicKey,
                Arrays.copyOfRange(data, offset, keyOffset + keyLength));
    }

    /** The ES256 or RS256 public key a COSE key holds; a key of any other algorithm is not supported. */
    private static PublicKey publicKey(JsonNode coseKey) throws CeremonyException {
        int keyType = coseKey.path(COSE_KEY_TYPE).asInt();
        int algorithm = coseKey.path(COSE_ALGORITHM).asInt();
        try {
            if (algorithm == ES256 && keyType == COSE_KEY_TYPE_EC2
                    && coseKey.path(COSE_EC2_CURVE).asInt() == COSE_CURVE_P256) {
                return PublicKeys.ec("secp256r1", new BigInteger(1, cborBytes(coseKey, COSE_EC2_X)),
                        new BigInteger(1, cborBytes(coseKey, COSE_EC2_Y)));
            }
            if (algorithm == RS256 && keyType == COSE_KEY_TYPE_RSA) {
                return PublicKeys.rsa(new BigInteger(1, cborBytes(coseKey, COSE_RSA_MODULUS)),
                        new BigInteger(1, cborBytes(coseKey, COSE_RSA_EXPONENT)));
            }
        } catch (GeneralSecurityException e) {
            throw new CeremonyException(Failure.MALFORMED, "the passkey's public key is not valid");
        }
        throw new CeremonyException(Failure.NOT_SUPPORTED,
                "COSE algorithm " + algorithm + " is not ES256 (-7) or RS256 (-257)");
    }

    /** Whether {@code signature} signs the authenticator data followed by the client data hash. */
    private static boolean signatureMatches(JsonNode coseKey, byte[] authenticatorData, byte[] clientDataHash,
                                            byte[] signature) throws CeremonyException {
        PublicKey key = publicKey(coseKey);
        String algorithm = coseKey.path(COSE_ALGORITHM).asInt() == ES256 ? "SHA256withECDSA" : "SHA256withRSA";
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(authenticatorData);
            verifier.update(clientDataHash);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            throw new CeremonyException(Failure.INVALID, "the signature is not a valid " + algorithm + " signature");
        }
    }

    /**
     * The relying party ID or a subdomain of it, over HTTPS, or over HTTP for a {@code localhost} page,
     * the one insecure origin browsers let run WebAuthn ceremonies.
     */
    static boolean originMatches(String origin, String relyingPartyId) {
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || !DomainNames.isWithin(host, relyingPartyId)) {
            return false;
        }
        String scheme = uri.getScheme();
        return "https".equals(scheme)
                || ("http".equals(scheme) && (host.equals("localhost") || host.endsWith(".localhost")));
    }

    private static JsonNode decodeCbor(byte[] data, String name) throws CeremonyException {
        JsonNode node;
        try {
            node = CBOR_MAPPER.readTree(data);
        } catch (IOException e) {
            throw new CeremonyException(Failure.MALFORMED, name + " is not valid CBOR");
        }
        if (node == null || !node.isObject()) {
            throw new CeremonyException(Failure.MALFORMED, name + " is not a CBOR map");
        }
        return node;
    }

    private static byte[] cborBytes(JsonNode map, String key) throws CeremonyException {
        JsonNode value = map.path(key);
        if (!value.isBinary()) {
            throw new CeremonyException(Failure.MALFORMED, key + " is not a byte string");
        }
        try {
            return value.binaryValue();
        } catch (IOException e) {
            throw new CeremonyException(Failure.MALFORMED, key + " is not a byte string");
        }
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
}

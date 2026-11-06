package io.floci.aws.core.common.auth;

import io.floci.aws.core.common.Digests;
import io.floci.aws.services.iam.IamService;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Temporary signing credentials for an authorized request to Floci's runtime-owned listener. */
public final class LocalAwsSigningSession implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private final IamService iam;
    private final String accountId;
    private final String accessKey = accessKey();
    private final String secret = randomToken(32);
    private final String token = randomToken(48);
    private final Instant expiration = Instant.now().plusSeconds(120);

    private LocalAwsSigningSession(IamService iam, String accountId) {
        this.iam = iam;
        this.accountId = accountId;
    }

    public static LocalAwsSigningSession forAccount(IamService iam, String accountId, String roleArn) {
        return open(iam, accountId, session -> iam.registerSessionForAccount(accountId, session.accessKey,
                session.secret, session.token, roleArn, session.expiration, null));
    }

    /** The caller's request scope must be the role's account, as for IAM's assumed-role registration. */
    public static LocalAwsSigningSession assumedRole(IamService iam, String accountId, String roleArn,
                                                      String sessionName, String assumedRoleId) {
        return open(iam, accountId, session -> iam.registerSession(session.accessKey, session.secret, session.token,
                roleArn, session.expiration, null, accountId, sessionName, assumedRoleId));
    }

    private static LocalAwsSigningSession open(IamService iam, String accountId, Consumer<LocalAwsSigningSession> register) {
        LocalAwsSigningSession session = new LocalAwsSigningSession(iam, accountId);
        try {
            register.accept(session);
            return session;
        } catch (RuntimeException | Error failure) {
            try {
                session.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Input header names are already lowercase and validated by the invoking service. */
    public Map<String, List<String>> sign(String method, URI uri, byte[] payload, String region, String service,
                                          Map<String, List<String>> inputHeaders) throws Exception {
        TreeMap<String, List<String>> headers = new TreeMap<>(inputHeaders);
        headers.remove("authorization");
        String stamp = AMZ_DATE.format(Instant.now());
        headers.put("host", List.of(uri.getRawAuthority()));
        headers.put("x-amz-date", List.of(stamp));
        headers.put("x-amz-security-token", List.of(token));
        String signedHeaders = String.join(";", headers.keySet());
        CredentialScope scope = new CredentialScope(accessKey, stamp.substring(0, 8), region, service);
        String canonical = method + "\n" + uri.getRawPath() + "\n"
                + SigV4Canonicalization.canonicalQueryString(uri.getRawQuery(), false) + "\n"
                + SigV4Canonicalization.canonicalHeaders(signedHeaders, name -> headers.get(name).stream()
                        .map(SigV4RequestValidator::normalizeHeaderValue).collect(Collectors.joining(","))) + "\n"
                + signedHeaders + "\n" + Digests.sha256Hex(payload);
        String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + scope.credentialScope() + "\n" + Digests.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, scope.date(), region, service), toSign));
        headers.put("authorization", List.of("AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope.credentialScope()
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature));
        return headers;
    }

    @Override
    public void close() {
        iam.unregisterSession(accountId, accessKey);
    }

    private static String accessKey() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder key = new StringBuilder("ASIA");
        for (int i = 0; i < 16; i++) {
            key.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return key.toString();
    }

    private static String randomToken(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}

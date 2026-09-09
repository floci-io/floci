package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Mints EKS bearer tokens the way {@code aws eks get-token} does: a presigned
 * {@code sts:GetCallerIdentity} URL with an {@code x-k8s-aws-id} header bound into the
 * signature, base64url-encoded and prefixed with {@code k8s-aws-v1.}. This is the format
 * the {@code aws-iam-authenticator} webhook that fronts EKS clusters expects; see
 * https://github.com/kubernetes-sigs/aws-iam-authenticator's token generator for the reference
 * implementation this mirrors: a 60-second {@code X-Amz-Expires} (STS ignores it for this
 * action; real validity is a verifier-enforced 15 minutes off {@code X-Amz-Date}), refreshed
 * a minute early for clock-skew slack.
 *
 * <p>Lets the kubernetes Lambda executor reach a real EKS cluster without shelling out to
 * the AWS CLI or an exec-credential plugin process, keeping {@link KubernetesApiClient}
 * dependency-free (see its class javadoc for why that matters).
 *
 * <p>Only {@code eks get-token --cluster-name <name>} is recognized, as {@code aws eks
 * update-kubeconfig} actually generates it: {@code get-token} need not be the first two
 * arguments (a global {@code --region} always precedes it in a generated kubeconfig, and
 * {@code --output json} follows), so recognition scans for that pair anywhere in the args
 * list rather than requiring it at a fixed position. {@code --role}/{@code --role-arn}
 * (assume-role before minting, in either the subcommand's own long form or the abbreviated
 * form {@code update-kubeconfig --role-arn} actually writes) and {@code aws-iam-authenticator
 * token} are not supported and are rejected explicitly rather than silently mishandled.
 *
 * <p>Credentials are resolved from {@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY}/
 * {@code AWS_SESSION_TOKEN}, else the {@code AWS_PROFILE} (default {@code default}) section of
 * the shared credentials file ({@code AWS_SHARED_CREDENTIALS_FILE}, else {@code
 * ~/.aws/credentials}). Unlike plain env-var precedence, an {@code AWS_PROFILE} the kubeconfig
 * exec block's own {@code env} entries name explicitly (how {@code aws eks update-kubeconfig
 * --profile <p>} selects one) is honored ahead of static keys that merely happen to be ambient
 * in Floci's own process; only keys the exec block itself sets take precedence over that
 * profile. The SSO/IMDS/container-credentials/IRSA legs of the SDK's default chain are not
 * implemented.
 */
final class EksTokenMinter {

    private static final String CLUSTER_ID_HEADER = "x-k8s-aws-id";
    private static final String TOKEN_PREFIX = "k8s-aws-v1.";
    /**
     * The {@code X-Amz-Expires} query value, matching aws-iam-authenticator's token generator.
     * STS currently ignores this value for {@code GetCallerIdentity}; the token's real validity
     * is {@link #TOKEN_VALIDITY_SECONDS}, enforced by the verifying webhook off {@code X-Amz-Date}
     * instead. Kept at parity with the reference implementation rather than raised, since a
     * larger value here has no verified effect and isn't what real callers actually send.
     */
    private static final int PRESIGN_QUERY_EXPIRES_SECONDS = 60;
    /** Real token validity: aws-iam-authenticator's verifier treats every token as good for 15 minutes. */
    private static final int TOKEN_VALIDITY_SECONDS = 15 * 60;
    /** Re-mint this long before the token's real validity would expire, for clock-skew slack. */
    private static final int REFRESH_MARGIN_SECONDS = 60;
    private static final String EMPTY_BODY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private EksTokenMinter() {
    }

    /**
     * Returns a caching token supplier if {@code exec} is a recognized {@code aws eks get-token}
     * invocation, or empty if it is some other exec/auth-provider plugin this class does not
     * handle. When the shape is recognized but a required piece (cluster name, region) is
     * missing or unsupported ({@code --role-arn}), throws immediately rather than returning
     * empty, so the caller gets a specific error instead of the generic "exec not supported" one.
     */
    static Optional<Supplier<String>> tokenSupplierIfRecognized(JsonNode exec) {
        if (!isAwsEksGetToken(exec)) {
            return Optional.empty();
        }
        var execEnv = execEnv(exec);
        UnaryOperator<String> env = name -> execEnv.getOrDefault(name, System.getenv(name));
        var args = parseArgs(exec, env);
        return Optional.of(new CachingTokenSupplier(args.clusterName(), args.region(), execEnv));
    }

    /**
     * Kubeconfig lets an exec plugin declare extra environment variables under {@code exec.env}
     * (a list of {@code {name, value}} pairs). This is how {@code aws eks update-kubeconfig
     * --profile <p>} propagates the profile it was run with, since the exec plugin would
     * otherwise be a subprocess with no reason to inherit it. We don't spawn a subprocess, but
     * we still need to honor these entries ahead of Floci's own process environment, or a
     * profile-scoped kubeconfig silently resolves the wrong AWS identity.
     */
    private static Map<String, String> execEnv(JsonNode exec) {
        var envNode = exec.path("env");
        if (!envNode.isArray()) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : envNode) {
            var name = entry.path("name").asText(null);
            var value = entry.path("value").asText(null);
            if (name != null && value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    static boolean isAwsEksGetToken(JsonNode exec) {
        var command = exec.path("command").asText("");
        if (command.isBlank()) {
            return false;
        }
        var fileName = Path.of(command).getFileName();
        var commandName = fileName == null ? command : fileName.toString();
        if (!"aws".equals(commandName)) {
            return false;
        }
        return findSubcommandIndex(exec.path("args")) >= 0;
    }

    /**
     * Finds {@code "eks", "get-token"} as a consecutive pair anywhere in {@code args}, not just
     * at the front. {@code aws eks update-kubeconfig} always generates {@code --region <region>
     * eks get-token --cluster-name <name> --output json}, i.e. a global option ahead of the
     * subcommand (confirmed against the AWS CLI's own {@code update_kubeconfig.py}), so a
     * strictly-positional check would reject every kubeconfig that tool actually produces.
     */
    private static int findSubcommandIndex(JsonNode args) {
        if (!args.isArray()) {
            return -1;
        }
        for (var i = 0; i + 1 < args.size(); i++) {
            if ("eks".equals(args.get(i).asText()) && "get-token".equals(args.get(i + 1).asText())) {
                return i;
            }
        }
        return -1;
    }

    record ExecArgs(String clusterName, String region) {
    }

    /**
     * Assumes {@link #isAwsEksGetToken(JsonNode)} already returned true for {@code exec}.
     * {@code env} is injected (rather than reading {@link System#getenv(String)} directly) so
     * the region fallback is deterministically testable regardless of the host's own environment.
     */
    static ExecArgs parseArgs(JsonNode exec, UnaryOperator<String> env) {
        var args = exec.path("args");
        var subcommandIndex = findSubcommandIndex(args);
        String clusterName = null;
        String region = null;
        for (var i = 0; i < args.size(); i++) {
            if (i == subcommandIndex || i == subcommandIndex + 1) {
                continue;
            }
            var arg = args.get(i).asText();
            if (isRoleOption(arg)) {
                throw new IllegalStateException(
                        "kubeconfig exec plugin 'aws eks get-token --role-arn' is not supported: the "
                                + "kubernetes Lambda executor only mints a token for its own resolved "
                                + "credentials, not an assumed role. Point --cluster-name at credentials "
                                + "that already have cluster access.");
            }
            if (("--cluster-name".equals(arg) || "--region".equals(arg)) && i + 1 < args.size()) {
                var value = args.get(++i).asText();
                if ("--cluster-name".equals(arg)) {
                    clusterName = value;
                } else {
                    region = value;
                }
            }
        }
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalStateException(
                    "kubeconfig exec plugin 'aws eks get-token' has no --cluster-name argument");
        }
        if (region == null || region.isBlank()) {
            region = env.apply("AWS_REGION");
        }
        if (region == null || region.isBlank()) {
            region = env.apply("AWS_DEFAULT_REGION");
        }
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "kubeconfig exec plugin 'aws eks get-token --cluster-name " + clusterName
                            + "' has no region: pass --region, or set AWS_REGION/AWS_DEFAULT_REGION.");
        }
        return new ExecArgs(clusterName, region);
    }

    /**
     * {@code aws eks get-token}'s own flag is {@code --role-arn}, but {@code aws eks
     * update-kubeconfig --role-arn <arn>} writes the abbreviated {@code --role} into the
     * kubeconfig it generates (confirmed against the AWS CLI source). More generally, AWS CLI's
     * argument parser accepts any unambiguous prefix of a long option, and {@code role-arn} is
     * the only {@code get-token} option starting with "role", so {@code --role-a} and
     * {@code --role-ar} are just as valid to a real invocation as {@code --role} itself. Matching
     * only the one abbreviation actually seen in generated output, rather than the full prefix
     * range, would leave the others free to silently mint a token with ambient or profile
     * credentials instead of the requested role: a wrong-identity bug, not a merely
     * unsupported-shape one. So every prefix from {@code --role} through {@code --role-arn},
     * with or without a {@code --flag=value} suffix, is rejected explicitly.
     */
    private static boolean isRoleOption(String arg) {
        var name = arg.split("=", 2)[0];
        return name.length() >= "--role".length() && "--role-arn".startsWith(name);
    }

    private static final class CachingTokenSupplier implements Supplier<String> {
        private final String clusterName;
        private final String region;
        private final Map<String, String> execEnv;
        private String token;
        private Instant expiresAt = Instant.EPOCH;

        private CachingTokenSupplier(String clusterName, String region, Map<String, String> execEnv) {
            this.clusterName = clusterName;
            this.region = region;
            this.execEnv = execEnv;
        }

        @Override
        public synchronized String get() {
            var now = Instant.now();
            if (token == null || !now.isBefore(expiresAt.minusSeconds(REFRESH_MARGIN_SECONDS))) {
                token = mint(clusterName, region, execEnv);
                expiresAt = now.plusSeconds(TOKEN_VALIDITY_SECONDS);
            }
            return token;
        }
    }

    private static String mint(String clusterName, String region, Map<String, String> execEnv) {
        return mint(clusterName, region, resolveCredentials(execEnv, System::getenv), Clock.systemUTC());
    }

    /** Package-visible so the signing math is testable with fixed credentials, no env/file I/O. */
    static String mint(String clusterName, String region, AwsCredentials credentials) {
        return mint(clusterName, region, credentials, Clock.systemUTC());
    }

    /**
     * Package-visible so the signing math is testable against a fixed, independently-verifiable
     * timestamp: with a real clock, X-Amz-Date changes every call, so the resulting signature
     * can only ever be checked for shape, never against a hard-coded expected value.
     */
    static String mint(String clusterName, String region, AwsCredentials credentials, Clock clock) {
        var now = clock.instant();
        var amzDate = AMZ_DATE.format(now);
        var dateStamp = AMZ_DATE_STAMP.format(now);
        var host = stsHost(region);
        var credentialScope = dateStamp + "/" + region + "/sts/aws4_request";
        var signedHeaders = "host;" + CLUSTER_ID_HEADER;

        var queryParams = new TreeMap<String, String>();
        queryParams.put("Action", "GetCallerIdentity");
        queryParams.put("Version", "2011-06-15");
        queryParams.put("X-Amz-Algorithm", "AWS4-HMAC-SHA256");
        queryParams.put("X-Amz-Credential", credentials.accessKeyId() + "/" + credentialScope);
        queryParams.put("X-Amz-Date", amzDate);
        queryParams.put("X-Amz-Expires", String.valueOf(PRESIGN_QUERY_EXPIRES_SECONDS));
        queryParams.put("X-Amz-SignedHeaders", signedHeaders);
        if (credentials.sessionToken() != null && !credentials.sessionToken().isBlank()) {
            queryParams.put("X-Amz-Security-Token", credentials.sessionToken());
        }

        var canonicalQueryString = queryParams.entrySet().stream()
                .map(e -> uriEncode(e.getKey()) + "=" + uriEncode(e.getValue()))
                .collect(Collectors.joining("&"));

        // Sorted by header name ("host" < "x-k8s-aws-id"), matching X-Amz-SignedHeaders above.
        var canonicalHeaders = "host:" + host + "\n" + CLUSTER_ID_HEADER + ":" + clusterName + "\n";
        var canonicalRequest = "GET\n/\n" + canonicalQueryString + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + EMPTY_BODY_SHA256;

        var stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);
        var signingKey = deriveSigningKey(credentials.secretAccessKey(), dateStamp, region, "sts");
        var signature = hexEncode(hmacSha256(signingKey, stringToSign));

        var url = "https://" + host + "/?" + canonicalQueryString + "&X-Amz-Signature=" + signature;
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(url.getBytes(StandardCharsets.UTF_8));
    }

    record AwsCredentials(String accessKeyId, String secretAccessKey, String sessionToken) {
    }

    /** Maps a region to its STS hostname, honoring the region's actual partition. */
    private static String stsHost(String region) {
        return "sts." + region + "." + partitionDnsSuffix(region);
    }

    /**
     * Sourced from the AWS SDKs' own public partition metadata (e.g. botocore's
     * {@code partitions.json}, MIT-licensed and openly published at
     * https://github.com/boto/botocore/blob/develop/botocore/data/partitions.json), rather than
     * assumed. Covers every partition that metadata currently defines: the commercial
     * {@code aws} partition and {@code aws-us-gov} share {@code amazonaws.com} so neither needs
     * a case of its own, {@code aws-cn} is the one commonly-hit split, and the classified
     * ISO/ISOB/ISOE/ISOF partitions (regions matching {@code us-iso-*}, {@code us-isob-*},
     * {@code eu-isoe-*}, {@code us-isof-*}) each resolve under their own distinct domain.
     */
    private static String partitionDnsSuffix(String region) {
        if (region.matches("cn-\\w+-\\d+")) {
            return "amazonaws.com.cn";
        }
        if (region.matches("us-iso-\\w+-\\d+")) {
            return "c2s.ic.gov";
        }
        if (region.matches("us-isob-\\w+-\\d+")) {
            return "sc2s.sgov.gov";
        }
        if (region.matches("eu-isoe-\\w+-\\d+")) {
            return "cloud.adc-e.uk";
        }
        if (region.matches("us-isof-\\w+-\\d+")) {
            return "csp.hci.ic.gov";
        }
        if (region.matches("eusc-de-\\w+-\\d+")) {
            return "amazonaws.eu";
        }
        return "amazonaws.com";
    }

    /**
     * {@code execEnv} (the kubeconfig exec block's own {@code env} entries, not merged with
     * Floci's process environment) is consulted as a unit before falling back to plain env-var
     * precedence. {@code aws eks update-kubeconfig --profile <p>} selects a profile by writing
     * exactly {@code AWS_PROFILE} into exec.env, a specific instruction for this resolution; if
     * static keys that merely happen to be ambient in Floci's own process were allowed to
     * outrank it (as flat "env vars beat profile" precedence would normally do), a profile-scoped
     * kubeconfig would silently authenticate as the wrong principal instead of failing loudly or
     * using the profile it named.
     */
    static AwsCredentials resolveCredentials(Map<String, String> execEnv, UnaryOperator<String> ambientEnv) {
        UnaryOperator<String> env = name -> execEnv.getOrDefault(name, ambientEnv.apply(name));

        if (hasPairedKeys("exec.env", execEnv.get("AWS_ACCESS_KEY_ID"), execEnv.get("AWS_SECRET_ACCESS_KEY"))) {
            // The session token must come from exec.env too, not the merged (execEnv-or-ambient)
            // lookup: a session token is only valid for the specific access/secret key pair STS
            // issued it alongside, so pairing exec.env's static keys with an unrelated session
            // token that merely happens to be ambient in Floci's own process would sign with a
            // key/token combination STS never issued together, and reject the request.
            return new AwsCredentials(execEnv.get("AWS_ACCESS_KEY_ID"), execEnv.get("AWS_SECRET_ACCESS_KEY"),
                    execEnv.get("AWS_SESSION_TOKEN"));
        }
        if (execEnv.containsKey("AWS_PROFILE")) {
            return resolveFromSharedCredentialsFile(env, execEnv.get("AWS_PROFILE"));
        }

        // execEnv has neither key at this point (the check above already rejected exactly one
        // being present), so these are necessarily ambient-sourced; the session token must be
        // too, for the same reason as the exec.env branch above.
        var accessKeyId = ambientEnv.apply("AWS_ACCESS_KEY_ID");
        var secretAccessKey = ambientEnv.apply("AWS_SECRET_ACCESS_KEY");
        if (hasPairedKeys("the environment", accessKeyId, secretAccessKey)) {
            return new AwsCredentials(accessKeyId, secretAccessKey, ambientEnv.apply("AWS_SESSION_TOKEN"));
        }
        return resolveFromSharedCredentialsFile(env, env.apply("AWS_PROFILE"));
    }

    /**
     * True if both keys are present, false if both are absent, and throws if exactly one is:
     * matching botocore's own env-credential provider, which raises {@code PartialCredentialsError}
     * for a lone access key or secret key rather than treating it as "absent, fall through to the
     * next provider." Silently falling through here (e.g. to a profile named alongside a broken
     * key pair) would mint a token under a different identity than whatever the caller actually
     * intended to supply.
     */
    private static boolean hasPairedKeys(String source, String accessKeyId, String secretAccessKey) {
        var hasAccessKeyId = accessKeyId != null && !accessKeyId.isBlank();
        var hasSecretAccessKey = secretAccessKey != null && !secretAccessKey.isBlank();
        if (hasAccessKeyId != hasSecretAccessKey) {
            throw new IllegalStateException(
                    "Partial AWS credentials in " + source + ": "
                            + (hasAccessKeyId ? "AWS_ACCESS_KEY_ID is set but AWS_SECRET_ACCESS_KEY is not"
                                    : "AWS_SECRET_ACCESS_KEY is set but AWS_ACCESS_KEY_ID is not")
                            + ". Set both, or neither.");
        }
        return hasAccessKeyId;
    }

    private static AwsCredentials resolveFromSharedCredentialsFile(UnaryOperator<String> env, String profileEnv) {
        var pathValue = env.apply("AWS_SHARED_CREDENTIALS_FILE");
        var path = (pathValue != null && !pathValue.isBlank())
                ? Path.of(pathValue)
                : Path.of(System.getProperty("user.home"), ".aws", "credentials");
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "Could not resolve AWS credentials to mint an EKS token: no AWS_ACCESS_KEY_ID/"
                            + "AWS_SECRET_ACCESS_KEY in the environment and no shared credentials file at "
                            + path + ". Set AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, or "
                            + "AWS_SHARED_CREDENTIALS_FILE.");
        }
        var profile = (profileEnv == null || profileEnv.isBlank()) ? "default" : profileEnv;
        var section = readIniSection(path, profile);
        var accessKeyId = section.get("aws_access_key_id");
        var secretAccessKey = section.get("aws_secret_access_key");
        if (accessKeyId == null || secretAccessKey == null) {
            throw new IllegalStateException(
                    "AWS credentials profile '" + profile + "' in " + path
                            + " has no aws_access_key_id/aws_secret_access_key");
        }
        return new AwsCredentials(accessKeyId, secretAccessKey, section.get("aws_session_token"));
    }

    private static Map<String, String> readIniSection(Path path, String profile) {
        var target = "[" + profile + "]";
        var result = new LinkedHashMap<String, String>();
        try {
            var inSection = false;
            for (var raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                var line = raw.strip();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inSection = line.equals(target);
                    continue;
                }
                if (!inSection) {
                    continue;
                }
                var eq = line.indexOf('=');
                if (eq > 0) {
                    result.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read AWS credentials file " + path + ": " + e.getMessage(), e);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("AWS credentials profile '" + profile + "' not found in " + path);
        }
        return result;
    }

    /** RFC 3986 percent-encoding, byte-wise over UTF-8, per the SigV4 spec (not {@link java.net.URLEncoder}). */
    private static String uriEncode(String input) {
        var result = new StringBuilder();
        for (var b : input.getBytes(StandardCharsets.UTF_8)) {
            var c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                result.append(c);
            } else {
                result.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return result.toString();
    }

    private static byte[] deriveSigningKey(String secretKey, String date, String region, String service) {
        var kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        var kDate = hmacSha256(kSecret, date);
        var kRegion = hmacSha256(kDate, region);
        var kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not compute HMAC-SHA256: " + e.getMessage(), e);
        }
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return hexEncode(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not compute SHA-256: " + e.getMessage(), e);
        }
    }

    private static String hexEncode(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (var b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

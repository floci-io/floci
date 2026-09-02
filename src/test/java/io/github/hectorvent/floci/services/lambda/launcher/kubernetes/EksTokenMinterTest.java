package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the {@code aws eks get-token} recognition, argument parsing, and the presigned
 * {@code sts:GetCallerIdentity} URL that becomes the bearer token, against fixed credentials so
 * the tests are deterministic regardless of the host's own AWS environment. What can't be
 * checked without a real AWS account (that STS actually accepts the signature) is left to
 * manual verification against a real cluster.
 */
class EksTokenMinterTest {

    private static final EksTokenMinter.AwsCredentials CREDENTIALS =
            new EksTokenMinter.AwsCredentials("AKIAEXAMPLE", "secret", null);

    @Test
    void recognizesAwsEksGetToken() throws Exception {
        assertThat(EksTokenMinter.isAwsEksGetToken(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster]
                """))).isTrue();
    }

    @Test
    void recognizesAwsFromAnAbsolutePath() throws Exception {
        assertThat(EksTokenMinter.isAwsEksGetToken(execNode("""
                command: /usr/local/bin/aws
                args: [eks, get-token, --cluster-name, my-cluster]
                """))).isTrue();
    }

    @Test
    void doesNotRecognizeOtherCommands() throws Exception {
        assertThat(EksTokenMinter.isAwsEksGetToken(execNode("""
                command: gcloud
                args: [container, clusters, get-credentials]
                """))).isFalse();
    }

    @Test
    void doesNotRecognizeAwsIamAuthenticator() throws Exception {
        assertThat(EksTokenMinter.isAwsEksGetToken(execNode("""
                command: aws-iam-authenticator
                args: [token, -i, my-cluster]
                """))).isFalse();
    }

    @Test
    void doesNotRecognizeOtherAwsSubcommands() throws Exception {
        assertThat(EksTokenMinter.isAwsEksGetToken(execNode("""
                command: aws
                args: [sts, get-caller-identity]
                """))).isFalse();
    }

    @Test
    void recognizesTheShapeAwsEksUpdateKubeconfigActuallyGenerates() throws Exception {
        // Confirmed against the AWS CLI's own update_kubeconfig.py: --region always precedes
        // the subcommand, and --output json always follows -- get-token is never args[0]/[1].
        assertThat(EksTokenMinter.isAwsEksGetToken(execNode("""
                command: aws
                args: [--region, us-west-2, eks, get-token, --cluster-name, my-cluster, --output, json]
                """))).isTrue();
    }

    @Test
    void parsesClusterNameAndRegionFromTheGeneratedShape() throws Exception {
        var args = EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [--region, us-west-2, eks, get-token, --cluster-name, my-cluster, --output, json]
                """), name -> {
            throw new AssertionError("--region is given inline, should not fall back to env");
        });

        assertThat(args.clusterName()).isEqualTo("my-cluster");
        assertThat(args.region()).isEqualTo("us-west-2");
    }

    @Test
    void parsesClusterNameAndExplicitRegion() throws Exception {
        var args = EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster, --region, eu-west-1]
                """), name -> {
            throw new AssertionError("should not fall back to env when --region is given");
        });

        assertThat(args.clusterName()).isEqualTo("my-cluster");
        assertThat(args.region()).isEqualTo("eu-west-1");
    }

    @Test
    void fallsBackToAwsRegionEnvVar() throws Exception {
        var args = EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster]
                """), name -> "AWS_REGION".equals(name) ? "ap-south-1" : null);

        assertThat(args.region()).isEqualTo("ap-south-1");
    }

    @Test
    void fallsBackToAwsDefaultRegionEnvVarWhenAwsRegionIsUnset() throws Exception {
        var args = EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster]
                """), name -> "AWS_DEFAULT_REGION".equals(name) ? "ca-central-1" : null);

        assertThat(args.region()).isEqualTo("ca-central-1");
    }

    @Test
    void rejectsMissingClusterName() throws Exception {
        assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --region, us-east-1]
                """), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--cluster-name");
    }

    @Test
    void rejectsRoleArn() throws Exception {
        assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster, --role-arn, arn:aws:iam::111122223333:role/x]
                """), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--role-arn");
    }

    @Test
    void rejectsTheAbbreviatedRoleFlagAwsEksUpdateKubeconfigActuallyGenerates() throws Exception {
        // update_kubeconfig.py writes "--role", not "--role-arn", when --role-arn was passed to
        // update-kubeconfig. Missing this form would silently mint under ambient credentials
        // instead of the requested role, so it must reject exactly like the long form does.
        assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [--region, us-west-2, eks, get-token, --cluster-name, my-cluster, --output, json, --role, arn:aws:iam::111122223333:role/x]
                """), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--role-arn");
    }

    @Test
    void rejectsTheEqualsSignFormOfRoleArn() throws Exception {
        assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster, --role-arn=arn:aws:iam::111122223333:role/x]
                """), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--role-arn");
    }

    @Test
    void rejectsTheEqualsSignFormOfTheAbbreviatedRoleFlag() throws Exception {
        assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster, --role=arn:aws:iam::111122223333:role/x]
                """), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--role-arn");
    }

    @Test
    void rejectsIntermediateUnambiguousRoleArnAbbreviations() throws Exception {
        // AWS CLI's argument parser accepts any unambiguous prefix of a long option, and
        // role-arn is the only get-token option starting with "role", so these are just as
        // valid to a real invocation as the --role form update-kubeconfig actually generates.
        for (var abbreviation : new String[]{"--role-a", "--role-ar"}) {
            assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                    command: aws
                    args: [eks, get-token, --cluster-name, my-cluster, %s, arn:aws:iam::111122223333:role/x]
                    """.formatted(abbreviation)), name -> null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("--role-arn");
        }
    }

    @Test
    void rejectsWhenNoRegionIsResolvable() throws Exception {
        assertThatThrownBy(() -> EksTokenMinter.parseArgs(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster]
                """), name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("region");
    }

    @Test
    void mintedTokenDecodesToAWellFormedPresignedGetCallerIdentityUrl() throws Exception {
        var token = EksTokenMinter.mint("my-cluster", "us-west-2", CREDENTIALS);

        assertThat(token).startsWith("k8s-aws-v1.");
        var url = decode(token);
        var uri = URI.create(url);
        var query = parseQuery(uri);

        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("sts.us-west-2.amazonaws.com");
        assertThat(query.get("Action")).isEqualTo("GetCallerIdentity");
        assertThat(query.get("Version")).isEqualTo("2011-06-15");
        assertThat(query.get("X-Amz-Algorithm")).isEqualTo("AWS4-HMAC-SHA256");
        assertThat(query.get("X-Amz-Credential")).startsWith("AKIAEXAMPLE/").endsWith("/us-west-2/sts/aws4_request");
        assertThat(query.get("X-Amz-Expires")).isEqualTo("60");
        assertThat(query.get("X-Amz-SignedHeaders")).isEqualTo("host;x-k8s-aws-id");
        assertThat(query.get("X-Amz-Signature")).matches("[0-9a-f]{64}");
        assertThat(query).doesNotContainKey("X-Amz-Security-Token");
    }

    @Test
    void mintedSignatureMatchesAnIndependentlyComputedReferenceValue() throws Exception {
        // Fixed inputs, cross-checked against a from-scratch Python re-implementation of the
        // SigV4 spec (not ported from EksTokenMinter's own code, so it can't share a bug with
        // it): canonical request -> string to sign -> HMAC-derived signing key -> signature.
        // Structural assertions elsewhere only prove the token has the right shape; this proves
        // the actual signature bytes are what the spec says they should be for these inputs.
        var credentials = new EksTokenMinter.AwsCredentials(
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "FQoGZXIvYXdzEXAMPLESESSIONTOKENvaluevaluevalue");
        var clock = Clock.fixed(Instant.parse("2024-01-15T12:00:00Z"), ZoneOffset.UTC);

        var token = EksTokenMinter.mint("my-cluster", "us-west-2", credentials, clock);
        var query = parseQuery(URI.create(decode(token)));

        assertThat(query.get("X-Amz-Date")).isEqualTo("20240115T120000Z");
        assertThat(query.get("X-Amz-Credential"))
                .isEqualTo("AKIAIOSFODNN7EXAMPLE/20240115/us-west-2/sts/aws4_request");
        assertThat(query.get("X-Amz-Signature"))
                .isEqualTo("1f3eff1793d8e9b9ecf0b461c6b40f9aa3029d913ffc70a17b498c932a6c34e1");
    }

    @Test
    void includesSecurityTokenWhenSessionTokenIsPresent() throws Exception {
        var credentials = new EksTokenMinter.AwsCredentials("AKIAEXAMPLE", "secret", "session-token");
        var query = parseQuery(URI.create(decode(EksTokenMinter.mint("my-cluster", "us-west-2", credentials))));

        assertThat(query.get("X-Amz-Security-Token")).isEqualTo("session-token");
    }

    @Test
    void signatureIsBoundToTheClusterName() throws Exception {
        var forClusterA = signatureOf(EksTokenMinter.mint("cluster-a", "us-west-2", CREDENTIALS));
        var forClusterB = signatureOf(EksTokenMinter.mint("cluster-b", "us-west-2", CREDENTIALS));

        // The whole point of x-k8s-aws-id being a signed header: a token minted for one
        // cluster must not verify against another, so the two signatures must differ.
        assertThat(forClusterA).isNotEqualTo(forClusterB);
    }

    @Test
    void signsChinaRegionsAgainstTheChinaStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "cn-north-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.cn-north-1.amazonaws.com.cn");
    }

    @Test
    void signsGovCloudRegionsAgainstTheStandardStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "us-gov-west-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.us-gov-west-1.amazonaws.com");
    }

    @Test
    void signsIsoRegionsAgainstTheIsoStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "us-iso-east-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.us-iso-east-1.c2s.ic.gov");
    }

    @Test
    void signsIsoBRegionsAgainstTheIsoBStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "us-isob-east-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.us-isob-east-1.sc2s.sgov.gov");
    }

    @Test
    void signsIsoERegionsAgainstTheIsoEStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "eu-isoe-west-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.eu-isoe-west-1.cloud.adc-e.uk");
    }

    @Test
    void signsIsoFRegionsAgainstTheIsoFStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "us-isof-south-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.us-isof-south-1.csp.hci.ic.gov");
    }

    @Test
    void signsEuscRegionsAgainstTheEuscStsSuffix() throws Exception {
        var uri = URI.create(decode(EksTokenMinter.mint("my-cluster", "eusc-de-east-1", CREDENTIALS)));

        assertThat(uri.getHost()).isEqualTo("sts.eusc-de-east-1.amazonaws.eu");
    }

    @Test
    void execEnvSuppliesCredentialsAheadOfTheProcessEnvironment() throws Exception {
        var supplier = EksTokenMinter.tokenSupplierIfRecognized(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster, --region, us-west-2]
                env:
                  - name: AWS_ACCESS_KEY_ID
                    value: AKIAFROMEXECENV
                  - name: AWS_SECRET_ACCESS_KEY
                    value: secret-from-exec-env
                """)).orElseThrow();

        var query = parseQuery(URI.create(decode(supplier.get())));

        // Proves exec.env, not whatever AWS_ACCESS_KEY_ID happens to be in Floci's own
        // environment, drove credential resolution -- the bug aws eks update-kubeconfig
        // --profile <p> would otherwise trip, since it threads the profile through exec.env.
        assertThat(query.get("X-Amz-Credential")).startsWith("AKIAFROMEXECENV/");
    }

    @Test
    void execEnvProfileTakesPrecedenceOverAmbientStaticKeys(@TempDir Path tempDir) throws Exception {
        var credentialsFile = tempDir.resolve("credentials");
        Files.writeString(credentialsFile, """
                [work]
                aws_access_key_id = AKIAFROMPROFILE
                aws_secret_access_key = secret-from-profile
                """);
        Map<String, String> execEnv = Map.of("AWS_PROFILE", "work");
        // Simulates static keys that merely happen to be ambient in Floci's own process,
        // unrelated to this kubeconfig -- these must not win just because plain env-var
        // precedence would normally put raw keys ahead of a profile.
        UnaryOperator<String> ambientEnv = name -> switch (name) {
            case "AWS_ACCESS_KEY_ID" -> "AKIAAMBIENT";
            case "AWS_SECRET_ACCESS_KEY" -> "ambient-secret";
            case "AWS_SHARED_CREDENTIALS_FILE" -> credentialsFile.toString();
            default -> null;
        };

        var credentials = EksTokenMinter.resolveCredentials(execEnv, ambientEnv);

        assertThat(credentials.accessKeyId()).isEqualTo("AKIAFROMPROFILE");
    }

    @Test
    void execEnvExplicitKeysStillOutrankExecEnvProfile() throws Exception {
        Map<String, String> execEnv = Map.of(
                "AWS_PROFILE", "work",
                "AWS_ACCESS_KEY_ID", "AKIAFROMEXECENV",
                "AWS_SECRET_ACCESS_KEY", "secret-from-exec-env");

        var credentials = EksTokenMinter.resolveCredentials(execEnv, name -> null);

        assertThat(credentials.accessKeyId()).isEqualTo("AKIAFROMEXECENV");
    }

    @Test
    void execEnvExplicitKeysDoNotInheritAnUnrelatedAmbientSessionToken() throws Exception {
        // A session token is only valid for the specific key pair STS issued it with. If
        // exec.env supplies static keys but no session token, an ambient one (left over from
        // some unrelated temporary credential set in Floci's own process) must not get
        // attached, or the mismatched key/token combination gets rejected by STS.
        Map<String, String> execEnv = Map.of(
                "AWS_ACCESS_KEY_ID", "AKIAFROMEXECENV",
                "AWS_SECRET_ACCESS_KEY", "secret-from-exec-env");
        UnaryOperator<String> ambientEnv = name -> "AWS_SESSION_TOKEN".equals(name) ? "unrelated-ambient-token" : null;

        var credentials = EksTokenMinter.resolveCredentials(execEnv, ambientEnv);

        assertThat(credentials.sessionToken()).isNull();
    }

    @Test
    void ambientKeysDoNotInheritAnUnrelatedExecEnvSessionToken() throws Exception {
        // Symmetric case: exec.env names a session token but no keys of its own (nothing real
        // generates this, but the risk is the same either direction), so the keys resolved
        // from the ambient environment must not pick it up either.
        Map<String, String> execEnv = Map.of("AWS_SESSION_TOKEN", "exec-env-only-token");
        UnaryOperator<String> ambientEnv = name -> switch (name) {
            case "AWS_ACCESS_KEY_ID" -> "AKIAAMBIENT";
            case "AWS_SECRET_ACCESS_KEY" -> "ambient-secret";
            default -> null;
        };

        var credentials = EksTokenMinter.resolveCredentials(execEnv, ambientEnv);

        assertThat(credentials.sessionToken()).isNull();
    }

    @Test
    void rejectsAPartialExecEnvKeyPairRatherThanFallingThroughToTheProfile() throws Exception {
        // AWS_ACCESS_KEY_ID with no AWS_SECRET_ACCESS_KEY alongside AWS_PROFILE is a broken
        // config, not "no explicit keys, use the profile" -- silently falling through here
        // would mint a token under a different identity than intended.
        Map<String, String> execEnv = Map.of(
                "AWS_PROFILE", "work",
                "AWS_ACCESS_KEY_ID", "AKIAFROMEXECENV");

        assertThatThrownBy(() -> EksTokenMinter.resolveCredentials(execEnv, name -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_SECRET_ACCESS_KEY");
    }

    @Test
    void rejectsAPartialAmbientKeyPairRatherThanFallingThroughToTheSharedCredentialsFile() throws Exception {
        UnaryOperator<String> ambientEnv = name -> "AWS_SECRET_ACCESS_KEY".equals(name) ? "ambient-secret" : null;

        assertThatThrownBy(() -> EksTokenMinter.resolveCredentials(Map.of(), ambientEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_ACCESS_KEY_ID");
    }

    @Test
    void execEnvSuppliesRegionFallbackAheadOfTheProcessEnvironment() throws Exception {
        var supplier = EksTokenMinter.tokenSupplierIfRecognized(execNode("""
                command: aws
                args: [eks, get-token, --cluster-name, my-cluster]
                env:
                  - name: AWS_REGION
                    value: eu-central-1
                  - name: AWS_ACCESS_KEY_ID
                    value: AKIAFROMEXECENV
                  - name: AWS_SECRET_ACCESS_KEY
                    value: secret-from-exec-env
                """)).orElseThrow();

        var uri = URI.create(decode(supplier.get()));

        assertThat(uri.getHost()).isEqualTo("sts.eu-central-1.amazonaws.com");
    }

    private static String signatureOf(String token) throws Exception {
        return parseQuery(URI.create(decode(token))).get("X-Amz-Signature");
    }

    private static String decode(String token) {
        assertThat(token).startsWith("k8s-aws-v1.");
        return new String(Base64.getUrlDecoder().decode(token.substring("k8s-aws-v1.".length())),
                StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                        p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
    }

    private static JsonNode execNode(String yaml) throws Exception {
        return new ObjectMapper(new YAMLFactory()).readTree(yaml);
    }
}

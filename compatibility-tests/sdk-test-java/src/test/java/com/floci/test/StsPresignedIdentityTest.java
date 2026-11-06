package com.floci.test;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.User;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class StsPresignedIdentityTest {
    @ParameterizedTest
    @CsvSource({"false, HTTP_1_1", "true, HTTP_1_1", "false, HTTP_2", "true, HTTP_2"})
    void sdkPresignedRequestKeepsUserOrRoleIdentity(boolean role, HttpClient.Version version) throws Exception {
        String name = TestFixtures.uniqueName("sts-presigned");
        try (IamClient iam = TestFixtures.iamClient(); StsClient root = TestFixtures.stsClient()) {
            String expectedArn;
            String expectedId;
            AwsCredentials credentials;
            String accessKey = null;
            if (role) {
                String roleArn = iam.createRole(r -> r.roleName(name).assumeRolePolicyDocument(
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                                + "\"Principal\":\"*\",\"Action\":\"sts:AssumeRole\"}]}"))
                        .role().arn();
                try {
                    AssumeRoleResponse session = root.assumeRole(r -> r.roleArn(roleArn).roleSessionName("identity"));
                    expectedArn = session.assumedRoleUser().arn();
                    expectedId = session.assumedRoleUser().assumedRoleId();
                    credentials = AwsSessionCredentials.create(session.credentials().accessKeyId(),
                            session.credentials().secretAccessKey(), session.credentials().sessionToken());
                } catch (RuntimeException error) {
                    iam.deleteRole(r -> r.roleName(name));
                    throw error;
                }
            } else {
                User user = iam.createUser(r -> r.userName(name)).user();
                expectedArn = user.arn();
                expectedId = user.userId();
                try {
                    AccessKey key = iam.createAccessKey(r -> r.userName(name)).accessKey();
                    accessKey = key.accessKeyId();
                    credentials = AwsBasicCredentials.create(accessKey, key.secretAccessKey());
                } catch (RuntimeException error) {
                    iam.deleteUser(r -> r.userName(name));
                    throw error;
                }
            }
            try (StsClient caller = StsClient.builder().endpointOverride(TestFixtures.endpoint())
                    .region(TestFixtures.region()).credentialsProvider(StaticCredentialsProvider.create(credentials)).build()) {
                GetCallerIdentityResponse header = caller.getCallerIdentity();
                assertThat(header.arn()).isEqualTo(expectedArn);
                assertThat(header.userId()).isEqualTo(expectedId);
                Document query = presignedIdentity(credentials, version);
                assertThat(query.getElementsByTagNameNS("*", "Arn").item(0).getTextContent()).isEqualTo(expectedArn);
                assertThat(query.getElementsByTagNameNS("*", "UserId").item(0).getTextContent()).isEqualTo(expectedId);
                assertThat(query.getElementsByTagNameNS("*", "Account").item(0).getTextContent()).isEqualTo(header.account());
            } finally {
                if (role) {
                    iam.deleteRole(r -> r.roleName(name));
                } else {
                    String key = accessKey;
                    try {
                        iam.deleteAccessKey(r -> r.userName(name).accessKeyId(key));
                    } finally {
                        iam.deleteUser(r -> r.userName(name));
                    }
                }
            }
        }
    }

    private static Document presignedIdentity(AwsCredentials credentials, HttpClient.Version version) throws Exception {
        byte[] body = "Action=GetCallerIdentity&Version=2011-06-15".getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest signed = presign(credentials, body, "sts", Instant.now());
        HttpResponse<byte[]> response = send(signed, signed.getUri(), body, version);
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        boolean signaturesEnabled = Boolean.parseBoolean(System.getenv("FLOCI_AUTH_VALIDATE_SIGNATURES"));
        if (signaturesEnabled) {
            URI forged = URI.create(signed.getUri().toString().replaceAll("X-Amz-Signature=[^&]*",
                    "X-Amz-Signature=" + "0".repeat(64)));
            assertSignatureRefused(send(signed, forged, body, version));
            Instant signedAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
            SdkHttpFullRequest expired = presign(credentials, body, "sts", signedAt);
            Instant before = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
            HttpResponse<byte[]> expiryResponse = send(expired, expired.getUri(), body, version);
            Instant after = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.SECONDS);
            assertSignatureRefused(expiryResponse);
            DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
            String prefix = "Signature expired: " + format.format(signedAt) + " is now earlier than ";
            String expiryMessage = parse(expiryResponse.body()).getElementsByTagNameNS("*", "Message")
                    .item(0).getTextContent();
            assertThat(expiryMessage).startsWith(prefix);
            Instant cutoff = Instant.from(format.parse(expiryMessage.substring(prefix.length())));
            assertThat(cutoff).isBetween(before, after);
            assertClockRefusal(credentials, body, version, true, 1200);
            assertClockRefusal(credentials, body, version, false, 1200);
            assertClockRefusal(credentials, body, version, false, -1200);
            SdkHttpFullRequest wrongService = presign(credentials, body, "iam", Instant.now());
            HttpResponse<byte[]> scopeResponse = send(wrongService, wrongService.getUri(), body, version);
            assertSignatureRefused(scopeResponse);
            assertThat(parse(scopeResponse.body()).getElementsByTagNameNS("*", "Message").item(0).getTextContent())
                    .isEqualTo("Credential should be scoped to correct service: 'sts'");
            assertSignatureRefused(send(signed, signed.getUri(),
                    (new String(body, StandardCharsets.UTF_8) + "&Extra=changed").getBytes(StandardCharsets.UTF_8), version));
        }
        if (signaturesEnabled && credentials instanceof AwsSessionCredentials) {
            for (String replacement : new String[]{"", "X-Amz-Security-Token=wrong"}) {
                URI wrongToken = URI.create(signed.getUri().toString()
                        .replaceAll("X-Amz-Security-Token=[^&]*", replacement));
                HttpResponse<byte[]> invalid = send(signed, wrongToken, body, version);
                assertThat(invalid.statusCode()).isEqualTo(403);
                assertThat(new String(invalid.body(), StandardCharsets.UTF_8)).contains("InvalidClientTokenId");
            }
        }
        return parse(response.body());
    }

    private static void assertClockRefusal(AwsCredentials credentials, byte[] body, HttpClient.Version version,
                                           boolean query, long offset) throws Exception {
        Instant signedAt = Instant.now().plusSeconds(offset).truncatedTo(ChronoUnit.SECONDS);
        SdkHttpFullRequest signed = query ? presign(credentials, body, "sts", signedAt)
                : Aws4Signer.create().sign(SdkHttpFullRequest.builder()
                        .uri(TestFixtures.endpoint()).method(SdkHttpMethod.POST)
                        .putHeader("Content-Type", "application/x-www-form-urlencoded")
                        .contentStreamProvider(() -> new ByteArrayInputStream(body)).build(),
                Aws4SignerParams.builder().awsCredentials(credentials).signingRegion(TestFixtures.region())
                        .signingName("sts").signingClockOverride(Clock.fixed(signedAt, ZoneOffset.UTC)).build());
        long cutoffOffset = offset > 0 ? 900 : -900;
        Instant before = Instant.now().plusSeconds(cutoffOffset).truncatedTo(ChronoUnit.SECONDS);
        HttpResponse<byte[]> response = send(signed, signed.getUri(), body, version);
        Instant after = Instant.now().plusSeconds(cutoffOffset).truncatedTo(ChronoUnit.SECONDS);
        assertSignatureRefused(response);
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
        String prefix = offset > 0
                ? "Signature not yet current: " + format.format(signedAt) + " is still later than "
                : "Signature expired: " + format.format(signedAt) + " is now earlier than ";
        String message = parse(response.body()).getElementsByTagNameNS("*", "Message").item(0).getTextContent();
        assertThat(message).startsWith(prefix);
        assertThat(Instant.from(format.parse(message.substring(prefix.length())))).isBetween(before, after);
    }

    private static SdkHttpFullRequest presign(AwsCredentials credentials, byte[] body, String service, Instant signedAt) {
        return Aws4Signer.create().presign(SdkHttpFullRequest.builder()
                        .uri(TestFixtures.endpoint()).method(SdkHttpMethod.POST)
                        .putHeader("Content-Type", "application/x-www-form-urlencoded")
                        .contentStreamProvider(() -> new ByteArrayInputStream(body)).build(),
                Aws4PresignerParams.builder().awsCredentials(credentials).signingRegion(TestFixtures.region())
                        .signingName(service).signingClockOverride(Clock.fixed(signedAt, ZoneOffset.UTC))
                        .expirationTime(signedAt.plusSeconds(60)).build());
    }

    private static Document parse(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }

    private static void assertSignatureRefused(HttpResponse<byte[]> response) {
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(403);
        assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("SignatureDoesNotMatch");
    }

    private static HttpResponse<byte[]> send(SdkHttpFullRequest signed, URI uri, byte[] body,
                                              HttpClient.Version version) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        signed.headers().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Length")) {
                values.forEach(value -> request.header(name, value));
            }
        });
        HttpResponse<byte[]> response = HttpClient.newBuilder().version(version).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.version()).isEqualTo(version);
        return response;
    }
}

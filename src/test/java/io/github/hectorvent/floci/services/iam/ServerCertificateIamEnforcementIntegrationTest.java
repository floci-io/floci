package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A policy statement naming a single server certificate has to bind to that certificate and no
 * other. Building the target ARN is only half of it; these drive the whole path, so a statement
 * that is silently inert would fail here rather than pass unnoticed.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ServerCertificateIamEnforcementIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";
    private static final String REGION = "us-east-1";
    private static final CertificateGenerator GENERATOR = new CertificateGenerator();

    private static String arn(String pathAndName) {
        return "arn:aws:iam::" + ACCOUNT_ID + ":server-certificate/" + pathAndName;
    }

    @Test
    void aDenyNamingOneCertificateLeavesTheOthersAlone() {
        String suffix = suffix();
        String denied = "deny-" + suffix;
        String allowed = "keep-" + suffix;
        upload(denied, null);
        upload(allowed, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:DeleteServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(denied)));

        userIam(akid, "DeleteServerCertificate", Map.of("ServerCertificateName", denied))
                .statusCode(403).body(containsString("AccessDenied"));
        userIam(akid, "DeleteServerCertificate", Map.of("ServerCertificateName", allowed))
                .statusCode(200);
    }

    /**
     * The reference gives the resource as {@code server-certificate/${CertificateNameWithPath}}, so
     * a certificate at {@code /team/} is only named by an ARN carrying that path. The second half is
     * what makes this discriminating: the same ARN without the path must not match.
     */
    @Test
    void theStoredPathIsPartOfTheArn() {
        String suffix = suffix();
        String name = "pathed-" + suffix;
        upload(name, "/team/");

        String withPath = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:GetServerCertificate","Resource":"%s"}]}"""
                .formatted(arn("team/" + name)));
        userIam(withPath, "GetServerCertificate", Map.of("ServerCertificateName", name))
                .statusCode(403).body(containsString("AccessDenied"));

        String withoutPath = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:GetServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(name)));
        userIam(withoutPath, "GetServerCertificate", Map.of("ServerCertificateName", name))
                .statusCode(200);
    }

    @Test
    void aResourceScopedAllowGrantsThatCertificateOnly() {
        String suffix = suffix();
        String granted = "allow-" + suffix;
        String other = "other-" + suffix;
        upload(granted, null);
        upload(other, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:GetServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(granted)));

        userIam(akid, "GetServerCertificate", Map.of("ServerCertificateName", granted))
                .statusCode(200);
        userIam(akid, "GetServerCertificate", Map.of("ServerCertificateName", other))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * An upload has no stored path to read, so its ARN comes from the request's own {@code Path}.
     * The same name at a different path is a different resource and stays allowed.
     */
    @Test
    void anUploadIsAuthorizedAgainstThePathItAsksFor() {
        String name = "upload-" + suffix();
        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:UploadServerCertificate","Resource":"%s"}]}"""
                .formatted(arn("team/" + name)));

        CertificateGenerator.GeneratedCertificate pair = material();
        uploadAs(akid, name, "/team/", pair)
                .statusCode(403).body(containsString("AccessDenied"));
        uploadAs(akid, name, null, pair)
                .statusCode(200);
    }

    /**
     * The Query controller accepts the legacy {@code Operation} parameter when {@code Action} is
     * absent, and dispatches on it. The resource has to be resolved from the same place, or a
     * caller can name the operation that way and have the deny evaluated against {@code *}.
     */
    @Test
    void theLegacyOperationParameterIsAuthorizedAgainstTheCertificateToo() {
        String name = "legacy-" + suffix();
        upload(name, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:DeleteServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(name)));

        given()
                .header("Authorization", auth(akid, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Operation", "DeleteServerCertificate")
                .formParam("Version", "2010-05-08")
                .formParam("ServerCertificateName", name)
        .when().post("/").then()
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * A certificate stays in the partition it was created in, which is why the service re-mints a
     * moved ARN beside its current one. The permission check has to read the partition from the
     * stored ARN for the same reason: deriving it from the caller's signing region would let a
     * request signed in another partition be checked against an ARN that names no real resource,
     * and a deny on the certificate's actual ARN would not match it.
     */
    @Test
    void signingInAnotherPartitionDoesNotEscapeTheDeny() {
        String name = "partition-" + suffix();
        upload(name, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:DeleteServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(name)));

        given()
                .header("Authorization", auth(akid, "iam", "cn-north-1"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteServerCertificate")
                .formParam("Version", "2010-05-08")
                .formParam("ServerCertificateName", name)
        .when().post("/").then()
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /**
     * The reference lists no resource type for {@code ListServerCertificates}, so it is checked
     * against {@code *} and a statement naming one certificate cannot reach it.
     */
    @Test
    void theListIsNotScopedToACertificate() {
        String name = "listed-" + suffix();
        upload(name, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:ListServerCertificates","Resource":"%s"}]}"""
                .formatted(arn(name)));

        userIam(akid, "ListServerCertificates", Map.of()).statusCode(200);
    }

    /**
     * The model is explicit that a rename needs permission on both names, so that a principal
     * allowed on the old one cannot rename a certificate into a name they may not write: "to
     * change the certificate named ProductionCert to ProdCert, the principal must have a policy
     * that allows them to update both certificates".
     */
    @Test
    void aRenameIsDeniedWhenOnlyTheDestinationNameIsDenied() {
        String suffix = suffix();
        String from = "rename-from-" + suffix;
        String reserved = "rename-reserved-" + suffix;
        upload(from, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:UpdateServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(reserved)));

        userIam(akid, "UpdateServerCertificate", Map.of("ServerCertificateName", from,
                "NewServerCertificateName", reserved))
                .statusCode(403).body(containsString("AccessDenied"));

        // The rename did not happen: the certificate is still reachable under its old name, and
        // the denied name holds nothing.
        adminIam("GetServerCertificate", Map.of("ServerCertificateName", from)).statusCode(200);
        adminIam("GetServerCertificate", Map.of("ServerCertificateName", reserved)).statusCode(404);
    }

    /** A move is a rename of the path, so the destination path is checked the same way. */
    @Test
    void aMoveIsDeniedWhenOnlyTheDestinationPathIsDenied() {
        String name = "moved-" + suffix();
        upload(name, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:UpdateServerCertificate","Resource":"%s"}]}"""
                .formatted(arn("reserved/" + name)));

        userIam(akid, "UpdateServerCertificate", Map.of("ServerCertificateName", name,
                "NewPath", "/reserved/"))
                .statusCode(403).body(containsString("AccessDenied"));

        // The name alone proves nothing here: a moved certificate is still reachable by name, so
        // the path and the ARN are what show the move did not happen.
        adminIam("GetServerCertificate", Map.of("ServerCertificateName", name))
                .statusCode(200)
                .body("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate"
                        + ".ServerCertificateMetadata.Path", equalTo("/"))
                .body("GetServerCertificateResponse.GetServerCertificateResult.ServerCertificate"
                        + ".ServerCertificateMetadata.Arn", equalTo(arn(name)));
    }

    /**
     * The old name still has to be allowed. Naming the destination must not become a way to
     * rename a certificate the principal cannot otherwise touch.
     */
    @Test
    void aRenameIsDeniedWhenOnlyTheSourceNameIsDenied() {
        String suffix = suffix();
        String from = "source-denied-" + suffix;
        upload(from, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"},
                  {"Effect":"Deny","Action":"iam:UpdateServerCertificate","Resource":"%s"}]}"""
                .formatted(arn(from)));

        userIam(akid, "UpdateServerCertificate", Map.of("ServerCertificateName", from,
                "NewServerCertificateName", "source-denied-target-" + suffix))
                .statusCode(403).body(containsString("AccessDenied"));
    }

    /** With both names allowed the rename goes through, so the extra resource is not a blanket no. */
    @Test
    void aRenameSucceedsWhenBothNamesAreAllowed() {
        String suffix = suffix();
        String from = "both-from-" + suffix;
        String to = "both-to-" + suffix;
        upload(from, null);

        String akid = userWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"iam:*","Resource":"*"}]}""");

        userIam(akid, "UpdateServerCertificate", Map.of("ServerCertificateName", from,
                "NewServerCertificateName", to)).statusCode(200);
        adminIam("GetServerCertificate", Map.of("ServerCertificateName", to)).statusCode(200);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static CertificateGenerator.GeneratedCertificate material() {
        return GENERATOR.generateSelfSignedCertificate(
                "certs.test.local", List.of(), KeyAlgorithm.EC_prime256v1);
    }

    private static void upload(String name, String path) {
        uploadAs(ACCOUNT_ID, name, path, material()).statusCode(200);
    }

    private static ValidatableResponse uploadAs(String akid, String name, String path,
                                                CertificateGenerator.GeneratedCertificate pair) {
        RequestSpecification spec = given()
                .header("Authorization", auth(akid, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UploadServerCertificate")
                .formParam("Version", "2010-05-08")
                .formParam("ServerCertificateName", name)
                .formParam("CertificateBody", pair.certificatePem())
                .formParam("PrivateKey", pair.privateKeyPem());
        if (path != null) {
            spec.formParam("Path", path);
        }
        return spec.when().post("/").then();
    }

    /** An IAM user in {@link #ACCOUNT_ID} carrying {@code policy} inline. */
    private static String userWith(String policy) {
        String user = "cert-iam-" + suffix();
        adminIam("CreateUser", Map.of("UserName", user)).statusCode(200);
        adminIam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "p",
                "PolicyDocument", policy)).statusCode(200);
        return adminIam("CreateAccessKey", Map.of("UserName", user)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static ValidatableResponse adminIam(String action, Map<String, String> params) {
        return iamCall(ACCOUNT_ID, action, params);
    }

    private static ValidatableResponse userIam(String akid, String action, Map<String, String> params) {
        return iamCall(akid, action, params);
    }

    private static ValidatableResponse iamCall(String akid, String action, Map<String, String> params) {
        RequestSpecification spec = given()
                .header("Authorization", auth(akid, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static String auth(String accessKeyId, String service) {
        return auth(accessKeyId, service, REGION);
    }

    private static String auth(String accessKeyId, String service, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260919/" + region + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}

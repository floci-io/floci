package io.floci.aws.services.ses;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedRestRequests;
import io.floci.aws.testing.TestAccounts;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.IamTestPolicies.allow;
import static io.floci.aws.testing.SignedRestRequests.denied;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * SES requests are checked against the caller's policies on the resources they name, through the generated SES v2
 * routes and the generated table of request resources: each v2 call on the ARN of the identity, configuration set,
 * template, contact list, custom verification email template, dedicated IP pool, export or import job or tenant its
 * request names, in the format AWS's Service Reference gives, which is the ARN SES's own tag calls take; a v1 call on
 * the same ARNs; a tenant on the ARN SES stored for it, whose id its name does not carry.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SesIamIntegrationTest {

    private static final String REGION = "us-east-1";

    @TestHTTPResource("/")
    URI endpoint;

    @Inject
    IamService iam;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aConfigurationSetScopedAllowReachesThatConfigurationSetAndRefusesAnother() throws Exception {
        Caller caller = caller("[\"ses:GetConfigurationSet\",\"ses:ListTagsForResource\"]", "configuration-set/allowed-*");
        String allowed = "allowed-" + suffix();
        String other = "other-" + suffix();
        for (String name : new String[] {allowed, other}) {
            send(caller.owner(), "POST", "/v2/email/configuration-sets", "{\"ConfigurationSetName\":\"" + name + "\"}")
                    .then().statusCode(200);
        }

        send(caller.signer(), "GET", "/v2/email/configuration-sets/" + allowed, null).then().statusCode(200)
                .body("ConfigurationSetName", equalTo(allowed));
        denied(send(caller.signer(), "GET", "/v2/email/configuration-sets/" + other, null), "ses:GetConfigurationSet");
        send(caller.signer(), "GET", tags(caller.arn("configuration-set/" + allowed)), null).then().statusCode(200);
        denied(send(caller.signer(), "GET", tags(caller.arn("configuration-set/" + other)), null),
                "ses:ListTagsForResource");
    }

    @Test
    void anIdentityScopedAllowReachesThatIdentityAndRefusesAnother() throws Exception {
        String allowed = "allowed-" + suffix() + "@example.com";
        String other = "other-" + suffix() + "@example.com";
        Caller caller = caller("[\"ses:GetEmailIdentity\",\"ses:ListTagsForResource\"]", "identity/" + allowed);
        for (String identity : new String[] {allowed, other}) {
            send(caller.owner(), "POST", "/v2/email/identities", "{\"EmailIdentity\":\"" + identity + "\"}")
                    .then().statusCode(200);
        }

        send(caller.signer(), "GET", "/v2/email/identities/" + allowed, null).then().statusCode(200);
        denied(send(caller.signer(), "GET", "/v2/email/identities/" + other, null), "ses:GetEmailIdentity");
        send(caller.signer(), "GET", tags(caller.arn("identity/" + allowed)), null).then().statusCode(200);
        denied(send(caller.signer(), "GET", tags(caller.arn("identity/" + other)), null), "ses:ListTagsForResource");
    }

    @Test
    void aTemplateScopedAllowReachesThatTemplateAndRefusesAnother() throws Exception {
        String allowed = "allowed-" + suffix();
        String other = "other-" + suffix();
        Caller caller = caller("[\"ses:GetEmailTemplate\",\"ses:ListTagsForResource\"]", "template/" + allowed);
        for (String name : new String[] {allowed, other}) {
            send(caller.owner(), "POST", "/v2/email/templates", "{\"TemplateName\":\"" + name
                    + "\",\"TemplateContent\":{\"Subject\":\"s\",\"Text\":\"t\"}}").then().statusCode(200);
        }

        send(caller.signer(), "GET", "/v2/email/templates/" + allowed, null).then().statusCode(200)
                .body("TemplateName", equalTo(allowed));
        denied(send(caller.signer(), "GET", "/v2/email/templates/" + other, null), "ses:GetEmailTemplate");
        send(caller.signer(), "GET", tags(caller.arn("template/" + allowed)), null).then().statusCode(200);
        denied(send(caller.signer(), "GET", tags(caller.arn("template/" + other)), null), "ses:ListTagsForResource");
    }

    /**
     * A tenant's ARN ends in the id SES mints for it, which a request naming the tenant does not give: an allow on the
     * ARN the create returned reaches that tenant, as AWS checks it on that ARN, and refuses another.
     */
    @Test
    void aTenantIsCheckedOnTheArnStoredForItIdIncluded() throws Exception {
        Caller caller = caller("\"ses:GetTenant\"", "tenant/none/none");
        String allowed = "allowed-" + suffix();
        String other = "other-" + suffix();
        String allowedArn = send(caller.owner(), "POST", "/v2/email/tenants", "{\"TenantName\":\"" + allowed + "\"}")
                .then().statusCode(200).extract().path("TenantArn");
        send(caller.owner(), "POST", "/v2/email/tenants", "{\"TenantName\":\"" + other + "\"}").then().statusCode(200);
        Caller exact = caller.withPolicy("\"ses:GetTenant\"", allowedArn);

        send(exact.signer(), "POST", "/v2/email/tenants/get", "{\"TenantName\":\"" + allowed + "\"}").then()
                .statusCode(200).body("Tenant.TenantArn", equalTo(allowedArn));
        denied(send(exact.signer(), "POST", "/v2/email/tenants/get", "{\"TenantName\":\"" + other + "\"}"),
                "ses:GetTenant");
    }

    /**
     * The resources a request names by a name or an id are checked on their ARN whether the resource exists or not: an
     * allow on one name reaches the operation, which answers that it has none of that name, and refuses another name.
     */
    @Test
    void aNamedResourceIsCheckedOnItsArnBeforeItsServiceLooksItUp() throws Exception {
        String[][] cases = {
            {"ses:GetContactList", "contact-list", "/v2/email/contact-lists/"},
            {"ses:GetCustomVerificationEmailTemplate", "custom-verification-email-template",
                "/v2/email/custom-verification-email-templates/"},
            {"ses:GetDedicatedIpPool", "dedicated-ip-pool", "/v2/email/dedicated-ip-pools/"},
            {"ses:GetExportJob", "export-job", "/v2/email/export-jobs/"},
            {"ses:GetImportJob", "import-job", "/v2/email/import-jobs/"},
        };
        for (String[] c : cases) {
            String allowed = "allowed-" + suffix();
            Caller caller = caller("\"" + c[0] + "\"", c[1] + "/" + allowed);
            reachedItsService(send(caller.signer(), "GET", c[2] + allowed, null));
            denied(send(caller.signer(), "GET", c[2] + "other-" + suffix(), null), c[0]);
        }
    }

    /** SES v1 shares the namespace and its resource types: a v1 call is checked on the ARN its request names too. */
    @Test
    void aV1CallIsCheckedOnTheResourceItsRequestNames() throws Exception {
        String allowed = "allowed-" + suffix();
        Caller caller = caller("[\"ses:GetCustomVerificationEmailTemplate\",\"ses:DeleteConfigurationSet\"]",
                "*/" + allowed);

        query(caller.signer(), "Action=GetCustomVerificationEmailTemplate&TemplateName=" + allowed).then()
                .statusCode(400).body(containsString("CustomVerificationEmailTemplateDoesNotExist"));
        denied(query(caller.signer(), "Action=GetCustomVerificationEmailTemplate&TemplateName=other-" + suffix()),
                "ses:GetCustomVerificationEmailTemplate");
        query(caller.signer(), "Action=DeleteConfigurationSet&ConfigurationSetName=" + allowed).then()
                .statusCode(400).body(containsString("ConfigurationSetDoesNotExist"));
        denied(query(caller.signer(), "Action=DeleteConfigurationSet&ConfigurationSetName=other-" + suffix()),
                "ses:DeleteConfigurationSet");
    }

    /** A v1 call an allow on {@code *} grants is still granted, whatever resource it names. */
    @Test
    void aV1CallAnAllowOnEveryResourceGrantsIsStillGranted() throws Exception {
        Caller caller = caller("\"ses:DeleteConfigurationSet\"", "*");

        query(caller.signer(), "Action=DeleteConfigurationSet&ConfigurationSetName=missing-" + suffix()).then()
                .statusCode(400).body(containsString("ConfigurationSetDoesNotExist"));
    }

    /** A v2 create that tags what it makes is also checked as {@code ses:TagResource}, as the Service Reference lists. */
    @Test
    void aCreateThatTagsWhatItMakesAlsoNeedsTheTaggingPermission() throws Exception {
        Caller caller = caller("\"ses:CreateConfigurationSet\"", "*");
        String untagged = "untagged-" + suffix();
        String tagged = "tagged-" + suffix();

        denied(send(caller.signer(), "POST", "/v2/email/configuration-sets", "{\"ConfigurationSetName\":\"" + tagged
                + "\",\"Tags\":[{\"Key\":\"team\",\"Value\":\"mail\"}]}"), "ses:TagResource");
        send(caller.owner(), "GET", "/v2/email/configuration-sets/" + tagged, null).then().statusCode(404);
        send(caller.signer(), "POST", "/v2/email/configuration-sets", "{\"ConfigurationSetName\":\"" + untagged + "\"}")
                .then().statusCode(200);
    }

    /** An event destination delivering through a role passes that role, so it is also checked as {@code iam:PassRole}. */
    @Test
    void anEventDestinationThatPassesARoleNeedsThePassRolePermission() throws Exception {
        Caller caller = caller("\"ses:CreateConfigurationSetEventDestination\"", "*");
        String set = "passes-" + suffix();
        send(caller.owner(), "POST", "/v2/email/configuration-sets", "{\"ConfigurationSetName\":\"" + set + "\"}")
                .then().statusCode(200);

        denied(send(caller.signer(), "POST", "/v2/email/configuration-sets/" + set + "/event-destinations",
                "{\"EventDestinationName\":\"firehose\",\"EventDestination\":{\"Enabled\":true,"
                + "\"MatchingEventTypes\":[\"SEND\"],\"KinesisFirehoseDestination\":{\"IamRoleArn\":"
                + "\"arn:aws:iam::" + caller.account() + ":role/delivery\",\"DeliveryStreamArn\":"
                + "\"arn:aws:firehose:" + REGION + ":" + caller.account() + ":deliverystream/events\"}}}"),
                "iam:PassRole");
        send(caller.owner(), "GET", "/v2/email/configuration-sets/" + set + "/event-destinations", null).then()
                .statusCode(200).body("EventDestinations.size()", equalTo(0));
    }

    // Probed in us-east-1: a send from an address of a verified domain is checked on the domain's identity, an allow
    // on the address's own ARN does not reach it, and an allow on the configuration set alone does not either.
    @Test
    void aV2SendIsCheckedOnTheIdentityItsSenderSendsAs() throws Exception {
        String domain = "d-" + suffix() + ".example.com";
        String set = "send-" + suffix();
        Caller caller = caller("[\"ses:SendEmail\"]", "identity/" + domain);
        verifiedDomain(caller, domain);
        send(caller.owner(), "POST", "/v2/email/configuration-sets", "{\"ConfigurationSetName\":\"" + set + "\"}")
                .then().statusCode(200);

        send(caller.signer(), "POST", "/v2/email/outbound-emails", v2Send("sender@" + domain, null))
                .then().statusCode(200);
        denied(send(caller.withPolicy("[\"ses:SendEmail\"]", caller.arn("identity/sender@" + domain)).signer(),
                "POST", "/v2/email/outbound-emails", v2Send("sender@" + domain, null)), "ses:SendEmail");
        denied(send(caller.withPolicy("[\"ses:SendEmail\"]", caller.arn("configuration-set/" + set)).signer(),
                "POST", "/v2/email/outbound-emails", v2Send("sender@" + domain, set)), "ses:SendEmail");
        send(caller.withPolicy("[\"ses:SendEmail\"]", "*").signer(), "POST", "/v2/email/outbound-emails",
                v2Send("sender@" + domain, set)).then().statusCode(200);
    }

    @Test
    void aV1SendIsCheckedOnTheIdentityItsSourceSendsAs() throws Exception {
        String domain = "d-" + suffix() + ".example.com";
        Caller caller = caller("[\"ses:SendEmail\"]", "identity/" + domain);
        verifiedDomain(caller, domain);
        String form = "Action=SendEmail&Source=sender%40" + domain
                + "&Destination.ToAddresses.member.1=success%40simulator.amazonses.com"
                + "&Message.Subject.Data=s&Message.Body.Text.Data=b";

        query(caller.signer(), form).then().statusCode(200);
        denied(query(caller.withPolicy("[\"ses:SendEmail\"]", caller.arn("identity/other.example.com")).signer(),
                form), "ses:SendEmail");
    }

    // Probed in us-east-1: a raw send with no Source is checked on the identity of its "From" header.
    @Test
    void aV1RawSendWithoutSourceIsCheckedOnTheIdentityOfItsFromHeader() throws Exception {
        String domain = "d-" + suffix() + ".example.com";
        Caller caller = caller("[\"ses:SendRawEmail\"]", "identity/" + domain);
        verifiedDomain(caller, domain);
        String message = "From: sender@" + domain + "\r\nTo: success@simulator.amazonses.com\r\nSubject: s\r\n\r\nb\r\n";
        String form = "Action=SendRawEmail&Destinations.member.1=success%40simulator.amazonses.com&RawMessage.Data="
                + URLEncoder.encode(Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8);

        query(caller.signer(), form).then().statusCode(200);
        denied(query(caller.withPolicy("[\"ses:SendRawEmail\"]", caller.arn("identity/other.example.com")).signer(),
                form), "ses:SendRawEmail");
    }

    /**
     * A domain identity of the caller's account SES has verified: its DKIM records published in a hosted zone of that
     * account, which SES looks them up in.
     */
    private void verifiedDomain(Caller caller, String domain) throws Exception {
        List<String> tokens = send(caller.owner(), "POST", "/v2/email/identities",
                "{\"EmailIdentity\":\"" + domain + "\"}").then().statusCode(200)
                .extract().jsonPath().getList("DkimAttributes.Tokens", String.class);
        AccessKey dns = SignedIamUsers.createUser(iam, cleanup, caller.account(), "dns-owner-" + suffix(), "access",
                allow("\"route53:*\"", "*"));
        AwsRequestSigner route53 = AwsRequestSigner.signedAs(dns.getAccessKeyId(), dns.getSecretAccessKey(), "route53");
        String location = SignedRestRequests.send(endpoint, route53, "POST", "/2013-04-01/hostedzone",
                "<CreateHostedZoneRequest xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><Name>" + domain
                        + "</Name><CallerReference>" + suffix() + "</CallerReference></CreateHostedZoneRequest>",
                "application/xml").then().statusCode(201).extract().header("Location");
        StringBuilder changes = new StringBuilder();
        for (String token : tokens) {
            changes.append("<Change><Action>CREATE</Action><ResourceRecordSet><Name>").append(token)
                    .append("._domainkey.").append(domain).append(".</Name><Type>CNAME</Type><TTL>300</TTL>")
                    .append("<ResourceRecords><ResourceRecord><Value>").append(token)
                    .append(".dkim.amazonses.com.</Value></ResourceRecord></ResourceRecords></ResourceRecordSet>")
                    .append("</Change>");
        }
        SignedRestRequests.send(endpoint, route53, "POST",
                "/2013-04-01/hostedzone/" + location.substring(location.lastIndexOf('/') + 1) + "/rrset",
                "<ChangeResourceRecordSetsRequest xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"><ChangeBatch>"
                        + "<Changes>" + changes + "</Changes></ChangeBatch></ChangeResourceRecordSetsRequest>",
                "application/xml").then().statusCode(200);
        send(caller.owner(), "GET", "/v2/email/identities/" + domain, null).then().statusCode(200)
                .body("VerifiedForSendingStatus", equalTo(true));
    }

    private static String v2Send(String from, String configurationSet) {
        return "{\"FromEmailAddress\":\"" + from + "\","
                + (configurationSet == null ? "" : "\"ConfigurationSetName\":\"" + configurationSet + "\",")
                + "\"Destination\":{\"ToAddresses\":[\"success@simulator.amazonses.com\"]},"
                + "\"Content\":{\"Simple\":{\"Subject\":{\"Data\":\"s\"},\"Body\":{\"Text\":{\"Data\":\"b\"}}}}}";
    }

    /** IAM let the request through to SES, which refused it for naming nothing it holds. */
    private static void reachedItsService(Response response) {
        response.then().statusCode(anyOf(equalTo(400), equalTo(404))).body(not(containsString("not authorized")));
    }

    /** A user of a fresh account allowed {@code actions} on {@code resource}, with an owner allowed every SES action. */
    private Caller caller(String actions, String resource) {
        String account = TestAccounts.randomId();
        return new Caller(this, account, user(account, "ses-owner", allow("\"ses:*\"", "*")),
                user(account, "ses-caller", allow(actions, resource.startsWith("arn:") || "*".equals(resource)
                        ? resource : arn(account, resource))));
    }

    private record Caller(SesIamIntegrationTest test, String account, AwsRequestSigner owner,
                          AwsRequestSigner signer) {

        String arn(String resource) {
            return SesIamIntegrationTest.arn(account, resource);
        }

        /** Another user of the same account, allowed {@code actions} on {@code resource}. */
        Caller withPolicy(String actions, String resource) {
            return new Caller(test, account, owner, test.user(account, "ses-exact", allow(actions, resource)));
        }
    }

    private static String arn(String account, String resource) {
        return "arn:aws:ses:" + REGION + ":" + account + ":" + resource;
    }

    private AwsRequestSigner user(String account, String name, String policy) {
        AccessKey key = SignedIamUsers.createUser(iam, cleanup, account, name + "-" + suffix(), "access", policy);
        return AwsRequestSigner.signedAs(key.getAccessKeyId(), key.getSecretAccessKey(), "ses");
    }

    private static String tags(String arn) {
        return "/v2/email/tags?ResourceArn=" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Response send(AwsRequestSigner signer, String method, String path, String body) throws Exception {
        return SignedRestRequests.send(endpoint, signer, method, path, body, "application/json");
    }

    private Response query(AwsRequestSigner signer, String form) throws Exception {
        String body = form + "&Version=2010-12-01";
        return given().headers(signer.headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
    }
}

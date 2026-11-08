package io.floci.aws.services.cognito.verification;

import io.floci.aws.services.cognito.model.CognitoUser;
import io.floci.aws.services.cognito.model.UserPool;
import io.floci.aws.services.ses.SesService;
import io.floci.aws.services.ses.model.EmailContent;
import io.floci.aws.services.ses.model.SendEmailRequest;
import io.floci.aws.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CognitoMessageDispatcherTest {

    private SesService ses;
    private SnsService sns;
    private CognitoMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ses = mock(SesService.class);
        sns = mock(SnsService.class);
        dispatcher = new CognitoMessageDispatcher(ses, sns, "us-east-1");
    }

    @Test
    void dispatch_emailFlow_callsSesWithRenderedTemplate() {
        UserPool pool = pool(Map.of(
            "EmailSubject", "Verify your account",
            "EmailMessage", "Hi! Your code is {####}."
        ));
        CognitoUser user = user("alice@example.com", null);

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "123456", List.of("EMAIL"));

        SendEmailRequest sent = sentEmail();
        assertEquals(SendEmailRequest.builder()
            .source(sent.source())
            .toAddresses(List.of("alice@example.com"))
            .region("us-east-1")
            .content(new EmailContent.Simple("Verify your account", "Hi! Your code is 123456.", null, List.of()))
            .build(), sent);
        verifyNoInteractions(sns);
    }

    @Test
    void dispatch_smsSignupFlow_usesSmsMessageTemplate() {
        UserPool pool = pool(Map.of(
            "SmsMessage", "Signup: {####}",
            "SmsAuthenticationMessage", "MFA: {####}"
        ));
        CognitoUser user = user("alice@example.com", "+5215551234567");

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "654321", List.of("SMS"));

        verify(sns).publish(
            isNull(), isNull(),
            eq("+5215551234567"),
            eq("Signup: 654321"),
            isNull(), isNull(), eq("us-east-1"));
        verifyNoInteractions(ses);
    }

    @Test
    void dispatch_smsMfaFlow_usesSmsAuthenticationMessageTemplate() {
        // SmsAuthenticationMessage is a top-level UserPool attribute, not part of
        // VerificationMessageTemplate (which only holds SmsMessage for verification codes).
        UserPool pool = pool(Map.of("SmsMessage", "Signup: {####}"));
        pool.setSmsAuthenticationMessage("MFA: {####}");
        CognitoUser user = user("alice@example.com", "+5215551234567");

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SMS_MFA,
            "654321", List.of("SMS"));

        verify(sns).publish(
            isNull(), isNull(),
            eq("+5215551234567"),
            eq("MFA: 654321"),
            isNull(), isNull(), eq("us-east-1"));
        verifyNoInteractions(ses);
    }

    @Test
    void dispatch_smsOtpFlow_usesSmsAuthenticationMessageTemplate() {
        // Per AWS: SmsAuthenticationMessage covers "SMS OTP and MFA authentication", not just MFA.
        UserPool pool = pool(Map.of("SmsMessage", "Signup: {####}"));
        pool.setSmsAuthenticationMessage("Auth code: {####}");
        CognitoUser user = user("alice@example.com", "+5215551234567");

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SMS_OTP,
            "654321", List.of("SMS"));

        verify(sns).publish(
            isNull(), isNull(),
            eq("+5215551234567"),
            eq("Auth code: 654321"),
            isNull(), isNull(), eq("us-east-1"));
        verifyNoInteractions(ses);
    }

    @Test
    void dispatch_emptyTemplate_usesDefaults() {
        UserPool pool = pool(Map.of());
        CognitoUser user = user("alice@example.com", null);

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "111222", List.of("EMAIL"));

        SendEmailRequest sent = sentEmail();
        assertEquals(SendEmailRequest.builder()
            .source(sent.source())
            .toAddresses(List.of("alice@example.com"))
            .region("us-east-1")
            .content(new EmailContent.Simple("Your verification code", "Your verification code is 111222.",
                null, List.of()))
            .build(), sent);
    }

    @Test
    void dispatch_templateWithoutPlaceholder_appendsFailsafe() {
        UserPool pool = pool(Map.of("EmailMessage", "Welcome, please verify."));
        CognitoUser user = user("alice@example.com", null);

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "999000", List.of("EMAIL"));

        SendEmailRequest sent = sentEmail();
        assertNotNull(subject(sent));
        assertEquals(SendEmailRequest.builder()
            .source(sent.source())
            .toAddresses(List.of("alice@example.com"))
            .region("us-east-1")
            .content(new EmailContent.Simple(subject(sent), "Welcome, please verify.\nCode: 999000", null, List.of()))
            .build(), sent);
    }

    @Test
    void dispatch_missingDeliveryMediums_emailFallbackWhenEmailPresent() {
        UserPool pool = pool(Map.of());
        CognitoUser user = user("alice@example.com", "+5215551234567");

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "444555", List.of());

        SendEmailRequest sent = sentEmail();
        assertNotNull(subject(sent));
        assertNotNull(bodyText(sent));
        assertEquals(SendEmailRequest.builder()
            .source(sent.source())
            .toAddresses(List.of("alice@example.com"))
            .region("us-east-1")
            .content(new EmailContent.Simple(subject(sent), bodyText(sent), null, List.of()))
            .build(), sent);
        verifyNoInteractions(sns);
    }

    @Test
    void dispatch_missingDeliveryMediums_smsFallbackWhenOnlyPhone() {
        UserPool pool = pool(Map.of());
        CognitoUser user = user(null, "+5215551234567");

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "777888", List.of());

        verify(sns).publish(isNull(), isNull(), eq("+5215551234567"),
            anyString(), isNull(), isNull(), anyString());
        verifyNoInteractions(ses);
    }

    @Test
    void dispatch_sendsFromThePoolsOwnRegion() {
        UserPool pool = pool(Map.of());
        pool.setArn("arn:aws-cn:cognito-idp:cn-north-1:000000000000:userpool/cn-north-1_AbCdEfGhI");

        dispatcher.dispatch(pool, user("alice@example.com", "+5215551234567"),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL", "SMS"));

        assertEquals("cn-north-1", sentEmail().region());
        verify(sns).publish(isNull(), isNull(), eq("+5215551234567"), anyString(), isNull(), isNull(),
            eq("cn-north-1"));
    }

    @Test
    void dispatch_poolWithoutAnArnSendsFromTheDeploymentDefaultRegion() {
        CognitoMessageDispatcher china = new CognitoMessageDispatcher(ses, sns, "cn-northwest-1");

        china.dispatch(pool(Map.of()), user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("cn-northwest-1", sentEmail().region());
    }

    @Test
    void dispatch_withoutEmailConfiguration_sendsFromTheDefaultAddress() {
        dispatcher.dispatch(pool(Map.of()), user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_developerSendingAccount_sendsFromTheConfiguredFrom() {
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example",
            "From", "Repro App <noreply@repro.example>"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("Repro App <noreply@repro.example>", sentEmail().source());
    }

    @Test
    void dispatch_senderName_staysInTheFromHeaderAndLeavesTheReturnPathBare() {
        // SES takes Source as the default return path, the envelope sender, which takes a bare
        // address.
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example",
            "From", "Repro App <noreply@repro.example>"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        SendEmailRequest sent = sentEmail();
        assertEquals("Repro App <noreply@repro.example>", sent.source());
        assertEquals("noreply@repro.example", sent.returnPath());
    }

    @Test
    void dispatch_developerSendingAccountWithoutFrom_sendsFromTheSourceArnAddress() {
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("noreply@repro.example", sentEmail().source());
    }

    @Test
    void dispatch_cognitoDefaultSendingAccount_sendsFromTheSourceArnAddressWithoutTheSenderName() {
        // With COGNITO_DEFAULT the SourceArn address is the custom FROM address; a sender name in
        // From is available only with the pool's own SES (DEVELOPER).
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "COGNITO_DEFAULT",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example",
            "From", "Repro App <noreply@repro.example>"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("noreply@repro.example", sentEmail().source());
    }

    @Test
    void dispatch_cognitoDefaultSendingAccountWithoutSourceArn_ignoresFrom() {
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "COGNITO_DEFAULT",
            "From", "noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_domainIdentity_sendsFromTheConfiguredFrom() {
        // A domain identity names no address of its own, so From supplies it.
        verified("repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/repro.example",
            "From", "noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("noreply@repro.example", sentEmail().source());
    }

    @Test
    void dispatch_unverifiedSourceArnIdentity_sendsFromTheDefaultAddress() {
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example",
            "From", "Repro App <noreply@repro.example>"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_developerSendingAccountFromWithoutSourceArn_sendsFromTheDefaultAddress() {
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "From", "Repro App <noreply@repro.example>"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_fromOutsideTheEmailIdentity_sendsFromTheDefaultAddress() {
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example",
            "From", "Billing <billing@bank.example>"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_fromOutsideTheDomainIdentity_sendsFromTheDefaultAddress() {
        verified("repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/repro.example",
            "From", "noreply@bank.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_fromInASubdomainOfTheDomainIdentity_sendsFromTheConfiguredFrom() {
        // SES: a verified domain covers its subdomains without identities of their own.
        verified("repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/repro.example",
            "From", "no-reply@auth.Repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@auth.Repro.example", sentEmail().source());
    }

    @Test
    void dispatch_fromThatDiffersFromTheEmailIdentityOnlyInCase_sendsFromTheDefaultAddress() {
        // SES: email address identities are case sensitive, domain part included.
        verified("noreply@REPRO.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@REPRO.example",
            "From", "noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_fromThatIsNotOneMailbox_sendsFromTheDefaultAddress() {
        verified("repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/repro.example",
            "From", "noreply@repro.example, billing@bank.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_sourceArnInAnotherAccount_sendsFromTheDefaultAddress() {
        when(ses.isVerifiedIdentity(anyString(), anyString())).thenReturn(true);
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:111122223333:identity/noreply@repro.example",
            "From", "noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_sourceArnInAnotherPartition_sendsFromTheDefaultAddress() {
        verified("noreply@repro.example", "cn-north-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws-cn:ses:cn-north-1:000000000000:identity/noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_identityVerifiedOnlyOutsideTheSourceArnRegion_sendsFromTheDefaultAddress() {
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:eu-west-1:000000000000:identity/noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("no-reply@verificationemail.com", sentEmail().source());
    }

    @Test
    void dispatch_wildcardRegionSourceArn_checksTheIdentityInThePoolsRegion() {
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "SourceArn", "arn:aws:ses:*:000000000000:identity/noreply@repro.example"));

        dispatcher.dispatch(pool, user("alice@example.com", null),
            VerificationCode.Purpose.SIGNUP_CONFIRMATION, "123456", List.of("EMAIL"));

        assertEquals("noreply@repro.example", sentEmail().source());
    }

    @Test
    void dispatchInvitation_withoutTemplate_emailsAwsDefaultInvitation() {
        dispatcher.dispatchInvitation(pool(Map.of()), invitee("alice@example.com", null), "Temp1234!",
            List.of("EMAIL"), null);

        assertEquals(SendEmailRequest.builder()
            .source("no-reply@verificationemail.com")
            .toAddresses(List.of("alice@example.com"))
            .region("us-east-1")
            .content(new EmailContent.Simple("Your temporary password",
                "Your username is alice and temporary password is Temp1234!.", null, List.of()))
            .build(), sentEmail());
        verifyNoInteractions(sns);
    }

    @Test
    void dispatchInvitation_rendersThePoolsInviteMessageTemplateOnBothMediums() {
        UserPool pool = invitePool(Map.of(
            "EmailSubject", "Welcome to Repro",
            "EmailMessage", "Hi {username}, sign in with {####}",
            "SMSMessage", "{username} / {####}"));

        dispatcher.dispatchInvitation(pool, invitee("alice@example.com", "+5215551234567"), "Temp1234!",
            List.of("EMAIL", "SMS"), null);

        SendEmailRequest sent = sentEmail();
        assertEquals("Welcome to Repro", subject(sent));
        assertEquals("Hi alice, sign in with Temp1234!", bodyText(sent));
        verify(sns).publish(isNull(), isNull(), eq("+5215551234567"), eq("alice / Temp1234!"),
            isNull(), isNull(), eq("us-east-1"));
    }

    @Test
    void dispatchInvitation_sendsOnlyThroughTheRequestedMedium() {
        dispatcher.dispatchInvitation(pool(Map.of()), invitee("alice@example.com", "+5215551234567"), "Temp1234!",
            List.of("SMS"), null);

        verify(sns).publish(isNull(), isNull(), eq("+5215551234567"),
            eq("Your username is alice and temporary password is Temp1234!."), isNull(), isNull(), eq("us-east-1"));
        verifyNoInteractions(ses);
    }

    @Test
    void dispatchInvitation_customMessageResponse_overridesThePoolsTemplate() {
        UserPool pool = invitePool(Map.of("EmailSubject", "Pool subject", "EmailMessage", "Pool {####}"));

        dispatcher.dispatchInvitation(pool, invitee("alice@example.com", null), "Temp1234!", List.of("EMAIL"),
            Map.of("emailSubject", "Trigger subject", "emailMessage", "Trigger {username} {####}"));

        SendEmailRequest sent = sentEmail();
        assertEquals("Trigger subject", subject(sent));
        assertEquals("Trigger alice Temp1234!", bodyText(sent));
    }

    @Test
    void dispatchInvitation_sendsFromThePoolsEmailConfigurationSender() {
        verified("noreply@repro.example", "us-east-1");
        UserPool pool = emailConfiguredPool(Map.of(
            "EmailSendingAccount", "DEVELOPER",
            "SourceArn", "arn:aws:ses:us-east-1:000000000000:identity/noreply@repro.example",
            "From", "Repro App <noreply@repro.example>"));

        dispatcher.dispatchInvitation(pool, invitee("alice@example.com", null), "Temp1234!", List.of("EMAIL"), null);

        SendEmailRequest sent = sentEmail();
        assertEquals("Repro App <noreply@repro.example>", sent.source());
        assertEquals("noreply@repro.example", sent.returnPath());
    }

    @Test
    void dispatchInvitation_templateWithoutPasswordPlaceholder_isNotDelivered() {
        // "If your template doesn't have this placeholder, Amazon Cognito doesn't deliver the invitation message."
        UserPool pool = invitePool(Map.of("EmailMessage", "Welcome {username}"));

        dispatcher.dispatchInvitation(pool, invitee("alice@example.com", null), "Temp1234!", List.of("EMAIL"), null);

        verifyNoInteractions(ses);
    }

    @Test
    void dispatchInvitation_passwordlessUser_getsTheUsernameOnly() {
        dispatcher.dispatchInvitation(pool(Map.of()), invitee("alice@example.com", null), null, List.of("EMAIL"), null);

        assertEquals("Your username is alice.", bodyText(sentEmail()));
    }

    @Test
    void dispatchInvitation_passwordlessUser_leavesTheTemplatesPasswordPlaceholderEmpty() {
        UserPool pool = invitePool(Map.of("EmailMessage", "Hi {username}, your password: {####}"));

        dispatcher.dispatchInvitation(pool, invitee("alice@example.com", null), null, List.of("EMAIL"), null);

        assertEquals("Hi alice, your password: ", bodyText(sentEmail()));
    }

    @Test
    void dispatchInvitation_fillsBothPlaceholdersInOnePass() {
        CognitoUser user = invitee("alice@example.com", null);
        user.setUsername("{####}");

        dispatcher.dispatchInvitation(pool(Map.of()), user, "pw{username}1A!", List.of("EMAIL"), null);

        assertEquals("Your username is {####} and temporary password is pw{username}1A!.", bodyText(sentEmail()));
    }

    @Test
    void dispatchInvitation_failureOnTheOnlyMedium_isRethrown() {
        when(ses.sendServiceEmail(any())).thenThrow(new IllegalStateException("SES down"));

        assertThrows(IllegalStateException.class, () -> dispatcher.dispatchInvitation(pool(Map.of()),
            invitee("alice@example.com", null), "Temp1234!", List.of("EMAIL"), null));
    }

    @Test
    void dispatchInvitation_failureAfterAnotherMediumDelivered_isNotRethrown() {
        when(sns.publish(any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("SNS down"));

        assertDoesNotThrow(() -> dispatcher.dispatchInvitation(pool(Map.of()),
            invitee("alice@example.com", "+5215551234567"), "Temp1234!", List.of("EMAIL", "SMS"), null));
        assertEquals("alice@example.com", sentEmail().toAddresses().get(0));
    }

    @Test
    void dispatchInvitation_userWithoutTheRequestedAttribute_sendsNothing() {
        assertDoesNotThrow(() -> dispatcher.dispatchInvitation(pool(Map.of()), invitee("alice@example.com", null),
            "Temp1234!", List.of("SMS"), null));

        verifyNoInteractions(ses, sns);
    }

    private SendEmailRequest sentEmail() {
        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendServiceEmail(captor.capture());
        return captor.getValue();
    }

    private static String subject(SendEmailRequest sent) {
        return assertInstanceOf(EmailContent.Simple.class, sent.content()).subject();
    }

    private static String bodyText(SendEmailRequest sent) {
        return assertInstanceOf(EmailContent.Simple.class, sent.content()).bodyText();
    }

    private void verified(String identity, String region) {
        when(ses.isVerifiedIdentity(identity, region)).thenReturn(true);
    }

    private UserPool emailConfiguredPool(Map<String, Object> emailConfiguration) {
        UserPool p = pool(Map.of());
        p.setArn("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_AbCdEfGhI");
        p.setEmailConfiguration(emailConfiguration);
        return p;
    }

    private UserPool invitePool(Map<String, Object> inviteMessageTemplate) {
        UserPool p = pool(Map.of());
        p.setAdminCreateUserConfig(Map.of("InviteMessageTemplate", inviteMessageTemplate));
        return p;
    }

    private CognitoUser invitee(String email, String phone) {
        CognitoUser u = user(email, phone);
        u.setUsername("alice");
        return u;
    }

    private UserPool pool(Map<String, Object> template) {
        UserPool p = new UserPool();
        p.setVerificationMessageTemplate(new HashMap<>(template));
        return p;
    }

    private CognitoUser user(String email, String phone) {
        CognitoUser u = new CognitoUser();
        Map<String, String> attrs = new HashMap<>();
        if (email != null) attrs.put("email", email);
        if (phone != null) attrs.put("phone_number", phone);
        u.setAttributes(attrs);
        return u;
    }
}

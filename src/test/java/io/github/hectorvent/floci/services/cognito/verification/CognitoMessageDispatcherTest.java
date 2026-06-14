package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CognitoMessageDispatcherTest {

    private SesService ses;
    private SnsService sns;
    private CognitoMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ses = mock(SesService.class);
        sns = mock(SnsService.class);
        dispatcher = new CognitoMessageDispatcher(ses, sns);
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

        verify(ses).sendEmail(
            anyString(),
            eq(List.of("alice@example.com")),
            eq(List.of()), eq(List.of()), eq(List.of()),
            eq("Verify your account"),
            eq("Hi! Your code is 123456."),
            isNull(), isNull(), eq(List.of()), eq(List.of()),
            eq("us-east-1"));
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
    void dispatch_emptyTemplate_usesDefaults() {
        UserPool pool = pool(Map.of());
        CognitoUser user = user("alice@example.com", null);

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "111222", List.of("EMAIL"));

        verify(ses).sendEmail(
            anyString(), eq(List.of("alice@example.com")),
            eq(List.of()), eq(List.of()), eq(List.of()),
            eq("Your verification code"),
            eq("Your verification code is 111222."),
            isNull(), isNull(), eq(List.of()), eq(List.of()), eq("us-east-1"));
    }

    @Test
    void dispatch_templateWithoutPlaceholder_appendsFailsafe() {
        UserPool pool = pool(Map.of("EmailMessage", "Welcome, please verify."));
        CognitoUser user = user("alice@example.com", null);

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "999000", List.of("EMAIL"));

        verify(ses).sendEmail(
            anyString(), eq(List.of("alice@example.com")),
            eq(List.of()), eq(List.of()), eq(List.of()),
            anyString(),
            eq("Welcome, please verify.\nCode: 999000"),
            isNull(), isNull(), eq(List.of()), eq(List.of()), eq("us-east-1"));
    }

    @Test
    void dispatch_missingDeliveryMediums_emailFallbackWhenEmailPresent() {
        UserPool pool = pool(Map.of());
        CognitoUser user = user("alice@example.com", "+5215551234567");

        dispatcher.dispatch(pool, user, VerificationCode.Purpose.SIGNUP_CONFIRMATION,
            "444555", List.of());

        verify(ses).sendEmail(anyString(), eq(List.of("alice@example.com")),
            any(), any(), any(), anyString(), anyString(), isNull(), isNull(), any(), any(), anyString());
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

package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.sns.SnsService;

import java.util.List;
import java.util.Map;

/**
 * Routes a verification code through SES (email) or SNS (SMS) according to
 * {@link UserPool#getVerificationMessageTemplate()} and the requested delivery
 * mediums. Renders the {@code {####}} placeholder; appends a failsafe line if
 * the template lacks the placeholder.
 */
public final class CognitoMessageDispatcher {

    private static final String DEFAULT_EMAIL_SUBJECT = "Your verification code";
    private static final String DEFAULT_EMAIL_BODY = "Your code is {####}";
    private static final String DEFAULT_SMS_BODY = "Your code is {####}";
    private static final String DEFAULT_FROM = "no-reply@verificationemail.com";
    private static final String DEFAULT_REGION = "us-east-1";
    private static final String CODE_PLACEHOLDER = "{####}";

    private final SesService ses;
    private final SnsService sns;

    public CognitoMessageDispatcher(SesService ses, SnsService sns) {
        this.ses = ses;
        this.sns = sns;
    }

    public void dispatch(UserPool pool, CognitoUser user, VerificationCode.Purpose purpose,
                         String code, List<String> deliveryMediums) {

        Map<String, Object> template = pool.getVerificationMessageTemplate();
        if (template == null) template = Map.of();
        String email = user.getAttributes().get("email");
        String phone = user.getAttributes().get("phone_number");

        List<String> mediums = resolveDeliveryMediums(deliveryMediums, email, phone);

        for (String medium : mediums) {
            if ("EMAIL".equalsIgnoreCase(medium) && email != null) {
                String subject = stringOr(template.get("EmailSubject"), DEFAULT_EMAIL_SUBJECT);
                String body = renderTemplate(stringOr(template.get(emailTemplateKey(purpose)), DEFAULT_EMAIL_BODY), code);
                ses.sendEmail(
                    DEFAULT_FROM,
                    List.of(email),
                    List.of(), List.of(), List.of(),
                    subject,
                    body,
                    null,
                    DEFAULT_REGION
                );
            } else if ("SMS".equalsIgnoreCase(medium) && phone != null) {
                String body = renderTemplate(stringOr(template.get(smsTemplateKey(purpose)), DEFAULT_SMS_BODY), code);
                sns.publish(
                    null, null,
                    phone,
                    body,
                    null,
                    null,
                    DEFAULT_REGION
                );
            }
        }
    }

    /**
     * Cognito uses different template keys per purpose. For email there's only
     * {@code EmailMessage} today; we keep this method as a single hook so future
     * Cognito additions (e.g. {@code EmailMessageByLink}) plug in cleanly.
     */
    private String emailTemplateKey(VerificationCode.Purpose purpose) {
        return "EmailMessage";
    }

    /**
     * For SMS, Cognito uses {@code SmsAuthenticationMessage} when sending an MFA
     * challenge code, and {@code SmsMessage} for signup/verification codes.
     */
    private String smsTemplateKey(VerificationCode.Purpose purpose) {
        return purpose == VerificationCode.Purpose.SMS_MFA
                ? "SmsAuthenticationMessage"
                : "SmsMessage";
    }

    private List<String> resolveDeliveryMediums(List<String> requested, String email, String phone) {
        if (requested != null && !requested.isEmpty()) return requested;
        if (email != null) return List.of("EMAIL");
        if (phone != null) return List.of("SMS");
        return List.of();
    }

    private String stringOr(Object value, String fallback) {
        if (value == null) return fallback;
        String s = value.toString();
        return s.isEmpty() ? fallback : s;
    }

    private String renderTemplate(String template, String code) {
        if (template.contains(CODE_PLACEHOLDER)) {
            return template.replace(CODE_PLACEHOLDER, code);
        }
        return template + "\nCode: " + code;
    }
}

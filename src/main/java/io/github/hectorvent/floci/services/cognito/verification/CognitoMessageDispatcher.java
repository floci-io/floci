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

    // Defaults match AWS Cognito's out-of-the-box verification messages (used only when the
    // user pool configures no VerificationMessageTemplate).
    private static final String DEFAULT_EMAIL_SUBJECT = "Your verification code";
    private static final String DEFAULT_EMAIL_BODY = "Your verification code is {####}.";
    private static final String DEFAULT_SMS_BODY = "Your verification code is {####}.";
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
                String body = renderTemplate(stringOr(template.get(emailTemplateKey()), DEFAULT_EMAIL_BODY), code);
                ses.sendEmail(
                    DEFAULT_FROM,
                    List.of(email),
                    List.of(), List.of(), List.of(),
                    subject,
                    body,
                    null,          // bodyHtml
                    null,          // configurationSetName
                    List.of(),     // emailTags
                    List.of(),     // additionalHeaders
                    DEFAULT_REGION
                );
            } else if ("SMS".equalsIgnoreCase(medium) && phone != null) {
                String body = renderTemplate(stringOr(resolveSmsTemplate(pool, template, purpose), DEFAULT_SMS_BODY), code);
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
     * Email template key. Returns the code-based {@code EmailMessage} ({@code {####}}).
     * TODO: honor the pool's {@code DefaultEmailOption}: when set to {@code CONFIRM_WITH_LINK},
     * AWS uses {@code EmailMessageByLink}/{@code EmailSubjectByLink} ({@code {##Verify Email##}})
     * instead of the code template. Code-only is sufficient for the current foundation scope.
     */
    private String emailTemplateKey() {
        return "EmailMessage";
    }

    /**
     * Resolves the raw SMS template from the correct AWS source for the purpose.
     * {@code SmsAuthenticationMessage} (MFA) is a top-level UserPool attribute, NOT part of
     * the VerificationMessageTemplate. For verification/signup codes, the template's
     * {@code SmsMessage} takes precedence over the legacy top-level SmsVerificationMessage.
     */
    private String resolveSmsTemplate(UserPool pool, Map<String, Object> template,
                                      VerificationCode.Purpose purpose) {
        if (purpose == VerificationCode.Purpose.SMS_MFA) {
            return pool.getSmsAuthenticationMessage();
        }
        Object sms = template.get("SmsMessage");
        if (sms != null && !sms.toString().isEmpty()) {
            return sms.toString();
        }
        return pool.getSmsVerificationMessage();
    }

    // TODO: when no medium is explicitly requested, the choice should come from the pool's
    // AutoVerifiedAttributes config. In AWS's "SMS if phone available, otherwise email" mode,
    // Cognito prefers the phone number when both contacts are present; this fallback prefers email.
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

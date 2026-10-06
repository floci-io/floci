package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.field.address.DefaultAddressParser;
import org.apache.james.mime4j.field.address.ParseException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Routes a verification code through SES (email) or SNS (SMS) according to
 * {@link UserPool#getVerificationMessageTemplate()} and the requested delivery
 * mediums. Renders the {@code {####}} placeholder; appends a failsafe line if
 * the template lacks the placeholder.
 *
 * The caller may supply the response of a CustomMessage Lambda trigger invocation,
 * whose {@code emailSubject}/{@code emailMessage}/{@code smsMessage} take precedence
 * over the pool's own template.
 *
 * Email is sent from the address the pool's {@code EmailConfiguration} names when SES has verified
 * it for the pool's account, or from {@code no-reply@verificationemail.com} otherwise.
 */
public final class CognitoMessageDispatcher {

    private static final Logger LOG = Logger.getLogger(CognitoMessageDispatcher.class);

    // Defaults match AWS Cognito's out-of-the-box verification messages (used only when the
    // user pool configures no VerificationMessageTemplate).
    private static final String DEFAULT_EMAIL_SUBJECT = "Your verification code";
    private static final String DEFAULT_EMAIL_BODY = "Your verification code is {####}.";
    private static final String DEFAULT_SMS_BODY = "Your verification code is {####}.";
    private static final String DEFAULT_FROM = "no-reply@verificationemail.com";
    private static final Sender DEFAULT_SENDER = new Sender(DEFAULT_FROM, DEFAULT_FROM);
    private static final String DEVELOPER_SENDING_ACCOUNT = "DEVELOPER";
    private static final String SES_IDENTITY_RESOURCE_PREFIX = "identity/";
    private static final String CODE_PLACEHOLDER = "{####}";

    private final SesService ses;
    private final SnsService sns;
    private final String defaultRegion;

    /** {@code defaultRegion} serves a pool whose ARN names no region; a pool otherwise sends from its own. */
    public CognitoMessageDispatcher(SesService ses, SnsService sns, String defaultRegion) {
        this.ses = ses;
        this.sns = sns;
        this.defaultRegion = defaultRegion;
    }

    public void dispatch(UserPool pool, CognitoUser user, VerificationCode.Purpose purpose,
                         String code, List<String> deliveryMediums) {
        dispatch(pool, user, purpose, code, deliveryMediums, null);
    }

    /**
     * Same as {@link #dispatch(UserPool, CognitoUser, VerificationCode.Purpose, String, List)},
     * but takes the response of a CustomMessage Lambda trigger invocation (or {@code null} if
     * none was configured or invoked). When present, its {@code emailSubject}/{@code emailMessage}
     * (or {@code smsMessage}) take precedence over the pool's VerificationMessageTemplate, matching
     * how real Cognito lets the trigger override the delivered message.
     */
    public void dispatch(UserPool pool, CognitoUser user, VerificationCode.Purpose purpose,
                         String code, List<String> deliveryMediums,
                         Map<String, Object> customMessageResponse) {

        Map<String, Object> template = pool.getVerificationMessageTemplate();
        if (template == null) template = Map.of();
        String email = user.getAttributes().get("email");
        String phone = user.getAttributes().get("phone_number");

        List<String> mediums = resolveDeliveryMediums(deliveryMediums, email, phone);
        String region = AwsArnUtils.regionOrDefault(pool.getArn(), defaultRegion);

        for (String medium : mediums) {
            if ("EMAIL".equalsIgnoreCase(medium) && email != null) {
                String subject = stringOrNull(customMessageResponse, "emailSubject");
                if (subject == null) subject = stringOr(template.get("EmailSubject"), DEFAULT_EMAIL_SUBJECT);
                String rawBody = stringOrNull(customMessageResponse, "emailMessage");
                if (rawBody == null) rawBody = stringOr(template.get(emailTemplateKey()), DEFAULT_EMAIL_BODY);
                String body = renderTemplate(rawBody, code);
                Sender sender = sender(pool, region);
                ses.sendEmail(SendEmailRequest.builder()
                    .source(sender.from())
                    // SES takes Source as the default return path, the envelope sender it records
                    // and hands to the SMTP relay, which takes a bare address.
                    .returnPath(sender.from().equals(sender.address()) ? null : sender.address())
                    .toAddresses(List.of(email))
                    .region(region)
                    .content(new EmailContent.Simple(subject, body, null, List.of()))
                    .build());
            } else if ("SMS".equalsIgnoreCase(medium) && phone != null) {
                String rawBody = stringOrNull(customMessageResponse, "smsMessage");
                if (rawBody == null) rawBody = stringOr(resolveSmsTemplate(pool, template, purpose), DEFAULT_SMS_BODY);
                String body = renderTemplate(rawBody, code);
                sns.publish(
                    null, null,
                    phone,
                    body,
                    null,
                    null,
                    region
                );
            }
        }
    }

    /**
     * The sender for the pool's {@code EmailConfiguration} (EmailConfigurationType):
     * <ul>
     *   <li>{@code DEVELOPER}: {@code From}, a sender's address or name and address, when set.
     *   Only this sending account takes a sender name.</li>
     *   <li>Otherwise the address of the {@code SourceArn} identity, which with
     *   {@code COGNITO_DEFAULT} is the custom FROM address. A domain identity names no address,
     *   so {@code From} supplies it.</li>
     *   <li>Without either, {@code no-reply@verificationemail.com}.</li>
     * </ul>
     * A configured sender is used only when the {@code SourceArn} names an SES identity that is
     * verified in the pool's partition and account, in the {@code SourceArn} Region (the pool's
     * own for a wildcard Region), and {@code From}, when it supplies the sender, is one mailbox at
     * that address or in that domain or one of its subdomains. Anything else falls back to {@code no-reply@verificationemail.com},
     * so a pool cannot send as an address its account has not verified.
     */
    private Sender sender(UserPool pool, String sendRegion) {
        Map<String, Object> config = pool.getEmailConfiguration();
        if (config == null) {
            return DEFAULT_SENDER;
        }
        String from = stringOrNull(config, "From");
        boolean developer = DEVELOPER_SENDING_ACCOUNT.equals(config.get("EmailSendingAccount"));
        String sourceArn = stringOrNull(config, "SourceArn");
        String identity = sesIdentity(sourceArn);
        if (identity == null) {
            if (sourceArn != null) {
                LOG.warnv("User pool {0} SourceArn {1} names no SES identity; sending from {2}",
                    pool.getId(), sourceArn, DEFAULT_FROM);
            } else if (developer && from != null) {
                LOG.warnv("User pool {0} sets From {1} without a SourceArn; sending from {2}",
                    pool.getId(), from, DEFAULT_FROM);
            }
            return DEFAULT_SENDER;
        }
        if (!isVerifiedForPool(AwsArnUtils.parse(sourceArn), identity, pool, sendRegion)) {
            LOG.warnv("User pool {0} SourceArn {1} is not an SES identity verified for the pool''s account in that Region; sending from {2}",
                pool.getId(), sourceArn, DEFAULT_FROM);
            return DEFAULT_SENDER;
        }
        boolean identityIsAddress = identity.contains("@");
        if (identityIsAddress && !(developer && from != null)) {
            return new Sender(identity, identity);
        }
        Mailbox mailbox = from == null ? null : parseMailbox(from);
        if (mailbox == null || !isCoveredBy(mailbox, identity)) {
            LOG.warnv("User pool {0} From {1} is not an address of the SES identity {2}; sending from {3}",
                pool.getId(), from, identity, DEFAULT_FROM);
            return DEFAULT_SENDER;
        }
        return new Sender(from, mailbox.getAddress());
    }

    /**
     * True when the identity is verified in SES for the pool's account. The identity store is read
     * in the caller's account, which is the pool's, so the ARN's partition and account must be the
     * pool's too.
     */
    private boolean isVerifiedForPool(AwsArnUtils.Arn sourceArn, String identity, UserPool pool,
                                      String sendRegion) {
        String poolPartition = AwsArnUtils.partitionOrDefault(pool.getArn(), null);
        String poolAccount = AwsArnUtils.accountOrDefault(pool.getArn(), null);
        if (poolPartition == null || !poolPartition.equals(sourceArn.partition())
            || poolAccount == null || !poolAccount.equals(sourceArn.accountId())) {
            return false;
        }
        String region = sourceArn.region().isEmpty() || "*".equals(sourceArn.region())
            ? sendRegion : sourceArn.region();
        return ses.isVerifiedIdentity(identity, region);
    }

    /** One RFC 5322 mailbox, {@code addr} or {@code Name <addr>}, or {@code null} for anything else. */
    private static Mailbox parseMailbox(String value) {
        try {
            Mailbox mailbox = DefaultAddressParser.DEFAULT.parseMailbox(value);
            return mailbox.getDomain() == null ? null : mailbox;
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * True when the mailbox is the email address identity, or an address in the domain identity or
     * one of its subdomains. SES email address identities are case sensitive; domains are not.
     */
    private static boolean isCoveredBy(Mailbox mailbox, String identity) {
        if (identity.contains("@")) {
            return identity.equals(mailbox.getAddress());
        }
        String domain = mailbox.getDomain().toLowerCase(Locale.ROOT);
        String identityDomain = identity.toLowerCase(Locale.ROOT);
        return domain.equals(identityDomain) || domain.endsWith("." + identityDomain);
    }

    /** The identity an SES {@code identity/<name>} ARN names, or {@code null} for anything else. */
    private static String sesIdentity(String sourceArn) {
        return AwsArnUtils.resourceIfArnFor(sourceArn, "ses")
            .filter(resource -> resource.startsWith(SES_IDENTITY_RESOURCE_PREFIX))
            .map(resource -> resource.substring(SES_IDENTITY_RESOURCE_PREFIX.length()))
            .filter(identity -> !identity.isEmpty() && identity.chars().noneMatch(Character::isWhitespace))
            .orElse(null);
    }

    /** The From header, and the bare address SES uses as the envelope sender. */
    private record Sender(String from, String address) {
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
     * {@code SmsAuthenticationMessage} is a top-level UserPool attribute, NOT part of the
     * VerificationMessageTemplate, and per AWS covers both MFA and USER_AUTH's SMS_OTP. For
     * verification/signup codes, the template's {@code SmsMessage} takes precedence over the
     * legacy top-level SmsVerificationMessage.
     */
    private String resolveSmsTemplate(UserPool pool, Map<String, Object> template,
                                      VerificationCode.Purpose purpose) {
        if (purpose == VerificationCode.Purpose.SMS_MFA || purpose == VerificationCode.Purpose.SMS_OTP) {
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

    private String stringOrNull(Map<String, Object> response, String key) {
        if (response == null) return null;
        Object value = response.get(key);
        if (value == null) return null;
        String s = value.toString();
        return s.isEmpty() ? null : s;
    }

    private String renderTemplate(String template, String code) {
        if (template.contains(CODE_PLACEHOLDER)) {
            return template.replace(CODE_PLACEHOLDER, code);
        }
        return template + "\nCode: " + code;
    }
}

package io.github.hectorvent.floci.services.cognito.verification;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.DomainNames;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * AdminCreateUser invitations go out the same way, from {@link UserPool#getAdminCreateUserConfig()}.
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
    private static final String USERNAME_PLACEHOLDER = "{username}";
    private static final Pattern INVITE_PLACEHOLDERS = Pattern.compile("\\{username}|\\{####}");
    // AdminCreateUser invitation defaults, used when neither a CustomMessage trigger nor the pool's
    // AdminCreateUserConfig.InviteMessageTemplate supplies the text.
    private static final String DEFAULT_INVITE_SUBJECT = "Your temporary password";
    private static final String DEFAULT_INVITE_BODY = "Your username is {username} and temporary password is {####}.";
    private static final String DEFAULT_PASSWORDLESS_INVITE_BODY = "Your username is {username}.";

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
                // The pool's EmailMfaConfiguration is the template for email MFA codes and, once set, for
                // USER_AUTH's email sign-in codes too, as SmsAuthenticationMessage is for both SMS ones.
                Map<String, Object> emailTemplate = purpose == VerificationCode.Purpose.EMAIL_MFA
                        || (purpose == VerificationCode.Purpose.EMAIL_OTP && pool.getEmailMfaConfiguration() != null)
                        ? emailMfaTemplate(pool) : template;
                String subject = stringOrNull(customMessageResponse, "emailSubject");
                if (subject == null) subject = stringOr(emailTemplate.get("EmailSubject"), DEFAULT_EMAIL_SUBJECT);
                String rawBody = stringOrNull(customMessageResponse, "emailMessage");
                if (rawBody == null) rawBody = stringOr(emailTemplate.get(emailTemplateKey()), DEFAULT_EMAIL_BODY);
                sendEmail(pool, region, email, subject, renderTemplate(rawBody, code));
            } else if ("SMS".equalsIgnoreCase(medium) && phone != null) {
                String rawBody = stringOrNull(customMessageResponse, "smsMessage");
                if (rawBody == null) rawBody = stringOr(resolveSmsTemplate(pool, template, purpose), DEFAULT_SMS_BODY);
                sendSms(region, phone, renderTemplate(rawBody, code));
            }
        }
    }

    /**
     * Sends the AdminCreateUser invitation to each of {@code deliveryMediums} that the user has an
     * {@code email} or {@code phone_number} for. The text is the CustomMessage trigger's response
     * when it gave one, else the pool's {@code AdminCreateUserConfig.InviteMessageTemplate}, else
     * AWS's default, with {@code {username}} and {@code {####}} (the temporary password) filled in.
     *
     * <p>{@code temporaryPassword} is {@code null} for a user created without a password, whose
     * default message names only the username and whose template's {@code {####}} is left empty. As
     * on AWS, a message without {@code {####}} is not delivered to a user who has a password, since
     * it could not tell them the password.</p>
     *
     * <p>A failed send is rethrown only when no message reached the user, so a caller that stores
     * the user once this returns never leaves a delivered invitation naming an account it did not
     * create.</p>
     */
    public void dispatchInvitation(UserPool pool, CognitoUser user, String temporaryPassword,
                                   List<String> deliveryMediums, Map<String, Object> customMessageResponse) {
        Map<String, Object> template = inviteMessageTemplate(pool);
        String defaultBody = temporaryPassword == null ? DEFAULT_PASSWORDLESS_INVITE_BODY : DEFAULT_INVITE_BODY;
        String email = invitationRecipient(user, deliveryMediums, "EMAIL");
        String phone = invitationRecipient(user, deliveryMediums, "SMS");
        String region = AwsArnUtils.regionOrDefault(pool.getArn(), defaultRegion);

        List<Runnable> sends = new ArrayList<>();
        if (email != null) {
            String body = renderInvitation(firstNonNull(stringOrNull(customMessageResponse, "emailMessage"),
                stringOrNull(template, "EmailMessage"), defaultBody), user.getUsername(), temporaryPassword);
            String subject = firstNonNull(stringOrNull(customMessageResponse, "emailSubject"),
                stringOrNull(template, "EmailSubject"), DEFAULT_INVITE_SUBJECT);
            if (body != null) {
                sends.add(() -> sendEmail(pool, region, email, subject, body));
            }
        }
        if (phone != null) {
            String body = renderInvitation(firstNonNull(stringOrNull(customMessageResponse, "smsMessage"),
                stringOrNull(template, "SMSMessage"), defaultBody), user.getUsername(), temporaryPassword);
            if (body != null) {
                sends.add(() -> sendSms(region, phone, body));
            }
        }

        RuntimeException failure = null;
        boolean delivered = false;
        for (Runnable send : sends) {
            try {
                send.run();
                delivered = true;
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                }
            }
        }
        if (failure != null && !delivered) {
            throw failure;
        }
        if (failure != null) {
            LOG.warnv(failure, "An invitation for user {0} in pool {1} failed on one medium and went out on another",
                user.getUsername(), pool.getId());
        }
    }

    /** Whether any of {@code deliveryMediums} can reach the user, so an invitation has somewhere to go. */
    public static boolean hasInvitationRecipient(CognitoUser user, List<String> deliveryMediums) {
        return invitationRecipient(user, deliveryMediums, "EMAIL") != null
            || invitationRecipient(user, deliveryMediums, "SMS") != null;
    }

    /** The user's {@code email} for {@code EMAIL} or {@code phone_number} for {@code SMS}, when requested. */
    private static String invitationRecipient(CognitoUser user, List<String> deliveryMediums, String medium) {
        if (!deliveryMediums.contains(medium)) {
            return null;
        }
        return user.getAttributes().get("EMAIL".equals(medium) ? "email" : "phone_number");
    }

    private void sendEmail(UserPool pool, String region, String email, String subject, String body) {
        Sender sender = sender(pool, region);
        ses.sendServiceEmail(SendEmailRequest.builder()
            .source(sender.from())
            // SES takes Source as the default return path, the envelope sender it records
            // and hands to the SMTP relay, which takes a bare address.
            .returnPath(sender.from().equals(sender.address()) ? null : sender.address())
            .toAddresses(List.of(email))
            .region(region)
            .content(new EmailContent.Simple(subject, body, null, List.of()))
            .build());
    }

    private void sendSms(String region, String phone, String body) {
        sns.publish(
            null, null,
            phone,
            body,
            null,
            null,
            region
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inviteMessageTemplate(UserPool pool) {
        Map<String, Object> config = pool.getAdminCreateUserConfig();
        return config != null && config.get("InviteMessageTemplate") instanceof Map<?, ?> template
            ? (Map<String, Object>) template : Map.of();
    }

    /**
     * The invitation text, or {@code null} when it has no {@code {####}} to carry the user's
     * temporary password. Both placeholders are filled in one pass, so neither a username nor a
     * password that happens to contain the other placeholder is substituted twice.
     */
    private static String renderInvitation(String template, String username, String temporaryPassword) {
        if (temporaryPassword != null && !template.contains(CODE_PLACEHOLDER)) {
            return null;
        }
        return INVITE_PLACEHOLDERS.matcher(template).replaceAll(match -> Matcher.quoteReplacement(
            USERNAME_PLACEHOLDER.equals(match.group()) ? username
                : temporaryPassword == null ? "" : temporaryPassword));
    }

    private static String firstNonNull(String first, String second, String fallback) {
        if (first != null) {
            return first;
        }
        return second != null ? second : fallback;
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
        return DomainNames.isWithin(domain, identityDomain);
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

    /** The pool's EmailMfaConfiguration as an email template ({@code Subject}, {@code Message}). */
    private static Map<String, Object> emailMfaTemplate(UserPool pool) {
        Map<String, Object> configuration = pool.getEmailMfaConfiguration();
        if (configuration == null) {
            return Map.of();
        }
        Map<String, Object> template = new HashMap<>();
        if (configuration.get("Subject") != null) {
            template.put("EmailSubject", configuration.get("Subject"));
        }
        if (configuration.get("Message") != null) {
            template.put("EmailMessage", configuration.get("Message"));
        }
        return template;
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

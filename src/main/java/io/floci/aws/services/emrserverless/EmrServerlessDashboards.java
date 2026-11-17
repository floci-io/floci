package io.floci.aws.services.emrserverless;

import io.floci.aws.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The links {@code GetDashboardForJobRun} gives, "a URL that you can use to access the application UIs for a job run",
 * and {@code GetResourceDashboard} gives for a session. Each "is valid for one hour after you generate it. To access
 * the application UI after that hour elapses, you must invoke the API again to generate a new URL." Floci keeps no
 * Spark UI or Spark History Server for a run or a session, so a link opens a page of Floci's own about it
 * ({@link EmrServerlessDashboardController}). It names the account, Region, application, the run and its attempt or
 * the session, and when it expires, signed with a key this process makes: a link changed, or past its hour, opens
 * nothing, and the links given before a restart open nothing after it.
 */
@ApplicationScoped
public class EmrServerlessDashboards {

    /** Where Floci serves the page a link opens. */
    static final String PATH = "/_aws/emr-serverless/dashboard";
    static final Duration VALIDITY = Duration.ofHours(1);
    private static final String ALGORITHM = "HmacSHA256";
    private static final int KEY_BYTES = 32;

    private final String baseUrl;
    private final Clock clock;
    private final byte[] key;

    @Inject
    public EmrServerlessDashboards(EmulatorConfig config, Clock clock) {
        this(config.effectiveBaseUrl(), clock, newKey());
    }

    EmrServerlessDashboards(String baseUrl, Clock clock, byte[] key) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.clock = clock;
        this.key = key.clone();
    }

    /**
     * What a link names: one attempt of a run, or a session, whose {@code attempt} is 0, and the second, since the
     * epoch, it expires at.
     */
    public record Link(String accountId, String region, String applicationId, String jobRunId, String sessionId,
                       int attempt, long expires) {
    }

    /** A link to a run's attempt, valid for {@link #VALIDITY} from now. */
    public String url(String accountId, String region, String applicationId, String jobRunId, int attempt) {
        Link link = new Link(accountId, region, applicationId, jobRunId, null, attempt, expiry());
        return prefix(link) + "&jobRun=" + encoded(jobRunId) + "&attempt=" + attempt + suffix(link);
    }

    /** A link to a session, valid for {@link #VALIDITY} from now. */
    public String sessionUrl(String accountId, String region, String applicationId, String sessionId) {
        Link link = new Link(accountId, region, applicationId, null, sessionId, 0, expiry());
        return prefix(link) + "&session=" + encoded(sessionId) + suffix(link);
    }

    /**
     * The link a page request's parameters name, or null when one is missing or malformed, it names both a run and a
     * session or neither, the signature is not this process's for them, or the link has expired. A session's link
     * carries no attempt.
     */
    public Link verified(String accountId, String region, String applicationId, String jobRunId, String sessionId,
                         String attempt, String expires, String signature) {
        if (accountId == null || region == null || applicationId == null || signature == null
                || (jobRunId == null) == (sessionId == null) || (sessionId != null && attempt != null)) {
            return null;
        }
        Link link;
        try {
            link = new Link(accountId, region, applicationId, jobRunId, sessionId,
                    sessionId != null ? 0 : Integer.parseInt(attempt), Long.parseLong(expires));
        } catch (NumberFormatException e) {
            return null;
        }
        byte[] expected = signature(link).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))
                || clock.instant().getEpochSecond() >= link.expires()) {
            return null;
        }
        return link;
    }

    private long expiry() {
        return clock.instant().plus(VALIDITY).getEpochSecond();
    }

    private String prefix(Link link) {
        return baseUrl + PATH + "?account=" + encoded(link.accountId()) + "&region=" + encoded(link.region())
                + "&application=" + encoded(link.applicationId());
    }

    private String suffix(Link link) {
        return "&expires=" + link.expires() + "&signature=" + signature(link);
    }

    // Over what the link names; a run's link and a session's are told apart, so neither opens as the other.
    private String signature(Link link) {
        String signed = String.join("\n", link.accountId(), link.region(), link.applicationId(),
                link.jobRunId() != null ? "jobRun" : "session",
                link.jobRunId() != null ? link.jobRunId() : link.sessionId(),
                String.valueOf(link.attempt()), String.valueOf(link.expires()));
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign an EMR Serverless dashboard link", e);
        }
    }

    private static byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

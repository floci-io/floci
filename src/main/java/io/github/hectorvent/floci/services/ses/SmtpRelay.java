package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.MailMessage;
import io.vertx.ext.mail.StartTLSOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.james.mime4j.dom.Body;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.dom.address.MailboxList;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional SMTP relay for SES. When {@code floci.services.ses.smtp-host}
 * is configured, sent emails are forwarded to the SMTP server in addition
 * to being stored in the inspection endpoint. When unconfigured (or set
 * to empty string), this class is a no-op.
 *
 * <p>Uses a dedicated Vert.x {@link MailClient} configured directly from
 * {@link EmulatorConfig} rather than wiring through {@code quarkus.mailer.*},
 * which would crash on startup if the host property resolved to empty.
 */
@ApplicationScoped
public class SmtpRelay {

    private static final Logger LOG = Logger.getLogger(SmtpRelay.class);
    private static final long RELAY_TIMEOUT_SECONDS = 30;

    private final MailClient mailClient;
    private final ExecutorService relayExecutor;
    private final boolean enabled;

    @Inject
    public SmtpRelay(io.vertx.core.Vertx vertx, EmulatorConfig config) {
        this.enabled = config.services().ses().enabled()
                && config.services().ses().smtpHost()
                        .filter(h -> !h.isBlank())
                        .isPresent();
        if (enabled) {
            this.relayExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ses-smtp-relay");
                t.setDaemon(true);
                return t;
            });
            String host = config.services().ses().smtpHost().get();
            int port = config.services().ses().smtpPort();
            LOG.infov("SES SMTP relay enabled: {0}:{1}", host, String.valueOf(port));

            MailConfig mailConfig = new MailConfig()
                    .setHostname(host)
                    .setPort(port);
            config.services().ses().smtpUser()
                    .filter(u -> !u.isBlank())
                    .ifPresent(u -> {
                        mailConfig.setUsername(u);
                        config.services().ses().smtpPass()
                                .filter(p -> !p.isBlank())
                                .ifPresent(mailConfig::setPassword);
                    });
            String starttls = config.services().ses().smtpStarttls();
            if ("REQUIRED".equalsIgnoreCase(starttls)) {
                mailConfig.setStarttls(StartTLSOptions.REQUIRED);
            } else if ("OPTIONAL".equalsIgnoreCase(starttls)) {
                mailConfig.setStarttls(StartTLSOptions.OPTIONAL);
            } else {
                if (!"DISABLED".equalsIgnoreCase(starttls)) {
                    LOG.warnv("Unrecognized smtp-starttls value \"{0}\", defaulting to DISABLED", starttls);
                }
                mailConfig.setStarttls(StartTLSOptions.DISABLED);
            }
            this.mailClient = MailClient.create(vertx, mailConfig);
        } else {
            this.relayExecutor = null;
            this.mailClient = null;
        }
    }

    /** Package-private constructor for tests (synchronous executor, mock client). */
    SmtpRelay(MailClient mailClient, boolean enabled) {
        this.mailClient = mailClient;
        // Synchronous executor for deterministic test ordering
        this.relayExecutor = new AbstractExecutorService() {
            private volatile boolean shutdown = false;
            @Override public void execute(Runnable r) { r.run(); }
            @Override public void shutdown() { shutdown = true; }
            @Override public java.util.List<Runnable> shutdownNow() { shutdown = true; return java.util.List.of(); }
            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long t, java.util.concurrent.TimeUnit u) { return true; }
        };
        this.enabled = enabled;
    }

    @PreDestroy
    void shutdown() {
        if (relayExecutor != null) {
            relayExecutor.shutdownNow();
        }
        if (mailClient != null) {
            mailClient.close();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Relays a structured email asynchronously.
     */
    public void relay(String from, List<String> toAddresses, List<String> ccAddresses,
                      List<String> bccAddresses, List<String> replyToAddresses,
                      String subject, String bodyText, String bodyHtml) {
        if (!enabled) {
            return;
        }
        try {
            relayExecutor.execute(() -> doRelay(from, toAddresses, ccAddresses,
                    bccAddresses, replyToAddresses, subject, bodyText, bodyHtml));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.warnv("SMTP relay skipped (executor shutting down) for from={0}", from);
        }
    }

    private void doRelay(String from, List<String> toAddresses, List<String> ccAddresses,
                         List<String> bccAddresses, List<String> replyToAddresses,
                         String subject, String bodyText, String bodyHtml) {
        try {
            MailMessage mail = new MailMessage();
            mail.setFrom(from);
            if (toAddresses != null) {
                mail.setTo(toAddresses);
            }
            if (ccAddresses != null) {
                mail.setCc(ccAddresses);
            }
            if (bccAddresses != null) {
                mail.setBcc(bccAddresses);
            }
            if (replyToAddresses != null && !replyToAddresses.isEmpty()) {
                mail.addHeader("Reply-To", String.join(", ", replyToAddresses));
            }
            mail.setSubject(subject != null ? subject : "");
            if (bodyText != null) {
                mail.setText(bodyText);
            }
            if (bodyHtml != null) {
                mail.setHtml(bodyHtml);
            }

            mailClient.sendMail(mail).toCompletionStage().toCompletableFuture().get(RELAY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            LOG.debugv("SMTP relay: sent from={0}, to={1}, subject={2}", from, toAddresses, subject);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv(e, "SMTP relay interrupted for from={0}, to={1}", from, toAddresses);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warnv("SMTP relay timed out after {0}s for from={1}, to={2}", RELAY_TIMEOUT_SECONDS, from, toAddresses);
        } catch (Exception e) {
            LOG.warnv(e, "SMTP relay failed for from={0}, to={1}", from, toAddresses);
        }
    }

    /**
     * Relays a raw MIME message. Parsed with Mime4j, then sent via MailClient.
     */
    public void relayRaw(String from, List<String> destinations, String rawMessage) {
        if (!enabled) {
            return;
        }
        try {
            relayExecutor.execute(() -> doRelayRaw(from, destinations, rawMessage));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            LOG.warnv("SMTP raw relay skipped (executor shutting down) for from={0}", from);
        }
    }

    private void doRelayRaw(String from, List<String> destinations, String rawMessage) {
        try {
            byte[] mimeBytes = tryBase64Decode(rawMessage);
            var builder = new DefaultMessageBuilder();
            var message = builder.parseMessage(new ByteArrayInputStream(mimeBytes));

            MailMessage mail = new MailMessage();

            // From
            if (message.getFrom() != null && !message.getFrom().isEmpty()) {
                mail.setFrom(message.getFrom().get(0).getAddress());
            } else {
                mail.setFrom(from);
            }

            // SES SendRawEmail: when Destinations is provided, it is the
            // authoritative envelope recipient list — MIME To/Cc headers are
            // display-only and must not add extra SMTP RCPT TO entries.
            if (destinations != null && !destinations.isEmpty()) {
                mail.setTo(destinations);
            } else {
                MailboxList toList = message.getTo() != null ? message.getTo().flatten() : null;
                if (toList != null && !toList.isEmpty()) {
                    mail.setTo(toMailboxAddresses(toList));
                }
                MailboxList ccList = message.getCc() != null ? message.getCc().flatten() : null;
                if (ccList != null && !ccList.isEmpty()) {
                    mail.setCc(toMailboxAddresses(ccList));
                }
                MailboxList bccList = message.getBcc() != null ? message.getBcc().flatten() : null;
                if (bccList != null && !bccList.isEmpty()) {
                    mail.setBcc(toMailboxAddresses(bccList));
                }
            }

            // Subject
            mail.setSubject(message.getSubject() != null ? message.getSubject() : "");

            // Body
            extractBodyFromEntity(message, mail);

            mailClient.sendMail(mail).toCompletionStage().toCompletableFuture().get(RELAY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            LOG.debugv("SMTP relay: sent raw from={0}, destinations={1}", from, destinations);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv(e, "SMTP raw relay interrupted for from={0}", from);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warnv("SMTP raw relay timed out after {0}s for from={1}", RELAY_TIMEOUT_SECONDS, from);
        } catch (Exception e) {
            LOG.warnv(e, "SMTP raw relay failed for from={0}", from);
        }
    }

    private static void extractBodyFromEntity(Entity entity, MailMessage mail) {
        Body body = entity.getBody();
        if (body instanceof TextBody textBody) {
            // Keep the first text/plain and text/html parts encountered so that a
            // text-typed attachment later in a multipart/mixed message cannot
            // clobber the body already picked up from multipart/alternative.
            if ("text/html".equalsIgnoreCase(entity.getMimeType()) && mail.getHtml() == null) {
                mail.setHtml(readTextBody(textBody));
            } else if ("text/plain".equalsIgnoreCase(entity.getMimeType()) && mail.getText() == null) {
                mail.setText(readTextBody(textBody));
            }
        } else if (body instanceof Multipart multipart) {
            for (Entity part : multipart.getBodyParts()) {
                extractBodyFromEntity(part, mail);
            }
        }
    }

    private static String readTextBody(TextBody textBody) {
        try {
            Charset charset = StandardCharsets.UTF_8;
            if (textBody.getMimeCharset() != null) {
                try {
                    charset = Charset.forName(textBody.getMimeCharset());
                } catch (Exception ignored) {}
            }
            try (var is = textBody.getInputStream()) {
                return new String(is.readAllBytes(), charset);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> toMailboxAddresses(MailboxList list) {
        List<String> addresses = new ArrayList<>();
        for (Mailbox mailbox : list) {
            addresses.add(mailbox.getAddress());
        }
        return addresses;
    }

    static byte[] tryBase64Decode(String data) {
        try {
            return Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            return data.getBytes(StandardCharsets.UTF_8);
        }
    }
}

package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.ServiceCallAuthorizer;
import io.floci.aws.services.iam.model.DelegationRequest;
import io.floci.aws.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The notifications IAM publishes to a partner's SNS topic as a delegation request moves.
 *
 * <p>This is the only way a partner learns anything after filing a request: no operation on the
 * family answers the exchange token, so the token reaches its owner here or not at all. The guide
 * says as much, "IAM uses Amazon Simple Notification Service (SNS) to communicate delegation
 * request state changes to you", and lists the states that notify. Every transition out of
 * {@code UNASSIGNED} is one of them, which is why a notice is taken wherever a state is written
 * rather than at the six call sites that write one.
 *
 * <p>Both shapes are published in full. Every notification carries {@code Type}, {@code RequestId},
 * {@code RequestorWorkflowId}, {@code State}, {@code OwnerAccountId} and {@code UpdatedAt}, and the
 * one carrying the token adds {@code ExchangeToken} and {@code ExpiresAt}, which is the release
 * instant plus {@code SessionDuration}: "Credentials expire at: release time + approved duration".
 * The delegation fields are a JSON string in the {@code Message} of an ordinary SNS notification,
 * which is the envelope {@link SnsService#publish} already writes.
 *
 * <p>IAM publishes as a service rather than as the caller, so the topic's own policy decides and
 * nothing the customer or the partner holds plays any part. It reads both of them in the account
 * the topic ARN names, which is the partner's, where most of the calls that trigger one are the
 * customer's. The guide has the partner allow
 * {@code SNS:Publish} to {@code iam.amazonaws.com} on the topic and warns that without it "you
 * will not receive state change notifications or the exchange token", so a refusal here costs the
 * notification and not the operation that produced it: the state has already changed, and AWS
 * rolls nothing back for a notification it could not deliver.
 */
@ApplicationScoped
public class DelegationNotifier {

    static final String STATE_CHANGE = "StateChange";
    static final String EXCHANGE_TOKEN = "ExchangeToken";

    private static final Logger LOG = Logger.getLogger(DelegationNotifier.class);
    /** ISO 8601 to the millisecond, the form both published examples carry. */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final SnsService snsService;
    private final ServiceCallAuthorizer serviceCallAuthorizer;
    private final ObjectMapper objectMapper;

    public DelegationNotifier(SnsService snsService, ServiceCallAuthorizer serviceCallAuthorizer,
                              ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.serviceCallAuthorizer = serviceCallAuthorizer;
        this.objectMapper = objectMapper;
    }

    /**
     * One notification, as the transition that produced it left the request.
     *
     * <p>A stored request is a mutable record shared by every caller holding it, and these are
     * published after the service's lock is released, so what goes out is copied here while the
     * transition still owns the record. Publishing from the record itself would send whatever state
     * a later caller had moved it to.
     */
    public record Notice(String type, String requestId, String requestorWorkflowId, String state,
                         String ownerAccountId, Instant updatedAt, String exchangeToken,
                         Instant expiresAt, String topicArn, String requestorAccountId) {

        /** The notification for a state the request has just moved to. */
        public static Notice stateChange(DelegationRequest request) {
            return new Notice(STATE_CHANGE, request.getDelegationRequestId(),
                    request.getRequestorWorkflowId(), String.valueOf(request.getState()),
                    request.getOwnerAccountId(), request.getUpdatedTime(), null, null,
                    request.getNotificationChannel(), request.getRequestorId());
        }

        /**
         * The notification that carries the token, sent at {@code FINALIZED} beside the state
         * change rather than instead of it: the guide's table lists both for that state.
         */
        public static Notice exchangeToken(DelegationRequest request) {
            return new Notice(EXCHANGE_TOKEN, request.getDelegationRequestId(),
                    request.getRequestorWorkflowId(), String.valueOf(request.getState()),
                    request.getOwnerAccountId(), request.getUpdatedTime(),
                    request.getExchangeToken(),
                    request.getTokenReleasedAt().plusSeconds(request.getSessionDuration()),
                    request.getNotificationChannel(), request.getRequestorId());
        }
    }

    /** Publishes each notice, in the order the transitions produced them. */
    public void publish(List<Notice> notices) {
        for (Notice notice : notices) {
            try {
                publish(notice);
            } catch (Exception e) {
                // Nothing a notification does may reach the caller. The transition behind it is
                // already written and AWS rolls none back for a message it could not deliver, so a
                // topic that is gone, a policy that cannot be read and a failed delivery all cost
                // the notification alone, and the notices after this one are still owed theirs.
                // These are published from a finally, where an escape would also replace whatever
                // the call was already answering.
                LOG.warnv(e, "Delegation request {0}: {1} to topic {2} not published",
                        notice.requestId(), notice.type(), notice.topicArn());
            }
        }
    }

    /**
     * Whether a {@code NotificationChannel} can name a topic at all. The member's own shape,
     * {@code notificationChannelType}, is looser than an ARN, so this is what
     * {@code CreateDelegationRequest} holds the value to and what a publish checks before trying.
     */
    static boolean namesATopic(String channel) {
        return AwsArnUtils.isArnFor(channel, "sns") && !AwsArnUtils.parse(channel).resource().isBlank();
    }

    private void publish(Notice notice) throws Exception {
        String topicArn = notice.topicArn();
        if (!namesATopic(topicArn)) {
            // The create refuses a channel that cannot name a topic, so reaching this is a request
            // stored before it did. The failure AWS describes for a topic it cannot publish to is
            // the same one either way: the partner does not receive the notification.
            LOG.debugv("Delegation request {0}: {1} not published, NotificationChannel {2} is not "
                    + "an SNS topic", notice.requestId(), notice.type(), topicArn);
            return;
        }
        // The topic belongs to the partner and most of these transitions are the customer's calls,
        // so both the policy and the topic are read in the account the topic ARN names. Account
        // aware storage reads the calling account otherwise, where neither one exists.
        AwsArnUtils.Arn topic = AwsArnUtils.parse(topicArn);
        RequestScopes.callAsChecked(topic.accountId(), () -> {
            publishToTopic(notice, topic.region());
            return null;
        });
    }

    private void publishToTopic(Notice notice, String region) throws JsonProcessingException {
        String topicArn = notice.topicArn();
        if (serviceCallAuthorizer.authorize("iam", "sns:Publish", topicArn, sourceArn(notice),
                notice.requestorAccountId()) != IamPolicyEvaluator.Decision.ALLOW) {
            return;
        }
        snsService.publish(topicArn, null, message(notice), null, region);
    }

    /**
     * The request whose own lifecycle triggered the publish, which is what {@code aws:SourceArn}
     * and {@code aws:SourceAccount} name. AWS publishes a topic policy for this with no condition
     * on either, so nothing here turns on them; they are set because a topic owner writing the
     * confused-deputy conditions the SNS guide recommends is testing for exactly this request, in
     * the partner account that filed it.
     */
    private static String sourceArn(Notice notice) {
        return AwsArnUtils.Arn.global(AwsArnUtils.parse(notice.topicArn()).partition(), "iam",
                notice.requestorAccountId(), "delegation-request/" + notice.requestId()).toString();
    }

    /**
     * The delegation payload. Field order follows the guide's two field tables, the published
     * examples ordering their keys differently from each other. An account nobody has claimed is
     * left out rather than sent as null, which is how the rest of IAM answers a member it has no
     * value for.
     */
    private String message(Notice notice) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("Type", notice.type());
        payload.put("RequestId", notice.requestId());
        payload.put("RequestorWorkflowId", notice.requestorWorkflowId());
        payload.put("State", notice.state());
        // "Customer's AWS account ID", which a request filed at a named customer carries from
        // the start and an open one only once it is claimed. Until then there is none to send.
        if (notice.ownerAccountId() != null) {
            payload.put("OwnerAccountId", notice.ownerAccountId());
        }
        payload.put("UpdatedAt", TIMESTAMP_FORMAT.format(notice.updatedAt()));
        if (EXCHANGE_TOKEN.equals(notice.type())) {
            payload.put("ExchangeToken", notice.exchangeToken());
            payload.put("ExpiresAt", TIMESTAMP_FORMAT.format(notice.expiresAt()));
        }
        return objectMapper.writeValueAsString(payload);
    }
}

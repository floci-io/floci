package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegionFacts;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.model.DelegationPermission;
import io.github.hectorvent.floci.services.iam.model.DelegationRequest;
import io.github.hectorvent.floci.services.iam.model.DelegationRequestState;
import io.github.hectorvent.floci.services.iam.model.PolicyParameter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The delegation requests behind IAM temporary delegation for AWS Partners.
 *
 * <p>A delegation request is the one IAM record two accounts share: the partner creates it and the
 * customer claims, approves or refuses it. Storage is therefore keyed by the request id under the
 * creating account and resolved across accounts on read, because the reference makes the id
 * globally resolvable and decides access from the request's own owner fields: a request nobody owns
 * "can be called by any account", one aimed at an account can be read only from there.
 *
 * <p>Expiry is applied on read rather than swept by a worker. Every state carries the window AWS
 * gives it, a day for {@code UNASSIGNED} and a week for the rest, and a request past its window is
 * answered as {@code EXPIRED}. That needs no scheduler to be correct and no clock to be mocked
 * globally, which is why the service takes its own {@link Clock}.
 *
 * <p>A member that breaks the format or constraints it declares is refused with
 * {@code ValidationError}, which the reference's common errors publish for exactly that across
 * every IAM operation and which the rest of IAM answers in the same shape. The modeled
 * {@code InvalidInput} is kept for a value that cannot be read as the member's type at all, the
 * way the shared pagination already answers a {@code MaxItems} that is not a number, and that is
 * what a {@code NotificationChannel} naming no topic gets: the member's declared shape is looser
 * than an ARN, so the shape alone would let through a channel nothing could be published to.
 *
 * <p>Every state the service writes is announced on the partner's topic through
 * {@link DelegationNotifier}, which is the only channel this family has outwards. A notice is
 * taken wherever a state is written, so an expiry noticed on a read announces itself the same way
 * a claim does, and they are published once the lock is released: delivery reaches SNS and its
 * subscribers, which is not work to hold a lock over, and a notification that cannot be delivered
 * must not undo the transition that earned it. A call that fails after expiring a request still
 * announces the expiry, which is why they are published in a finally rather than on the way out.
 */
@ApplicationScoped
public class DelegationRequestService {

    /** {@code delegationRequestIdType}, and the "dr-" prefix the reference's own examples carry. */
    private static final String ID_PREFIX = "dr-";
    private static final int ID_BYTES = 12;
    /** {@code tradeInTokenType} publishes no length and no pattern, so this is simply unguessable. */
    private static final int TOKEN_BYTES = 32;
    private static final Pattern ID_PATTERN = Pattern.compile("[\\w-]+");
    private static final int ID_MIN = 16;
    private static final int ID_MAX = 128;
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\d{12}");
    private static final Pattern NOTIFICATION_CHANNEL_PATTERN = Pattern.compile("^[a-zA-Z0-9:_.-]+$");
    /**
     * {@code delegationRequestTextType}: tab, newline, carriage return and the printable range,
     * which is the class every free-text member on these operations carries. Written with escaped
     * {@code \\u} sequences so the regex engine reads them rather than the compiler.
     */
    private static final Pattern TEXT_PATTERN =
            Pattern.compile("[\\u0009\\u000A\\u000D\\u0020-\\u007E\\u00A1-\\u00FF]*");
    /** The same class where the member's own pattern requires at least one character. */
    private static final Pattern TEXT_REQUIRED_PATTERN =
            Pattern.compile("[\\u0009\\u000A\\u000D\\u0020-\\u007E\\u00A1-\\u00FF]+");
    private static final Pattern REDIRECT_URL_PATTERN = Pattern.compile(
            "^http(s?)://[a-zA-Z0-9._/-]*(\\?[a-zA-Z0-9._=&-]*)?(#[a-zA-Z0-9._/-]*)?$");
    private static final Pattern OWNER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9:/+=,.@_-]+$");
    /** Printable ASCII, which is what a policy parameter's name and values are held to. */
    private static final Pattern PARAMETER_TEXT_PATTERN = Pattern.compile("[ -~]+");
    private static final Set<String> PARAMETER_TYPES =
            Set.of(PolicyParameter.TYPE_STRING, PolicyParameter.TYPE_STRING_LIST);

    private static final int SESSION_DURATION_MIN = 300;
    private static final int SESSION_DURATION_MAX = 43200;

    /**
     * The longest delegation the account root may approve. Not a declared constraint but a
     * documented one, and it belongs to the approval rather than to the request: the user guide's
     * own note reads "The maximum duration for delegated access is 12 hours. However, root users
     * can only approve delegation requests with a duration of 4 hours or less. If a request
     * specifies more than 4 hours, you must use a non-root identity to approve the request."
     */
    private static final int ROOT_APPROVAL_DURATION_MAX = 14400;

    /**
     * {@code summaryStateType}, of which {@code FAILED} is unreachable here: it reports a
     * generation that ran and did not finish, and nothing is generated asynchronously.
     */
    private static final String SUMMARY_AVAILABLE = "AVAILABLE";
    private static final String SUMMARY_NOT_AVAILABLE = "NOT_AVAILABLE";
    private static final String SUMMARY_NOT_SUPPORTED = "NOT_SUPPORTED";
    /** {@code summaryContentType} holds the content to 10000 characters. */
    private static final int SUMMARY_MAX_CONTENT = 10000;
    /** "At this time, the only supported entity type is delegation-request". */
    private static final String SUMMARY_ENTITY_TYPE = "delegation-request";

    /** The service that registers one, which is the only service whose ARN names a request. */
    private static final String SUMMARY_ENTITY_SERVICE = "iam";
    /** The locale the reference's own example answers, and the one language Floci writes. */
    private static final String SUMMARY_LOCALE = "en";

    /**
     * The path the console deep link points at. AWS publishes the field's purpose and its length
     * but no format, so this follows the console URL the tree already uses for CodePipeline
     * events: the host of the request's own partition, with the commercial console's path, which
     * is the only path any partition publishes. Floci serves no approval page of its own: the
     * customer side of the workflow is the API, which is what the integration tests drive.
     */
    private static final String CONSOLE_PATH = "/iam/home";

    private final SecureRandom random = new SecureRandom();
    private final AccountAwareStorageBackend<DelegationRequest> requests;
    private final Clock clock;
    private final DelegationNotifier notifier;
    /**
     * Serializes the read, check and write every lifecycle call makes, the way the sibling IAM
     * services guard theirs. Without it two callers can both claim the same unassigned request, or
     * two creates can both pass the workflow-id uniqueness check, and the later write would decide.
     */
    private final Object requestLock = new Object();

    @Inject
    public DelegationRequestService(StorageFactory storageFactory, Clock clock,
                                    DelegationNotifier notifier) {
        this(storageFactory.create("iam", "iam-delegation-requests.json", new TypeReference<>() {}),
                clock, notifier);
    }

    DelegationRequestService(AccountAwareStorageBackend<DelegationRequest> requests, Clock clock,
                             DelegationNotifier notifier) {
        this.requests = requests;
        this.clock = clock;
        this.notifier = notifier;
    }

    /** The {@code CreateDelegationRequest} request, as the handler parsed it. */
    public record NewRequest(String description, String notificationChannel,
                             String requestorWorkflowId, Integer sessionDuration,
                             DelegationPermission permissions, String ownerAccountId,
                             String redirectUrl, String requestMessage, Boolean onlySendByOwner) {}

    /** A created request together with the deep link the partner redirects the customer to. */
    public record Created(DelegationRequest request, String consoleDeepLink) {}

    // ── Partner side ──────────────────────────────────────────────────────────

    /**
     * Files a request on behalf of the calling partner account.
     *
     * <p>It starts {@code UNASSIGNED} whether or not it names a target account: the reference
     * describes that state as covering both a request "created without specifying a target account"
     * and one "with a target account ID but not yet claimed by the account owner".
     *
     * <p>No quota is enforced. {@code LimitExceeded} is modeled, and AWS publishes no number for
     * requests per account, so a limit here would be invented rather than emulated.
     */
    public Created create(String requestorAccountId, String region, NewRequest command) {
        requirePresent(command.description(), "description");
        requirePresent(command.notificationChannel(), "notificationChannel");
        requirePresent(command.requestorWorkflowId(), "requestorWorkflowId");
        // Description and RequestMessage carry a pattern ending in '*' and no minimum, so an empty
        // value is inside the constraints AWS publishes for them and is not read as an omission.
        checkText(command.description(), "description", 0, 1000, TEXT_PATTERN);
        checkText(command.notificationChannel(), "notificationChannel", 2, 400,
                NOTIFICATION_CHANNEL_PATTERN);
        checkText(command.requestorWorkflowId(), "requestorWorkflowId", 5, 400,
                TEXT_REQUIRED_PATTERN);
        checkText(command.requestMessage(), "requestMessage", 0, 200, TEXT_PATTERN);
        checkText(command.redirectUrl(), "redirectUrl", 1, 255, REDIRECT_URL_PATTERN);
        if (command.sessionDuration() == null) {
            throw constraintViolation(null, "sessionDuration", "Member must not be null");
        }
        if (command.sessionDuration() < SESSION_DURATION_MIN) {
            throw constraintViolation(command.sessionDuration().toString(), "sessionDuration",
                    "Member must have value greater than or equal to " + SESSION_DURATION_MIN);
        }
        if (command.sessionDuration() > SESSION_DURATION_MAX) {
            throw constraintViolation(command.sessionDuration().toString(), "sessionDuration",
                    "Member must have value less than or equal to " + SESSION_DURATION_MAX);
        }
        if (command.permissions() == null) {
            throw constraintViolation(null, "permissions", "Member must not be null");
        }
        validatePermissions(command.permissions());
        if (command.ownerAccountId() != null
                && !ACCOUNT_PATTERN.matcher(command.ownerAccountId()).matches()) {
            throw constraintViolation(command.ownerAccountId(), "ownerAccountId",
                    "Member must satisfy regular expression pattern: " + ACCOUNT_PATTERN.pattern());
        }
        // Last, because every check above enforces a declared constraint and this one enforces a
        // documented one: notificationChannelType is looser than an ARN, while the member's own
        // documentation says "At this time, only SNS topic ARNs are accepted for notification".
        // A value inside the shape that names no topic cannot be read as the member's type, which
        // is what InvalidInput is for here.
        if (!DelegationNotifier.namesATopic(command.notificationChannel())) {
            throw new AwsException("InvalidInput",
                    "NotificationChannel must be an Amazon SNS topic ARN.", 400);
        }
        synchronized (requestLock) {
            return fileNewRequest(requestorAccountId, region, command);
        }
    }

    private Created fileNewRequest(String requestorAccountId, String region, NewRequest command) {
        // "IAM maintains a uniqueness check on this workflow id for each request", and the failure
        // it names is the EntityAlreadyExists the operation models. Expired requests still count:
        // the reference qualifies the check by existence, not by state. The check and the write
        // that follows it share the lock, so two creates cannot both find the id free.
        if (findByWorkflowId(requestorAccountId, command.requestorWorkflowId()).isPresent()) {
            throw new AwsException("EntityAlreadyExists",
                    "A delegation request with workflow id " + command.requestorWorkflowId()
                            + " already exists.", 409);
        }

        Instant now = clock.instant();
        DelegationRequest request = new DelegationRequest();
        request.setDelegationRequestId(newId());
        request.setDescription(command.description());
        request.setNotificationChannel(command.notificationChannel());
        request.setRequestorWorkflowId(command.requestorWorkflowId());
        request.setSessionDuration(command.sessionDuration());
        request.setPermissions(command.permissions());
        request.setOwnerAccountId(command.ownerAccountId());
        request.setRedirectUrl(command.redirectUrl());
        request.setRequestMessage(command.requestMessage());
        request.setOnlySendByOwner(command.onlySendByOwner());
        // The partner is identified by the account that called. AWS shows a registered partner
        // name here, which onboarding assigns and no API sets, so Floci leaves the name unset
        // rather than inventing one.
        request.setRequestorId(requestorAccountId);
        request.setState(DelegationRequestState.UNASSIGNED);
        request.setCreateDate(now);
        request.setUpdatedTime(now);
        request.setExpirationTime(now.plus(DelegationRequestState.UNASSIGNED.window()));
        put(request);
        return new Created(request, consoleDeepLink(region, request.getDelegationRequestId()));
    }

    // ── Customer side ─────────────────────────────────────────────────────────

    /**
     * Claims a request for the calling identity, {@code UNASSIGNED} to {@code ASSIGNED}.
     *
     * <p>A request already aimed at an account is invisible from anywhere else, so claiming it from
     * another account answers {@code NoSuchEntity} rather than a refusal that names it.
     */
    public DelegationRequest associate(String callerAccountId, String callerArn, String id) {
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                Instant now = clock.instant();
                DelegationRequest request = visible(callerAccountId, id, now, notices);
                requireState(request, DelegationRequestState.UNASSIGNED);
                request.setOwnerAccountId(callerAccountId);
                request.setOwnerId(callerArn);
                return moveTo(request, DelegationRequestState.ASSIGNED, now, notices);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    /** Approves a request, from {@code ASSIGNED} or from an administrator's review. */
    public DelegationRequest accept(String callerAccountId, String callerArn, String id) {
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                Instant now = clock.instant();
                DelegationRequest request = visible(callerAccountId, id, now, notices);
                requireState(request, DelegationRequestState.ASSIGNED,
                        DelegationRequestState.PENDING_APPROVAL);
                requireApproverMayHoldItThatLong(request, callerArn);
                request.setApproverId(callerArn);
                return moveTo(request, DelegationRequestState.ACCEPTED, now, notices);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    /**
     * Refuses a request. The notes are the explanation the reference describes, which is the one
     * writer of {@code RejectionReason} on the shape.
     */
    public DelegationRequest reject(String callerAccountId, String id, String notes) {
        checkText(notes, "notes", 0, 500, TEXT_PATTERN);
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                Instant now = clock.instant();
                DelegationRequest request = visible(callerAccountId, id, now, notices);
                requireState(request, DelegationRequestState.ASSIGNED,
                        DelegationRequestState.PENDING_APPROVAL, DelegationRequestState.ACCEPTED);
                request.setRejectionReason(notes);
                return moveTo(request, DelegationRequestState.REJECTED, now, notices);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    /**
     * Forwards a request to an administrator, which is what an update does: "when the delegation
     * request is updated, it reaches the PENDING_APPROVAL state". It needs an owner, the reference
     * giving the update permission to whoever owns the request.
     */
    public DelegationRequest update(String callerAccountId, String id, String notes) {
        checkText(notes, "notes", 0, 500, TEXT_PATTERN);
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                Instant now = clock.instant();
                DelegationRequest request = visible(callerAccountId, id, now, notices);
                requireState(request, DelegationRequestState.ASSIGNED,
                        DelegationRequestState.PENDING_APPROVAL);
                request.setNotes(notes);
                return moveTo(request, DelegationRequestState.PENDING_APPROVAL, now, notices);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    /**
     * Releases the exchange token, {@code ACCEPTED} to {@code FINALIZED}.
     *
     * <p>"The delegation period (exchange token validity) begins when the request reaches
     * Finalized state", so the release instant is kept with the token: every credential the token
     * mints expires at release plus {@code SessionDuration}, however late it is redeemed. The
     * reference says the move "cannot be rolled back", which is what makes {@code FINALIZED}
     * terminal for the lifecycle operations while still expiring on its own window.
     *
     * <p>{@code OnlySendByOwner} is read here, which is the only operation it speaks to: the flag
     * "prevents any party other than the owner from calling SendDelegationToken API for this
     * delegation request", for the case the reference names, where a request was forwarded to a
     * more privileged user for approval and the owner still has to be the one who releases it. So
     * this is the one delegation call decided per identity rather than per account: the approver
     * who accepted the request is a different ARN from the owner who claimed it, and with the flag
     * set only the second of them may release.
     *
     * <p>No operation on the family answers the token. AWS publishes it only in the
     * {@code ExchangeToken} notification on the partner's topic, which is sent here beside the
     * state change rather than instead of it, the guide listing both for {@code FINALIZED}.
     */
    public DelegationRequest release(String callerAccountId, String callerArn, String id) {
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                Instant now = clock.instant();
                DelegationRequest request = visible(callerAccountId, id, now, notices);
                requireState(request, DelegationRequestState.ACCEPTED);
                if (Boolean.TRUE.equals(request.getOnlySendByOwner())
                        && !request.getOwnerId().equals(callerArn)) {
                    throw new AwsException("AccessDenied", "User is not authorized to perform: "
                            + "iam:SendDelegationToken on " + request.getDelegationRequestId()
                            + ", which is marked to be sent only by its owner.", 403);
                }
                request.setExchangeToken(newToken());
                request.setTokenReleasedAt(now);
                DelegationRequest finalized =
                        moveTo(request, DelegationRequestState.FINALIZED, now, notices);
                notices.add(DelegationNotifier.Notice.exchangeToken(finalized));
                return finalized;
            }
        } finally {
            notifier.publish(notices);
        }
    }

    // ── Token ─────────────────────────────────────────────────────────────────

    /** A redeemed exchange token: the request behind it, and when its credentials expire. */
    public record Redeemed(DelegationRequest request, Instant credentialExpiry) {}

    /**
     * The request an exchange token belongs to, if the caller may redeem it and it is still live.
     *
     * <p>"The exchange token can only be redeemed by the product provider account that created the
     * delegation request", so the creating account is checked first and on its own: that is a
     * refusal rather than an expiry, and checking it after the window would answer every foreign
     * caller with whichever of the two happened to fail first.
     *
     * <p>A token nothing matches is answered as an expired one. {@code GetDelegatedAccessToken}
     * models three errors and none of them says "no such token", and from the caller's side the
     * two situations are the same: the reference's own remedy, "Request a new token and retry the
     * operation", is the answer to both.
     *
     * <p>No expiry pass is needed on the way in. {@code SessionDuration} tops out at twelve hours
     * and {@code FINALIZED} runs for a week, so the token's window always closes first and a
     * request whose own window has run out has an unredeemable token either way.
     */
    public Redeemed redeem(String callerAccountId, String token) {
        synchronized (requestLock) {
            DelegationRequest request = requests.scanAllAccounts().stream()
                    .filter(candidate -> candidate.getExchangeToken() != null
                            && candidate.getExchangeToken().equals(token))
                    .findFirst()
                    .orElseThrow(DelegationRequestService::expiredToken);
            if (!request.getRequestorId().equals(callerAccountId)) {
                throw new AwsException("AccessDenied", "The exchange token for delegation request "
                        + request.getDelegationRequestId() + " can only be redeemed by the account "
                        + "that created the request.", 403);
            }
            Instant expiry = request.getTokenReleasedAt().plusSeconds(request.getSessionDuration());
            if (!clock.instant().isBefore(expiry)) {
                throw expiredToken();
            }
            return new Redeemed(request, expiry);
        }
    }

    // ── Readers ───────────────────────────────────────────────────────────────

    /** One request, by id, as the calling account is allowed to see it. */
    public DelegationRequest get(String callerAccountId, String id) {
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                return visible(callerAccountId, id, clock.instant(), notices);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    /**
     * The requests the calling account owns, newest last, optionally narrowed to one owner.
     *
     * <p>An unowned request is left out even when it names this account as its target: "if a
     * delegation request has no owner, even if it is assigned to a specific account, it will not be
     * part of the ListDelegationRequests output for that account". A partner therefore does not see
     * its own filings here, which is consistent with the guide telling partners to track state from
     * the notifications instead.
     */
    public List<DelegationRequest> list(String callerAccountId, String ownerId) {
        checkText(ownerId, "ownerId", 20, 2048, OWNER_ID_PATTERN);
        Instant now = clock.instant();
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                return visibleToOwner(callerAccountId, ownerId, now, notices);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    /** The answer {@code GetHumanReadableSummary} gives: content, its locale, and how it went. */
    public record Summary(String content, String locale, String state) {}

    /**
     * The summary of an entity, which is only ever a delegation request.
     *
     * <p>Who may read it is the question {@link #get} answers: "these rules are identical to
     * GetDelegationRequest API behavior, such that a party who has permissions to call
     * GetDelegationRequest for a given delegation request will always be able to retrieve the
     * human readable summary for that request", so the lookup is the same one and a request this
     * account cannot see is {@code NoSuchEntity} here too.
     *
     * <p>The answers here that are chosen rather than read. An ARN naming any other entity type
     * is {@code NOT_SUPPORTED}, which is the state the model publishes and the only thing a type
     * AWS says it does not support can mean. A locale Floci does not write is
     * {@code NOT_AVAILABLE} with no content, because "summary content is non-empty only if the
     * SummaryState is AVAILABLE" and answering English text under another locale would be a claim
     * the response itself contradicts. And a request id inside a well-formed ARN that no id could
     * ever match is {@code NoSuchEntity} rather than a constraint failure: the operation declares
     * the ARN's length and nothing about what is inside it, so the id is not a member to validate,
     * and an unmatchable id names no request, which is what that error says.
     *
     * <p>The ARN's own account is not matched against the request. The id is what resolves across
     * accounts, which is how the whole family reads one, and Floci mints these ARNs under the
     * calling account rather than the owner's, so holding a caller to the account in the ARN would
     * refuse the ARN Floci itself hands out.
     */
    public Summary summarize(String callerAccountId, String entityArn, String locale) {
        requirePresent(entityArn, "entityArn");
        checkText(entityArn, "entityArn", 20, 2048, null);
        checkText(locale, "locale", 2, 12, null);
        String answeredLocale = locale == null ? SUMMARY_LOCALE : locale;
        String id = summarizedRequestId(entityArn);
        if (id == null) {
            return new Summary(null, answeredLocale, SUMMARY_NOT_SUPPORTED);
        }
        if (!namesARequest(id)) {
            throw new AwsException("NoSuchEntity", "The delegation request named by " + entityArn
                    + " cannot be found.", 404);
        }
        List<DelegationNotifier.Notice> notices = new ArrayList<>();
        try {
            synchronized (requestLock) {
                DelegationRequest request = visible(callerAccountId, id, clock.instant(), notices);
                if (!writtenIn(answeredLocale)) {
                    return new Summary(null, answeredLocale, SUMMARY_NOT_AVAILABLE);
                }
                return new Summary(DelegationRequestSummary.markdown(request, SUMMARY_MAX_CONTENT),
                        answeredLocale, SUMMARY_AVAILABLE);
            }
        } finally {
            notifier.publish(notices);
        }
    }

    private List<DelegationRequest> visibleToOwner(String callerAccountId, String ownerId,
                                                   Instant now,
                                                   List<DelegationNotifier.Notice> notices) {
        return requests.scanAllAccounts().stream()
                .map(request -> expireIfDue(request, now, notices))
                .filter(request -> request.getOwnerId() != null
                        && callerAccountId.equals(request.getOwnerAccountId()))
                .filter(request -> ownerId == null || ownerId.equals(request.getOwnerId()))
                .sorted(Comparator.comparing(DelegationRequest::getCreateDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * The request behind {@code id}, if the calling account may see it at all.
     *
     * <p>The rules are the ones published on {@code GetDelegationRequest}: a request with neither
     * owner account nor owner "can be called by any account", one with an owner account only from
     * that account, and a fully owned one by its owner. Floci decides at account granularity, there
     * being no per-identity permission on a delegation request to consult, so the last two collapse
     * into the same answer.
     */
    private DelegationRequest visible(String callerAccountId, String id, Instant now,
                                      List<DelegationNotifier.Notice> notices) {
        requirePresent(id, "delegationRequestId");
        checkText(id, "delegationRequestId", ID_MIN, ID_MAX, ID_PATTERN);
        DelegationRequest request = requests.scanAllAccounts().stream()
                .filter(candidate -> id.equals(candidate.getDelegationRequestId()))
                .findFirst()
                .orElseThrow(() -> noSuchRequest(id));
        if (request.getOwnerAccountId() != null
                && !request.getOwnerAccountId().equals(callerAccountId)) {
            throw noSuchRequest(id);
        }
        return expireIfDue(request, now, notices);
    }

    /**
     * The request id an entity ARN names, or null when the ARN names another type of entity.
     *
     * <p>A value that is not an ARN at all is the modeled {@code InvalidInput}: {@code arnType}
     * declares a length and no pattern, so this cannot be a constraint failure, and it is the
     * same split the rest of this family makes between a member that breaks its constraints and
     * one that cannot be read as its type.
     *
     * <p>Another service's ARN is read as another type of entity rather than as a request of
     * IAM's that happens to be spelled oddly. Only IAM registers a delegation request, so an ARN
     * naming any other service names nothing this can summarize, however its resource reads.
     */
    private static String summarizedRequestId(String entityArn) {
        if (!AwsArnUtils.isArn(entityArn)) {
            throw new AwsException("InvalidInput", "The value '" + entityArn + "' at 'entityArn' "
                    + "is not an Amazon Resource Name.", 400);
        }
        return AwsArnUtils.resourceIfArnFor(entityArn, SUMMARY_ENTITY_SERVICE)
                .filter(resource -> resource.startsWith(SUMMARY_ENTITY_TYPE + "/"))
                .map(resource -> resource.substring(SUMMARY_ENTITY_TYPE.length() + 1))
                .orElse(null);
    }

    /** Whether an id is inside {@code delegationRequestIdType} at all. */
    private static boolean namesARequest(String id) {
        return id.length() >= ID_MIN && id.length() <= ID_MAX && ID_PATTERN.matcher(id).matches();
    }

    /**
     * Whether Floci writes the summary in this locale. The supported strings are the console's
     * languages, and Floci writes one of them, so the language subtag decides and the region
     * inside {@code en-GB} does not.
     */
    private static boolean writtenIn(String locale) {
        return locale.split("[-_]", 2)[0].equalsIgnoreCase(SUMMARY_LOCALE);
    }

    private Optional<DelegationRequest> findByWorkflowId(String requestorAccountId, String workflowId) {
        return requests.scanForAccount(requestorAccountId, key -> true).stream()
                .filter(candidate -> workflowId.equals(candidate.getRequestorWorkflowId()))
                .findFirst();
    }

    /**
     * Applies the state's own window. A request sitting past it is {@code EXPIRED} from the next
     * read onwards, and the transition is written back so the answer does not depend on who asks.
     * It is a transition like any other, so it notifies: the guide lists {@code EXPIRED} among the
     * states a partner hears about, and the read that notices it is the only moment Floci has.
     */
    private DelegationRequest expireIfDue(DelegationRequest request, Instant now,
                                          List<DelegationNotifier.Notice> notices) {
        if (request.getState() == DelegationRequestState.EXPIRED
                || request.getExpirationTime() == null
                || now.isBefore(request.getExpirationTime())) {
            return request;
        }
        request.setState(DelegationRequestState.EXPIRED);
        request.setUpdatedTime(now);
        put(request);
        notices.add(DelegationNotifier.Notice.stateChange(request));
        return request;
    }

    /**
     * Commits a transition and takes the notice for it. It is given the same instant the lookup
     * expired against, so the deadline cannot fall between the two and let a request that is
     * already past its window move on instead of expiring.
     */
    private DelegationRequest moveTo(DelegationRequest request, DelegationRequestState state,
                                     Instant now, List<DelegationNotifier.Notice> notices) {
        // A StateChange is sent "when a delegation request transitions to a new state", so a
        // second UpdateDelegationRequest on a request already under review writes the notes it
        // carries, refreshes the expiry window and tells the partner nothing.
        boolean moved = request.getState() != state;
        request.setState(state);
        request.setUpdatedTime(now);
        request.setExpirationTime(state.window() == null ? null : now.plus(state.window()));
        put(request);
        if (moved) {
            notices.add(DelegationNotifier.Notice.stateChange(request));
        }
        return request;
    }

    /**
     * A state the operation does not move out of. No modeled error says "wrong state": the four
     * writers publish {@code NoSuchEntity}, {@code ServiceFailure} and
     * {@code ConcurrentModification}, and {@code AcceptDelegationRequest} does not even publish
     * {@code InvalidInput}, so the conflict shape is the closest of the three and is used on all
     * four rather than a different code per operation.
     */
    private static void requireState(DelegationRequest request, DelegationRequestState... allowed) {
        for (DelegationRequestState state : allowed) {
            if (request.getState() == state) {
                return;
            }
        }
        throw new AwsException("ConcurrentModification", "Delegation request "
                + request.getDelegationRequestId() + " is " + request.getState()
                + " and cannot be changed from that state.", 409);
    }

    /**
     * The four-hour cap on what the account root may approve, which {@code create} cannot apply:
     * the twelve hours it enforces belong to the request, and this one belongs to whoever
     * approves it, so it is read here where the approver is known.
     *
     * <p>Root is the caller whose ARN ends in {@code :root}, which is what this family already
     * names an approver by, and covers a root credential itself as well as a key resolving to no
     * IAM user, which this service answers with the account-root stand-in.
     *
     * <p>{@code AcceptDelegationRequest} models {@code NoSuchEntity}, {@code ServiceFailure} and
     * {@code ConcurrentModification} and nothing for this, and no source gives the code AWS
     * answers, so the code is chosen: the guide states the rule as which identity may approve,
     * which is what {@code SendDelegationToken}'s own owner rule answers {@code AccessDenied}
     * for. A duration the record does not carry is nothing to hold to a cap.
     */
    private static void requireApproverMayHoldItThatLong(DelegationRequest request,
                                                         String callerArn) {
        if (callerArn == null || !callerArn.endsWith(":root")
                || request.getSessionDuration() == null
                || request.getSessionDuration() <= ROOT_APPROVAL_DURATION_MAX) {
            return;
        }
        throw new AwsException("AccessDenied", "User is not authorized to perform: "
                + "iam:AcceptDelegationRequest on " + request.getDelegationRequestId()
                + ", which asks for " + request.getSessionDuration() + " seconds: the root user may"
                + " only approve a delegation of " + ROOT_APPROVAL_DURATION_MAX / 3600
                + " hours or less. Approve it as an IAM user or role instead.", 403);
    }

    private void put(DelegationRequest request) {
        requests.putForAccount(request.getRequestorId(), request.getDelegationRequestId(), request);
    }

    private void validatePermissions(DelegationPermission permissions) {
        // The structure is required and both of its members are optional, which the CLI
        // reference marks by putting [required] on --permissions and on neither member.
        checkText(permissions.getPolicyTemplateArn(), "policyTemplateArn", 20, 2048, null);
        if (permissions.getParameters().size() > DelegationPermission.MAX_PARAMETERS) {
            throw constraintViolation(Integer.toString(permissions.getParameters().size()),
                    "parameters", "Member must have length less than or equal to "
                            + DelegationPermission.MAX_PARAMETERS);
        }
        for (PolicyParameter parameter : permissions.getParameters()) {
            checkText(parameter.getName(), "parameters.name", 5, 256, PARAMETER_TEXT_PATTERN);
            if (parameter.getType() != null && !PARAMETER_TYPES.contains(parameter.getType())) {
                throw constraintViolation(parameter.getType(), "parameters.type",
                        "Member must satisfy enum value set: " + PARAMETER_TYPES);
            }
            for (String value : parameter.getValues()) {
                checkText(value, "parameters.values", 0, Integer.MAX_VALUE,
                        PARAMETER_TEXT_PATTERN);
            }
        }
    }

    /** A member the operation requires. Absent is an omission; empty is a value to check. */
    private static void requirePresent(String value, String member) {
        if (value == null) {
            throw constraintViolation(null, member, "Member must not be null");
        }
    }

    /**
     * The length and pattern a member declares, applied to a value that is there. A null is left
     * alone, {@link #requirePresent} being what says a member had to arrive at all, and a null
     * pattern means the member declares none.
     */
    private static void checkText(String value, String member, int min, int max, Pattern pattern) {
        if (value == null) {
            return;
        }
        if (value.length() < min) {
            throw constraintViolation(value, member,
                    "Member must have length greater than or equal to " + min);
        }
        if (value.length() > max) {
            throw constraintViolation(value, member,
                    "Member must have length less than or equal to " + max);
        }
        if (pattern != null && !pattern.matcher(value).matches()) {
            throw constraintViolation(value, member,
                    "Member must satisfy regular expression pattern: " + pattern.pattern());
        }
    }

    /**
     * A member that does not meet the format or the constraints it declares. The reference's
     * common errors publish {@code ValidationError} for exactly that, "the input doesn't meet the
     * required format or constraints", for every IAM operation and without listing it on any of
     * them, which is why it appears in no operation's error table and in none of these. The member
     * is named without the value, as {@link IamValidationErrors} reads it, and a member the request
     * never sent keeps the one word that still stands where a value would.
     */
    private static AwsException constraintViolation(String value, String member, String constraint) {
        if (value == null) {
            return SmithyValidationErrors.validationError(SmithyValidationErrors.notNull(member));
        }
        return IamValidationErrors.validationError(member, value, constraint);
    }

    private static AwsException noSuchRequest(String id) {
        return new AwsException("NoSuchEntity",
                "The delegation request with id " + id + " cannot be found.", 404);
    }

    private String newId() {
        byte[] bytes = new byte[ID_BYTES];
        random.nextBytes(bytes);
        return ID_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * An opaque exchange token. The model gives {@code tradeInTokenType} no pattern and no length,
     * only {@code sensitive}, so there is no published shape to match and nothing is encoded in
     * it: the account allowed to redeem it is read off the request it was minted on.
     */
    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The one shape {@code GetDelegatedAccessToken} publishes for a token it will not exchange. */
    private static AwsException expiredToken() {
        return new AwsException("ExpiredTradeInTokenException", "The trade-in token provided in "
                + "the request has expired and can no longer be exchanged for credentials. "
                + "Request a new token and retry the operation.", 400);
    }

    /**
     * The deep link on the console host of the region's partition. The China and GovCloud user
     * guides state a host; the EUSC and ISO partitions publish none and keep the commercial one.
     */
    private static String consoleDeepLink(String region, String id) {
        String host = AwsRegionFacts.consoleHost(AwsRegions.partitionFor(region))
                .or(() -> AwsRegionFacts.consoleHost(AwsRegions.DEFAULT_PARTITION))
                .orElseThrow();
        return "https://" + host + CONSOLE_PATH + "?region=" + region
                + "#/delegation_requests/" + id;
    }
}

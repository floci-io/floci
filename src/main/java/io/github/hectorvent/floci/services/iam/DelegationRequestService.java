package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegionFacts;
import io.github.hectorvent.floci.core.common.AwsRegions;
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
 * way the shared pagination already answers a {@code MaxItems} that is not a number.
 */
@ApplicationScoped
public class DelegationRequestService {

    /** {@code delegationRequestIdType}, and the "dr-" prefix the reference's own examples carry. */
    private static final String ID_PREFIX = "dr-";
    private static final int ID_BYTES = 12;
    private static final Pattern ID_PATTERN = Pattern.compile("[\\w-]+");
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
    /**
     * Serializes the read, check and write every lifecycle call makes, the way the sibling IAM
     * services guard theirs. Without it two callers can both claim the same unassigned request, or
     * two creates can both pass the workflow-id uniqueness check, and the later write would decide.
     */
    private final Object requestLock = new Object();

    @Inject
    public DelegationRequestService(StorageFactory storageFactory, Clock clock) {
        this(storageFactory.create("iam", "iam-delegation-requests.json", new TypeReference<>() {}), clock);
    }

    DelegationRequestService(AccountAwareStorageBackend<DelegationRequest> requests, Clock clock) {
        this.requests = requests;
        this.clock = clock;
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
        synchronized (requestLock) {
            Instant now = clock.instant();
            DelegationRequest request = visible(callerAccountId, id, now);
            requireState(request, DelegationRequestState.UNASSIGNED);
            request.setOwnerAccountId(callerAccountId);
            request.setOwnerId(callerArn);
            return moveTo(request, DelegationRequestState.ASSIGNED, now);
        }
    }

    /** Approves a request, from {@code ASSIGNED} or from an administrator's review. */
    public DelegationRequest accept(String callerAccountId, String callerArn, String id) {
        synchronized (requestLock) {
            Instant now = clock.instant();
            DelegationRequest request = visible(callerAccountId, id, now);
            requireState(request, DelegationRequestState.ASSIGNED, DelegationRequestState.PENDING_APPROVAL);
            request.setApproverId(callerArn);
            return moveTo(request, DelegationRequestState.ACCEPTED, now);
        }
    }

    /**
     * Refuses a request. The notes are the explanation the reference describes, which is the one
     * writer of {@code RejectionReason} on the shape.
     */
    public DelegationRequest reject(String callerAccountId, String id, String notes) {
        checkText(notes, "notes", 0, 500, TEXT_PATTERN);
        synchronized (requestLock) {
            Instant now = clock.instant();
            DelegationRequest request = visible(callerAccountId, id, now);
            requireState(request, DelegationRequestState.ASSIGNED,
                    DelegationRequestState.PENDING_APPROVAL, DelegationRequestState.ACCEPTED);
            request.setRejectionReason(notes);
            return moveTo(request, DelegationRequestState.REJECTED, now);
        }
    }

    /**
     * Forwards a request to an administrator, which is what an update does: "when the delegation
     * request is updated, it reaches the PENDING_APPROVAL state". It needs an owner, the reference
     * giving the update permission to whoever owns the request.
     */
    public DelegationRequest update(String callerAccountId, String id, String notes) {
        checkText(notes, "notes", 0, 500, TEXT_PATTERN);
        synchronized (requestLock) {
            Instant now = clock.instant();
            DelegationRequest request = visible(callerAccountId, id, now);
            requireState(request, DelegationRequestState.ASSIGNED, DelegationRequestState.PENDING_APPROVAL);
            request.setNotes(notes);
            return moveTo(request, DelegationRequestState.PENDING_APPROVAL, now);
        }
    }

    // ── Readers ───────────────────────────────────────────────────────────────

    /** One request, by id, as the calling account is allowed to see it. */
    public DelegationRequest get(String callerAccountId, String id) {
        synchronized (requestLock) {
            return visible(callerAccountId, id, clock.instant());
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
        synchronized (requestLock) {
            return visibleToOwner(callerAccountId, ownerId, now);
        }
    }

    private List<DelegationRequest> visibleToOwner(String callerAccountId, String ownerId, Instant now) {
        return requests.scanAllAccounts().stream()
                .map(request -> expireIfDue(request, now))
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
    private DelegationRequest visible(String callerAccountId, String id, Instant now) {
        requirePresent(id, "delegationRequestId");
        checkText(id, "delegationRequestId", 16, 128, ID_PATTERN);
        DelegationRequest request = requests.scanAllAccounts().stream()
                .filter(candidate -> id.equals(candidate.getDelegationRequestId()))
                .findFirst()
                .orElseThrow(() -> noSuchRequest(id));
        if (request.getOwnerAccountId() != null
                && !request.getOwnerAccountId().equals(callerAccountId)) {
            throw noSuchRequest(id);
        }
        return expireIfDue(request, now);
    }

    private Optional<DelegationRequest> findByWorkflowId(String requestorAccountId, String workflowId) {
        return requests.scanForAccount(requestorAccountId, key -> true).stream()
                .filter(candidate -> workflowId.equals(candidate.getRequestorWorkflowId()))
                .findFirst();
    }

    /**
     * Applies the state's own window. A request sitting past it is {@code EXPIRED} from the next
     * read onwards, and the transition is written back so the answer does not depend on who asks.
     */
    private DelegationRequest expireIfDue(DelegationRequest request, Instant now) {
        if (request.getState() == DelegationRequestState.EXPIRED
                || request.getExpirationTime() == null
                || now.isBefore(request.getExpirationTime())) {
            return request;
        }
        request.setState(DelegationRequestState.EXPIRED);
        request.setUpdatedTime(now);
        put(request);
        return request;
    }

    /**
     * Commits a transition. It takes the same instant the lookup expired against, so the deadline
     * cannot fall between the two and let a request that is already past its window move on
     * instead of expiring.
     */
    private DelegationRequest moveTo(DelegationRequest request, DelegationRequestState state, Instant now) {
        request.setState(state);
        request.setUpdatedTime(now);
        request.setExpirationTime(state.window() == null ? null : now.plus(state.window()));
        put(request);
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
     * them, which is why it appears in no operation's error table and in none of these.
     */
    private static AwsException constraintViolation(String value, String member, String constraint) {
        return new AwsException("ValidationError", "1 validation error detected: Value "
                + (value == null ? "null" : "'" + value + "'") + " at '" + member
                + "' failed to satisfy constraint: " + constraint, 400);
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

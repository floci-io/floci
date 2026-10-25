package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.iam.model.DelegationPermission;
import io.github.hectorvent.floci.services.iam.model.DelegationRequest;
import io.github.hectorvent.floci.services.iam.model.DelegationRequestState;
import io.github.hectorvent.floci.services.iam.model.PolicyParameter;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.DelegationRequests.entityArn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The state machine and both expiry windows, driven with a clock this test owns.
 *
 * <p>Expiry is the half that cannot be driven over the wire without waiting a day, so the service
 * takes its own {@link java.time.Clock} and this advances it. The global test clock is deliberately
 * not touched: it is shared by every class in the Quarkus instance.
 */
class DelegationRequestServiceTest {

    private static final String PARTNER = "000000000001";
    private static final String CUSTOMER = "000000000002";
    private static final String CUSTOMER_ARN = "arn:aws:iam::000000000002:user/admin";
    /** A second identity in the customer's account, the privileged approver the reference names. */
    private static final String APPROVER_ARN = "arn:aws:iam::000000000002:user/security-admin";
    /** The customer's account root, which is also what a key naming no IAM user resolves to. */
    private static final String ROOT_ARN = "arn:aws:iam::000000000002:root";
    private static final String REGION = "us-east-1";
    private static final String TOPIC = "arn:aws:sns:us-east-1:000000000001:partner-notifications";

    /** Enough trials and racers to surface an unguarded read-then-write reliably. */
    private static final int TRIALS = 50;
    private static final int RACERS = 8;

    private MutableClock clock;
    private DelegationRequestService service;
    /** Every notification the service has published, in order, with nothing reaching SNS. */
    private final List<DelegationNotifier.Notice> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        published.clear();
        DelegationNotifier notifier = mock(DelegationNotifier.class);
        doAnswer(invocation -> {
            published.addAll(invocation.getArgument(0));
            return null;
        }).when(notifier).publish(anyList());
        service = new DelegationRequestService(
                AccountAwareStorageBackend.inMemory(PARTNER), clock, notifier);
    }

    /** What went out, as the type and state of each notification in the order it was published. */
    private List<String> announced() {
        return published.stream().map(notice -> notice.type() + ":" + notice.state()).toList();
    }

    private DelegationRequestService.Created createdIn(String region, String workflowId,
                                                       String ownerAccountId) {
        return createdIn(region, workflowId, ownerAccountId, null);
    }

    private DelegationRequestService.Created createdIn(String region, String workflowId,
                                                       String ownerAccountId,
                                                       Boolean onlySendByOwner) {
        return createdIn(region, workflowId, ownerAccountId, onlySendByOwner, TOPIC);
    }

    private DelegationRequestService.Created createdIn(String region, String workflowId,
                                                       String ownerAccountId,
                                                       Boolean onlySendByOwner, String channel) {
        return service.create(PARTNER, region, new DelegationRequestService.NewRequest(
                "Set up the integration", channel,
                workflowId, 3600,
                new DelegationPermission("arn:aws:iam::partner:policy/template/example",
                        List.of(new PolicyParameter("BucketName", PolicyParameter.TYPE_STRING,
                                List.of("example-bucket")))),
                ownerAccountId, null, null, onlySendByOwner));
    }

    /** A request of this length, every other member being the one the helpers above file. */
    private DelegationRequest createdFor(String workflowId, int sessionDuration) {
        return service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "Set up the integration", TOPIC, workflowId, sessionDuration,
                new DelegationPermission("arn:aws:iam::partner:policy/template/example",
                        List.of()),
                null, null, null, null)).request();
    }

    private DelegationRequest created(String workflowId, String ownerAccountId) {
        return createdIn(REGION, workflowId, ownerAccountId).request();
    }

    private DelegationRequest assigned(String workflowId) {
        DelegationRequest request = created(workflowId, null);
        return service.associate(CUSTOMER, CUSTOMER_ARN, request.getDelegationRequestId());
    }

    private DelegationRequest accepted(String workflowId) {
        DelegationRequest request = assigned(workflowId);
        return service.accept(CUSTOMER, CUSTOMER_ARN, request.getDelegationRequestId());
    }

    private DelegationRequest finalized(String workflowId) {
        DelegationRequest request = accepted(workflowId);
        return service.release(CUSTOMER, CUSTOMER_ARN, request.getDelegationRequestId());
    }

    /**
     * The deep link lands on the console host of the request's own partition, so a partner in
     * China or GovCloud redirects the customer to a console that exists there. The China and
     * GovCloud user guides state a host; the EUSC and ISO partitions publish none and keep the
     * commercial one.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,       console.aws.amazon.com",
            "cn-north-1,      console.amazonaws.cn",
            "us-gov-west-1,   console.amazonaws-us-gov.com",
            "eusc-de-east-1,  console.aws.amazon.com",
            "us-iso-east-1,   console.aws.amazon.com"})
    void theDeepLinkPointsAtTheConsoleHostOfTheRequestsPartition(String region, String host) {
        DelegationRequestService.Created created = createdIn(region, "workflow-1", null);

        assertEquals("https://" + host + "/iam/home?region=" + region + "#/delegation_requests/"
                + created.request().getDelegationRequestId(), created.consoleDeepLink());
    }

    @Test
    void releasingTheTokenFinalizesTheRequestAndMintsSomethingToExchange() {
        DelegationRequest request = finalized("workflow-1");

        assertEquals(DelegationRequestState.FINALIZED, request.getState());
        assertNotNull(request.getExchangeToken());
        // The week FINALIZED runs for and the token's own window both start at the release, so
        // the one instant the transition took is the anchor for both.
        assertNotNull(request.getTokenReleasedAt());
        assertEquals(request.getTokenReleasedAt().plus(Duration.ofDays(7)),
                request.getExpirationTime());
        // No operation on the family answers the token, so nothing but the record holds it.
        assertEquals(request.getExchangeToken(),
                service.get(CUSTOMER, request.getDelegationRequestId()).getExchangeToken());
    }

    @Test
    void onlyAnAcceptedRequestReleasesItsToken() {
        String assignedId = assigned("workflow-1").getDelegationRequestId();
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.release(CUSTOMER, CUSTOMER_ARN, assignedId)).getErrorCode());

        String finalizedId = finalized("workflow-2").getDelegationRequestId();
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.release(CUSTOMER, CUSTOMER_ARN, finalizedId)).getErrorCode(),
                "the reference says finalizing cannot be rolled back, so it cannot be repeated");
    }

    /**
     * {@code OnlySendByOwner} "prevents any party other than the owner from calling
     * SendDelegationToken API for this delegation request", for the case the reference names: the
     * owner forwarded it to a more privileged user for approval, and that approver must not be the
     * one who releases the token. It is the one call on this family decided per identity rather
     * than per account.
     */
    @Test
    void aRequestMarkedOwnerOnlyIsReleasedByItsOwnerAndNobodyElse() {
        String id = createdIn(REGION, "workflow-1", null, true).request().getDelegationRequestId();
        service.associate(CUSTOMER, CUSTOMER_ARN, id);
        service.update(CUSTOMER, id, "please approve");
        service.accept(CUSTOMER, APPROVER_ARN, id);
        assertEquals(APPROVER_ARN, service.get(CUSTOMER, id).getApproverId());

        assertEquals("AccessDenied", assertThrows(AwsException.class,
                () -> service.release(CUSTOMER, APPROVER_ARN, id)).getErrorCode());
        assertEquals(DelegationRequestState.ACCEPTED, service.get(CUSTOMER, id).getState());

        assertEquals(DelegationRequestState.FINALIZED,
                service.release(CUSTOMER, CUSTOMER_ARN, id).getState());
    }

    @Test
    void withoutThatFlagTheApproverReleasesItJustAsWell() {
        String id = createdIn(REGION, "workflow-1", null, null).request().getDelegationRequestId();
        service.associate(CUSTOMER, CUSTOMER_ARN, id);
        service.accept(CUSTOMER, APPROVER_ARN, id);

        assertEquals(DelegationRequestState.FINALIZED,
                service.release(CUSTOMER, APPROVER_ARN, id).getState());
    }

    /**
     * "The delegation period starts when customer releases the exchange token, not when the
     * product provider redeems it", and "all credentials obtained from the same exchange token
     * expire at the same time", so a later exchange buys a shorter session rather than a fresh
     * hour.
     */
    @Test
    void everyExchangeOfOneTokenExpiresWhenTheFirstWouldHave() {
        DelegationRequest request = finalized("workflow-1");
        Instant releasedAt = request.getTokenReleasedAt();

        DelegationRequestService.Redeemed first =
                service.redeem(PARTNER, request.getExchangeToken());
        assertEquals(releasedAt.plus(Duration.ofSeconds(3600)), first.credentialExpiry());

        clock.advance(Duration.ofMinutes(50));
        DelegationRequestService.Redeemed second =
                service.redeem(PARTNER, request.getExchangeToken());
        assertEquals(first.credentialExpiry(), second.credentialExpiry());
    }

    @Test
    void onlyTheAccountThatFiledTheRequestCanExchangeItsToken() {
        DelegationRequest request = finalized("workflow-1");

        assertEquals("AccessDenied", assertThrows(AwsException.class,
                () -> service.redeem(CUSTOMER, request.getExchangeToken())).getErrorCode());
    }

    @Test
    void aTokenPastTheReleasedDurationIsNoLongerExchangeable() {
        DelegationRequest request = finalized("workflow-1");

        clock.advance(Duration.ofSeconds(3599));
        assertNotNull(service.redeem(PARTNER, request.getExchangeToken()));

        clock.advance(Duration.ofSeconds(1));
        assertEquals("ExpiredTradeInTokenException", assertThrows(AwsException.class,
                () -> service.redeem(PARTNER, request.getExchangeToken())).getErrorCode());
    }

    @Test
    void aTokenNothingMatchesReadsAsOneThatExpired() {
        finalized("workflow-1");

        // Only three errors are modeled and none says "no such token": from the caller's side a
        // token that never existed and one that ran out call for the same retry.
        assertEquals("ExpiredTradeInTokenException", assertThrows(AwsException.class,
                () -> service.redeem(PARTNER, "not-a-token")).getErrorCode());
    }

    @Test
    void aNewRequestStartsUnassignedAndExpiresInADay() {
        DelegationRequest request = created("workflow-1", null);

        assertEquals(DelegationRequestState.UNASSIGNED, request.getState());
        assertTrue(request.getDelegationRequestId().startsWith("dr-"));
        assertEquals(PARTNER, request.getRequestorId());
        assertEquals(request.getCreateDate().plus(Duration.ofDays(1)), request.getExpirationTime());
        // Nothing registers a partner name, so none is invented.
        assertNull(request.getRequestorName());
    }

    @Test
    void anUnclaimedRequestIsExpiredAfterADay() {
        String id = created("workflow-1", null).getDelegationRequestId();

        clock.advance(Duration.ofHours(23));
        assertEquals(DelegationRequestState.UNASSIGNED, service.get(CUSTOMER, id).getState());

        clock.advance(Duration.ofHours(2));
        assertEquals(DelegationRequestState.EXPIRED, service.get(CUSTOMER, id).getState());
    }

    @Test
    void aClaimedRequestGetsTheWeekTheReferenceGivesIt() {
        String id = assigned("workflow-1").getDelegationRequestId();

        // Past the one-day window the request would have had while unclaimed.
        clock.advance(Duration.ofDays(6));
        assertEquals(DelegationRequestState.ASSIGNED, service.get(CUSTOMER, id).getState());

        clock.advance(Duration.ofDays(2));
        assertEquals(DelegationRequestState.EXPIRED, service.get(CUSTOMER, id).getState());
    }

    @Test
    void anExpiredRequestCannotBeClaimedOrApproved() {
        String id = created("workflow-1", null).getDelegationRequestId();
        clock.advance(Duration.ofDays(2));

        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.associate(CUSTOMER, CUSTOMER_ARN, id)).getErrorCode());
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.accept(CUSTOMER, CUSTOMER_ARN, id)).getErrorCode());
    }

    @Test
    void claimingSetsTheOwnerAndMovesToAssigned() {
        DelegationRequest request = assigned("workflow-1");

        assertEquals(DelegationRequestState.ASSIGNED, request.getState());
        assertEquals(CUSTOMER, request.getOwnerAccountId());
        assertEquals(CUSTOMER_ARN, request.getOwnerId());
    }

    @Test
    void aRequestCanOnlyBeClaimedOnce() {
        DelegationRequest request = assigned("workflow-1");

        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.associate(CUSTOMER, CUSTOMER_ARN,
                        request.getDelegationRequestId())).getErrorCode());
    }

    @Test
    void aRequestAimedAtOneAccountIsInvisibleFromAnother() {
        String id = created("workflow-1", CUSTOMER).getDelegationRequestId();

        // Not a refusal naming it: the reference lets only the target account see it at all.
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> service.get("000000000003", id)).getErrorCode());
        assertEquals(DelegationRequestState.UNASSIGNED, service.get(CUSTOMER, id).getState());
    }

    @Test
    void approvingRecordsTheApprover() {
        DelegationRequest request = assigned("workflow-1");

        DelegationRequest accepted = service.accept(CUSTOMER, CUSTOMER_ARN,
                request.getDelegationRequestId());

        assertEquals(DelegationRequestState.ACCEPTED, accepted.getState());
        assertEquals(CUSTOMER_ARN, accepted.getApproverId());
    }

    @Test
    void anUpdateForwardsForApprovalAndThenAcceptanceWorks() {
        DelegationRequest request = assigned("workflow-1");

        DelegationRequest updated = service.update(CUSTOMER,
                request.getDelegationRequestId(), "please review");

        assertEquals(DelegationRequestState.PENDING_APPROVAL, updated.getState());
        assertEquals("please review", updated.getNotes());
        assertEquals(DelegationRequestState.ACCEPTED, service.accept(CUSTOMER, CUSTOMER_ARN,
                request.getDelegationRequestId()).getState());
    }

    @Test
    void rejectionKeepsItsExplanationAndIsFinal() {
        DelegationRequest request = assigned("workflow-1");
        String id = request.getDelegationRequestId();

        DelegationRequest rejected = service.reject(CUSTOMER, id, "not this quarter");

        assertEquals(DelegationRequestState.REJECTED, rejected.getState());
        // Reject's notes are the explanation, which is the one writer of RejectionReason.
        assertEquals("not this quarter", rejected.getRejectionReason());
        assertNull(rejected.getNotes());
        // "Once a request is rejected, it cannot be accepted or updated later."
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.accept(CUSTOMER, CUSTOMER_ARN, id)).getErrorCode());
        assertEquals("ConcurrentModification", assertThrows(AwsException.class,
                () -> service.update(CUSTOMER, id, "reopen")).getErrorCode());
    }

    @Test
    void aWorkflowIdCannotBeReusedByThePartnerThatHoldsIt() {
        created("workflow-1", null);

        assertEquals("EntityAlreadyExists", assertThrows(AwsException.class,
                () -> created("workflow-1", null)).getErrorCode());
    }

    @Test
    void aWorkflowIdStaysTakenAfterTheRequestExpires() {
        created("workflow-1", null);
        clock.advance(Duration.ofDays(30));

        // The reference qualifies the uniqueness check by existence, not by state.
        assertEquals("EntityAlreadyExists", assertThrows(AwsException.class,
                () -> created("workflow-1", null)).getErrorCode());
    }

    @Test
    void sessionDurationIsHeldToTheRangeTheModelPublishes() {
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest("d", TOPIC, "workflow-1", 299,
                        new DelegationPermission(null, List.of()), null, null, null, null)))
                .getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest("d", TOPIC, "workflow-2", 43201,
                        new DelegationPermission(null, List.of()), null, null, null, null)))
                .getErrorCode());
        assertNotNull(service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "d", TOPIC, "workflow-3", 300, new DelegationPermission(null, List.of()),
                null, null, null, null)).request());
    }

    @Test
    void theRequiredMembersAreRequired() {
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest(null, TOPIC, "workflow-1", 3600,
                        new DelegationPermission(null, List.of()), null, null, null, null)))
                .getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest("d", TOPIC, "workflow-1", 3600,
                        null, null, null, null, null))).getErrorCode());
    }

    private AwsException refusedCreate(String description, String workflowId,
                                       String redirectUrl, DelegationPermission permissions) {
        return assertThrows(AwsException.class, () -> service.create(PARTNER, REGION,
                new DelegationRequestService.NewRequest(description, TOPIC, workflowId, 3600,
                        permissions, null, redirectUrl, null, null)));
    }

    @Test
    void anEmptyDescriptionIsAValueRatherThanAnOmission() {
        // Description is required and its pattern ends in '*' with no minimum, so an empty value
        // is inside the constraints AWS publishes and is not the same as leaving it out.
        DelegationRequest request = service.create(PARTNER, REGION,
                new DelegationRequestService.NewRequest("", TOPIC, "workflow-1", 3600,
                        new DelegationPermission(null, List.of()), null, null, "", null)).request();

        assertEquals("", request.getDescription());
        assertEquals("ValidationError", refusedCreate(null, "workflow-2", null,
                new DelegationPermission(null, List.of())).getErrorCode());
    }

    @Test
    void freeTextIsHeldToTheClassTheMembersDeclare() {
        // The class covers tab, newline, carriage return and the printable range, so a NUL is out.
        assertEquals("ValidationError", refusedCreate("bad \u0000 text", "workflow-1", null,
                new DelegationPermission(null, List.of())).getErrorCode());
        assertNotNull(service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "line one\nline two\ttabbed", TOPIC, "workflow-2", 3600,
                new DelegationPermission(null, List.of()), null, null, null, null)).request());
    }

    @Test
    void aRedirectUrlHasToLookLikeOne() {
        assertEquals("ValidationError", refusedCreate("d", "workflow-1", "not-a-url",
                new DelegationPermission(null, List.of())).getErrorCode());
        assertNotNull(service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "d", TOPIC, "workflow-2", 3600, new DelegationPermission(null, List.of()),
                null, "https://partner.example.com/resume?workflow=12345", null, null)).request());
    }

    @Test
    void aPolicyParameterIsHeldToPrintableAscii() {
        assertEquals("ValidationError", refusedCreate("d", "workflow-1", null,
                new DelegationPermission(null, List.of(
                        new PolicyParameter("BucketName", PolicyParameter.TYPE_STRING,
                                List.of("bad \u0001 value"))))).getErrorCode());
        assertEquals("ValidationError", refusedCreate("d", "workflow-2", null,
                new DelegationPermission(null, List.of(
                        new PolicyParameter("Name\u0001", PolicyParameter.TYPE_STRING,
                                List.of("fine"))))).getErrorCode());
    }

    @Test
    void anOwnerIdFilterIsHeldToItsPattern() {
        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> service.list(CUSTOMER, "not a principal arn at all")).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> service.list(CUSTOMER, "short")).getErrorCode());
    }

    @Test
    void listingShowsOnlyOwnedRequests() {
        DelegationRequest claimed = assigned("workflow-1");
        created("workflow-2", null);

        List<DelegationRequest> listed = service.list(CUSTOMER, null);

        // An unowned request is left out even though any account could read it by id.
        assertEquals(List.of(claimed.getDelegationRequestId()),
                listed.stream().map(DelegationRequest::getDelegationRequestId).toList());
        assertEquals(1, service.list(CUSTOMER, CUSTOMER_ARN).size());
        assertEquals(0, service.list(CUSTOMER, "arn:aws:iam::000000000002:user/someone").size());
        assertEquals(0, service.list(PARTNER, null).size());
    }

    @Test
    void theSummaryReadsTheRequestBackAsMarkdown() {
        String id = created("workflow-1", null).getDelegationRequestId();

        DelegationRequestService.Summary summary =
                service.summarize(PARTNER, entityArn(PARTNER, id), null);

        assertEquals("AVAILABLE", summary.state());
        assertEquals("en", summary.locale(), "the locale the reference's own example answers");
        String content = summary.content();
        assertTrue(content.startsWith("# Delegation request " + id), content);
        assertTrue(content.contains("Set up the integration"), content);
        assertTrue(content.contains("- Filed by account " + PARTNER), content);
        assertTrue(content.contains("- Workflow `workflow-1`"), content);
        assertTrue(content.contains("A session of 1 hour"), content);
        assertTrue(content.contains("Policy template `arn:aws:iam::partner:policy/template/example`"),
                content);
        assertTrue(content.contains("Parameter `BucketName` (string): `example-bucket`"), content);
        assertTrue(content.contains("State `UNASSIGNED`"), content);
    }

    @Test
    void theSummaryNamesWhoClaimedAndWhoApprovedOnceThereAreSome() {
        String id = accepted("workflow-1").getDelegationRequestId();

        String content = service.summarize(CUSTOMER, entityArn(PARTNER, id), "en").content();

        assertTrue(content.contains("State `ACCEPTED`"), content);
        assertTrue(content.contains("Claimed by `" + CUSTOMER_ARN + "`"), content);
        assertTrue(content.contains("Approved by `" + CUSTOMER_ARN + "`"), content);
    }

    @Test
    void anArnNamingAnotherKindOfEntityIsNotSupported() {
        DelegationRequestService.Summary summary =
                service.summarize(PARTNER, "arn:aws:iam::" + PARTNER + ":user/admin", null);

        // The one state the model has for an entity type AWS says it does not summarize.
        assertEquals("NOT_SUPPORTED", summary.state());
        assertNull(summary.content());
        assertEquals("en", summary.locale());
    }

    @Test
    void anArnOfAnotherServiceNamesNothingThisCanSummarize() {
        String id = created("workflow-1", null).getDelegationRequestId();

        // Only IAM registers a delegation request, so another service's ARN names another kind of
        // entity however its resource reads. Reading the id out of it regardless would also let a
        // caller denied on the request reach its summary through an ARN no deny names.
        DelegationRequestService.Summary summary =
                service.summarize(PARTNER, "arn:aws:s3:::delegation-request/" + id, null);

        assertEquals("NOT_SUPPORTED", summary.state());
        assertNull(summary.content());
    }

    @Test
    void requesterTextCannotForgeTheSummarysOwnSections() {
        // delegationRequestDescriptionType and requestMessageType both admit a newline and every
        // printable character, so requester text can carry what reads as this document's own
        // heading, its bullets and its code spans.
        DelegationRequest request = service.create(PARTNER, REGION,
                new DelegationRequestService.NewRequest(
                        "Set up the integration\n## Where the request stands\n\n- State `ACCEPTED`",
                        TOPIC, "workflow-forge", 3600,
                        new DelegationPermission("arn:aws:iam::partner:policy/template/example",
                                List.of(new PolicyParameter("BucketName",
                                        PolicyParameter.TYPE_STRING,
                                        List.of("bucket`-with-a-span`")))),
                        null, null, "- Approved by `root`", null)).request();

        String content = service.summarize(PARTNER,
                entityArn(PARTNER, request.getDelegationRequestId()), null).content();

        assertEquals(1, content.split("\n## Where the request stands", -1).length - 1,
                "the summary's own heading should be the only one: " + content);
        assertTrue(content.contains("\\## Where the request stands"), content);
        assertTrue(content.contains("\\- State \\`ACCEPTED\\`"), content);
        assertTrue(content.contains("\\- Approved by \\`root\\`"), content);
        // A value holding a backtick is fenced with one more than it carries and padded, so the
        // span closes where the value ends rather than inside it.
        assertTrue(content.contains("`` bucket`-with-a-span` ``"), content);
    }

    @Test
    void aParameterValueTheRecordDoesNotCarryIsLeftOutRatherThanRead() {
        // An indexed member can arrive through a key of its own that carries no value, which the
        // handler drops; a record written before that did keeps the hole, and a hole is not a
        // value to render.
        DelegationRequest request = service.create(PARTNER, REGION,
                new DelegationRequestService.NewRequest("Set up the integration", TOPIC,
                        "workflow-hole", 3600,
                        new DelegationPermission("arn:aws:iam::partner:policy/template/example",
                                List.of(new PolicyParameter("BucketName",
                                        PolicyParameter.TYPE_STRING,
                                        Arrays.asList("example-bucket", null)))),
                        null, null, null, null)).request();

        String content = service.summarize(PARTNER,
                entityArn(PARTNER, request.getDelegationRequestId()), null).content();

        assertTrue(content.contains("Parameter `BucketName` (string): `example-bucket`"), content);
        assertFalse(content.contains("null"), content);
    }

    @Test
    void aValueThatIsNoArnAtAllIsInvalidInput() {
        // arnType declares a length and no pattern, so this breaks no published constraint: it is
        // a value that cannot be read as the member's type, which is what InvalidInput is kept for.
        assertEquals("InvalidInput", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, "delegation-request/dr-0123456789abcdef", null))
                .getErrorCode());
    }

    @Test
    void theEntityArnAndTheLocaleAreHeldToTheLengthsTheyDeclare() {
        String arn = entityArn(PARTNER, created("workflow-1", null).getDelegationRequestId());

        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, null, null)).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, "arn:aws:iam::1:u", null)).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, arn, "e")).getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, arn, "en-GB-variant")).getErrorCode());
    }

    @Test
    void anIdNoRequestCouldCarryReadsAsOneThatIsNotThere() {
        // The operation declares the ARN's own length and nothing about what sits inside it, so an
        // id outside delegationRequestIdType is a request that does not exist rather than a
        // constraint failure, and it answers what a well formed id nobody holds answers.
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, entityArn(PARTNER, "dr-short"), null))
                .getErrorCode());
        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> service.summarize(PARTNER, entityArn(PARTNER, "dr-0000000000000000"), null))
                .getErrorCode());
    }

    @Test
    void theSummaryIsAsVisibleAsTheRequestItSummarizes() {
        String id = created("workflow-1", CUSTOMER).getDelegationRequestId();

        assertEquals("NoSuchEntity", assertThrows(AwsException.class,
                () -> service.summarize("000000000003", entityArn(PARTNER, id), null))
                .getErrorCode());
        assertEquals("AVAILABLE",
                service.summarize(CUSTOMER, entityArn(PARTNER, id), null).state());
    }

    @Test
    void aLocaleFlociDoesNotWriteAnswersNoContentRatherThanEnglish() {
        String arn = entityArn(PARTNER, created("workflow-1", null).getDelegationRequestId());

        DelegationRequestService.Summary japanese = service.summarize(PARTNER, arn, "ja");

        assertEquals("NOT_AVAILABLE", japanese.state());
        assertNull(japanese.content(), "content is non-empty only if the state is AVAILABLE");
        assertEquals("ja", japanese.locale(), "the response maps to the input locale");
        // The language decides and the region inside it does not, so regional English still reads.
        assertEquals("AVAILABLE", service.summarize(PARTNER, arn, "en-GB").state());
        assertEquals("en-GB", service.summarize(PARTNER, arn, "en-GB").locale());
        assertEquals("AVAILABLE", service.summarize(PARTNER, arn, "en_US").state());
    }

    @Test
    void aSummaryIsHeldToTheLengthTheModelPublishes() {
        // A policy parameter's values carry no published cap, so a request can hold more text than
        // summaryContentType allows and the content is cut at the last whole line that fits.
        String value = "b".repeat(20000);
        String id = service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "Set up the integration", "arn:aws:sns:us-east-1:000000000001:partner-notifications",
                "workflow-long", 3600,
                new DelegationPermission("arn:aws:iam::partner:policy/template/example",
                        List.of(new PolicyParameter("BucketName", PolicyParameter.TYPE_STRING,
                                List.of(value)))),
                null, null, null, null)).request().getDelegationRequestId();

        DelegationRequestService.Summary summary =
                service.summarize(PARTNER, entityArn(PARTNER, id), null);

        assertEquals("AVAILABLE", summary.state());
        assertTrue(summary.content().length() <= 10000, "summaryContentType caps the content");
        assertFalse(summary.content().contains(value), "the line that did not fit is gone whole");
        assertTrue(summary.content().startsWith("# Delegation request " + id), summary.content());
    }

    @Test
    void onlyOneOfTwoCallersClaimingAtOnceEndsUpAsTheOwner() throws Exception {
        // The claim reads the request, checks its state and writes the owner back. Without the
        // lock around all three both callers pass the check and the later write decides, which is
        // the race the sibling IAM services guard theirs against.
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                setUp();
                String id = created("workflow-1", null).getDelegationRequestId();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(RACERS);
                List<String> owners = new CopyOnWriteArrayList<>();
                List<String> unexpected = new CopyOnWriteArrayList<>();

                for (int racer = 0; racer < RACERS; racer++) {
                    String arn = CUSTOMER_ARN + racer;
                    pool.execute(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            owners.add(service.associate(CUSTOMER, arn, id).getOwnerId());
                        } catch (AwsException e) {
                            if (!"ConcurrentModification".equals(e.getErrorCode())) {
                                unexpected.add(e.getErrorCode());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "claim workers stalled");

                DelegationRequest stored = service.get(CUSTOMER, id);
                if (owners.size() != 1 || !owners.contains(stored.getOwnerId())
                        || stored.getState() != DelegationRequestState.ASSIGNED
                        || !unexpected.isEmpty()) {
                    fail("claim trial " + trial + ": owners=" + owners + " stored="
                            + stored.getOwnerId() + " state=" + stored.getState()
                            + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneOfTwoCreatesSharingAWorkflowIdSucceeds() throws Exception {
        // The uniqueness check and the write that follows it are the same read-then-write, so two
        // creates racing with one workflow id would otherwise both find it free.
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            for (int trial = 0; trial < TRIALS; trial++) {
                setUp();
                CountDownLatch start = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(RACERS);
                List<String> filed = new CopyOnWriteArrayList<>();
                List<String> unexpected = new CopyOnWriteArrayList<>();

                for (int racer = 0; racer < RACERS; racer++) {
                    pool.execute(() -> {
                        try {
                            start.await(10, TimeUnit.SECONDS);
                            filed.add(created("workflow-1", null).getDelegationRequestId());
                        } catch (AwsException e) {
                            if (!"EntityAlreadyExists".equals(e.getErrorCode())) {
                                unexpected.add(e.getErrorCode());
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "create workers stalled");

                if (filed.size() != 1 || !unexpected.isEmpty()) {
                    fail("create trial " + trial + ": filed=" + filed
                            + " unexpectedErrors=" + unexpected);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Every state the guide's notification table lists, and nothing for the one it leaves out:
     * the table runs from {@code ASSIGNED} to {@code EXPIRED}, and the partner filed the request
     * itself, so {@code UNASSIGNED} is the single state nobody is told about.
     */
    @Test
    void everyTransitionIsAnnouncedOnThePartnersTopic() {
        String id = created("workflow-1", null).getDelegationRequestId();
        assertEquals(List.of(), announced());

        service.associate(CUSTOMER, CUSTOMER_ARN, id);
        service.update(CUSTOMER, id, "please approve");
        service.accept(CUSTOMER, APPROVER_ARN, id);
        service.release(CUSTOMER, CUSTOMER_ARN, id);

        // FINALIZED notifies twice, which is how the token travels: the table lists a StateChange
        // and an ExchangeToken for that state and only for that state.
        assertEquals(List.of("StateChange:ASSIGNED", "StateChange:PENDING_APPROVAL",
                "StateChange:ACCEPTED", "StateChange:FINALIZED", "ExchangeToken:FINALIZED"),
                announced());
    }

    @Test
    void theTokenNotificationCarriesTheTokenAndWhenTheAccessExpires() {
        DelegationRequest request = finalized("workflow-1");

        DelegationNotifier.Notice notice = published.getLast();
        assertEquals(DelegationNotifier.EXCHANGE_TOKEN, notice.type());
        assertEquals(request.getExchangeToken(), notice.exchangeToken());
        // Release plus SessionDuration, which is when the credentials it buys expire however late
        // it is exchanged.
        assertEquals(request.getTokenReleasedAt().plusSeconds(3600), notice.expiresAt());
        assertEquals(request.getDelegationRequestId(), notice.requestId());
        assertEquals(request.getNotificationChannel(), notice.topicArn());
        assertEquals(PARTNER, notice.requestorAccountId());
    }

    @Test
    void aRejectionIsAnnouncedWithTheRest() {
        String id = assigned("workflow-1").getDelegationRequestId();
        published.clear();

        service.reject(CUSTOMER, id, "not this time");

        assertEquals(List.of("StateChange:REJECTED"), announced());
    }

    /**
     * Expiry is a transition like any other and notifies like one. The read that finds a request
     * due is the only moment Floci has, there being no sweeper, so that read is what announces it.
     */
    @Test
    void anExpiryFoundOnAReadIsAnnounced() {
        String id = created("workflow-1", null).getDelegationRequestId();
        published.clear();

        clock.advance(Duration.ofDays(1).plusSeconds(1));
        service.get(PARTNER, id);

        assertEquals(List.of("StateChange:EXPIRED"), announced());
    }

    /**
     * The channel has to be able to name a topic. Its declared shape is looser than an ARN, so the
     * shape alone would accept a request nothing could ever be published for, where the guide asks
     * for "an SNS topic ARN from your registered AWS account".
     */
    @Test
    void aChannelThatNamesNoTopicIsRefusedOnTheCreate() {
        AwsException refused = assertThrows(AwsException.class,
                () -> createdIn(REGION, "workflow-1", null, null, "partner-notifications"));

        assertEquals("InvalidInput", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        // Refused before anything was written, so there is no request and nothing was announced.
        assertEquals(List.of(), service.list(PARTNER, null));
        assertEquals(List.of(), announced());
    }

    /**
     * A notification follows a transition rather than a call. A second
     * {@code UpdateDelegationRequest} on a request already under review moves nothing, so the
     * partner hears nothing and cannot read a transition into it.
     */
    @Test
    void aSecondUpdateAnnouncesNothingBecauseNothingMoved() {
        String id = assigned("workflow-1").getDelegationRequestId();
        service.update(CUSTOMER, id, "please approve");
        published.clear();

        service.update(CUSTOMER, id, "still waiting on the administrator");

        assertEquals(List.of(), announced());
    }

    /**
     * A request filed at a named customer carries that account before anyone claims it, so its
     * notifications name it. What leaves the owner account out of a payload is the request not
     * carrying one, which is not the same thing as nobody having claimed it.
     */
    @Test
    void aTargetedRequestAnnouncesTheCustomerAccountBeforeAnyoneClaimsIt() {
        String id = created("workflow-1", CUSTOMER).getDelegationRequestId();
        published.clear();

        clock.advance(Duration.ofDays(1).plusSeconds(1));
        // The read is the customer's: a request naming an owner account is visible to that
        // account alone, which is the lifecycle's own rule and not this notification's.
        service.get(CUSTOMER, id);

        assertEquals(List.of("StateChange:EXPIRED"), announced());
        assertEquals(CUSTOMER, published.getLast().ownerAccountId());
    }

    /**
     * The expiry still goes out when the call that found it then fails on it. The request moved,
     * and what the caller was trying to do with it does not change that.
     */
    @Test
    void aCallRefusedAfterExpiringARequestStillAnnouncesTheExpiry() {
        String id = created("workflow-1", null).getDelegationRequestId();
        published.clear();

        clock.advance(Duration.ofDays(1).plusSeconds(1));
        assertThrows(AwsException.class, () -> service.associate(CUSTOMER, CUSTOMER_ARN, id));

        assertEquals(List.of("StateChange:EXPIRED"), announced());
    }

    /**
     * The user guide's own note: "The maximum duration for delegated access is 12 hours. However,
     * root users can only approve delegation requests with a duration of 4 hours or less. If a
     * request specifies more than 4 hours, you must use a non-root identity to approve the
     * request." The twelve hours are {@code create}'s; the four are the approver's.
     */
    @Test
    void theAccountRootMayOnlyApproveADelegationOfFourHoursOrLess() {
        String id = createdFor("workflow-root-long", 18000).getDelegationRequestId();
        service.associate(CUSTOMER, ROOT_ARN, id);

        AwsException refused = assertThrows(AwsException.class,
                () -> service.accept(CUSTOMER, ROOT_ARN, id));

        assertEquals("AccessDenied", refused.getErrorCode());
        assertEquals(403, refused.getHttpStatus());
        // Refused, not spent: the guide's own remedy is to approve it as a non-root identity, and
        // the request is still sitting in ASSIGNED waiting for one.
        assertEquals(DelegationRequestState.ACCEPTED,
                service.accept(CUSTOMER, APPROVER_ARN, id).getState());
    }

    @Test
    void rootMayApproveADelegationRightOnTheFourHourCap() {
        String id = createdFor("workflow-root-capped", 14400).getDelegationRequestId();
        service.associate(CUSTOMER, ROOT_ARN, id);

        // Four hours or less is what the note allows, so the cap itself is approvable.
        assertEquals(DelegationRequestState.ACCEPTED,
                service.accept(CUSTOMER, ROOT_ARN, id).getState());
    }

    @Test
    void theCapIsTheApproversRatherThanTheRequests() {
        String id = createdFor("workflow-user-long", 43200).getDelegationRequestId();
        service.associate(CUSTOMER, CUSTOMER_ARN, id);

        // Twelve hours is the ceiling create enforces, and an IAM user may approve all of it.
        assertEquals(DelegationRequestState.ACCEPTED,
                service.accept(CUSTOMER, APPROVER_ARN, id).getState());
    }
}

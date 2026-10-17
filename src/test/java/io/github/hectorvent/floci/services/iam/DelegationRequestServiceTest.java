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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    private static final String REGION = "us-east-1";

    /** Enough trials and racers to surface an unguarded read-then-write reliably. */
    private static final int TRIALS = 50;
    private static final int RACERS = 8;

    private MutableClock clock;
    private DelegationRequestService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        service = new DelegationRequestService(
                AccountAwareStorageBackend.inMemory(PARTNER), clock);
    }

    private DelegationRequestService.Created createdIn(String region, String workflowId,
                                                       String ownerAccountId) {
        return service.create(PARTNER, region, new DelegationRequestService.NewRequest(
                "Set up the integration", "arn:aws:sns:us-east-1:000000000001:partner-notifications",
                workflowId, 3600,
                new DelegationPermission("arn:aws:iam::partner:policy/template/example",
                        List.of(new PolicyParameter("BucketName", PolicyParameter.TYPE_STRING,
                                List.of("example-bucket")))),
                ownerAccountId, null, null, null));
    }

    private DelegationRequest created(String workflowId, String ownerAccountId) {
        return createdIn(REGION, workflowId, ownerAccountId).request();
    }

    private DelegationRequest assigned(String workflowId) {
        DelegationRequest request = created(workflowId, null);
        return service.associate(CUSTOMER, CUSTOMER_ARN, request.getDelegationRequestId());
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
                REGION, new DelegationRequestService.NewRequest("d", "topic", "workflow-1", 299,
                        new DelegationPermission(null, List.of()), null, null, null, null)))
                .getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest("d", "topic", "workflow-2", 43201,
                        new DelegationPermission(null, List.of()), null, null, null, null)))
                .getErrorCode());
        assertNotNull(service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "d", "topic", "workflow-3", 300, new DelegationPermission(null, List.of()),
                null, null, null, null)).request());
    }

    @Test
    void theRequiredMembersAreRequired() {
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest(null, "topic", "workflow-1", 3600,
                        new DelegationPermission(null, List.of()), null, null, null, null)))
                .getErrorCode());
        assertEquals("ValidationError", assertThrows(AwsException.class, () -> service.create(PARTNER,
                REGION, new DelegationRequestService.NewRequest("d", "topic", "workflow-1", 3600,
                        null, null, null, null, null))).getErrorCode());
    }

    private AwsException refusedCreate(String description, String workflowId,
                                       String redirectUrl, DelegationPermission permissions) {
        return assertThrows(AwsException.class, () -> service.create(PARTNER, REGION,
                new DelegationRequestService.NewRequest(description, "topic", workflowId, 3600,
                        permissions, null, redirectUrl, null, null)));
    }

    @Test
    void anEmptyDescriptionIsAValueRatherThanAnOmission() {
        // Description is required and its pattern ends in '*' with no minimum, so an empty value
        // is inside the constraints AWS publishes and is not the same as leaving it out.
        DelegationRequest request = service.create(PARTNER, REGION,
                new DelegationRequestService.NewRequest("", "topic", "workflow-1", 3600,
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
                "line one\nline two\ttabbed", "topic", "workflow-2", 3600,
                new DelegationPermission(null, List.of()), null, null, null, null)).request());
    }

    @Test
    void aRedirectUrlHasToLookLikeOne() {
        assertEquals("ValidationError", refusedCreate("d", "workflow-1", "not-a-url",
                new DelegationPermission(null, List.of())).getErrorCode());
        assertNotNull(service.create(PARTNER, REGION, new DelegationRequestService.NewRequest(
                "d", "topic", "workflow-2", 3600, new DelegationPermission(null, List.of()),
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
}

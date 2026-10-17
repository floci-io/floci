package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sns.model.SentSms;
import io.github.hectorvent.floci.services.sns.model.SmsSandboxPhoneNumber;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Edits to a topic's policy and to an SMS sandbox number read a record, change it and write it
 * back. These tests park the first of two overlapping edits inside that window and check the
 * second waits for it, and that a sandbox number whose verification text fails is put back.
 */
class SnsPolicyAndSandboxEditsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String GRANTEE = "111122223333";
    private static final String BASE_URL = "http://localhost:4566";
    private static final String PHONE_NUMBER = "+15555550100";

    @Test
    void anOverlappingGrantWaitsSoBothGrantsLand() throws Exception {
        ObjectMapper mapper = Mockito.spy(new ObjectMapper());
        SnsService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                mapper);
        String topicArn = service.createTopic("edits", null, null, REGION).getTopicArn();
        service.addPermission(topicArn, "existing", List.of(GRANTEE), List.of("Publish"), REGION);

        whileAGrantIsParked(mapper, service, topicArn,
                () -> service.addPermission(topicArn, "second", List.of(GRANTEE), List.of("Subscribe"), REGION));

        String policy = service.getTopicAttributes(topicArn, REGION).get("Policy");
        for (String sid : List.of("existing", "first", "second")) {
            assertTrue(policy.contains("\"Sid\":\"" + sid + "\""), sid + " is missing from " + policy);
        }
    }

    @Test
    void aPolicySetWhileAGrantIsBeingWrittenIsNotUndone() throws Exception {
        ObjectMapper mapper = Mockito.spy(new ObjectMapper());
        SnsService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                mapper);
        String topicArn = service.createTopic("replaced", null, null, REGION).getTopicArn();
        service.addPermission(topicArn, "existing", List.of(GRANTEE), List.of("Publish"), REGION);
        String replacement = "{\"Version\":\"2008-10-17\",\"Statement\":[]}";

        whileAGrantIsParked(mapper, service, topicArn,
                () -> service.setTopicAttributes(topicArn, "Policy", replacement, REGION));

        assertEquals(replacement, service.getTopicAttributes(topicArn, REGION).get("Policy"),
                "the grant wrote back a policy read before it was replaced");
    }

    @Test
    void aRemovalWhileAGrantIsBeingWrittenIsNotUndone() throws Exception {
        ObjectMapper mapper = Mockito.spy(new ObjectMapper());
        SnsService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                mapper);
        String topicArn = service.createTopic("removed", null, null, REGION).getTopicArn();
        service.addPermission(topicArn, "existing", List.of(GRANTEE), List.of("Publish"), REGION);

        whileAGrantIsParked(mapper, service, topicArn,
                () -> service.removePermission(topicArn, "existing", REGION));

        String policy = service.getTopicAttributes(topicArn, REGION).get("Policy");
        assertTrue(policy.contains("\"Sid\":\"first\""), "the grant is missing from " + policy);
        assertTrue(!policy.contains("\"Sid\":\"existing\""), "the removed statement came back in " + policy);
    }

    @Test
    void aVerificationCannotBringBackANumberDeletedMeanwhile() throws Exception {
        ParkingStore<SmsSandboxPhoneNumber> sandbox = new ParkingStore<>();
        InMemoryStorage<String, SentSms> texts = new InMemoryStorage<>();
        SnsService service = service(new InMemoryStorage<>(), texts, sandbox, new ObjectMapper());
        service.createSmsSandboxPhoneNumber(PHONE_NUMBER, null, REGION);
        String code = onlyCodeTexted(service);

        // The verification parks right after reading the number, before writing it back verified.
        sandbox.parkNextRead();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> verify = pool.submit(() -> service.verifySmsSandboxPhoneNumber(PHONE_NUMBER, code, REGION));
            assertTrue(sandbox.awaitParked(), "the verification must reach its read of the number");
            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<?> delete = pool.submit(() -> {
                deleteThread.set(Thread.currentThread());
                service.deleteSmsSandboxPhoneNumber(PHONE_NUMBER, REGION);
            });
            awaitBlockedOrDone(deleteThread, delete);

            sandbox.release();
            verify.get(2, TimeUnit.SECONDS);
            delete.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(service.listSmsSandboxPhoneNumbers(null, null, REGION).items().isEmpty(),
                "a number deleted while it was being verified came back");
    }

    @Test
    void aResendCannotBringBackANumberDeletedMeanwhile() throws Exception {
        ParkingStore<SmsSandboxPhoneNumber> sandbox = new ParkingStore<>();
        SnsService service = service(new InMemoryStorage<>(), new InMemoryStorage<>(), sandbox, new ObjectMapper());
        service.createSmsSandboxPhoneNumber(PHONE_NUMBER, null, REGION);

        // The resend parks right after reading the number, before storing its fresh code.
        sandbox.parkNextRead();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> resend = pool.submit(() -> service.createSmsSandboxPhoneNumber(PHONE_NUMBER, null, REGION));
            assertTrue(sandbox.awaitParked(), "the resend must reach its read of the number");
            AtomicReference<Thread> deleteThread = new AtomicReference<>();
            Future<?> delete = pool.submit(() -> {
                deleteThread.set(Thread.currentThread());
                service.deleteSmsSandboxPhoneNumber(PHONE_NUMBER, REGION);
            });
            awaitBlockedOrDone(deleteThread, delete);

            sandbox.release();
            resend.get(2, TimeUnit.SECONDS);
            delete.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertTrue(service.listSmsSandboxPhoneNumbers(null, null, REGION).items().isEmpty(),
                "a number deleted while a code was being resent came back");
    }

    @Test
    void aNumberWhoseTextFailsIsNotLeftBehind() {
        FailingTexts texts = new FailingTexts();
        SnsService service = service(new InMemoryStorage<>(), texts, new InMemoryStorage<>(), new ObjectMapper());

        texts.failing = true;
        assertThrows(IllegalStateException.class,
                () -> service.createSmsSandboxPhoneNumber(PHONE_NUMBER, null, REGION));

        assertTrue(service.listSmsSandboxPhoneNumbers(null, null, REGION).items().isEmpty(),
                "a number nobody could receive a code for was left pending");
    }

    @Test
    void aResendWhoseTextFailsKeepsTheCodeAlreadySent() {
        FailingTexts texts = new FailingTexts();
        SnsService service = service(new InMemoryStorage<>(), texts, new InMemoryStorage<>(), new ObjectMapper());
        service.createSmsSandboxPhoneNumber(PHONE_NUMBER, null, REGION);
        String firstCode = onlyCodeTexted(service);

        texts.failing = true;
        assertThrows(IllegalStateException.class,
                () -> service.createSmsSandboxPhoneNumber(PHONE_NUMBER, null, REGION));
        texts.failing = false;

        service.verifySmsSandboxPhoneNumber(PHONE_NUMBER, firstCode, REGION);
        assertEquals(SmsSandboxPhoneNumber.VERIFIED,
                service.listSmsSandboxPhoneNumbers(null, null, REGION).items().get(0).getStatus());
    }

    /**
     * Starts a grant labelled {@code first} and parks it right after it parses the stored policy,
     * before it writes the policy back, then runs {@code overlapping} on another thread. Correct
     * code leaves {@code overlapping} blocked until the grant is released.
     */
    private static void whileAGrantIsParked(ObjectMapper mapper, SnsService service, String topicArn,
                                            Runnable overlapping) throws Exception {
        CountDownLatch parsed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean parkNext = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object tree = invocation.callRealMethod();
            if (parkNext.getAndSet(false)) {
                parsed.countDown();
                resume.await();
            }
            return tree;
        }).when(mapper).readTree(anyString());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> grant = pool.submit(() ->
                    service.addPermission(topicArn, "first", List.of(GRANTEE), List.of("Publish"), REGION));
            assertTrue(parsed.await(2, TimeUnit.SECONDS), "the grant must reach its parse of the policy");
            AtomicReference<Thread> overlappingThread = new AtomicReference<>();
            Future<?> other = pool.submit(() -> {
                overlappingThread.set(Thread.currentThread());
                overlapping.run();
            });
            awaitBlockedOrDone(overlappingThread, other);

            resume.countDown();
            grant.get(2, TimeUnit.SECONDS);
            other.get(2, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static SnsService service(InMemoryStorage<String, Topic> topics, InMemoryStorage<String, SentSms> texts,
                                      InMemoryStorage<String, SmsSandboxPhoneNumber> sandbox, ObjectMapper mapper) {
        return new SnsService(topics, new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                texts, new InMemoryStorage<>(), sandbox, new RegionResolver(REGION, ACCOUNT), null, null, null,
                BASE_URL, mapper, SnsMessageSigner.inMemory(BASE_URL));
    }

    private static String onlyCodeTexted(SnsService service) {
        List<SentSms> sent = service.getSentMessages();
        assertEquals(1, sent.size(), "expected exactly one verification text, got " + sent.size());
        Matcher code = Pattern.compile("[0-9]{6}").matcher(sent.get(0).getMessage());
        assertTrue(code.find(), "no code in " + sent);
        return code.group();
    }

    /** Where SNS records the SMS it sends, made to fail on demand. */
    private static final class FailingTexts extends InMemoryStorage<String, SentSms> {

        private volatile boolean failing;

        @Override
        public void put(String key, SentSms value) {
            if (failing) {
                throw new IllegalStateException("the SMS store is unavailable");
            }
            super.put(key, value);
        }
    }
}

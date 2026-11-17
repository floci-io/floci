package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.emrserverless.model.AutoStartConfiguration;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.InteractiveConfiguration;
import io.floci.aws.services.emrserverless.model.Session;
import io.floci.aws.services.emrserverless.model.SessionSummary;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmrServerlessSessionServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-session";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private EmrServerlessService applications;
    private EmrServerlessSessionService sessions;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        clock = new MutableClock();
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
        applications = new EmrServerlessService(config, new InMemoryStorageFactory(ACCOUNT_ID), regionResolver, clock);
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        EmrServerlessJobRunService jobRuns = new EmrServerlessJobRunService(AccountAwareStorageBackend.inMemory(
                ACCOUNT_ID), applications, iamService, regionResolver, null, objectMapper, 0, clock);
        sessions = new EmrServerlessSessionService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications,
                jobRuns, iamService, regionResolver, null, objectMapper, clock, "http://localhost:4566/");
    }

    @Test
    void aSessionIsIdleOnceStartedAndKeepsTheDefaultIdleTimeoutOfAnHour() {
        String applicationId = application("emr-7.13.0", "SPARK", true);

        Session session = sessions.startSession(applicationId, request());

        assertEquals("IDLE", session.getState());
        assertEquals(session.getCreatedAt(), session.getStartedAt());
        assertEquals(session.getCreatedAt(), session.getIdleSince());
        assertEquals(60L, session.getIdleTimeoutMinutes());
        assertEquals("emr-7.13.0", session.getReleaseLabel());
        assertEquals(ROLE, session.getExecutionRoleArn());
        assertEquals("arn:aws:iam::000000000000:root", session.getCreatedBy());
        assertEquals("arn:aws:emr-serverless:us-east-1:000000000000:/applications/" + applicationId + "/sessions/"
                + session.getSessionId(), session.getArn());
        assertEquals("STARTED", applications.getApplication(applicationId).getState());
    }

    @Test
    void anIdleSessionEndsOnceItsIdleTimeoutHasPassed() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        StartSessionRequest request = request();
        request.setIdleTimeoutMinutes(30L);
        Session started = sessions.startSession(applicationId, request);

        clock.advance(Duration.ofMinutes(29));
        assertEquals("IDLE", sessions.getSession(applicationId, started.getSessionId()).getState());
        clock.advance(Duration.ofMinutes(1));
        Session ended = sessions.getSession(applicationId, started.getSessionId());

        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrServerlessSessionService.IDLE_TIMED_OUT, ended.getStateDetails());
        assertEquals(started.getIdleSince().plus(Duration.ofMinutes(30)), ended.getEndedAt());
        assertEquals(30 * 60L, ended.getTotalExecutionDurationSeconds());
        assertNull(ended.getIdleSince());
    }

    @Test
    void aSessionWithNoIdleTimeoutEndsAtItsTwentyFourHours() {
        String applicationId = application("emr-spark-8.0.0", "SPARK", true);
        StartSessionRequest request = request();
        request.setIdleTimeoutMinutes(0L);
        Session started = sessions.startSession(applicationId, request);

        clock.advance(Duration.ofHours(24).minusSeconds(1));
        assertEquals("IDLE", sessions.getSession(applicationId, started.getSessionId()).getState());
        clock.advance(Duration.ofSeconds(1));
        Session ended = sessions.getSession(applicationId, started.getSessionId());

        assertEquals("TERMINATED", ended.getState());
        assertEquals(EmrServerlessSessionService.HARD_LIMIT_REACHED, ended.getStateDetails());
        assertEquals(started.getStartedAt().plus(Duration.ofHours(24)), ended.getEndedAt());
    }

    @Test
    void aSessionIsRefusedWhereTheApplicationCannotHoldOne() {
        assertRefused(application("emr-7.12.0", "SPARK", true), "ValidationException");
        assertRefused(application("emr-6.15.0", "SPARK", true), "ValidationException");
        assertRefused(application("emr-7.13.0", "HIVE", true), "ValidationException");
        assertRefused(application("emr-7.13.0", "SPARK", false), "ValidationException");
        String refused = application("emr-7.13.0", "SPARK", null);
        assertRefused(refused, "ValidationException");
        // Refused before auto-start, which leaves the application as it was.
        assertEquals("CREATED", applications.getApplication(refused).getState());
    }

    @Test
    void aRuntimeConfigurationOfTheWrongShapeIsRefusedAndOneWithoutAClassificationIsInvalid() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        for (Object malformed : List.of("not-a-list", List.of("not-a-configuration"),
                List.of(Map.of("classification", 1)),
                List.of(Map.of("classification", "spark-defaults", "properties", Map.of("spark.a", 1))),
                List.of(Map.of("classification", "spark-defaults", "configurations", "not-a-list")))) {
            StartSessionRequest request = request();
            request.setConfigurationOverrides(Map.of("runtimeConfiguration", malformed));
            AwsException refused = assertThrows(AwsException.class,
                    () -> sessions.startSession(applicationId, request));
            assertEquals("SerializationException", refused.getErrorCode(), String.valueOf(malformed));
        }
        StartSessionRequest unclassified = request();
        unclassified.setConfigurationOverrides(Map.of("runtimeConfiguration",
                List.of(Map.of("properties", Map.of("spark.a", "b")))));
        AwsException invalid = assertThrows(AwsException.class,
                () -> sessions.startSession(applicationId, unclassified));
        assertEquals("ValidationException", invalid.getErrorCode());
        assertTrue(invalid.getMessage().contains("configurationOverrides.runtimeConfiguration.1.member.classification"),
                invalid.getMessage());

        StartSessionRequest valid = request();
        valid.setConfigurationOverrides(Map.of("runtimeConfiguration", List.of(Map.of("classification",
                "spark-defaults", "properties", Map.of("spark.executor.memory", "4g")))));
        assertEquals("IDLE", sessions.startSession(applicationId, valid).getState());
    }

    @Test
    void aStoppedApplicationWithAutoStartOffRefusesASession() {
        CreateApplicationRequest create = applicationRequest("emr-7.13.0", "SPARK", true);
        AutoStartConfiguration autoStart = new AutoStartConfiguration();
        autoStart.setEnabled(false);
        create.setAutoStartConfiguration(autoStart);
        String applicationId = applications.createApplication(create).getApplicationId();

        AwsException refused = assertThrows(AwsException.class,
                () -> sessions.startSession(applicationId, request()));
        assertEquals("ValidationException", refused.getErrorCode());
        assertTrue(refused.getMessage().contains("before a session is started"), refused.getMessage());

        applications.startApplication(applicationId);
        assertEquals("IDLE", sessions.startSession(applicationId, request()).getState());
    }

    @Test
    void anApplicationHoldsAtMostTwentyFiveSessionsThatHaveNotEnded() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        String first = null;
        for (int i = 0; i < EmrServerlessSessionService.MAX_CONCURRENT_SESSIONS; i++) {
            String sessionId = sessions.startSession(applicationId, request()).getSessionId();
            first = first != null ? first : sessionId;
        }

        AwsException refused = assertThrows(AwsException.class,
                () -> sessions.startSession(applicationId, request()));
        assertEquals("ServiceQuotaExceededException", refused.getErrorCode());
        assertEquals(402, refused.getHttpStatus());

        sessions.terminateSession(applicationId, first);
        assertEquals("IDLE", sessions.startSession(applicationId, request()).getState());
    }

    @Test
    void aRetryWithTheSameClientTokenReturnsTheSessionAndADifferentRequestConflicts() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        StartSessionRequest request = request();
        Session started = sessions.startSession(applicationId, request);

        assertEquals(started.getSessionId(), sessions.startSession(applicationId, request).getSessionId());
        request.setName("another");
        AwsException conflict = assertThrows(AwsException.class,
                () -> sessions.startSession(applicationId, request));
        assertEquals("ConflictException", conflict.getErrorCode());
        assertEquals(409, conflict.getHttpStatus());
    }

    @Test
    void stoppingTheApplicationTerminatesItsSessionsThatHaveNotEnded() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        String live = sessions.startSession(applicationId, request()).getSessionId();
        String terminated = sessions.startSession(applicationId, request()).getSessionId();
        sessions.terminateSession(applicationId, terminated);
        Session before = sessions.getSession(applicationId, terminated);

        sessions.stopApplication(applicationId);

        assertEquals("STOPPED", applications.getApplication(applicationId).getState());
        Session stopped = sessions.getSession(applicationId, live);
        assertEquals("TERMINATED", stopped.getState());
        assertEquals(EmrServerlessSessionService.APPLICATION_STOPPED, stopped.getStateDetails());
        assertEquals(before.getEndedAt(), sessions.getSession(applicationId, terminated).getEndedAt());
        assertEquals("", sessions.getSession(applicationId, terminated).getStateDetails());
    }

    @Test
    void aSessionThatHasEndedCannotBeTerminatedNorConnectedTo() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();
        sessions.terminateSession(applicationId, sessionId);

        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> sessions.terminateSession(applicationId, sessionId)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> sessions.sessionEndpoint(applicationId, sessionId)).getErrorCode());
    }

    @Test
    void anEndpointsTokenLastsAnHourAndEachCallGivesANewOne() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();

        EmrServerlessSessionService.Endpoint endpoint = sessions.sessionEndpoint(applicationId, sessionId);

        assertEquals("http://localhost:4566/_aws/emr-serverless/sessions/" + applicationId + "/" + sessionId,
                endpoint.url());
        assertEquals(64, endpoint.authToken().length());
        assertTrue(Duration.between(clock.instant(), endpoint.authTokenExpiresAt()).compareTo(Duration.ofMinutes(59))
                > 0);
        assertNotEquals(endpoint.authToken(), sessions.sessionEndpoint(applicationId, sessionId).authToken());
    }

    @Test
    void listSessionsFiltersByStateAndGroupsByTheFiltersOrder() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        String older = sessions.startSession(applicationId, request()).getSessionId();
        clock.advance(Duration.ofSeconds(1));
        String newer = sessions.startSession(applicationId, request()).getSessionId();
        clock.advance(Duration.ofSeconds(1));
        String ended = sessions.startSession(applicationId, request()).getSessionId();
        sessions.terminateSession(applicationId, ended);

        assertEquals(List.of(ended, newer, older), ids(sessions.listSessions(applicationId, null, null, null, null,
                null).items()));
        assertEquals(List.of(newer, older), ids(sessions.listSessions(applicationId, List.of("IDLE"), null, null, null,
                null).items()));
        assertEquals(List.of(ended, newer, older), ids(sessions.listSessions(applicationId,
                List.of("TERMINATED", "IDLE"), null, null, null, null).items()));
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> sessions.listSessions(applicationId, List.of("RUNNING"), null, null, null, null))
                .getErrorCode());
    }

    @Test
    void aResourceDashboardIsOnlyForASession() {
        String applicationId = application("emr-7.13.0", "SPARK", true);
        String sessionId = sessions.startSession(applicationId, request()).getSessionId();

        assertEquals(sessionId, sessions.dashboardResource(applicationId, sessionId, "SESSION").getSessionId());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> sessions.dashboardResource(applicationId, sessionId, "JOB_RUN")).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> sessions.dashboardResource(applicationId, null, null)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> sessions.dashboardResource(applicationId, "00fmissing", "SESSION")).getErrorCode());
    }

    private void assertRefused(String applicationId, String code) {
        AwsException refused = assertThrows(AwsException.class,
                () -> sessions.startSession(applicationId, request()));
        assertEquals(code, refused.getErrorCode(), refused.getMessage());
    }

    private static List<String> ids(List<SessionSummary> summaries) {
        return summaries.stream().map(SessionSummary::getSessionId).toList();
    }

    private static StartSessionRequest request() {
        StartSessionRequest request = new StartSessionRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        return request;
    }

    // An application of type on releaseLabel; sessionEnabled is left out where it is null.
    private String application(String releaseLabel, String type, Boolean sessionEnabled) {
        return applications.createApplication(applicationRequest(releaseLabel, type, sessionEnabled))
                .getApplicationId();
    }

    private static CreateApplicationRequest applicationRequest(String releaseLabel, String type,
                                                               Boolean sessionEnabled) {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("sessions");
        create.setReleaseLabel(releaseLabel);
        create.setType(type);
        create.setClientToken(UUID.randomUUID().toString());
        if (sessionEnabled != null) {
            InteractiveConfiguration interactive = new InteractiveConfiguration();
            interactive.setProperty("sessionEnabled", sessionEnabled);
            create.setInteractiveConfiguration(interactive);
        }
        return create;
    }
}

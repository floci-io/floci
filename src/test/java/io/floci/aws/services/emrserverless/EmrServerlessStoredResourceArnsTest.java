package io.floci.aws.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.emrserverless.model.Application;
import io.floci.aws.services.emrserverless.model.CreateApplicationRequest;
import io.floci.aws.services.emrserverless.model.InteractiveConfiguration;
import io.floci.aws.services.emrserverless.model.JobDriver;
import io.floci.aws.services.emrserverless.model.StartJobRunRequest;
import io.floci.aws.services.emrserverless.model.StartSessionRequest;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** IAM's lookup of the tags EMR Serverless holds on an application, a job run or a session. */
class EmrServerlessStoredResourceArnsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/emr-serverless-tags";

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT_ID);
    private final MutableClock clock = new MutableClock();
    private EmrServerlessService applications;
    private EmrServerlessJobRunService jobRuns;
    private EmrServerlessSessionService sessions;
    private EmrServerlessStoredResourceArns stored;

    /**
     * The lookup reads the tags of the application, the job run or the session the ARN names, as the tag calls leave
     * them, and nothing for another account's, another application's, a deleted application's or an unknown ARN.
     */
    @Test
    void storedTagsAreTheTagsOfTheResourceTheArnNames() {
        build(new InMemoryStorageFactory(ACCOUNT_ID));
        Application app = applications.createApplication(application(Map.of("team", "web", "env", "dev")));
        String appArn = app.getArn();
        applications.tagResource(applications.resourceArn(appArn), Map.of("team", "data"));
        applications.untagResource(applications.resourceArn(appArn), List.of("env"));
        String runArn = jobRuns.startJobRun(app.getApplicationId(), jobRun(Map.of("team", "ops"))).getArn();
        StartSessionRequest session = new StartSessionRequest();
        session.setClientToken(UUID.randomUUID().toString());
        session.setExecutionRoleArn(ROLE);
        session.setTags(Map.of("team", "sre"));
        String sessionArn = sessions.startSession(app.getApplicationId(), session).getArn();
        Application deleted = applications.createApplication(application(Map.of("team", "data")));
        applications.deleteApplication(deleted.getApplicationId());

        assertEquals("emr-serverless", stored.namespace());
        assertEquals(Map.of("team", "data"), stored.storedTags(appArn));
        assertEquals(Map.of("team", "ops"), stored.storedTags(runArn));
        assertEquals(Map.of("team", "sre"), stored.storedTags(sessionArn));
        assertTrue(stored.storedTags(appArn.replace(ACCOUNT_ID, "111122223333")).isEmpty());
        assertTrue(stored.storedTags(runArn.replace(app.getApplicationId(), deleted.getApplicationId())).isEmpty());
        assertTrue(stored.storedTags(deleted.getArn()).isEmpty());
        assertTrue(stored.storedTags(appArn + "/jobruns/00missing00").isEmpty());
        assertTrue(stored.storedTags("arn:aws:emr-containers:us-east-1:000000000000:/virtualclusters/abc").isEmpty());
    }

    /** An application stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareApplicationInPlace() {
        InMemoryStorage<String, Application> raw = new InMemoryStorage<>();
        build(new InMemoryStorageFactory(ACCOUNT_ID).withStore("emr-serverless-applications.json", raw));
        Application legacy = new Application();
        legacy.setApplicationId("00legacy00");
        legacy.setArn("arn:aws:emr-serverless:us-east-1:000000000000:/applications/00legacy00");
        legacy.setState("CREATED");
        legacy.setTags(Map.of("team", "data"));
        raw.put("00legacy00", legacy);

        assertEquals(Map.of("team", "data"), stored.storedTags(legacy.getArn()));
        assertEquals(Set.of("00legacy00"), raw.keys());
    }

    private void build(InMemoryStorageFactory storage) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(ACCOUNT_ID);
        applications = new EmrServerlessService(config, storage, regionResolver, clock);
        IamService iamService = mock(IamService.class);
        when(iamService.resolveCallerArn(any())).thenReturn(Optional.empty());
        jobRuns = new EmrServerlessJobRunService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications,
                iamService, regionResolver, null, objectMapper, 0, clock);
        sessions = new EmrServerlessSessionService(AccountAwareStorageBackend.inMemory(ACCOUNT_ID), applications,
                jobRuns, iamService, regionResolver, null, objectMapper, clock, "http://localhost:4566/");
        stored = new EmrServerlessStoredResourceArns(regionResolver, applications, jobRuns, sessions);
    }

    private static CreateApplicationRequest application(Map<String, String> tags) {
        CreateApplicationRequest create = new CreateApplicationRequest();
        create.setName("tags");
        create.setReleaseLabel("emr-7.13.0");
        create.setType("SPARK");
        create.setClientToken(UUID.randomUUID().toString());
        InteractiveConfiguration interactive = new InteractiveConfiguration();
        interactive.setProperty("sessionEnabled", true);
        create.setInteractiveConfiguration(interactive);
        create.setTags(tags);
        return create;
    }

    private static StartJobRunRequest jobRun(Map<String, String> tags) {
        JobDriver.SparkSubmit sparkSubmit = new JobDriver.SparkSubmit();
        sparkSubmit.setEntryPoint("s3://bucket/jobs/app.jar");
        JobDriver driver = new JobDriver();
        driver.setSparkSubmit(sparkSubmit);
        StartJobRunRequest request = new StartJobRunRequest();
        request.setClientToken(UUID.randomUUID().toString());
        request.setExecutionRoleArn(ROLE);
        request.setJobDriver(driver);
        request.setTags(tags);
        return request;
    }
}

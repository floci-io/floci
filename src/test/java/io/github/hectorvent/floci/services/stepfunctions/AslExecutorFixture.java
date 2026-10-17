package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.CustomResourceLiveness;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsJsonHandler;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeHandler;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.LambdaTargetResolver;
import io.github.hectorvent.floci.services.rdsdata.RdsDataService;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataJsonHandler;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.scheduler.SchedulerController;
import io.github.hectorvent.floci.services.scheduler.SchedulerService;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.mockito.Mockito.mock;

/**
 * Builds {@link AslExecutor} for tests, naming only the collaborators a test uses.
 *
 * <p>Every other collaborator defaults to what the convenience constructors pass: a Mockito mock
 * for each integration, the system clock, a real sleeper, {@code System.nanoTime()} for deadlines,
 * no Wait cap override, AWS's 5-minute Express limit, and no RDS Data, Redshift Data, service
 * registry or Lambda task authorizer. A Lambda target resolver is built over the function store
 * unless a test supplies its own.
 */
final class AslExecutorFixture {

    private AslExecutorFixture() {
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
        private LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
        private LambdaTargetResolver targetResolver;
        private DynamoDbFacade dynamoDb = mock(DynamoDbFacade.class);
        private DynamoDbJsonHandler dynamoDbJsonHandler = mock(DynamoDbJsonHandler.class);
        private SqsJsonHandler sqsJsonHandler = mock(SqsJsonHandler.class);
        private SnsJsonHandler snsJsonHandler = mock(SnsJsonHandler.class);
        private CloudFormationQueryHandler cloudFormationHandler = mock(CloudFormationQueryHandler.class);
        private Ec2Service ec2Service = mock(Ec2Service.class);
        private S3Service s3Service = mock(S3Service.class);
        private EcsService ecsService = mock(EcsService.class);
        private EcsJsonHandler ecsJsonHandler = mock(EcsJsonHandler.class);
        private EventBridgeHandler eventBridgeHandler = mock(EventBridgeHandler.class);
        private SchedulerService schedulerService = mock(SchedulerService.class);
        private SchedulerController schedulerController = mock(SchedulerController.class);
        private RdsDataService rdsDataService;
        private ObjectMapper objectMapper = new ObjectMapper();
        private JsonataEvaluator jsonataEvaluator;
        @SuppressWarnings("unchecked")
        private Instance<StepFunctionsService> sfnService = mock(Instance.class);
        private EmulatorConfig config = mock(EmulatorConfig.class);
        private Vertx vertx;
        private CustomResourceLiveness customResourceLiveness = mock(CustomResourceLiveness.class);
        private Clock clock = Clock.systemUTC();
        private AslExecutor.Sleeper sleeper = TimeUnit.NANOSECONDS::sleep;
        private Integer maxWaitSeconds;
        private RedshiftDataJsonHandler redshiftDataHandler;
        private ServiceRegistry serviceRegistry;
        private LambdaTaskAuthorizer lambdaTaskAuthorizer;
        private Duration expressMaxExecutionTime;
        private LongSupplier nanoTime;

        private Builder() {
        }

        Builder lambdaExecutor(LambdaExecutorService lambdaExecutor) {
            this.lambdaExecutor = lambdaExecutor;
            return this;
        }

        Builder functionStore(LambdaFunctionStore functionStore) {
            this.functionStore = functionStore;
            return this;
        }

        Builder targetResolver(LambdaTargetResolver targetResolver) {
            this.targetResolver = targetResolver;
            return this;
        }

        Builder dynamoDb(DynamoDbFacade dynamoDb) {
            this.dynamoDb = dynamoDb;
            return this;
        }

        Builder dynamoDbJsonHandler(DynamoDbJsonHandler dynamoDbJsonHandler) {
            this.dynamoDbJsonHandler = dynamoDbJsonHandler;
            return this;
        }

        Builder sqsJsonHandler(SqsJsonHandler sqsJsonHandler) {
            this.sqsJsonHandler = sqsJsonHandler;
            return this;
        }

        Builder snsJsonHandler(SnsJsonHandler snsJsonHandler) {
            this.snsJsonHandler = snsJsonHandler;
            return this;
        }

        Builder cloudFormationHandler(CloudFormationQueryHandler cloudFormationHandler) {
            this.cloudFormationHandler = cloudFormationHandler;
            return this;
        }

        Builder ec2Service(Ec2Service ec2Service) {
            this.ec2Service = ec2Service;
            return this;
        }

        Builder s3Service(S3Service s3Service) {
            this.s3Service = s3Service;
            return this;
        }

        Builder ecsService(EcsService ecsService) {
            this.ecsService = ecsService;
            return this;
        }

        Builder ecsJsonHandler(EcsJsonHandler ecsJsonHandler) {
            this.ecsJsonHandler = ecsJsonHandler;
            return this;
        }

        Builder eventBridgeHandler(EventBridgeHandler eventBridgeHandler) {
            this.eventBridgeHandler = eventBridgeHandler;
            return this;
        }

        Builder schedulerService(SchedulerService schedulerService) {
            this.schedulerService = schedulerService;
            return this;
        }

        Builder schedulerController(SchedulerController schedulerController) {
            this.schedulerController = schedulerController;
            return this;
        }

        Builder rdsDataService(RdsDataService rdsDataService) {
            this.rdsDataService = rdsDataService;
            return this;
        }

        Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        Builder jsonataEvaluator(JsonataEvaluator jsonataEvaluator) {
            this.jsonataEvaluator = jsonataEvaluator;
            return this;
        }

        Builder sfnService(Instance<StepFunctionsService> sfnService) {
            this.sfnService = sfnService;
            return this;
        }

        Builder config(EmulatorConfig config) {
            this.config = config;
            return this;
        }

        Builder vertx(Vertx vertx) {
            this.vertx = vertx;
            return this;
        }

        Builder customResourceLiveness(CustomResourceLiveness customResourceLiveness) {
            this.customResourceLiveness = customResourceLiveness;
            return this;
        }

        Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        Builder sleeper(AslExecutor.Sleeper sleeper) {
            this.sleeper = sleeper;
            return this;
        }

        Builder maxWaitSeconds(Integer maxWaitSeconds) {
            this.maxWaitSeconds = maxWaitSeconds;
            return this;
        }

        Builder redshiftDataHandler(RedshiftDataJsonHandler redshiftDataHandler) {
            this.redshiftDataHandler = redshiftDataHandler;
            return this;
        }

        Builder serviceRegistry(ServiceRegistry serviceRegistry) {
            this.serviceRegistry = serviceRegistry;
            return this;
        }

        Builder lambdaTaskAuthorizer(LambdaTaskAuthorizer lambdaTaskAuthorizer) {
            this.lambdaTaskAuthorizer = lambdaTaskAuthorizer;
            return this;
        }

        Builder expressMaxExecutionTime(Duration expressMaxExecutionTime) {
            this.expressMaxExecutionTime = expressMaxExecutionTime;
            return this;
        }

        Builder nanoTime(LongSupplier nanoTime) {
            this.nanoTime = nanoTime;
            return this;
        }

        AslExecutor build() {
            return new AslExecutor(lambdaExecutor,
                    targetResolver != null ? targetResolver : new LambdaTargetResolver(functionStore, null),
                    dynamoDb, dynamoDbJsonHandler, sqsJsonHandler, snsJsonHandler, cloudFormationHandler,
                    ec2Service, s3Service, ecsService, ecsJsonHandler, eventBridgeHandler,
                    schedulerService, schedulerController, rdsDataService, objectMapper,
                    jsonataEvaluator != null ? jsonataEvaluator : new JsonataEvaluator(objectMapper),
                    sfnService, config, vertx, customResourceLiveness, clock, sleeper, maxWaitSeconds,
                    redshiftDataHandler, serviceRegistry, lambdaTaskAuthorizer, expressMaxExecutionTime,
                    nanoTime);
        }
    }
}

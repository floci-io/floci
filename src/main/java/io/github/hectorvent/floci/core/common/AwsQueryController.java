package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.autoscaling.AutoScalingQueryHandler;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsQueryHandler;
import io.github.hectorvent.floci.services.cognito.CognitoJsonHandler;
import io.github.hectorvent.floci.services.docdb.DocDbQueryHandler;
import io.github.hectorvent.floci.services.docdb.DocDbService;
import io.github.hectorvent.floci.services.ec2.Ec2QueryHandler;
import io.github.hectorvent.floci.services.elasticache.ElastiCacheQueryHandler;
import io.github.hectorvent.floci.services.elasticbeanstalk.ElasticBeanstalkQueryHandler;
import io.github.hectorvent.floci.services.elb.ElbClassicQueryHandler;
import io.github.hectorvent.floci.services.elbv2.ElbV2QueryHandler;
import io.github.hectorvent.floci.services.iam.IamQueryHandler;
import io.github.hectorvent.floci.services.iam.StsQueryHandler;
import io.github.hectorvent.floci.services.neptune.NeptuneQueryHandler;
import io.github.hectorvent.floci.services.neptune.NeptuneService;
import io.github.hectorvent.floci.services.rds.RdsQueryHandler;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.redshift.RedshiftQueryHandler;
import io.github.hectorvent.floci.services.ses.SesQueryHandler;
import io.github.hectorvent.floci.services.sns.SnsQueryHandler;
import io.github.hectorvent.floci.services.sqs.SqsQueryHandler;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Generic dispatcher for all AWS services that use the Query Protocol (form-encoded POST, XML response).
 * Routes requests to the appropriate service handler based on the service name extracted from the
 * Authorization header's credential scope. When there is no such header, or its scope names no
 * service that speaks the Query protocol, the action name and the {@code Version} the request
 * declares decide instead, as {@link #inferServiceFromAction} describes.
 * <p>
 * Requests run on virtual threads, so an SQS ReceiveMessage long poll, which waits up to 20 seconds,
 * does not hold one of the shared request workers while it waits.
 *
 * @see AwsJsonController
 */
@Path("/")
public class AwsQueryController {

    static final String QUERY_GET_PATH = "/_query";

    private static final Logger LOG = Logger.getLogger(AwsQueryController.class);

    private final CloudFormationQueryHandler cloudFormationQueryHandler;
    private final ElastiCacheQueryHandler elastiCacheQueryHandler;
    private final RdsQueryHandler rdsQueryHandler;
    private final NeptuneQueryHandler neptuneQueryHandler;
    private final NeptuneService neptuneService;
    private final DocDbQueryHandler docDbQueryHandler;
    private final DocDbService docDbService;
    private final RdsService rdsService;
    /** ARNs already reported as held by both services, so the warning is logged once each. */
    private final Set<String> reportedArnCollisions = ConcurrentHashMap.newKeySet();
    private final SqsQueryHandler sqsQueryHandler;
    private final SnsQueryHandler snsQueryHandler;
    private final SesQueryHandler sesQueryHandler;
    private final IamQueryHandler iamQueryHandler;
    private final StsQueryHandler stsQueryHandler;
    private final CloudWatchMetricsQueryHandler cloudWatchMetricsQueryHandler;
    private final CognitoJsonHandler cognitoJsonHandler;
    private final Ec2QueryHandler ec2QueryHandler;
    private final ElbV2QueryHandler elbV2QueryHandler;
    private final ElbClassicQueryHandler elbClassicQueryHandler;
    private final AutoScalingQueryHandler autoScalingQueryHandler;
    private final ElasticBeanstalkQueryHandler elasticBeanstalkQueryHandler;
    private final RedshiftQueryHandler redshiftQueryHandler;
    private final AwsQueryServiceResolver serviceResolver;
    private final RegionResolver regionResolver;

    @Inject
    public AwsQueryController(CloudFormationQueryHandler cloudFormationQueryHandler,
                              ElastiCacheQueryHandler elastiCacheQueryHandler,
                              RdsQueryHandler rdsQueryHandler,
                              NeptuneQueryHandler neptuneQueryHandler,
                              NeptuneService neptuneService,
                              DocDbQueryHandler docDbQueryHandler,
                              DocDbService docDbService,
                              RdsService rdsService,
                              SqsQueryHandler sqsQueryHandler, SnsQueryHandler snsQueryHandler,
                              SesQueryHandler sesQueryHandler,
                              IamQueryHandler iamQueryHandler, StsQueryHandler stsQueryHandler,
                              CloudWatchMetricsQueryHandler cloudWatchMetricsQueryHandler,
                              CognitoJsonHandler cognitoJsonHandler,
                              Ec2QueryHandler ec2QueryHandler,
                              ElbV2QueryHandler elbV2QueryHandler,
                              ElbClassicQueryHandler elbClassicQueryHandler,
                              AutoScalingQueryHandler autoScalingQueryHandler,
                              ElasticBeanstalkQueryHandler elasticBeanstalkQueryHandler,
                              RedshiftQueryHandler redshiftQueryHandler,
                              AwsQueryServiceResolver serviceResolver,
                              RegionResolver regionResolver) {
        this.cloudFormationQueryHandler = cloudFormationQueryHandler;
        this.elastiCacheQueryHandler = elastiCacheQueryHandler;
        this.rdsQueryHandler = rdsQueryHandler;
        this.neptuneQueryHandler = neptuneQueryHandler;
        this.neptuneService = neptuneService;
        this.docDbQueryHandler = docDbQueryHandler;
        this.docDbService = docDbService;
        this.rdsService = rdsService;
        this.sqsQueryHandler = sqsQueryHandler;
        this.snsQueryHandler = snsQueryHandler;
        this.sesQueryHandler = sesQueryHandler;
        this.iamQueryHandler = iamQueryHandler;
        this.stsQueryHandler = stsQueryHandler;
        this.cloudWatchMetricsQueryHandler = cloudWatchMetricsQueryHandler;
        this.cognitoJsonHandler = cognitoJsonHandler;
        this.ec2QueryHandler = ec2QueryHandler;
        this.elbV2QueryHandler = elbV2QueryHandler;
        this.elbClassicQueryHandler = elbClassicQueryHandler;
        this.autoScalingQueryHandler = autoScalingQueryHandler;
        this.elasticBeanstalkQueryHandler = elasticBeanstalkQueryHandler;
        this.redshiftQueryHandler = redshiftQueryHandler;
        this.serviceResolver = serviceResolver;
        this.regionResolver = regionResolver;
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_XML)
    @RunOnVirtualThread
    public Response dispatch(
            @HeaderParam("Authorization") String authorization,
            @Context UriInfo uriInfo,
            MultivaluedMap<String, String> formParams) {
        return dispatch(IamEnforcementFilter.requestAuthorization(authorization, uriInfo.getQueryParameters()),
                formParams);
    }

    private Response dispatch(String authorization, MultivaluedMap<String, String> formParams) {
        String action = AwsQueryServiceResolver.action(formParams);
        if (action == null) {
            return xmlErrorResponse("MissingAction",
                    "The request must contain the parameter Action", 400);
        }

        String service = serviceResolver.resolve(authorization, action, formParams.getFirst("Version"));
        LOG.debugv("Query protocol service={0} action={1}", service, action);

        String region = regionResolver.resolveRegionFromAuth(authorization);

        try {
            return dispatchToHandler(service, action, formParams, authorization, region);
        } catch (AwsException e) {
            // Handlers that don't render their own error XML would otherwise reach
            // AwsExceptionMapper, which emits JSON — the same JSON-on-the-XML-wire defect.
            // The declared code and status must survive; only the encoding changes.
            return xmlErrorResponse(e.getErrorCode(), e.getMessage(), e.getHttpStatus(),
                    e.getHttpStatus() >= 500 ? "Receiver" : "Sender");
        } catch (Exception e) {
            // A handler bug (e.g. an NPE) must never leak Quarkus's JSON error page onto
            // the Query/XML wire — SDK parsers fail before they can surface anything useful.
            LOG.errorv(e, "Unhandled error dispatching Query action {0} for service {1}", action, service);
            return xmlErrorResponse("InternalFailure",
                    "Unexpected error: " + AwsErrorMessages.describe(e), 500, "Receiver");
        }
    }

    private Response dispatchToHandler(String service, String action,
                                       MultivaluedMap<String, String> formParams,
                                       String authorization, String region) {
        return switch (service) {
            case "sqs" -> sqsQueryHandler.handle(action, formParams, region);
            case "sns" -> snsQueryHandler.handle(action, formParams, region);
            case "iam" -> iamQueryHandler.handle(action, formParams, authorization);
            case "sts" -> stsQueryHandler.handle(action, formParams);
            case "elasticache" -> elastiCacheQueryHandler.handle(action, formParams, region);
            case "rds" -> {
                // Neptune signs requests with "rds" credential scope (same wire protocol).
                // Route to Neptune when Engine=neptune (create ops), when the cluster/instance
                // already exists in Neptune storage (describe/modify/delete ops), or when a
                // tagging ResourceName is a Neptune ARN.
                String engine = formParams.getFirst("Engine");
                String clusterId = formParams.getFirst("DBClusterIdentifier");
                String instanceId = formParams.getFirst("DBInstanceIdentifier");

                Response identifierClash = rdsAlreadyHoldsIdentifier(action, formParams, region);
                if (identifierClash != null) {
                    yield identifierClash;
                }

                if ("neptune".equalsIgnoreCase(engine)
                        || neptuneService.hasCluster(clusterId)
                        || neptuneService.hasInstance(instanceId)
                        || neptuneService.hasResourceWithArn(formParams.getFirst("ResourceName"))) {
                    yield neptuneQueryHandler.handle(action, formParams);
                }

                if ("docdb".equalsIgnoreCase(engine)
                       || docDbService.hasCluster(clusterId, region)
                       || docDbService.hasInstance(instanceId, region)
                       || namesDocDbResource(formParams.getFirst("ResourceName"), region)) {
                        yield docDbQueryHandler.handle(action, formParams);
                }
                yield rdsQueryHandler.handle(action, formParams, region);
            }
            case "neptune" -> isFamilyListing(action, formParams)
                    ? rdsQueryHandler.handle(action, formParams, region)
                    : neptuneQueryHandler.handle(action, formParams);
            case "docdb" -> {
                // The same check as on the rds scope: DocumentDB is reachable under either, and a
                // create that skipped it here would put a second record under an ARN RDS owns.
                Response clash = rdsAlreadyHoldsIdentifier(action, formParams, region);
                if (clash != null) {
                    yield clash;
                }
                // The list form answers for the whole RDS family on either endpoint; the RDS
                // handler assembles it from both stores.
                if (isFamilyListing(action, formParams)) {
                    yield rdsQueryHandler.handle(action, formParams, region);
                }
                yield docDbQueryHandler.handle(action, formParams);
            }
            case "email" -> sesQueryHandler.handle(action, formParams, region);
            case "monitoring" -> cloudWatchMetricsQueryHandler.handle(action, formParams, region);
            case "cloudformation" -> cloudFormationQueryHandler.handle(action, formParams, region);
            case "cognito-idp" -> handleCognitoQuery(action, formParams, region);
            case "ec2" -> ec2QueryHandler.handle(action, formParams, region);
            case "elasticloadbalancing" -> isElbClassicRequest(action, formParams)
                    ? elbClassicQueryHandler.handle(action, formParams, region)
                    : elbV2QueryHandler.handle(action, formParams, region);
            case "autoscaling" -> autoScalingQueryHandler.handle(action, formParams, region);
            case "elasticbeanstalk" -> elasticBeanstalkQueryHandler.handle(action, formParams, region);
            case "redshift" -> redshiftQueryHandler.handle(action, formParams, authorization);
            default -> xmlErrorResponse("UnknownService",
                    "Unknown or unsupported service: " + service, 400);
        };
    }

    /** The API version a Classic (v1) Elastic Load Balancing request declares. */
    private static final String ELB_CLASSIC_API_VERSION = "2012-06-01";

    /** The API version an ELBv2 (ALB/NLB) request declares. */
    private static final String ELB_V2_API_VERSION = "2015-12-01";

    /**
     * Whether an {@code elasticloadbalancing} request is for the Classic (2012-06-01) API.
     *
     * <p>Classic ELB and ELBv2 share an endpoint host <em>and</em> a credential scope, so the
     * service name cannot separate them — the same in-case split the {@code rds} scope already
     * needs for Neptune and DocumentDB. What separates them is the API version, which every
     * Query-protocol request carries as the {@code Version} form parameter and which both service
     * models declare. That is the discriminator used here, in preference to guessing from the
     * shape of the parameters: a client that says {@code Version=2012-06-01} has told us which API
     * it is speaking, and answering it from the other one is the defect this routing exists to fix.
     *
     * <p>The parameter shape is only a fallback, for a hand-rolled client that omitted
     * {@code Version}. Then an action unique to one API decides, and for the action names both
     * APIs define, the presence of a Classic-only parameter does: {@code LoadBalancerName} or
     * {@code LoadBalancerNames} identify a load balancer by name, which is Classic's addressing
     * model, whereas ELBv2 uses {@code LoadBalancerArn}, {@code Name} or {@code ResourceArns}.
     * With neither present the request stays with ELBv2, which is the pre-existing behaviour.
     */
    private static boolean isElbClassicRequest(String action, MultivaluedMap<String, String> formParams) {
        String version = formParams.getFirst("Version");
        if (ELB_CLASSIC_API_VERSION.equals(version)) {
            return true;
        }
        if (ELB_V2_API_VERSION.equals(version)) {
            return false;
        }
        if (ElbClassicQueryHandler.CLASSIC_ONLY_ACTIONS.contains(action)) {
            return true;
        }
        if (!ElbClassicQueryHandler.SHARED_ACTIONS.contains(action)) {
            return false;
        }
        return formParams.getFirst("LoadBalancerName") != null
                || formParams.getFirst("LoadBalancerNames.member.1") != null;
    }

    /**
     * The AlreadyExists answer when a create names an identifier RDS already holds here, or null.
     *
     * <p>One identifier space covers the whole RDS family: a live account refuses to create an
     * Aurora cluster named like an existing DocumentDB one. Answering that way is what stops two
     * services holding one identifier in one region, which no ARN could then tell apart — so the
     * check has to run on every route that reaches DocumentDB, not only the {@code rds} scope.
     *
     * <p>Only the identifier being created is checked: {@code CreateDBInstance} names its parent
     * cluster too, and that cluster existing in RDS is the normal case rather than a clash.
     */
    private Response rdsAlreadyHoldsIdentifier(String action,
                                               MultivaluedMap<String, String> formParams,
                                               String region) {
        if ("CreateDBCluster".equals(action) && rdsService.hasClusterOrInstance(
                formParams.getFirst("DBClusterIdentifier"), null, region)) {
            return xmlErrorResponse("DBClusterAlreadyExistsFault", "DB Cluster already exists", 400);
        }
        if ("CreateDBInstance".equals(action) && rdsService.hasClusterOrInstance(
                null, formParams.getFirst("DBInstanceIdentifier"), region)) {
            return xmlErrorResponse("DBInstanceAlreadyExists", "DB Instance already exists", 400);
        }
        return null;
    }

    /**
     * Whether a tagging {@code ResourceName} names a DocumentDB cluster or instance.
     *
     * <p>The tag actions carry no engine or identifier to route on — only the ARN of the resource
     * they address — so without reading it they reach the RDS handler, which does not hold
     * DocumentDB's records. The whole ARN is matched, not its trailing name: RDS and DocumentDB
     * share one ARN space, so a name both services use would otherwise be answered from the wrong
     * store, and an RDS parameter group or option group could be resolved as a cluster. Anything
     * DocumentDB does not hold under that exact ARN stays with RDS.
     *
     * <p>State persisted before creates were made to share one identifier space can still hold the
     * same identifier in both stores. DocumentDB answers it, which is what the identifier checks
     * above already do for every other action on that record — describe, modify and delete
     * included — so one identifier keeps one answer. The collision is logged once, because the
     * RDS record behind it is unreachable on this scope and only deleting one of the two fixes it.
     */
    private static boolean isFamilyListing(String action, MultivaluedMap<String, String> formParams) {
        return switch (action) {
            case "DescribeDBClusters" -> isBlank(formParams.getFirst("DBClusterIdentifier"));
            case "DescribeDBInstances" -> isBlank(formParams.getFirst("DBInstanceIdentifier"));
            default -> false;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean namesDocDbResource(String resourceName, String region) {
        if (!docDbService.hasResourceWithArn(resourceName)) {
            return false;
        }
        String identifier = resourceName.substring(resourceName.lastIndexOf(':') + 1);
        if (rdsService.hasClusterOrInstance(identifier, identifier, region)
                && reportedArnCollisions.add(resourceName)) {
            LOG.warnv("RDS and DocumentDB both hold {0}; DocumentDB answers for it, as it does for "
                    + "every other action on that identifier. Delete one of the two records.",
                    resourceName);
        }
        return true;
    }

    private Response handleCognitoQuery(String action, MultivaluedMap<String, String> formParams, String region) {
        // Cognito is primarily JSON 1.1, but we provide a bridge for Query protocol if hit.
        // Convert MultivaluedMap to JsonNode if needed, but for now just return UnsupportedOperation 
        // with Cognito namespace.
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", "Sender")
                    .elem("Code", "UnsupportedOperation")
                    .elem("Message", "Operation " + action + " is not supported by Cognito via Query protocol.")
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(400).entity(xml).type(MediaType.APPLICATION_XML).build();
    }

    private static final String CLOUDWATCH_API_VERSION = "2010-08-01";

    private static final Set<String> COGNITO_ACTIONS = Set.of(
            "AdminCreateUser", "AdminGetUser", "AdminDeleteUser", "AdminSetUserPassword",
            "AdminUpdateUserAttributes", "AdminUserGlobalSignOut", "ListUsers",
            "InitiateAuth", "AdminInitiateAuth", "RespondToAuthChallenge", "AdminRespondToAuthChallenge",
            "SignUp", "ConfirmSignUp", "ChangePassword", "ForgotPassword",
            "ConfirmForgotPassword", "GetUser", "UpdateUserAttributes",
            "CreateUserPool", "DescribeUserPool", "ListUserPools", "UpdateUserPool", "DeleteUserPool",
            "TagResource", "UntagResource", "ListTagsForResource",
            "CreateUserPoolClient", "DescribeUserPoolClient", "ListUserPoolClients", "DeleteUserPoolClient",
            "CreateGroup", "GetGroup", "ListGroups", "DeleteGroup",
            "AdminAddUserToGroup", "AdminRemoveUserFromGroup", "AdminListGroupsForUser"
    );

    @GET
    @Path(QUERY_GET_PATH)
    @Produces({MediaType.APPLICATION_XML, MediaType.TEXT_HTML})
    @RunOnVirtualThread
    public Response dispatchQueryGet(@HeaderParam("Authorization") String authorization,
                                               @Context UriInfo uriInfo,
                                               @Context ContainerRequestContext request) {
        // Only the pre-matching Query claim may select this internal route.
        if (!(request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY) instanceof ProtocolClaim claim)
                || claim.protocol() != WireProtocol.AWS_QUERY) {
            return Response.status(404).build();
        }
        MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
        String action = AwsQueryServiceResolver.action(queryParams);
        String auth = IamEnforcementFilter.requestAuthorization(authorization, queryParams);
        String service = serviceResolver.resolve(auth, action, queryParams.getFirst("Version"));
        if (!"monitoring".equals(service)) {
            return dispatch(auth, queryParams);
        }
        if (action == null) {
            return Response.status(404).type(MediaType.APPLICATION_XML)
                    .entity("<UnknownOperationException/>").build();
        }
        String version = queryParams.getFirst("Version");
        version = version == null ? "2009-05-15" : version;
        Set<String> actions = switch (version) {
            case "2009-05-15" -> Set.of("ListMetrics", "GetMetricStatistics");
            case CLOUDWATCH_API_VERSION -> QueryServiceModels.of("monitoring").operations();
            default -> Set.of();
        };
        if (!actions.contains(action)) {
            return xmlErrorResponse("InvalidAction", "Could not find operation " + action + " for version "
                    + version, 404);
        }
        return dispatch(IamEnforcementFilter.requestAuthorization(authorization, queryParams), queryParams);
    }

    /** A Query service, the API versions its clients declare, and the action names it serves. */
    private record QueryService(String name, Set<String> apiVersions, Set<String> actions) {

        /**
         * A service as its botocore models define it (see {@link QueryServiceModels}), plus any
         * action Floci serves that no model defines.
         */
        static QueryService modeled(String name, String... floci) {
            QueryServiceModels.ServiceModel model = QueryServiceModels.of(name);
            return new QueryService(name, model.apiVersions(),
                    Stream.concat(model.operations().stream(), Stream.of(floci))
                            .collect(Collectors.toUnmodifiableSet()));
        }
    }

    /**
     * Every Query service, in the order they are tried when the action name alone has to decide:
     * where two services define the same name, the earlier one takes it, unless
     * {@link #UNVERSIONED_OWNERS} names another.
     *
     * <p>Each service's versions and actions are those of its botocore models, so a name a service
     * defines reaches it whether Floci implements it or not. SES and Elastic Beanstalk publish the
     * same version and share no action name, so the action settles which of the two a request is
     * for. Both Elastic Load Balancing APIs lead to one service here and {@link #isElbClassicRequest}
     * splits them on the same field, and DocumentDB and Neptune publish RDS's version and are split
     * from it in the {@code rds} case. {@code ValidateIamAuthToken} is Floci's own ElastiCache
     * action. Cognito's model is JSON only, so it has no Query model and no Query client declares a
     * version for it; its names are listed by hand. SQS is tried last, so by name alone it takes only
     * what no other service defines, and its {@code Version} wins back the two names SNS also
     * defines.
     */
    private static final List<QueryService> QUERY_SERVICES = List.of(
            QueryService.modeled("sts"),
            QueryService.modeled("iam"),
            QueryService.modeled("sns"),
            QueryService.modeled("elasticache", "ValidateIamAuthToken"),
            QueryService.modeled("rds"),
            QueryService.modeled("monitoring"),
            QueryService.modeled("cloudformation"),
            QueryService.modeled("email"),
            new QueryService("cognito-idp", Set.of(), COGNITO_ACTIONS),
            QueryService.modeled("ec2"),
            QueryService.modeled("elasticloadbalancing"),
            QueryService.modeled("autoscaling"),
            QueryService.modeled("elasticbeanstalk"),
            QueryService.modeled("redshift"),
            QueryService.modeled("sqs"));

    /**
     * Where a name several services define goes when the request's {@code Version} does not decide:
     * the service that serves it here, where the order would pick an earlier one that does not.
     * {@code AwsQueryActionRoutingParityTest} fails when a name some service serves reaches one that
     * does not.
     */
    static final Map<String, String> UNVERSIONED_OWNERS = Map.ofEntries(
            Map.entry("AddTagsToResource", "rds"),
            Map.entry("RemoveTagsFromResource", "rds"),
            Map.entry("DescribeEngineDefaultParameters", "rds"),
            Map.entry("DescribeEvents", "rds"),
            Map.entry("DescribeEventCategories", "redshift"),
            Map.entry("DescribeAccountAttributes", "ec2"),
            Map.entry("DescribeSnapshots", "ec2"),
            Map.entry("DescribeAccountLimits", "elasticloadbalancing"),
            Map.entry("CreateIntegration", "redshift"),
            Map.entry("DeleteIntegration", "redshift"),
            Map.entry("DescribeIntegrations", "redshift"));

    /**
     * The service a Query request goes to when its credential scope does not name one.
     *
     * <p>The action name alone is ambiguous wherever two services define it, and then the earlier
     * one in {@link #QUERY_SERVICES} wins, or the one {@link #UNVERSIONED_OWNERS} names. The
     * {@code Version} every Query client sends is what
     * tells them apart, as {@link #isElbClassicRequest} already uses it for the two Elastic Load
     * Balancing APIs: {@code CreateUser} under IAM's version is IAM's, and under ElastiCache's it
     * is ElastiCache's.
     *
     * <p>{@code Version} only chooses among the services that define the name. One that names a
     * service without it, or none at all, leaves the name to decide, as it does for a hand-rolled
     * request that omits the field.
     */
    static String inferServiceFromAction(String action, String version) {
        String byName = null;
        for (QueryService service : QUERY_SERVICES) {
            if (!service.actions().contains(action)) {
                continue;
            }
            if (version != null && service.apiVersions().contains(version)) {
                return service.name();
            }
            if (byName == null) {
                byName = service.name();
            }
        }
        if (byName != null && UNVERSIONED_OWNERS.containsKey(action)) {
            return UNVERSIONED_OWNERS.get(action);
        }
        // A name no service defines goes to SQS, which is how a raw SQS request without an
        // Authorization header gets there.
        return byName != null ? byName : "sqs";
    }

    private Response xmlErrorResponse(String code, String message, int status) {
        return xmlErrorResponse(code, message, status, "Sender");
    }

    private Response xmlErrorResponse(String code, String message, int status, String type) {
        String xml = new XmlBuilder()
                .start("ErrorResponse")
                  .start("Error")
                    .elem("Type", type)
                    .elem("Code", code)
                    .elem("Message", message)
                  .end("Error")
                  .elem("RequestId", UUID.randomUUID().toString())
                .end("ErrorResponse")
                .build();
        return Response.status(status).entity(xml).type(MediaType.APPLICATION_XML).build();
    }
}

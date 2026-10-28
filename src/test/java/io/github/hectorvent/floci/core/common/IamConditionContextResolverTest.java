package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbFacade;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.dynamodb.backend.NativeDynamoDbBackend;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private static final Instant LOCK_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService dynamoDbService;
    private Instance<DynamoDbFacade> dynamoDbFacadeInstance;
    private Ec2Service ec2Service;
    private S3Service s3Service;
    private CloudWatchLogsService logsService;
    private RequestContext requestContext;
    private EmulatorConfig config;
    private IamConditionContextResolver resolver;
    private ResolvedServiceCatalog serviceCatalog;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        dynamoDbService = mock(DynamoDbService.class);
        NativeDynamoDbBackend dynamoDbBackend = new NativeDynamoDbBackend(null, null, dynamoDbService, null, null, mapper);
        dynamoDbFacadeInstance = mock(Instance.class);
        when(dynamoDbFacadeInstance.isResolvable()).thenReturn(true);
        when(dynamoDbFacadeInstance.get()).thenReturn(new DynamoDbFacade(dynamoDbBackend, dynamoDbBackend,
                new RegionResolver("us-east-1", "000000000000")));
        ec2Service = mock(Ec2Service.class);
        Instance<Ec2Service> ec2ServiceInstance = mock(Instance.class);
        when(ec2ServiceInstance.isResolvable()).thenReturn(true);
        when(ec2ServiceInstance.get()).thenReturn(ec2Service);
        s3Service = mock(S3Service.class);
        Instance<S3Service> s3ServiceInstance = mock(Instance.class);
        when(s3ServiceInstance.isResolvable()).thenReturn(true);
        when(s3ServiceInstance.get()).thenReturn(s3Service);
        logsService = mock(CloudWatchLogsService.class);
        Instance<CloudWatchLogsService> logsServiceInstance = mock(Instance.class);
        when(logsServiceInstance.isResolvable()).thenReturn(true);
        when(logsServiceInstance.get()).thenReturn(logsService);
        requestContext = new RequestContext();
        requestContext.setRegion("us-east-1");
        config = mock(EmulatorConfig.class);
        when(config.defaultRegion()).thenReturn("us-east-1");
        serviceCatalog = mock(ResolvedServiceCatalog.class);
        ServiceDescriptor dynamo = new ServiceDescriptor("dynamodb", "dynamodb", true, true,
                null, null, 0L, null, ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON),
                Set.of("DynamoDB_20111205."), Set.of("dynamodb"), Set.of(), Set.of(),
                Set.of("DynamoDB_20111205."));
        ServiceCatalog catalog = new ServiceCatalog(List.of(dynamo));
        when(serviceCatalog.matchTarget(any())).thenAnswer(call -> catalog.matchTarget(call.getArgument(0)));
        resolver = new IamConditionContextResolver(
                dynamoDbFacadeInstance, ec2ServiceInstance, s3ServiceInstance, logsServiceInstance, requestContext, config,
                null, serviceCatalog);
    }

    @Test
    void logStreamTagsUseRawParentNameAndRequestRegion() throws Exception {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty("floci.bufferedJsonBody"))
                .thenReturn(mapper.readTree("{\"logGroupName\":\" group \",\"logStreamName\":42}"));
        when(logsService.listTagsLogGroup(" group ", "eu-west-1")).thenReturn(Map.of("team", "blue"));
        requestContext.setRegion("eu-west-1");
        for (String action : List.of("logs:CreateLogStream", "logs:DeleteLogStream")) {
            assertEquals(Map.of("aws:ResourceTag/team", List.of("blue")), resolver.resolve("logs", action, request));
        }
        requestContext.setRegion(null);
        when(logsService.listTagsLogGroup(" group ", "us-east-1")).thenReturn(Map.of("team", "red"));
        assertEquals(Map.of("aws:ResourceTag/team", List.of("red")),
                resolver.resolve("logs", "logs:CreateLogStream", request));
    }

    @Test
    void missingLogGroupTagsDoNotReplaceTheHandlersMissingResourceError() throws Exception {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty("floci.bufferedJsonBody")).thenReturn(mapper.readTree("{\"logGroupName\":42}"));
        when(logsService.listTagsLogGroup("42", "us-east-1"))
                .thenThrow(new AwsException("ResourceNotFoundException", "missing", 400));
        assertNull(resolver.resolve("logs", "logs:DeleteLogStream", request));
    }

    @Test
    void unexpectedLogGroupLookupFailuresCannotEraseTagDenies() throws Exception {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty("floci.bufferedJsonBody")).thenReturn(mapper.readTree("{\"logGroupName\":\"group\"}"));
        AwsException failure = new AwsException("InternalFailure", "storage unavailable", 500);
        when(logsService.listTagsLogGroup("group", "us-east-1")).thenThrow(failure);
        assertSame(failure, assertThrows(AwsException.class,
                () -> resolver.resolve("logs", "logs:CreateLogStream", request)));
        IllegalStateException unexpected = new IllegalStateException("unavailable");
        doThrow(unexpected).when(logsService).listTagsLogGroup("group", "us-east-1");
        assertSame(unexpected, assertThrows(IllegalStateException.class,
                () -> resolver.resolve("logs", "logs:DeleteLogStream", request)));
    }

    /** A form request whose body can be read again, as a real request's restored stream can. */
    private static ContainerRequestContext formRequest(String body) {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getMediaType())
                .thenReturn(MediaType.valueOf("application/x-www-form-urlencoded"));
        when(containerRequest.getEntityStream())
                .thenAnswer(invocation -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return containerRequest;
    }

    private static ContainerRequestContext s3Request(String path, String body) {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(containerRequest.getEntityStream())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return containerRequest;
    }

    private TableDefinition fgacTable() {
        TableDefinition table = new TableDefinition();
        table.setTableName("FgacTable");
        table.setKeySchema(List.of(new KeySchemaElement("PK", "HASH")));
        return table;
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void iamPolicyContextKeepsEmptyValuesAndOmitsMissingValues() {
        assertNull(resolver.resolve("iam", "iam:AttachRolePolicy", formRequest("RoleName=role")));
        assertEquals(Map.of("iam:PolicyARN", List.of("")),
                resolver.resolve("iam", "iam:AttachRolePolicy", formRequest("PolicyArn=")));
        assertEquals(Map.of("iam:PolicyARN", List.of("")),
                resolver.resolve("iam", "iam:AttachRolePolicy", formRequest("PolicyArn")));
    }

    @Test
    void iamPolicyContextIsNotLeakedToOtherActions() {
        for (String action : List.of("iam:GetPolicy", "iam:DeletePolicy", "iam:PutRolePolicy",
                "iam:PutRolePermissionsBoundary")) {
            assertNull(resolver.resolve("iam", action, formRequest("PolicyArn=example")));
        }
    }

    @Test
    void iamPolicyContextDecodesOnceAndPreservesCase() {
        assertEquals(Map.of("iam:PolicyARN", List.of("arn:aws:iam::123456789012:policy/Team/A+B%2F")),
                resolver.resolve("iam", "iam:AttachGroupPolicy",
                        formRequest("PolicyArn=arn%3Aaws%3Aiam%3A%3A123456789012%3Apolicy%2FTeam%2FA%2BB%252F")));
    }

    @Test
    void iamPermissionsBoundaryComesFromTheBoundaryParameterOfEachActionThatTakesIt() {
        for (String action : List.of("iam:CreateRole", "iam:CreateUser", "iam:PutRolePermissionsBoundary",
                "iam:PutUserPermissionsBoundary")) {
            assertEquals(Map.of("iam:PermissionsBoundary", List.of("arn:aws:iam::123456789012:policy/Boundary")),
                    resolver.resolve("iam", action,
                            formRequest("PermissionsBoundary=arn%3Aaws%3Aiam%3A%3A123456789012%3Apolicy%2FBoundary")),
                    action);
            assertNull(resolver.resolve("iam", action, formRequest("RoleName=r&UserName=u")), action);
        }
        assertNull(resolver.resolve("iam", "iam:GetRole", formRequest("PermissionsBoundary=example")));
    }

    @Test
    void iamAwsServiceNameComesFromCreateServiceLinkedRole() {
        assertEquals(Map.of("iam:AWSServiceName", List.of("replication.ecr.amazonaws.com")),
                resolver.resolve("iam", "iam:CreateServiceLinkedRole",
                        formRequest("AWSServiceName=replication.ecr.amazonaws.com")));
        assertNull(resolver.resolve("iam", "iam:CreateServiceLinkedRole", formRequest("Description=d")));
        assertNull(resolver.resolve("iam", "iam:DeleteServiceLinkedRole",
                formRequest("AWSServiceName=replication.ecr.amazonaws.com")));
    }

    @Test
    void iamNotificationChannelComesFromCreateDelegationRequest() {
        assertEquals(Map.of("iam:NotificationChannel", List.of("arn:aws:sns:us-east-1:123456789012:t")),
                resolver.resolve("iam", "iam:CreateDelegationRequest",
                        formRequest("NotificationChannel=arn%3Aaws%3Asns%3Aus-east-1%3A123456789012%3At")));
        assertNull(resolver.resolve("iam", "iam:CreateDelegationRequest", formRequest("Description=d")));
    }

    @Test
    void iamOrganizationsPolicyIdComesFromGenerateOrganizationsAccessReport() {
        assertEquals(Map.of("iam:OrganizationsPolicyId", List.of("p-12345678")),
                resolver.resolve("iam", "iam:GenerateOrganizationsAccessReport",
                        formRequest("EntityPath=o-a%2Fr-b&OrganizationsPolicyId=p-12345678")));
        assertNull(resolver.resolve("iam", "iam:GenerateOrganizationsAccessReport",
                formRequest("EntityPath=o-a%2Fr-b")));
    }

    @Test
    void aPermissionsBoundaryConditionAllowsOnlyACreateRoleThatSetsThatBoundary() {
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"iam:CreateRole",
                  "Resource":"*","Condition":{"StringEquals":
                  {"iam:PermissionsBoundary":"arn:aws:iam::123456789012:policy/Boundary"}}}]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy), "iam:CreateRole", "*",
                resolver.resolve("iam", "iam:CreateRole", formRequest(
                        "RoleName=r&PermissionsBoundary=arn%3Aaws%3Aiam%3A%3A123456789012%3Apolicy%2FBoundary"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy), "iam:CreateRole", "*",
                resolver.resolve("iam", "iam:CreateRole", formRequest(
                        "RoleName=r&PermissionsBoundary=arn%3Aaws%3Aiam%3A%3A123456789012%3Apolicy%2FOther"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy), "iam:CreateRole", "*",
                resolver.resolve("iam", "iam:CreateRole", formRequest("RoleName=r"))));
    }

    @Test
    void queryGetConditionsUseTheFirstDecodedUrlValue() {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("Action", "CreateTags");
        query.add("ResourceId.1", "i-target");
        query.add("Tag.1.Key", "Team");
        query.add("Tag.1.Value", "A+B%2F");
        query.add("Tag.1.Value", "ignored");
        when(ec2Service.resourceTags("us-east-1", "i-target")).thenReturn(List.of(new Tag("Owner", "alice")));
        Map<String, List<String>> conditions = resolver.resolve(
                "ec2", "ec2:CreateTags", queryRequest("ec2", query));
        assertEquals(List.of("A+B%2F"), conditions.get("aws:RequestTag/Team"));
        assertEquals(List.of("Team"), conditions.get("aws:TagKeys"));
        assertEquals(List.of("alice"), conditions.get("aws:ResourceTag/Owner"));

        query.clear();
        query.add("Action", "AttachRolePolicy");
        query.add("PolicyArn", "arn:aws:iam::123456789012:policy/Team/A+B%2F");
        assertEquals(Map.of("iam:PolicyARN", List.of("arn:aws:iam::123456789012:policy/Team/A+B%2F")),
                resolver.resolve("iam", "iam:AttachRolePolicy", queryRequest("iam", query)));
    }

    private static ContainerRequestContext queryRequest(String service, MultivaluedMap<String, String> query) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeaderString("Authorization")).thenReturn(
                "AWS4-HMAC-SHA256 Credential=test/20261014/us-east-1/" + service + "/aws4_request");
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("/");
        when(uri.getQueryParameters()).thenReturn(query);
        when(request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY))
                .thenReturn(new ProtocolClaim(WireProtocol.AWS_QUERY, null, query.getFirst("Action"), null));
        return request;
    }

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, List<String>> conditions =
                resolver.resolve("s3", "s3:ListBucket", containerRequest);

        assertEquals(List.of("my_namespace/table/"), conditions.get("s3:prefix"));
        assertEquals(List.of("/"), conditions.get("s3:delimiter"));
        assertEquals(List.of("100"), conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void resolveReturnsNullForUnsupportedServiceOrAction() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest));
        // s3:DeleteObject is unsupported on purpose: real AWS gives it no object-tag keys, so a
        // delete conditioned on s3:ExistingObjectTag is denied.
        assertNull(resolver.resolve("s3", "s3:DeleteObject", containerRequest));
        // A supported object action with nothing to read tags from offers no keys either.
        assertNull(resolver.resolve("s3", "s3:GetObject", containerRequest));
    }

    @Test
    void objectTagKeysDecodeHeadersAndRetainCaseWithoutDuplicates() {
        ContainerRequestContext request = s3Request("/bucket/object", "");
        when(request.getHeaderString("x-amz-tagging"))
                .thenReturn("%54eam=one&Team=two&team=three&Cost%20Center=four&bad=%zz");
        Map<String, List<String>> conditions = resolver.resolve("s3", "s3:PutObject", request);
        assertEquals(List.of("Team", "team", "Cost Center"), conditions.get("s3:RequestObjectTagKeys"));
        assertEquals(List.of("two"), conditions.get("s3:RequestObjectTag/Team"));
        assertFalse(conditions.containsKey("s3:RequestObjectTag/bad"));
    }

    @Test
    void queryObjectTagKeysUseHeaderPrecedenceIncludingAnEmptyHeader() {
        ContainerRequestContext request = s3Request("/bucket/object", "");
        request.getUriInfo().getQueryParameters().putSingle("x-amz-tagging", "%54eam=payments");
        Map<String, List<String>> query = resolver.resolve("s3", "s3:PutObject", request);
        assertEquals(List.of("Team"), query.get("s3:RequestObjectTagKeys"));
        assertEquals(List.of("payments"), query.get("s3:RequestObjectTag/Team"));
        when(request.getHeaderString("x-amz-tagging")).thenReturn("Other=value");
        assertEquals(List.of("Other"), resolver.resolve("s3", "s3:PutObject", request)
                .get("s3:RequestObjectTagKeys"));
        when(request.getHeaderString("x-amz-tagging")).thenReturn("");
        assertNull(resolver.resolve("s3", "s3:PutObject", request));
    }

    @Test
    void objectTagKeysComeOnlyFromTheRequestedXmlTags() {
        ContainerRequestContext request = s3Request("/bucket/object",
                "<Tagging><TagSet><Tag><Key>Team</Key><Value>payments</Value></Tag>"
                        + "<Tag><Key>Cost&amp;Center</Key><Value>42</Value></Tag></TagSet></Tagging>");
        when(request.getUriInfo().getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(s3Service.getObjectTagging("bucket", "object", null)).thenReturn(Map.of("Previous", "old"));
        Map<String, List<String>> conditions = resolver.resolve("s3", "s3:PutObjectTagging", request);
        assertEquals(Set.of("Team", "Cost&Center"), new HashSet<>(conditions.get("s3:RequestObjectTagKeys")));
        assertEquals(List.of("old"), conditions.get("s3:ExistingObjectTag/Previous"));
        assertEquals(List.of("42"), conditions.get("s3:RequestObjectTag/Cost&Center"));
    }

    @Test
    void emptyObjectTagRequestsDoNotExposeRequestedKeys() {
        assertNull(resolver.resolve("s3", "s3:PutObject", s3Request("/bucket/object", "")));
        ContainerRequestContext empty = s3Request("/bucket/object", "<Tagging><TagSet/></Tagging>");
        when(empty.getUriInfo().getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(s3Service.getObjectTagging("bucket", "object", null)).thenReturn(Map.of("Previous", "old"));
        Map<String, List<String>> conditions = resolver.resolve("s3", "s3:PutObjectTagging", empty);
        assertEquals(List.of("old"), conditions.get("s3:ExistingObjectTag/Previous"));
        assertFalse(conditions.containsKey("s3:RequestObjectTagKeys"));
    }

    @Test
    void versionActionsCarryTheVersionTheyName() {
        ContainerRequestContext request = s3Request("/bucket/object", "");
        request.getUriInfo().getQueryParameters().putSingle("versionId", "v1");
        when(s3Service.getObjectTagging("bucket", "object", "v1")).thenReturn(Map.of("env", "a"));
        Map<String, List<String>> read = resolver.resolve("s3", "s3:GetObjectVersion", request);
        assertEquals(List.of("v1"), read.get("s3:versionid"));
        assertEquals(List.of("a"), read.get("s3:ExistingObjectTag/env"));
        // DeleteObjectVersion carries the id but, as DeleteObject, not the object's tags.
        assertEquals(Map.of("s3:versionid", List.of("v1")),
                resolver.resolve("s3", "s3:DeleteObjectVersion", request));
        // A base action never carries it.
        assertFalse(resolver.resolve("s3", "s3:GetObject", request).containsKey("s3:versionid"));
    }

    @Test
    void versionTaggingActionsCarryTheVersionAndItsTags() {
        ContainerRequestContext request = s3Request("/bucket/object",
                "<Tagging><TagSet><Tag><Key>Team</Key><Value>payments</Value></Tag></TagSet></Tagging>");
        request.getUriInfo().getQueryParameters().putSingle("versionId", "v1");
        when(s3Service.getObjectTagging("bucket", "object", "v1")).thenReturn(Map.of("env", "a"));
        for (String action : List.of("s3:GetObjectVersionTagging", "s3:DeleteObjectVersionTagging")) {
            Map<String, List<String>> conditions = resolver.resolve("s3", action, request);
            assertEquals(List.of("v1"), conditions.get("s3:versionid"), action);
            assertEquals(List.of("a"), conditions.get("s3:ExistingObjectTag/env"), action);
        }
        Map<String, List<String>> put = resolver.resolve("s3", "s3:PutObjectVersionTagging", request);
        assertEquals(List.of("v1"), put.get("s3:versionid"));
        assertEquals(List.of("a"), put.get("s3:ExistingObjectTag/env"));
        assertEquals(List.of("payments"), put.get("s3:RequestObjectTag/Team"));
    }

    @Test
    void annotationActionsCarryTheObjectsTags() {
        when(s3Service.getObjectTagging("bucket", "object", null)).thenReturn(Map.of("env", "a"));
        for (String action : List.of("s3:GetObjectAnnotation", "s3:ListObjectAnnotations",
                "s3:PutObjectAnnotation", "s3:DeleteObjectAnnotation")) {
            assertEquals(List.of("a"), resolver.resolve("s3", action, s3Request("/bucket/object", ""))
                    .get("s3:ExistingObjectTag/env"), action);
        }
    }

    @Test
    void objectLockKeysComeFromTheHeadersOfTheObjectCreated() {
        ContainerRequestContext request = s3Request("/bucket/object", "");
        when(request.getHeaderString("x-amz-object-lock-mode")).thenReturn("GOVERNANCE");
        when(request.getHeaderString("x-amz-object-lock-retain-until-date")).thenReturn("2026-01-11T00:00:00Z");
        when(request.getHeaderString("x-amz-object-lock-legal-hold")).thenReturn("ON");
        Map<String, List<String>> conditions = resolverAt(LOCK_NOW).resolve("s3", "s3:PutObject", request);
        assertEquals(List.of("GOVERNANCE"), conditions.get("s3:object-lock-mode"));
        assertEquals(List.of("2026-01-11T00:00:00Z"), conditions.get("s3:object-lock-retain-until-date"));
        assertEquals(List.of("10"), conditions.get("s3:object-lock-remaining-retention-days"));
        assertEquals(List.of("ON"), conditions.get("s3:object-lock-legal-hold"));
    }

    @Test
    void remainingRetentionDaysCountAPartDayAsAWholeOne() {
        assertEquals(List.of("10"), remainingRetentionDays("2026-01-11T00:00:00Z"));
        assertEquals(List.of("11"), remainingRetentionDays("2026-01-11T00:00:01Z"));
        assertEquals(List.of("1"), remainingRetentionDays("2026-01-01T00:00:01Z"));
        assertEquals(List.of("0"), remainingRetentionDays("2025-12-31T00:00:00Z"));
        assertNull(remainingRetentionDays("not-a-date"), "a date S3 would not parse offers no count");
    }

    @Test
    void legalHoldStatusComesFromTheBody() {
        ContainerRequestContext request = s3Request("/bucket/object", "<LegalHold><Status>OFF</Status></LegalHold>");
        assertEquals(Map.of("s3:object-lock-legal-hold", List.of("OFF")),
                resolver.resolve("s3", "s3:PutObjectLegalHold", request));
    }

    private List<String> remainingRetentionDays(String until) {
        ContainerRequestContext request = s3Request("/bucket/object",
                "<Retention><Mode>GOVERNANCE</Mode><RetainUntilDate>" + until + "</RetainUntilDate></Retention>");
        return resolverAt(LOCK_NOW).resolve("s3", "s3:PutObjectRetention", request)
                .get("s3:object-lock-remaining-retention-days");
    }

    @SuppressWarnings("unchecked")
    private IamConditionContextResolver resolverAt(Instant now) {
        return new IamConditionContextResolver(mock(Instance.class), mock(Instance.class), mock(Instance.class),
                mock(Instance.class), requestContext, config, null, serviceCatalog, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void resolvesDynamoDbLeadingKeysForGetItem() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
        assertEquals(List.of("ALL_ATTRIBUTES"), conditions.get("dynamodb:Select"));
    }

    @Test
    void omitsLeadingKeysWhenTheTableIsUnknown() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"MissingTable","Key":{"PK":{"S":"USER_alice"}}}"""));
        when(dynamoDbService.findTable(eq("MissingTable"), anyString()))
                .thenReturn(Optional.empty());

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest);

        assertFalse(conditions.containsKey("dynamodb:LeadingKeys"));
        // Attribute names do not depend on the key schema, so they are still populated.
        assertEquals(List.of("PK"), conditions.get("dynamodb:Attributes"));
    }

    @Test
    void carriesSelectForQuery() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable",
                 "KeyConditionExpression":"PK = :v",
                 "ExpressionAttributeValues":{":v":{"S":"USER_alice"}},
                 "Select":"COUNT"}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:Query", containerRequest);

        assertEquals(List.of("USER_alice"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("COUNT"), conditions.get("dynamodb:Select"));
    }

    @Test
    void transactionGetContextUsesOnlySupportedMembersWithoutChangingTheRequest() {
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));
        for (String extra : List.of("\"AttributesToGet\":[\"ignored\"]", "\"Select\":\"SPECIFIC_ATTRIBUTES\"",
                "\"IndexName\":\"ignored\"")) {
            ContainerRequestContext request = mock(ContainerRequestContext.class);
            JsonNode body = json("{\"TransactItems\":[{\"Get\":{\"TableName\":\"FgacTable\","
                    + "\"Key\":{\"PK\":{\"S\":\"USER_alice\"}}," + extra + "}}]}");
            JsonNode original = body.deepCopy();
            when(request.getProperty("floci.bufferedJsonBody")).thenReturn(body);
            Map<String, List<String>> context = resolver.dynamoDbTransactionItems("dynamodb:TransactGetItems",
                    request, "us-east-1", "000000000000").getFirst().context();
            assertEquals(List.of("ALL_ATTRIBUTES"), context.get("dynamodb:Select"));
            assertEquals(List.of("PK"), context.get("dynamodb:Attributes"));
            assertEquals(List.of("USER_alice"), context.get("dynamodb:LeadingKeys"));
            assertEquals(List.of("TransactGetItems"), context.get("dynamodb:EnclosingOperation"));
            assertEquals(original, body);
        }
    }

    @Test
    void singleItemWritesUseTheirReturnValuesAndExecutionDefault() {
        for (String action : List.of("PutItem", "UpdateItem", "DeleteItem")) {
            for (String value : List.of("NONE", "ALL_OLD", "UPDATED_OLD", "ALL_NEW", "UPDATED_NEW", "")) {
                ContainerRequestContext request = mock(ContainerRequestContext.class);
                when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json(
                        "{\"ReturnValues\":\"" + value + "\"}"));
                assertEquals(List.of(value), resolver.resolve("dynamodb", "dynamodb:" + action, request)
                        .get("dynamodb:ReturnValues"));
            }
            for (String body : List.of("{}", "{\"ReturnValues\":null}")) {
                ContainerRequestContext request = mock(ContainerRequestContext.class);
                when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json(body));
                assertEquals(List.of("NONE"), resolver.resolve("dynamodb", "dynamodb:" + action, request)
                        .get("dynamodb:ReturnValues"));
            }
        }
    }

    @Test
    void unrelatedActionsDoNotAcquireSpoofedReturnValues() {
        for (String action : List.of("GetItem", "Query", "Scan", "BatchGetItem", "BatchWriteItem",
                "TransactGetItems", "TransactWriteItems")) {
            ContainerRequestContext request = mock(ContainerRequestContext.class);
            when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json("{\"ReturnValues\":\"ALL_OLD\"}"));
            Map<String, List<String>> context = resolver.resolve("dynamodb", "dynamodb:" + action, request);
            assertFalse(context != null && context.containsKey("dynamodb:ReturnValues"));
        }
    }

    @Test
    void requestedReturnModesUseSeparateCompleteContextsWithoutNoneOrDuplicates() {
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));
        Map<String, List<String>> cases = Map.of(
                "", List.of("NONE"),
                ",\"ReturnValues\":null,\"ReturnValuesOnConditionCheckFailure\":null", List.of("NONE"),
                ",\"ReturnValues\":\"NONE\",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"", List.of("ALL_OLD"),
                ",\"ReturnValues\":\"ALL_OLD\",\"ReturnValuesOnConditionCheckFailure\":\"NONE\"", List.of("ALL_OLD"),
                ",\"ReturnValues\":\"ALL_OLD\",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"", List.of("ALL_OLD"),
                ",\"ReturnValues\":\"UPDATED_NEW\",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\"",
                List.of("UPDATED_NEW", "ALL_OLD"));
        for (Map.Entry<String, List<String>> entry : cases.entrySet()) {
            ContainerRequestContext request = mock(ContainerRequestContext.class);
            when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json(
                    "{\"TableName\":\"FgacTable\",\"Key\":{\"PK\":{\"S\":\"USER_alice\"}}"
                            + entry.getKey() + "}"));
            Map<String, List<String>> primary = resolver.resolve("dynamodb", "dynamodb:UpdateItem", request);
            List<Map<String, List<String>>> remaining =
                    resolver.resolveRemainingTargets("dynamodb", "dynamodb:UpdateItem", request);
            assertEquals(List.of(entry.getValue().getFirst()), primary.get("dynamodb:ReturnValues"));
            assertEquals(entry.getValue().size() - 1, remaining.size());
            assertEquals(List.of("USER_alice"), primary.get("dynamodb:LeadingKeys"));
            for (int i = 0; i < remaining.size(); i++) {
                assertEquals(List.of(entry.getValue().get(i + 1)), remaining.get(i).get("dynamodb:ReturnValues"));
                assertEquals(primary.get("dynamodb:LeadingKeys"), remaining.get(i).get("dynamodb:LeadingKeys"));
                assertEquals(primary.get("dynamodb:Attributes"), remaining.get(i).get("dynamodb:Attributes"));
            }
        }
    }

    @Test
    void legacyQueryConditionsRequireTheLegacyJsonTarget() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"TableName":"FgacTable","HashKeyValue":{"S":"USER_bob"},
                 "RangeKeyCondition":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"private"}]},
                 "Count":true,"AttributesToGet":["secret"],
                 "KeyConditions":{"PK":{"ComparisonOperator":"EQ","AttributeValueList":[{"S":"USER_alice"}]}}}
                """));
        when(request.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.0"));
        when(request.getHeaderString("X-Amz-Target")).thenReturn("DynamoDB_20111205.Query");
        TableDefinition table = fgacTable();
        table.setKeySchema(List.of(new KeySchemaElement("PK", "HASH"), new KeySchemaElement("SK", "RANGE")));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(table));
        Map<String, List<String>> legacy = resolver.resolve("dynamodb", "dynamodb:Query", request);
        assertEquals(List.of("USER_bob"), legacy.get("dynamodb:LeadingKeys"));
        assertEquals(Set.of("PK", "SK", "secret"), new HashSet<>(legacy.get("dynamodb:Attributes")));
        assertEquals(List.of("COUNT"), legacy.get("dynamodb:Select"));
        when(request.getHeaderString("X-Amz-Target")).thenReturn("DynamoDB_20120810.Query");
        Map<String, List<String>> modern = resolver.resolve("dynamodb", "dynamodb:Query", request);
        assertEquals(List.of("USER_alice"), modern.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("SPECIFIC_ATTRIBUTES"), modern.get("dynamodb:Select"));
    }

    @Test
    void legacyBatchDeleteCannotHidePartitionOrAttributeNames() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{"FgacTable":[{"DeleteRequest":{"Key":{
                  "HashKeyElement":{"S":"USER_bob"},"RangeKeyElement":{"S":"private"}}}}]}}
                """));
        when(request.getMediaType()).thenReturn(MediaType.valueOf("application/x-amz-json-1.0"));
        when(request.getHeaderString("X-Amz-Target")).thenReturn("DynamoDB_20111205.BatchWriteItem");
        TableDefinition table = fgacTable();
        table.setKeySchema(List.of(new KeySchemaElement("PK", "HASH"), new KeySchemaElement("SK", "RANGE")));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(table));
        Map<String, List<String>> conditions = resolver.resolve("dynamodb", "dynamodb:BatchWriteItem", request);
        assertEquals(List.of("USER_bob"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(Set.of("PK", "SK"), new HashSet<>(conditions.get("dynamodb:Attributes")));
    }

    @Test
    void returnsNullWhenNoBodyWasBuffered() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(null);

        assertNull(resolver.resolve("dynamodb", "dynamodb:GetItem", containerRequest));
    }

    @Test
    void resolvesBatchGetItemAcrossEveryRequestedKey() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{"FgacTable":{"Keys":[
                   {"PK":{"S":"USER_alice"}},{"PK":{"S":"USER_bob"}}]}}}"""));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));

        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertEquals(List.of("USER_alice", "USER_bob"), conditions.get("dynamodb:LeadingKeys"));
    }

    @Test
    void batchAliasesRetainIndependentSelections() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        String arn = "arn:aws:dynamodb:us-east-1:000000000000:table/FgacTable";
        when(request.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{
                  "FgacTable":{"Keys":[{"PK":{"S":"USER_alice"}}],"ProjectionExpression":"PK"},
                  "%s":{"Keys":[{"PK":{"S":"USER_bob"}}]}}}
                """.formatted(arn)));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));
        when(dynamoDbService.findTable(arn, "us-east-1")).thenReturn(Optional.of(fgacTable()));
        List<Map<String, List<String>>> contexts = resolver.dynamoDbBatchConditionContexts(
                "dynamodb:BatchGetItem", request, arn);
        assertEquals(2, contexts.size());
        assertEquals(List.of("SPECIFIC_ATTRIBUTES"), contexts.get(0).get("dynamodb:Select"));
        assertEquals(List.of("ALL_ATTRIBUTES"), contexts.get(1).get("dynamodb:Select"));
        assertEquals(List.of("USER_alice"), contexts.get(0).get("dynamodb:LeadingKeys"));
        assertEquals(List.of("USER_bob"), contexts.get(1).get("dynamodb:LeadingKeys"));
    }

    @Test
    void resolvesEachBatchTableWithItsOwnSchema() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        when(containerRequest.getProperty("floci.bufferedJsonBody")).thenReturn(json("""
                {"RequestItems":{
                   "FgacTable":{"Keys":[{"PK":{"S":"USER_alice"}}]},
                   "OtherTable":{"Keys":[{"Tenant":{"S":"USER_bob"}}]}}}"""));

        TableDefinition other = new TableDefinition();
        other.setTableName("OtherTable");
        other.setKeySchema(List.of(new KeySchemaElement("Tenant", "HASH")));
        when(dynamoDbService.findTable("FgacTable", "us-east-1")).thenReturn(Optional.of(fgacTable()));
        when(dynamoDbService.findTable("OtherTable", "us-east-1")).thenReturn(Optional.of(other));
        Map<String, List<String>> conditions =
                resolver.resolve("dynamodb", "dynamodb:BatchGetItem", containerRequest);

        assertEquals(List.of("USER_alice", "USER_bob"), conditions.get("dynamodb:LeadingKeys"));
        assertEquals(List.of("USER_bob"), resolver.dynamoDbBatchConditionContext(
                "dynamodb:BatchGetItem", containerRequest,
                "arn:aws:dynamodb:us-east-1:000000000000:table/OtherTable").get("dynamodb:LeadingKeys"));
    }

    // ── aws:RequestTag and aws:ResourceTag ─────────────────────────────────────

    @Test
    void putBucketTaggingResolvesRequestTagsFromTheXmlBody() {
        ContainerRequestContext containerRequest = s3Request("/my-bucket", "<Tagging><TagSet>"
                + "<Tag><Key>Team</Key><Value>payments</Value></Tag>"
                + "<Tag><Key>Env</Key><Value>prod</Value></Tag>"
                + "</TagSet></Tagging>");

        Map<String, List<String>> conditions =
                resolver.resolve("s3", "s3:PutBucketTagging", containerRequest);

        assertEquals(List.of("payments"), conditions.get("aws:RequestTag/Team"));
        assertEquals(List.of("prod"), conditions.get("aws:RequestTag/Env"));
        assertEquals(Set.of("Team", "Env"), new HashSet<>(conditions.get("aws:TagKeys")));
    }

    @Test
    void putBucketTaggingRestoresTheBodyForTheController() throws Exception {
        String body = "<Tagging><TagSet><Tag><Key>Team</Key><Value>payments</Value></Tag></TagSet></Tagging>";
        ContainerRequestContext containerRequest = s3Request("/my-bucket", body);

        resolver.resolve("s3", "s3:PutBucketTagging", containerRequest);

        ArgumentCaptor<InputStream> restored = ArgumentCaptor.forClass(InputStream.class);
        verify(containerRequest).setEntityStream(restored.capture());
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), restored.getValue().readAllBytes());
    }

    @Test
    void bucketActionsResolveResourceTagsFromTheBucketsCurrentTags() {
        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "alice"));

        for (String action : List.of("s3:GetBucketTagging", "s3:DeleteBucket")) {
            Map<String, List<String>> conditions =
                    resolver.resolve("s3", action, s3Request("/my-bucket", ""));
            assertEquals(List.of("alice"), conditions.get("aws:ResourceTag/Owner"), action);
        }
        // DeleteBucketTagging, which AWS checks as s3:PutBucketTagging.
        ContainerRequestContext deleteTagging = s3Request("/my-bucket", "");
        when(deleteTagging.getMethod()).thenReturn("DELETE");
        assertEquals(List.of("alice"), resolver.resolve("s3", "s3:PutBucketTagging", deleteTagging)
                .get("aws:ResourceTag/Owner"));
    }

    @Test
    void bucketActionsOfferNoResourceTagsWhenTheBucketLookupFails() {
        when(s3Service.getBucketTagging(anyString()))
                .thenThrow(new AwsException("NoSuchBucket", "The specified bucket does not exist.", 404));

        assertNull(resolver.resolve("s3", "s3:GetBucketTagging", s3Request("/missing-bucket", "")));
    }

    @Test
    void runInstancesResolvesRequestTagsFromEveryTagSpecification() {
        ContainerRequestContext containerRequest = formRequest("Action=RunInstances"
                + "&TagSpecification.1.ResourceType=instance"
                + "&TagSpecification.1.Tag.1.Key=Team&TagSpecification.1.Tag.1.Value=payments"
                + "&TagSpecification.2.ResourceType=volume"
                + "&TagSpecification.2.Tag.1.Key=Env&TagSpecification.2.Tag.1.Value=prod");

        Map<String, List<String>> conditions =
                resolver.resolve("ec2", "ec2:RunInstances", containerRequest);

        assertEquals(List.of("payments"), conditions.get("aws:RequestTag/Team"));
        assertEquals(List.of("prod"), conditions.get("aws:RequestTag/Env"));
        assertEquals(List.of("Team", "Env"), conditions.get("aws:TagKeys"));
        assertFalse(conditions.containsKey("aws:ResourceTag/Team"));
    }

    @Test
    void createTagsResolvesBothTheRequestedTagsAndTheResourcesCurrentTags() {
        when(ec2Service.resourceTags("us-east-1", "i-0123456789")).thenReturn(List.of(new Tag("Owner", "alice")));
        ContainerRequestContext containerRequest = formRequest(
                "Action=CreateTags&ResourceId.1=i-0123456789&Tag.1.Key=Env&Tag.1.Value=prod");

        Map<String, List<String>> conditions =
                resolver.resolve("ec2", "ec2:CreateTags", containerRequest);

        assertEquals(List.of("prod"), conditions.get("aws:RequestTag/Env"));
        assertEquals(List.of("alice"), conditions.get("aws:ResourceTag/Owner"));
    }

    @Test
    void instanceActionsResolveResourceTagsFromTheFirstNamedInstance() {
        when(ec2Service.resourceTags("us-east-1", "i-0123456789")).thenReturn(List.of(new Tag("Team", "payments")));

        for (String action : List.of("ec2:TerminateInstances", "ec2:DescribeInstances")) {
            Map<String, List<String>> conditions = resolver.resolve("ec2", action,
                    formRequest("Action=X&InstanceId.1=i-0123456789&InstanceId.2=i-9999999999"));
            assertEquals(List.of("payments"), conditions.get("aws:ResourceTag/Team"), action);
        }
        assertEquals(List.of("payments"),
                resolver.resolve("ec2", "ec2:DeleteTags",
                        formRequest("Action=DeleteTags&ResourceId.1=i-0123456789&Tag.1.Key=Team"))
                        .get("aws:ResourceTag/Team"));
    }

    @Test
    void resourceTagsAreReadInTheRequestRegion() {
        requestContext.setRegion("eu-west-1");
        when(ec2Service.resourceTags("eu-west-1", "i-0123456789")).thenReturn(List.of(new Tag("Team", "payments")));
        when(ec2Service.resourceTags("us-east-1", "i-0123456789")).thenReturn(List.of(new Tag("Team", "other")));

        assertEquals(List.of("payments"), resolver.resolve("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-0123456789")).get("aws:ResourceTag/Team"));
    }

    @Test
    void ec2ContextIsAbsentWhenNoResourceIsNamedOrTheActionIsNotCovered() {
        assertNull(resolver.resolve("ec2", "ec2:DescribeInstances", formRequest("Action=DescribeInstances")));
        assertNull(resolver.resolve("ec2", "ec2:DescribeVpcs", formRequest("Action=DescribeVpcs")));
        assertNull(resolver.resolve("ec2", "ec2:CreateTags",
                formRequest("Action=CreateTags&ResourceId.1=i-0123456789")));
    }

    @Test
    void formParametersAreBufferedOncePerRequest() {
        ContainerRequestContext containerRequest = formRequest("Action=TerminateInstances&InstanceId.1=i-1");
        when(ec2Service.resourceTags("us-east-1", "i-1")).thenReturn(List.of(new Tag("Team", "payments")));

        resolver.resolve("ec2", "ec2:TerminateInstances", containerRequest);

        verify(containerRequest).setEntityStream(any());
        verify(containerRequest).setProperty(eq("floci.bufferedFormBody"), any());
    }

    // ── The resolved context drives the real evaluator ─────────────────────────

    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void resourceTagConditionAllowsATaggedInstanceAndDeniesAnUntaggedOne() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        String body = "Action=TerminateInstances&InstanceId.1=i-0123456789";

        when(ec2Service.resourceTags("us-east-1", "i-0123456789")).thenReturn(List.of(new Tag("Team", "payments")));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy),
                "ec2:TerminateInstances", "*",
                resolver.resolve("ec2", "ec2:TerminateInstances", formRequest(body))));

        when(ec2Service.resourceTags("us-east-1", "i-0123456789")).thenReturn(List.of(new Tag("Team", "engineering")));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy),
                "ec2:TerminateInstances", "*",
                resolver.resolve("ec2", "ec2:TerminateInstances", formRequest(body))));
    }

    @Test
    void requestTagConditionAllowsTheDemandedTagValueAndDeniesAnotherOne() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:CreateTags","Resource":"*",
                   "Condition":{"StringEquals":{"aws:RequestTag/CostCenter":"1234"}}}
                ]}""";

        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy), "ec2:CreateTags", "*",
                resolver.resolve("ec2", "ec2:CreateTags", formRequest(
                        "Action=CreateTags&ResourceId.1=i-1&Tag.1.Key=CostCenter&Tag.1.Value=1234"))));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy), "ec2:CreateTags", "*",
                resolver.resolve("ec2", "ec2:CreateTags", formRequest(
                        "Action=CreateTags&ResourceId.1=i-1&Tag.1.Key=CostCenter&Tag.1.Value=9999"))));
    }

    @Test
    void resourceTagConditionOnABucketFollowsItsOwnerTag() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:GetBucketTagging","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Owner":"alice"}}}
                ]}""";

        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "alice"));
        assertEquals(Decision.ALLOW, evaluator.simulateCustomPolicy(List.of(policy), "s3:GetBucketTagging", "*",
                resolver.resolve("s3", "s3:GetBucketTagging", s3Request("/my-bucket", ""))));

        when(s3Service.getBucketTagging("my-bucket")).thenReturn(Map.of("Owner", "bob"));
        assertEquals(Decision.DENY, evaluator.simulateCustomPolicy(List.of(policy), "s3:GetBucketTagging", "*",
                resolver.resolve("s3", "s3:GetBucketTagging", s3Request("/my-bucket", ""))));
    }

    // ── Every target of a multi-resource request is evaluated ──────────────────

    @Test
    void remainingTargetsCarryEachLaterResourcesOwnTags() {
        when(ec2Service.resourceTags("us-east-1", "i-1")).thenReturn(List.of(new Tag("Team", "payments")));
        when(ec2Service.resourceTags("us-east-1", "i-2")).thenReturn(List.of(new Tag("Team", "engineering")));
        when(ec2Service.resourceTags("us-east-1", "i-3")).thenReturn(List.of());
        ContainerRequestContext containerRequest = formRequest(
                "Action=TerminateInstances&InstanceId.1=i-1&InstanceId.2=i-2&InstanceId.3=i-3");

        List<Map<String, List<String>>> remaining =
                resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances", containerRequest);

        assertEquals(2, remaining.size());
        assertEquals(List.of("engineering"), remaining.get(0).get("aws:ResourceTag/Team"));
        // An untagged target still gets its own, empty, context so a tag condition fails on it.
        assertEquals(Map.of(), remaining.get(1));
    }

    @Test
    void remainingTargetsOfCreateTagsRepeatTheRequestedTagsPerResource() {
        when(ec2Service.resourceTags("us-east-1", "vpc-2")).thenReturn(List.of(new Tag("Owner", "bob")));
        ContainerRequestContext containerRequest = formRequest(
                "Action=CreateTags&ResourceId.1=vpc-1&ResourceId.2=vpc-2&Tag.1.Key=Env&Tag.1.Value=prod");

        List<Map<String, List<String>>> remaining =
                resolver.resolveRemainingTargets("ec2", "ec2:CreateTags", containerRequest);

        assertEquals(1, remaining.size());
        assertEquals(List.of("prod"), remaining.get(0).get("aws:RequestTag/Env"));
        assertEquals(List.of("bob"), remaining.get(0).get("aws:ResourceTag/Owner"));
    }

    @Test
    void singleTargetRequestsAndOtherServicesHaveNoRemainingTargets() {
        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-1")));
        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:RunInstances",
                formRequest("Action=RunInstances&TagSpecification.1.ResourceType=instance")));
        assertEquals(List.of(), resolver.resolveRemainingTargets("s3", "s3:DeleteBucket",
                s3Request("/my-bucket", "")));
    }

    @Test
    void policyMustHoldForEveryTargetNotJustTheFirst() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        String body = "Action=TerminateInstances&InstanceId.1=i-1&InstanceId.2=i-2";
        when(ec2Service.resourceTags("us-east-1", "i-1")).thenReturn(List.of(new Tag("Team", "payments")));

        when(ec2Service.resourceTags("us-east-1", "i-2")).thenReturn(List.of(new Tag("Team", "engineering")));
        assertEquals(Decision.DENY, decisionForEveryTarget(policy, "ec2:TerminateInstances", formRequest(body)));

        when(ec2Service.resourceTags("us-east-1", "i-2")).thenReturn(List.of(new Tag("Team", "payments")));
        assertEquals(Decision.ALLOW, decisionForEveryTarget(policy, "ec2:TerminateInstances", formRequest(body)));
    }

    @Test
    void aRepeatedNumberedParameterKeepsItsFirstValueLikeTheHandlers() {
        when(ec2Service.resourceTags("us-east-1", "i-first")).thenReturn(List.of(new Tag("Team", "engineering")));
        when(ec2Service.resourceTags("us-east-1", "i-second")).thenReturn(List.of(new Tag("Team", "payments")));

        Map<String, List<String>> conditions = resolver.resolve("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-first&InstanceId.1=i-second"));

        assertEquals(List.of("engineering"), conditions.get("aws:ResourceTag/Team"));
    }

    @Test
    void remainingTargetsStopAtTheFirstNumberingGapLikeTheHandlers() {
        when(ec2Service.resourceTags("us-east-1", "i-2")).thenReturn(List.of(new Tag("Team", "payments")));

        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.2=i-2")));
        assertEquals(List.of(), resolver.resolveRemainingTargets("ec2", "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-1&InstanceId.3=i-3")));
    }

    @Test
    void aRepeatedKeyCannotSwapAnAllowedResourceInFrontOfTheDeniedOne() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"ec2:TerminateInstances","Resource":"*",
                   "Condition":{"StringEquals":{"aws:ResourceTag/Team":"payments"}}}
                ]}""";
        when(ec2Service.resourceTags("us-east-1", "i-denied")).thenReturn(List.of(new Tag("Team", "engineering")));
        when(ec2Service.resourceTags("us-east-1", "i-allowed")).thenReturn(List.of(new Tag("Team", "payments")));

        // The handler terminates i-denied, the first value, so that is what must be authorized.
        assertEquals(Decision.DENY, decisionForEveryTarget(policy, "ec2:TerminateInstances",
                formRequest("Action=TerminateInstances&InstanceId.1=i-denied&InstanceId.1=i-allowed")));
    }

    @Test
    void globalKeysArePopulatedForEveryServiceAndAction() {
        Map<String, List<String>> conditions = IamConditionContextResolver.withGlobalContext(
                null, "arn:aws:lambda:eu-west-2:000000000000:function:task", "eu-west-2",
                "000000000000", "000000000000");

        assertEquals(List.of("000000000000"), conditions.get("aws:ResourceAccount"));
        assertEquals(List.of("000000000000"), conditions.get("aws:PrincipalAccount"));
        assertEquals(List.of("eu-west-2"), conditions.get("aws:RequestedRegion"));
        assertEquals(List.of("false"), conditions.get("aws:PrincipalIsAWSService"));
    }

    @Test
    void resourceAccountComesFromTheResourceArnWhenItCarriesOne() {
        Map<String, List<String>> conditions = IamConditionContextResolver.withGlobalContext(
                Map.of("service:key", List.of("value")),
                "arn:aws:sqs:eu-west-2:111111111111:queue", "eu-west-2", "000000000000", "000000000000");

        assertEquals(List.of("111111111111"), conditions.get("aws:ResourceAccount"));
        assertEquals(List.of("value"), conditions.get("service:key"));
        assertFalse(conditions.containsKey("aws:SecureTransport"));
    }

    @Test
    void resourceAccountIsTheBucketOwnerWhenAnS3ArnCarriesNoAccount() {
        // S3 bucket and object ARNs have an empty account segment by design, so the owner has to
        // come from service state. Falling back to the caller would make a cross-account bucket
        // look like the caller's own.
        Map<String, List<String>> conditions = IamConditionContextResolver.withGlobalContext(
                null, "arn:aws:s3:::partner-bucket/report.csv", "eu-west-2",
                "000000000000", "111111111111");

        assertEquals(List.of("111111111111"), conditions.get("aws:ResourceAccount"));
        assertEquals(List.of("000000000000"), conditions.get("aws:PrincipalAccount"));
    }

    @Test
    void resourceAccountIsOmittedWhenNeitherTheArnNorServiceStateKnowsTheOwner() {
        Map<String, List<String>> conditions = IamConditionContextResolver.withGlobalContext(
                null, "arn:aws:s3:::orphan-bucket", "eu-west-2", "000000000000", null);

        assertFalse(conditions.containsKey("aws:ResourceAccount"),
                "guessing the owner would authorize cross-account access a Condition meant to refuse");
        assertEquals(List.of("000000000000"), conditions.get("aws:PrincipalAccount"));
    }

    @Test
    void aServiceCallCarriesTheServiceAndItsSourceButNoIamPrincipal() {
        Map<String, List<String>> conditions = IamConditionContextResolver.serviceCallContext(
                "ses.amazonaws.com", "arn:aws:sns:eu-west-2:111122223333:events", "111122223333",
                "arn:aws:ses:eu-west-2:444455556666:configuration-set/my-set", "444455556666");

        assertEquals(Map.of(
                "aws:PrincipalIsAWSService", List.of("true"),
                "aws:PrincipalServiceName", List.of("ses.amazonaws.com"),
                "aws:PrincipalServiceNamesList", List.of("ses.amazonaws.com"),
                "aws:SourceArn", List.of("arn:aws:ses:eu-west-2:444455556666:configuration-set/my-set"),
                "aws:SourceAccount", List.of("444455556666"),
                "aws:SourceOwner", List.of("444455556666"),
                "aws:ResourceAccount", List.of("111122223333"),
                "aws:RequestedRegion", List.of("eu-west-2")), conditions);
    }

    @Test
    void onlyACallToATopicFromAServiceSnsListsCarriesSourceOwner() {
        Map<String, List<String>> fromS3 = IamConditionContextResolver.serviceCallContext(
                "s3.amazonaws.com", "arn:aws:sns:eu-west-2:111122223333:events", "111122223333",
                "arn:aws:s3:::bucket", "111122223333");
        Map<String, List<String>> toQueue = IamConditionContextResolver.serviceCallContext(
                "ses.amazonaws.com", "arn:aws:sqs:eu-west-2:111122223333:events", "111122223333",
                "arn:aws:ses:eu-west-2:111122223333:configuration-set/my-set", "111122223333");

        assertFalse(fromS3.containsKey("aws:SourceOwner"));
        assertEquals(List.of("111122223333"), fromS3.get("aws:SourceAccount"));
        assertFalse(toQueue.containsKey("aws:SourceOwner"));
    }

    @Test
    void aServiceCallWithNoSourceLeavesTheSourceKeysOut() {
        Map<String, List<String>> conditions = IamConditionContextResolver.serviceCallContext(
                "ses.amazonaws.com", "arn:aws:s3:::bucket", "111122223333", null, null);

        assertFalse(conditions.containsKey("aws:SourceArn"));
        assertFalse(conditions.containsKey("aws:SourceAccount"));
        assertEquals(List.of("111122223333"), conditions.get("aws:ResourceAccount"));
    }

    private Decision decisionForEveryTarget(String policy, String action, ContainerRequestContext request) {
        Decision first = evaluator.simulateCustomPolicy(List.of(policy), action, "*",
                resolver.resolve("ec2", action, request));
        if (first == Decision.DENY) {
            return first;
        }
        for (Map<String, List<String>> target : resolver.resolveRemainingTargets("ec2", action, request)) {
            if (evaluator.simulateCustomPolicy(List.of(policy), action, "*", target) == Decision.DENY) {
                return Decision.DENY;
            }
        }
        return Decision.ALLOW;
    }
}

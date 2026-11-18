package io.floci.aws.services.fis;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AccountResolver;
import io.floci.aws.core.common.AwsErrorResponse;
import io.floci.aws.core.common.AwsNamespaces;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestOperationResolver;
import io.floci.aws.core.common.RequestOperationResolver.ResolvedOperation;
import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.WireProtocol;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FisApiFaultFilter}: which calls a running {@code aws:fis:inject-api-*} action fails, and the error each
 * service answers with.
 */
class FisApiFaultFilterTest {

    private static final String REGION = "us-east-1";
    private static final String SESSION_KEY = "ASIAFISSESSIONKEY001";
    private static final String TARGET_ROLE = "arn:aws:iam::000000000000:role/app/checkout";
    private static final double FAR_FUTURE = Double.MAX_VALUE;

    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private final FisService fisService = mock(FisService.class);
    private final IamService iamService = mock(IamService.class);
    private final RequestContext requestContext = mock(RequestContext.class);
    private final RequestOperationResolver operationResolver = mock(RequestOperationResolver.class);
    private final ResolvedServiceCatalog catalog = new ResolvedServiceCatalog(config);
    private int roll;

    @BeforeEach
    void setUp() {
        when(config.services().fis().enabled()).thenReturn(true);
        when(requestContext.getRegion()).thenReturn(REGION);
        caller(TARGET_ROLE);
    }

    @Test
    void aTargetRolesEc2CallGetsEc2sOwnThrottlingError() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "ec2", Set.of("DescribeInstances"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("ec2", "DescribeInstances");

        Response response = abortedWith(request("ec2", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE));

        assertEquals(503, response.getStatus());
        assertThat((String) response.getEntity(), containsString("<Code>RequestLimitExceeded</Code>"));
    }

    @Test
    void aKinesisJsonCallGetsTheCommonErrorInJson() {
        faults(new FisApiFault(FisApiFault.Kind.INTERNAL, "kinesis", Set.of("PutRecord"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("kinesis", "PutRecord");

        Response response = abortedWith(request("kinesis", WireProtocol.AWS_JSON_1_1,
                MediaType.valueOf("application/x-amz-json-1.1")));

        assertEquals(500, response.getStatus());
        assertEquals(new AwsErrorResponse("InternalFailure", "The request can't be processed right now because of "
                + "an internal server issue. Try again later. If the problem persists, contact AWS Support."),
                response.getEntity());
    }

    @Test
    void aKinesisCborCallGetsTheErrorInCbor() {
        faults(new FisApiFault(FisApiFault.Kind.UNAVAILABLE, "kinesis", Set.of("GetRecords"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("kinesis", "GetRecords");
        ContainerRequestContext ctx = request("kinesis", WireProtocol.AWS_CBOR_TARGET,
                MediaType.valueOf("application/x-amz-cbor-1.1"));
        when(ctx.getHeaderString("Content-Type")).thenReturn("application/x-amz-cbor-1.1");

        Response response = abortedWith(ctx);

        assertEquals(503, response.getStatus());
        assertEquals("application/x-amz-cbor-1.1", response.getMediaType().toString());
        assertEquals("ServiceUnavailable;Receiver", response.getHeaderString("x-amzn-query-error"));
    }

    @Test
    void aQueryServiceGetsAQueryErrorResponseFromTheReceiverForAServerFault() {
        faults(new FisApiFault(FisApiFault.Kind.UNAVAILABLE, "sns", Set.of("Publish"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("sns", "Publish");

        Response response = abortedWith(request("sns", WireProtocol.AWS_QUERY,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE));

        assertEquals(503, response.getStatus());
        String body = (String) response.getEntity();
        assertThat(body, containsString("<ErrorResponse xmlns=\"" + AwsNamespaces.SNS + "\">"));
        assertThat(body, containsString("<Type>Receiver</Type>"));
        assertThat(body, containsString("<Code>ServiceUnavailable</Code>"));
    }

    @Test
    void aRestXmlServiceGetsItsErrorResponseInItsOwnNamespace() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "route53", Set.of("ListHostedZones"), 100,
                        Set.of(TARGET_ROLE), FAR_FUTURE),
                new FisApiFault(FisApiFault.Kind.THROTTLE, "cloudfront", Set.of("ListDistributions"), 100,
                        Set.of(TARGET_ROLE), FAR_FUTURE));

        resolvesTo("route53", "ListHostedZones");
        String route53 = (String) abortedWith(request("route53", WireProtocol.REST, null)).getEntity();
        resolvesTo("cloudfront", "ListDistributions");
        String cloudFront = (String) abortedWith(request("cloudfront", WireProtocol.REST, null)).getEntity();

        assertThat(route53, containsString("<ErrorResponse xmlns=\"" + AwsNamespaces.ROUTE53 + "\">"));
        assertThat(route53, containsString("<Code>ThrottlingException</Code>"));
        assertThat(cloudFront, containsString("<ErrorResponse xmlns=\"" + AwsNamespaces.CLOUDFRONT + "\">"));
    }

    @Test
    void aServiceIsMatchedByItsIamNamespaceAsWellAsTheScopeItIsServedUnder() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "cloudwatch", Set.of("PutMetricData"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        when(operationResolver.resolve(anyString(), anyString(), any(), any())).thenReturn(
                new ResolvedOperation("monitoring", "cloudwatch:PutMetricData", "monitoring:PutMetricData"));

        Response response = abortedWith(request("monitoring", WireProtocol.AWS_QUERY,
                MediaType.APPLICATION_FORM_URLENCODED_TYPE));

        assertEquals(400, response.getStatus());
    }

    @Test
    void s3GetsItsOwnErrorEnvelope() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "s3", Set.of("GetObject"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("s3", "GetObject");

        Response response = abortedWith(request("s3", WireProtocol.REST, null));

        assertEquals(400, response.getStatus());
        String body = (String) response.getEntity();
        assertThat(body, containsString("<Error><Code>ThrottlingException</Code>"));
    }

    @Test
    void anotherRolesCallIsLeftAloneWithoutWorkingOutItsOperation() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "ec2", Set.of("DescribeInstances"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        caller("arn:aws:iam::000000000000:role/someone-else");
        ContainerRequestContext ctx = request("ec2", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        filter().filter(ctx);

        verify(ctx, never()).abortWith(any());
        verifyNoInteractions(operationResolver);
    }

    @Test
    void anOperationTheActionDoesNotNameIsLeftAlone() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "ec2", Set.of("DescribeInstances"), 100,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("ec2", "RunInstances");
        ContainerRequestContext ctx = request("ec2", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        filter().filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void onlyTheActionsPercentageOfCallsFail() {
        faults(new FisApiFault(FisApiFault.Kind.THROTTLE, "ec2", Set.of("DescribeInstances"), 30,
                Set.of(TARGET_ROLE), FAR_FUTURE));
        resolvesTo("ec2", "DescribeInstances");

        roll = 30;
        ContainerRequestContext spared = request("ec2", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        filter().filter(spared);
        verify(spared, never()).abortWith(any());

        roll = 29;
        assertEquals(503, abortedWith(request("ec2", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE)).getStatus());
    }

    @Test
    void withNoFaultRunningTheCallIsNotExamined() {
        faults();
        ContainerRequestContext ctx = request("ec2", null, MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        filter().filter(ctx);

        verify(ctx, never()).abortWith(any());
        verifyNoInteractions(iamService, operationResolver);
    }

    private void faults(FisApiFault... faults) {
        when(fisService.activeApiFaults(REGION)).thenReturn(List.of(faults));
    }

    private void caller(String principalArn) {
        when(iamService.resolveCallerArns(SESSION_KEY)).thenReturn(Optional.of(new CallerArns(
                "arn:aws:sts::000000000000:assumed-role/checkout/s1", principalArn,
                CallerArns.TYPE_ASSUMED_ROLE, "AROAEXAMPLE:s1", null, false)));
    }

    private void resolvesTo(String service, String operation) {
        when(operationResolver.resolve(anyString(), anyString(), any(), any()))
                .thenReturn(new ResolvedOperation(service, service + ":" + operation, service + ":" + operation));
    }

    private FisApiFaultFilter filter() {
        return new FisApiFaultFilter(config, fisService, iamService, new AccountResolver("000000000000"),
                requestContext, operationResolver, catalog, null, fixedRoll());
    }

    private RandomGenerator fixedRoll() {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return roll;
            }

            @Override
            public int nextInt(int bound) {
                return roll;
            }
        };
    }

    private Response abortedWith(ContainerRequestContext ctx) {
        filter().filter(ctx);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(response.capture());
        return response.getValue();
    }

    private static ContainerRequestContext request(String scope, WireProtocol protocol, MediaType mediaType) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString("Authorization")).thenReturn("AWS4-HMAC-SHA256 Credential=" + SESSION_KEY
                + "/20261117/" + REGION + "/" + scope + "/aws4_request, SignedHeaders=host, Signature=deadbeef");
        UriInfo uri = mock(UriInfo.class);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(ctx.getUriInfo()).thenReturn(uri);
        when(ctx.getMediaType()).thenReturn(mediaType);
        if (protocol != null) {
            when(ctx.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY))
                    .thenReturn(new ProtocolClaim(protocol, null, null, null));
        }
        return ctx;
    }
}

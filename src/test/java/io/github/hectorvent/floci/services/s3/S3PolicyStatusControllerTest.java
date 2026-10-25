package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AccountResolver;
import io.github.hectorvent.floci.core.common.IamEnforcementFilter;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.floci.ui.UiPages;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.sns.SnsQueryHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pins the REST route and XML shape for S3 GetBucketPolicyStatus. */
class S3PolicyStatusControllerTest {

    @Test
    void policyStatusSubresourceReturnsPolicyStatusXml() {
        String bucket = "policy-status-bucket";
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.isBucketPolicyPublic(bucket)).thenReturn(true);
        when(s3Service.listObjectsWithPrefixes(anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(new S3Service.ListObjectsResult(List.of(), List.of(), false, null));

        CurrentVertxRequest currentVertxRequest = mock(CurrentVertxRequest.class, RETURNS_DEEP_STUBS);
        when(currentVertxRequest.getCurrent().request().path()).thenReturn("/" + bucket);

        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.putSingle("policyStatus", "");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/" + bucket + "?policyStatus"));

        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString())).thenReturn(null);

        S3Controller controller = new S3Controller(
                s3Service,
                mock(S3SelectService.class),
                mock(RegionResolver.class),
                mock(SnsQueryHandler.class),
                currentVertxRequest,
                mock(UiPages.class),
                mock(CloudTrailService.class),
                mock(AccountResolver.class),
                mock(RequestContext.class),
                mock(IamService.class),
                mock(IamEnforcementFilter.class),
                mock(EmulatorConfig.class),
                Clock.systemUTC());

        Response response = controller.listObjects(bucket, null, null, null, null, null, null, null,
                null, null, null, uriInfo, headers);

        String entity = response.getEntity().toString();
        assertTrue(entity.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?><PolicyStatus"));
        assertTrue(entity.contains("<IsPublic>true</IsPublic></PolicyStatus>"));
    }
}

package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import io.floci.aws.core.common.IamOperation;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.ServiceRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;

@Path("/v1/metrics")
@IamOperation(namespace = "cloudwatch", operation = "PutMetricData")
public class OtlpMetricsController {

    private static final int MAX_REQUEST_BYTES = 5 * 1024 * 1024;
    private static final String PROTOBUF = "application/x-protobuf";
    private final OtlpMetricsService service;
    private final RequestContext requestContext;
    private final ServiceRegistry serviceRegistry;
    private final OtlpMetricsDecoder decoder = new OtlpMetricsDecoder();
    private final OtlpMetricsValidation validation = new OtlpMetricsValidation();

    @Inject
    public OtlpMetricsController(OtlpMetricsService service, RequestContext requestContext,
                                 ServiceRegistry serviceRegistry) {
        this.service = service;
        this.requestContext = requestContext;
        this.serviceRegistry = serviceRegistry;
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response ingest(@Context HttpHeaders headers, InputStream input) {
        if (!serviceRegistry.isServiceEnabled("monitoring")) {
            return OtlpMetricsResponses.error(headers.getMediaType(), 404, 5, "CloudWatch metrics service is disabled.");
        }
        MediaType mediaType = headers.getMediaType();
        boolean json = mediaType != null && mediaType.isCompatible(MediaType.APPLICATION_JSON_TYPE);
        boolean protobuf = mediaType != null && mediaType.isCompatible(MediaType.valueOf(PROTOBUF));
        if (!json && !protobuf) {
            return OtlpMetricsResponses.error(mediaType, 415, 3, "Unsupported content type.");
        }
        String encoding = headers.getHeaderString(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding)
                && !"gzip".equalsIgnoreCase(encoding)) {
            return OtlpMetricsResponses.error(mediaType, 415, 3, "Unsupported content encoding.");
        }
        try {
            InputStream body = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(input) : input;
            byte[] bytes = body.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                return OtlpMetricsResponses.error(mediaType, 413, 8, "Request exceeds maximum size of 5 MB.");
            }
            DynamicMessage request = json ? decoder.decodeJson(bytes) : decoder.decodeProtobuf(bytes);
            OtlpMetricsValidation.Result result = validation.validate(request);
            if (result.rejectedDataPoints() > 0 && result.rejectedDataPoints() == dataPointCount(request)) {
                return OtlpMetricsResponses.error(mediaType, 400, 3, result.errorMessage());
            }
            service.ingest(requestContext.getRegion(), result.requestBytes());
            DynamicMessage response = buildResponse(result);
            return successResponse(json, response);
        } catch (IOException e) {
            return OtlpMetricsResponses.error(mediaType, 400, 3, "Bad request.");
        }
    }

    private DynamicMessage buildResponse(OtlpMetricsValidation.Result result) {
        if (result.hasErrors()) {
            DynamicMessage.Builder psBuilder = DynamicMessage.newBuilder(OtlpDescriptors.PARTIAL_SUCCESS);
            psBuilder.setField(OtlpDescriptors.REJECTED_DATA_POINTS, (long) result.rejectedDataPoints());
            psBuilder.setField(OtlpDescriptors.PARTIAL_ERROR_MESSAGE, result.errorMessage());
            DynamicMessage.Builder respBuilder = DynamicMessage.newBuilder(OtlpDescriptors.EXPORT_RESPONSE);
            respBuilder.setField(OtlpDescriptors.RESPONSE_PARTIAL_SUCCESS, psBuilder.build());
            return respBuilder.build();
        }
        return DynamicMessage.newBuilder(OtlpDescriptors.EXPORT_RESPONSE).build();
    }

    private Response successResponse(boolean json, DynamicMessage response) throws IOException {
        byte[] responseBody = json ? JsonFormat.printer().print(response).getBytes(StandardCharsets.UTF_8)
                : response.toByteArray();
        return Response.ok(responseBody).type(json ? MediaType.APPLICATION_JSON : PROTOBUF).build();
    }

    private int dataPointCount(DynamicMessage request) {
        int count = 0;
        for (DynamicMessage resource : getList(request, OtlpDescriptors.RESOURCE_METRICS_LIST)) {
            for (DynamicMessage scope : getList(resource, OtlpDescriptors.SCOPE_METRICS_LIST)) {
                for (DynamicMessage metric : getList(scope, OtlpDescriptors.METRICS_LIST)) {
                    count += OtlpMetricsValidation.metricDataPointCount(metric);
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static List<DynamicMessage> getList(DynamicMessage msg, FieldDescriptor field) {
        return (List<DynamicMessage>) msg.getField(field);
    }
}

package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.util.JsonFormat;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsPartialSuccess;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
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
import java.util.zip.GZIPInputStream;

@Path("/v1/metrics")
public class OtlpMetricsController {

    private static final int MAX_REQUEST_BYTES = 5 * 1024 * 1024;
    private static final String PROTOBUF = "application/x-protobuf";
    private final OtlpMetricsService service;
    private final RegionResolver regionResolver;
    private final ServiceRegistry serviceRegistry;
    private final OtlpMetricsDecoder decoder = new OtlpMetricsDecoder();
    private final OtlpMetricsValidation validation = new OtlpMetricsValidation();

    @Inject
    public OtlpMetricsController(OtlpMetricsService service, RegionResolver regionResolver,
                                 ServiceRegistry serviceRegistry) {
        this.service = service;
        this.regionResolver = regionResolver;
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
            ExportMetricsServiceRequest request = json ? decoder.decodeJson(bytes) : decoder.decodeProtobuf(bytes);
            OtlpMetricsValidation.Result result = validation.validate(request);
            if (result.rejectedDataPoints() > 0 && result.rejectedDataPoints() == dataPointCount(request)) {
                return OtlpMetricsResponses.error(mediaType, 400, 3, result.errorMessage());
            }
            service.ingest(regionResolver.resolveRegion(headers), result.request());
            ExportMetricsServiceResponse response = result.hasErrors()
                    ? ExportMetricsServiceResponse.newBuilder().setPartialSuccess(ExportMetricsPartialSuccess
                            .newBuilder().setRejectedDataPoints(result.rejectedDataPoints())
                            .setErrorMessage(result.errorMessage())).build()
                    : ExportMetricsServiceResponse.getDefaultInstance();
            return successResponse(json, response);
        } catch (IOException e) {
            return OtlpMetricsResponses.error(mediaType, 400, 3, "Bad request.");
        }
    }

    private Response successResponse(boolean json, ExportMetricsServiceResponse response) throws IOException {
        byte[] body = json ? JsonFormat.printer().print(response).getBytes(StandardCharsets.UTF_8)
                : response.toByteArray();
        return Response.ok(body).type(json ? MediaType.APPLICATION_JSON : PROTOBUF).build();
    }

    private int dataPointCount(ExportMetricsServiceRequest request) {
        int count = 0;
        for (ResourceMetrics resource : request.getResourceMetricsList()) {
            for (ScopeMetrics scope : resource.getScopeMetricsList()) {
                for (Metric metric : scope.getMetricsList()) {
                    count += switch (metric.getDataCase()) {
                        case GAUGE -> metric.getGauge().getDataPointsCount();
                        case SUM -> metric.getSum().getDataPointsCount();
                        case HISTOGRAM -> metric.getHistogram().getDataPointsCount();
                        case EXPONENTIAL_HISTOGRAM -> metric.getExponentialHistogram().getDataPointsCount();
                        case SUMMARY -> metric.getSummary().getDataPointsCount();
                        case DATA_NOT_SET -> 0;
                    };
                }
            }
        }
        return count;
    }
}

package io.floci.aws.services.appsync.graphql.datasource;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.core.common.ServiceRegistry;
import io.floci.aws.core.common.auth.LocalAwsSigningSession;
import io.floci.aws.services.appsync.model.DataSource;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.quarkus.vertx.http.HttpServer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Signs audited, same-account AWS operations for Floci's actual local listener, never for an external URL. */
@ApplicationScoped
public class AwsIamHttpDataSourceInvoker {

    private static final Logger LOG = Logger.getLogger(AwsIamHttpDataSourceInvoker.class);
    private final IamService iam;
    private final ServiceRoleAuthorizer authorizer;
    private final ServiceRegistry services;
    private final RegionResolver regions;
    private final HttpServer server;
    // Its URL is exclusively the runtime-owned listener.
    private final HttpDataSourceTransport local;

    @Inject
    public AwsIamHttpDataSourceInvoker(IamService iam, ServiceRoleAuthorizer authorizer,
                                       ServiceRegistry services, RegionResolver regions,
                                       HttpServer server) {
        this(iam, authorizer, services, regions, server,
                new HttpDataSourceTransport(HttpDataSourceTransport.client(SystemDefaultDnsResolver.INSTANCE)));
    }

    AwsIamHttpDataSourceInvoker(IamService iam, ServiceRoleAuthorizer authorizer,
                                ServiceRegistry services, RegionResolver regions,
                                HttpServer server, HttpDataSourceTransport local) {
        this.iam = iam;
        this.authorizer = authorizer;
        this.services = services;
        this.regions = regions;
        this.server = server;
        this.local = local;
    }

    public Map<String, Object> invoke(DataSource source, HttpDataSourceRequest request, String apiRegion) {
        HttpDataSourceAwsTarget target = HttpDataSourceAwsTarget.parse(source, request, apiRegion, regions);
        String serviceId = "states".equals(target.service()) ? "stepfunctions" : "scheduler";
        if (!services.isServiceEnabled(serviceId)) {
            throw new AwsException("ServiceNotAvailableException", "Service " + serviceId + " is not enabled", 400);
        }
        authorizer.refusal(target.roleArn(), target.accountId(), ServicePrincipals.of("appsync"),
                target.sourceApiArn(), List.of()).ifPresent(reason -> {
            throw new AwsException("AccessDeniedException", reason, 403);
        });
        URI listener = server.getLocalBaseUri();
        if (listener == null) {
            throw new AwsException("InternalFailureException", "The local AWS listener is not ready", 500);
        }
        String path = request.uri().getRawPath();
        String query = request.uri().getRawQuery();
        URI uri = listener.resolve(path + (query == null ? "" : "?" + query));
        try (LocalAwsSigningSession session = LocalAwsSigningSession.forAccount(iam, target.accountId(), target.roleArn())) {
            Map<String, List<String>> headers = new TreeMap<>();
            request.headers().forEach((name, value) -> headers.put(name, List.of(value)));
            headers.putIfAbsent("content-type", List.of("states".equals(target.service())
                    ? "application/x-amz-json-1.0" : "application/json"));
            Map<String, String> signed = new TreeMap<>();
            session.sign(request.method(), uri, request.body(), target.region(), target.service(), headers)
                    .forEach((name, values) -> signed.put(name, String.join(",", values)));
            return local.send(new HttpDataSourceRequest(uri, request.method(), signed, request.body()));
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            LOG.debugv("Signed HTTP data source failed: service={0}, region={1}, cause={2}",
                    target.service(), target.region(), e.getClass().getSimpleName());
            throw new AwsException("HttpException", "The signed HTTP data source request could not complete", 500);
        }
    }

    @PreDestroy
    void close() {
        local.close();
    }
}

package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.config.FlociVersion;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.ContainerTeardowns;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbBackendSelector;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Path("{prefix:(_floci|_localstack)}")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatorInfoController {

    private static final Logger LOG = Logger.getLogger(EmulatorInfoController.class);
    static final int MANIFEST_VERSION = 1;
    static final int CONSOLE_CONTRACT = 2;

    private final ServiceRegistry serviceRegistry;
    private final InitLifecycleState initLifecycleState;
    private final String version;

    private final StorageFactory storageFactory;
    private final Instance<Resettable> resettables;
    private final Instance<ContainerTeardown> containerTeardowns;
    private final FlociCertificateAuthority certificateAuthority;
    private final EmulatorConfig config;
    private final DynamoDbBackendSelector dynamoDbBackendSelector;

    @Inject
    public EmulatorInfoController(ServiceRegistry serviceRegistry,
                                  InitLifecycleState initLifecycleState,
                                  StorageFactory storageFactory,
                                  Instance<Resettable> resettables,
                                  Instance<ContainerTeardown> containerTeardowns,
                                  FlociCertificateAuthority certificateAuthority,
                                  EmulatorConfig config,
                                  DynamoDbBackendSelector dynamoDbBackendSelector) {
        this.serviceRegistry = serviceRegistry;
        this.initLifecycleState = initLifecycleState;
        this.storageFactory = storageFactory;
        this.resettables = resettables;
        this.containerTeardowns = containerTeardowns;
        this.certificateAuthority = certificateAuthority;
        this.config = config;
        this.dynamoDbBackendSelector = dynamoDbBackendSelector;
        this.version = FlociVersion.current();
    }

    @GET
    @Path("/health")
    public Response health() {
        return Response.ok(Map.of(
                "services", serviceRegistry.getServices(),
                "edition", "community",
                "original_edition", "floci-always-free",
                "version", version)).build();
    }

    @GET
    @Path("/init")
    public Response init() {
        Map<String, Object> completed = new LinkedHashMap<>();
        completed.put("boot", initLifecycleState.isBootCompleted());
        completed.put("start", initLifecycleState.isStartCompleted());
        completed.put("ready", initLifecycleState.isReadyCompleted());
        completed.put("shutdown", initLifecycleState.isShutdownStarted());

        Map<String, Object> scripts = new LinkedHashMap<>();
        for (InitializationHook hook : InitializationHook.values()) {
            scripts.put(hook.getResponseKey(), initLifecycleState.getScripts(hook).stream()
                    .map(r -> Map.of("script", r.script(), "state", r.state(), "return_code", r.returnCode()))
                    .toList());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completed", completed);
        body.put("scripts", scripts);
        return Response.ok(body).build();
    }

    /**
     * The emulator manifest of the Floci Console Contract v2: what a web console, or a script, needs to
     * know about this emulator. Additive over the original {@code version}, {@code edition},
     * {@code original_edition} and {@code dynamodb_backend} keys, which stay as they were.
     */
    @GET
    @Path("/info")
    public Response info() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("manifest_version", MANIFEST_VERSION);
        body.put("console_contract", CONSOLE_CONTRACT);
        body.put("cloud", "aws");
        body.put("emulator", "floci");
        body.put("version", version);
        body.put("edition", "community");
        body.put("original_edition", "floci-always-free");
        body.put("dynamodb_backend", dynamoDbBackendSelector.selected());
        body.put("endpoint", config.effectiveBaseUrl());
        body.put("health_url", "/_floci/health");
        body.put("tls", Map.of("enabled", config.tls().enabled()));
        body.put("services", manifestServices(serviceRegistry.getServices()));

        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("region", config.defaultRegion());
        defaults.put("account_id", config.defaultAccountId());
        body.put("defaults", defaults);

        Map<String, Object> ui = new LinkedHashMap<>();
        ui.put("enabled", config.services().ui().enabled());
        ui.put("port", config.services().ui().port());
        body.put("ui", ui);
        return Response.ok(body).build();
    }

    /**
     * The health map's {@code running} and {@code available}, as the manifest's {@code enabled} and
     * {@code disabled}.
     */
    static Map<String, String> manifestServices(Map<String, String> healthServices) {
        Map<String, String> services = new TreeMap<>();
        healthServices.forEach((name, status) -> services.put(name, "running".equals(status) ? "enabled" : "disabled"));
        return services;
    }

    @GET
    @Path("/diagnose")
    public Response diagnose() {
        return Response.ok(Map.of()).build();
    }

    @GET
    @Path("/config")
    public Response config() {
        return Response.ok(Map.of()).build();
    }

    /**
     * The PEM a client needs to trust Floci. With the default configuration that is the local root
     * CA alone: it signs the HTTPS certificate and every certificate Floci issues. With a
     * user-provided certificate ({@code floci.tls.cert-path}) the HTTPS endpoint is signed by that
     * certificate's issuer instead, so the response is a bundle: the certificates in the user
     * certificate file first (never a private key that file may also hold), then the local CA,
     * which still signs what Floci itself issues. Trust the file once ({@code AWS_CA_BUNDLE},
     * {@code NODE_EXTRA_CA_CERTS}, a keychain) and both validate.
     */
    @GET
    @Path("/ca.pem")
    @Produces(MediaType.TEXT_PLAIN)
    public String caPem() throws IOException, GeneralSecurityException {
        String localCa = certificateAuthority.caPem();
        Optional<String> userCertificate = userCertificatePath();
        if (userCertificate.isEmpty()) {
            return localCa;
        }
        return ContainerCaBundle.certificatePem(Files.readString(java.nio.file.Path.of(userCertificate.get()))) + localCa;
    }

    /** Set when TLS serves a user-provided certificate, the same condition {@code TlsConfigSource} applies. */
    private Optional<String> userCertificatePath() {
        if (!config.tls().enabled() || config.tls().keyPath().filter(p -> !p.isBlank()).isEmpty()) {
            return Optional.empty();
        }
        return config.tls().certPath().filter(p -> !p.isBlank());
    }

    @POST
    @Path("/state/reset")
    public Response reset() {
        performReset();
        return Response.ok(Map.of("status", "OK")).build();
    }

    @POST
    @Path("/state/nuke")
    public Response nuke() {
        return reset();
    }

    private synchronized void performReset() {
        // Resolve live instances before taking any storage locks. Serialize resets so one reset
        // cannot resume a publisher while another is still wiping its storage.
        List<Resettable> services = new ArrayList<>();
        for (Resettable service : resettables) {
            services.add(service);
        }
        // Every service may refuse before the first destructive step, so a refusal changes nothing.
        for (Resettable service : services) {
            service.checkReset();
        }
        // Containers next: they are tracked independently of StorageBackend, so this can run
        // in any order relative to the storage wipe below, but stopping them here means a
        // client's reset actually reflects a clean slate instead of leaving Batch, CodeBuild,
        // or SageMaker containers running with no record of them left in the store.
        ContainerTeardowns.stopAll(containerTeardowns, LOG);
        RuntimeException failure = null;
        try {
            for (Resettable service : services) {
                service.beforeReset();
            }
            // Storage still precedes clear(): services recreate their bootstrap state there.
            storageFactory.clearAll();
            for (Resettable service : services) {
                service.clear();
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            // Every service, not only those whose beforeReset() ran: the teardowns above already
            // shut down the pools that afterReset() restores, and a beforeReset() that throws
            // would otherwise leave every later service with its pool terminated for good.
            for (Resettable service : services.reversed()) {
                try {
                    service.afterReset();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}

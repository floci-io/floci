package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.lifecycle.inithook.InitializationHook;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.POST;

import java.io.IOException;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Path("{prefix:(_floci|_localstack)}")
@Produces(MediaType.APPLICATION_JSON)
public class EmulatorInfoController {

    private final ServiceRegistry serviceRegistry;
    private final InitLifecycleState initLifecycleState;
    private final String version;

    private final StorageFactory storageFactory;
    private final Instance<Resettable> resettables;
    private final FlociCertificateAuthority certificateAuthority;
    private final EmulatorConfig config;

    @Inject
    public EmulatorInfoController(ServiceRegistry serviceRegistry,
                                  InitLifecycleState initLifecycleState,
                                  StorageFactory storageFactory,
                                  Instance<Resettable> resettables,
                                  FlociCertificateAuthority certificateAuthority,
                                  EmulatorConfig config) {
        this.serviceRegistry = serviceRegistry;
        this.initLifecycleState = initLifecycleState;
        this.storageFactory = storageFactory;
        this.resettables = resettables;
        this.certificateAuthority = certificateAuthority;
        this.config = config;
        this.version = resolveVersion();
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

    @GET
    @Path("/info")
    public Response info() {
        return Response.ok(Map.of("version", version, "edition", "community", "original_edition", "floci-always-free")).build();
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

    private void performReset() {
        for (Resettable r : resettables) {
            r.clear();
        }
        storageFactory.clearAll();
    }

    static String resolveVersion() {
        String env = System.getenv("FLOCI_VERSION");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "dev";
    }
}

package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.launcher.ContainerLauncher;
import io.github.hectorvent.floci.services.lambda.launcher.ImageResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LambdaImageConfigTest {

    private static final String REGION = "us-east-1";

    private LambdaService service;

    @BeforeEach
    void setUp() {
        LambdaFunctionStore store = new LambdaFunctionStore(new InMemoryStorage<String, LambdaFunction>());
        WarmPool warmPool = new WarmPool();
        CodeStore codeStore = new CodeStore(Path.of("target/test-data/lambda-code"));
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new LambdaService(store, warmPool, codeStore, new ZipExtractor(), regionResolver);
    }

    // -------------------------------------------------------------------------
    // LambdaService — createFunction
    // -------------------------------------------------------------------------

    @Nested
    class CreateFunction {

        @Test
        void storesImageConfigCommand() {
            Map<String, Object> req = imageRequest("fn-cmd");
            req.put("ImageConfig", Map.of("Command", List.of("app.handler")));

            LambdaFunction fn = service.createFunction(REGION, req);

            assertEquals(List.of("app.handler"), fn.getImageConfigCommand());
            assertNull(fn.getImageConfigEntryPoint());
        }

        @Test
        void storesImageConfigEntryPoint() {
            Map<String, Object> req = imageRequest("fn-ep");
            req.put("ImageConfig", Map.of("EntryPoint", List.of("/lambda-entrypoint.sh")));

            LambdaFunction fn = service.createFunction(REGION, req);

            assertEquals(List.of("/lambda-entrypoint.sh"), fn.getImageConfigEntryPoint());
            assertNull(fn.getImageConfigCommand());
        }

        @Test
        void storesCommandAndEntryPointTogether() {
            Map<String, Object> req = imageRequest("fn-both");
            req.put("ImageConfig", Map.of(
                    "Command", List.of("app.handler"),
                    "EntryPoint", List.of("/entry.sh")
            ));

            LambdaFunction fn = service.createFunction(REGION, req);

            assertEquals(List.of("app.handler"), fn.getImageConfigCommand());
            assertEquals(List.of("/entry.sh"), fn.getImageConfigEntryPoint());
        }

        @Test
        void noImageConfigLeavesFieldsNull() {
            LambdaFunction fn = service.createFunction(REGION, imageRequest("fn-none"));

            assertNull(fn.getImageConfigCommand());
            assertNull(fn.getImageConfigEntryPoint());
        }
    }

    // -------------------------------------------------------------------------
    // LambdaService — updateFunctionConfiguration
    // -------------------------------------------------------------------------

    @Nested
    class UpdateFunctionConfiguration {

        @Test
        void updatesImageConfigCommand() {
            service.createFunction(REGION, imageRequest("fn-upd-cmd"));

            Map<String, Object> update = new HashMap<>();
            update.put("ImageConfig", Map.of("Command", List.of("new.handler")));
            LambdaFunction fn = service.updateFunctionConfiguration(REGION, "fn-upd-cmd", update);

            assertEquals(List.of("new.handler"), fn.getImageConfigCommand());
        }

        @Test
        void updatesImageConfigEntryPoint() {
            service.createFunction(REGION, imageRequest("fn-upd-ep"));

            Map<String, Object> update = new HashMap<>();
            update.put("ImageConfig", Map.of("EntryPoint", List.of("/new-entry.sh")));
            LambdaFunction fn = service.updateFunctionConfiguration(REGION, "fn-upd-ep", update);

            assertEquals(List.of("/new-entry.sh"), fn.getImageConfigEntryPoint());
        }

        @Test
        void clearsCommandWhenEmptyListProvided() {
            Map<String, Object> req = imageRequest("fn-clear-cmd");
            req.put("ImageConfig", Map.of("Command", List.of("old.handler")));
            service.createFunction(REGION, req);

            Map<String, Object> update = new HashMap<>();
            update.put("ImageConfig", Map.of("Command", List.of()));
            LambdaFunction fn = service.updateFunctionConfiguration(REGION, "fn-clear-cmd", update);

            assertTrue(fn.getImageConfigCommand() == null || fn.getImageConfigCommand().isEmpty());
        }
    }

    // -------------------------------------------------------------------------
    // ContainerLauncher — ContainerSpec built for Image functions
    // -------------------------------------------------------------------------

    @Nested
    @ExtendWith(MockitoExtension.class)
    class ContainerLauncherImageConfig {

        @Mock ContainerLifecycleManager lifecycleManager;
        @Mock ContainerLogStreamer logStreamer;
        @Mock ImageResolver imageResolver;
        @Mock RuntimeApiServerFactory runtimeApiServerFactory;
        @Mock DockerHostResolver dockerHostResolver;
        @Mock EmulatorConfig config;
        @Mock EcrRegistryManager ecrRegistryManager;
        @Mock EmbeddedDnsServer embeddedDnsServer;
        @Mock RuntimeApiServer runtimeApiServer;
        @Mock DockerClient dockerClient;

        ContainerLauncher launcher;

        @BeforeEach
        void setUpLauncher() {
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
            EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);

            when(config.services()).thenReturn(services);
            when(services.lambda()).thenReturn(lambda);
            when(lambda.dockerNetwork()).thenReturn(Optional.empty());
            when(config.docker()).thenReturn(docker);
            when(docker.logMaxSize()).thenReturn("10m");
            when(docker.logMaxFile()).thenReturn("3");
            when(embeddedDnsServer.getServerIp()).thenReturn(Optional.empty());

            ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);
            launcher = new ContainerLauncher(containerBuilder, lifecycleManager, logStreamer, imageResolver,
                    runtimeApiServerFactory, dockerHostResolver, config, ecrRegistryManager);

            when(runtimeApiServerFactory.create()).thenReturn(runtimeApiServer);
            when(runtimeApiServer.getPort()).thenReturn(9000);
            when(dockerHostResolver.resolve()).thenReturn("127.0.0.1");
            when(lifecycleManager.create(any())).thenReturn("container-123");
            when(lifecycleManager.startCreated(eq("container-123"), any()))
                    .thenReturn(new ContainerLifecycleManager.ContainerInfo("container-123", Map.of()));
            when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

            lenient().when(dockerClient.copyArchiveToContainerCmd(any())).thenAnswer(inv -> {
                CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class);
                final java.io.InputStream[] captured = {null};
                when(cmd.withRemotePath(any())).thenReturn(cmd);
                when(cmd.withTarInputStream(any())).thenAnswer(streamInv -> {
                    captured[0] = streamInv.getArgument(0);
                    return cmd;
                });
                doAnswer(execInv -> {
                    if (captured[0] != null) {
                        try { captured[0].transferTo(java.io.OutputStream.nullOutputStream()); }
                        catch (Exception ignored) {}
                    }
                    return null;
                }).when(cmd).exec();
                return cmd;
            });
        }

        @Test
        void usesImageConfigCommandAsCmdForImageFunction() throws Exception {
            LambdaFunction fn = imageFn("img-cmd-fn");
            fn.setImageConfigCommand(List.of("app.handler"));

            launcher.launch(fn);

            ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycleManager).create(specCaptor.capture());

            ContainerSpec spec = specCaptor.getValue();
            assertEquals(List.of("app.handler"), spec.cmd());
            assertNull(spec.entrypoint());
        }

        @Test
        void usesImageConfigEntryPointForImageFunction() throws Exception {
            LambdaFunction fn = imageFn("img-ep-fn");
            fn.setImageConfigEntryPoint(List.of("/lambda-entrypoint.sh"));

            launcher.launch(fn);

            ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycleManager).create(specCaptor.capture());

            ContainerSpec spec = specCaptor.getValue();
            assertEquals(List.of("/lambda-entrypoint.sh"), spec.entrypoint());
        }

        @Test
        void doesNotSetCmdWhenNoImageConfigCommand() throws Exception {
            LambdaFunction fn = imageFn("img-no-cmd-fn");

            launcher.launch(fn);

            ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycleManager).create(specCaptor.capture());

            ContainerSpec spec = specCaptor.getValue();
            assertNull(spec.cmd(), "CMD should not be set when ImageConfig.Command is absent");
            assertNull(spec.entrypoint());
        }

        @Test
        void zipFunctionStillUsesHandlerAsCmd() throws Exception {
            LambdaFunction fn = new LambdaFunction();
            fn.setFunctionName("zip-fn");
            fn.setRuntime("nodejs20.x");
            fn.setHandler("index.handler");
            fn.setPackageType("Zip");
            when(imageResolver.resolve("nodejs20.x")).thenReturn("public.ecr.aws/lambda/nodejs:20");

            launcher.launch(fn);

            ArgumentCaptor<ContainerSpec> specCaptor = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycleManager).create(specCaptor.capture());

            assertEquals(List.of("index.handler"), specCaptor.getValue().cmd());
        }

        private LambdaFunction imageFn(String name) {
            LambdaFunction fn = new LambdaFunction();
            fn.setFunctionName(name);
            fn.setPackageType("Image");
            fn.setImageUri("localhost/my-image:latest");
            return fn;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> imageRequest(String name) {
        Map<String, Object> req = new HashMap<>();
        req.put("FunctionName", name);
        req.put("PackageType", "Image");
        req.put("Role", "arn:aws:iam::000000000000:role/test-role");
        req.put("Code", Map.of("ImageUri", "123456789012.dkr.ecr.us-east-1.amazonaws.com/my-repo:latest"));
        return req;
    }
}

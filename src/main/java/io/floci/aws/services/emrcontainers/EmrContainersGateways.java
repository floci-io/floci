package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.emrserverless.EmrServerlessJobRunService;
import io.floci.aws.services.emrserverless.SessionServers;
import io.floci.aws.services.emrserverless.SparkJobRunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What runs a managed endpoint's Jupyter Enterprise Gateway in the docker runner mode. "The interactive endpoint is
 * backed by a Jupyter Enterprise Gateway that provides the remote kernel lifecycle management capability that
 * interactive clients need", with the kernels "Python 3" and "PySpark on Kubernetes" (EMR on EKS Development Guide,
 * "Overview of interactive endpoints"), which run in the release's {@code notebook-spark} image ("the base image URI
 * in your Docker file must use notebook-spark in place of spark", "Custom kernel image with interactive endpoint").
 * That image holds jupyter_server and ipykernel but no Enterprise Gateway, so a Python program of Floci's own serves
 * the gateway's kernels API on jupyter_server in a container of it, its kernels in the same container: the image's
 * {@code python3} kernel, and a {@code spark-python-kubernetes} one, the name the guide's
 * {@code jupyter-kernel-overrides} example gives the PySpark kernel, whose {@code spark} and {@code sc} are a Spark
 * session in Spark's local mode, on Floci's S3, with the endpoint's execution role's credentials.
 *
 * <p>As Jupyter Enterprise Gateway does, a kernel gets the {@code KERNEL_} variables of the {@code env} its start
 * request carries, as a Jupyter client sends those of its own environment: {@code KERNEL_EXTRA_SPARK_OPTS}, "additional
 * Spark configurations for the Spark kernels" ("Example: Deploy a JupyterLab notebook"), goes to the PySpark kernel's
 * {@code spark-submit}. jupyter_server alone starts a kernel with its name and path only.
 *
 * <p>ListKernels is "Request that the interactive endpoint list the kernels that have been previously started using
 * the same session token" ("Monitoring interactive endpoints"), so the program lists only the kernels started with
 * the session token of the request asking, which it knows by the digest of it Floci passes in
 * {@link #SESSION_HEADER}, whether the kernels API or the sessions API started them. It lists sessions the same way,
 * as a session holds its kernel's model.
 */
final class EmrContainersGateways {

    /** The image a gateway runs in, {@code %s} standing for its release's container image tag. */
    static final String DEFAULT_IMAGE_TEMPLATE =
            "public.ecr.aws/emr-on-eks/notebook-spark/%s"; // partition-literal: ECR Public, where AWS publishes them
    /** The port the gateway listens on in its container. */
    static final int PORT = 8888;
    // Where the program goes in the container, from the variable it comes in.
    static final String PROGRAM_VARIABLE = "FLOCI_GATEWAY";
    /** The header in which the gateway gets a digest of the session token a request carries. */
    static final String SESSION_HEADER = "X-Floci-Session";
    private static final String HOME = "/tmp/floci-gateway";
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
    // The image's spark-defaults.conf turns it on, and spark-submit refuses it outside YARN and Kubernetes.
    static final String PYSPARK_VIRTUALENV = "spark.pyspark.virtualenv.enabled";
    // The runner's spark-defaults name local mode first, and an endpoint's would come after them.
    static final String MASTER = "spark.master";
    // S3A, which Floci's s3:// paths go through, runs on the AWS SDK v2. The image holds it, but its
    // spark-defaults.conf leaves it off the driver's class path, and Spark's launcher adds this variable's entries
    // after that class path.
    static final String CLASS_PATH_VARIABLE = "SPARK_DIST_CLASSPATH";
    static final String AWS_SDK = "/usr/share/aws/aws-java-sdk-v2/*";

    /** The servers' kind, in the answers to the calls they cannot take. */
    static final SessionServers.Kind KIND = new SessionServers.Kind("Jupyter Enterprise Gateway", "Endpoint",
            "GetManagedEndpointSessionCredentials");

    // Python 3.9's, the image's; jupyter_server 2's kernels and sessions APIs, their handlers replaced by ones that
    // pass on the env and list by session token, and its kernel manager by one that records each kernel's token.
    static final String PROGRAM = """
            import contextvars
            import json
            import os
            import sys

            from jupyter_client.jsonutil import json_default
            from jupyter_core.utils import ensure_async
            from jupyter_server.auth.decorator import authorized
            from jupyter_server.serverapp import ServerApp
            from jupyter_server.services.kernels import handlers as kernel_handlers
            from jupyter_server.services.kernels.kernelmanager import AsyncMappingKernelManager
            from jupyter_server.services.sessions import handlers as session_handlers
            from jupyter_server.utils import url_escape, url_path_join
            from tornado import web

            HOME = "@HOME@"
            SESSION_HEADER = "@SESSION_HEADER@"
            # The session token of the request being served, by the digest of it Floci passes on.
            SESSION = contextvars.ContextVar("session", default=None)
            # The session token each running kernel was started with, by that digest.
            STARTED_WITH = {}

            PYSPARK_KERNEL = '''
            import sys

            from ipykernel.kernelapp import IPKernelApp
            from pyspark.sql import SparkSession

            spark = SparkSession.builder.getOrCreate()
            IPKernelApp.launch_instance(argv=sys.argv[1:], user_ns={"spark": spark, "sc": spark.sparkContext})
            '''


            def write(path, text):
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w") as out:
                    out.write(text)


            write(HOME + "/pyspark_kernel.py", PYSPARK_KERNEL)
            write(HOME + "/kernels/spark-python-kubernetes/kernel.json", json.dumps({
                "display_name": "PySpark on Kubernetes",
                "language": "python",
                "argv": ["/bin/bash", "-c",
                         'exec "$SPARK_HOME/bin/spark-submit" $KERNEL_EXTRA_SPARK_OPTS ' + HOME
                         + '/pyspark_kernel.py -f "$0"',
                         "{connection_file}"],
            }))


            # Whether the request being served carries the session token the kernel was started with.
            def mine(kernel_id):
                return SESSION.get() is not None and STARTED_WITH.get(kernel_id) == SESSION.get()


            # Records the session token of a kernel the kernels API or the sessions API starts, and forgets it once
            # the kernel goes, whether a request, the culler or the kernel's death removes it.
            class KernelManager(AsyncMappingKernelManager):

                async def start_kernel(self, *, kernel_id=None, **kwargs):
                    starts = kernel_id is None or kernel_id not in self
                    kernel_id = await super().start_kernel(kernel_id=kernel_id, **kwargs)
                    if starts:
                        STARTED_WITH[kernel_id] = SESSION.get()
                    return kernel_id

                def remove_kernel(self, kernel_id):
                    STARTED_WITH.pop(kernel_id, None)
                    return super().remove_kernel(kernel_id)


            class WithSession:

                def prepare(self, *args, **kwargs):
                    SESSION.set(self.request.headers.get(SESSION_HEADER))
                    return super().prepare(*args, **kwargs)


            class MainKernelHandler(WithSession, kernel_handlers.MainKernelHandler):

                @web.authenticated
                @authorized
                async def get(self):
                    kernels = await ensure_async(self.kernel_manager.list_kernels())
                    self.finish(json.dumps([kernel for kernel in kernels if mine(kernel["id"])], default=json_default))

                @web.authenticated
                @authorized
                async def post(self):
                    km = self.kernel_manager
                    model = self.get_json_body() or {}
                    env = dict(os.environ)
                    env.pop("@PROGRAM_VARIABLE@", None)
                    env.update({key: str(value) for key, value in (model.get("env") or {}).items()
                                if key.startswith("KERNEL_")})
                    kernel_id = await ensure_async(km.start_kernel(
                        kernel_name=model.get("name") or km.default_kernel_name, path=model.get("path"), env=env))
                    kernel = await ensure_async(km.kernel_model(kernel_id))
                    self.set_header("Location", url_path_join(self.base_url, "api", "kernels", url_escape(kernel_id)))
                    self.set_status(201)
                    self.finish(json.dumps(kernel, default=json_default))


            # Sessions list as kernels do: a session holds its kernel's model.
            class SessionRootHandler(WithSession, session_handlers.SessionRootHandler):

                @web.authenticated
                @authorized
                async def get(self):
                    sessions = await ensure_async(self.session_manager.list_sessions())
                    self.finish(json.dumps([session for session in sessions if mine(session["kernel"]["id"])],
                                           default=json_default))


            # Its PATCH starts a kernel when it names a kernel spec.
            class SessionHandler(WithSession, session_handlers.SessionHandler):
                pass


            REPLACED = {kernel_handlers.MainKernelHandler: MainKernelHandler,
                        session_handlers.SessionRootHandler: SessionRootHandler,
                        session_handlers.SessionHandler: SessionHandler}
            for module in (kernel_handlers, session_handlers):
                module.default_handlers = [(path, REPLACED.get(handler, handler))
                                           for path, handler in module.default_handlers]
            os.environ["JUPYTER_PATH"] = HOME
            sys.exit(ServerApp.launch_instance(argv=[
                "--ServerApp.ip=0.0.0.0", "--ServerApp.port=@PORT@", "--ServerApp.port_retries=0",
                "--ServerApp.open_browser=False", "--ServerApp.allow_origin=*", "--ServerApp.root_dir=/home/hadoop",
                "--IdentityProvider.token=", "--ServerApp.disable_check_xsrf=True",
                "--ServerApp.allow_remote_access=True",
                "--KernelSpecManager.allowed_kernelspecs=python3",
                "--KernelSpecManager.allowed_kernelspecs=spark-python-kubernetes",
            ], kernel_manager_class=KernelManager))
            """.replace("@HOME@", HOME).replace("@PORT@", String.valueOf(PORT))
            .replace("@PROGRAM_VARIABLE@", PROGRAM_VARIABLE).replace("@SESSION_HEADER@", SESSION_HEADER);

    private EmrContainersGateways() {
    }

    /** The gateway's image for a release label: {@code imageTemplate}'s, else {@link #DEFAULT_IMAGE_TEMPLATE}'s. */
    static String image(String imageTemplate, String releaseLabel) {
        return String.format(imageTemplate != null ? imageTemplate : DEFAULT_IMAGE_TEMPLATE,
                EmrContainersSparkJobs.containerImageTag(releaseLabel));
    }

    /**
     * What runs an endpoint's gateway: its program, written out from its variable, in the endpoint's release's image,
     * on {@code dockerNetwork}, with the endpoint's execution role and the {@code spark-defaults} of its
     * {@code configurationOverrides}, listening on {@link #PORT}. Its kernels' {@code spark-submit} gets those
     * defaults, in Spark's local mode and client deploy mode whatever master and deploy mode they name, as a job run's
     * driver does, on the image's Python: the virtualenv the image gives a PySpark driver is made on YARN and
     * Kubernetes alone. Their class path gains the AWS SDK that S3A runs on, which the image's own leaves out. As a
     * Spark Connect server does, it checks no token
     * itself: Floci reaches it on the Docker host's loopback interface alone, and checks the session token first.
     */
    static SparkJobRunner.Job job(ObjectNode endpoint, String imageTemplate, String dockerNetwork) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(endpoint.get("arn").textValue());
        String releaseLabel = endpoint.get("releaseLabel").textValue();
        Map<String, String> sparkDefaults =
                new LinkedHashMap<>(EmrContainersSparkJobs.sparkDefaults(endpoint.get("configurationOverrides")));
        sparkDefaults.remove(MASTER);
        sparkDefaults.put(EmrContainersSparkJobs.DEPLOY_MODE, EmrContainersSparkJobs.CLIENT);
        sparkDefaults.put(PYSPARK_VIRTUALENV, "false");
        Map<String, String> environment = new LinkedHashMap<>(EmrServerlessJobRunService.environment(sparkDefaults,
                "spark.kubernetes.driverEnv."));
        environment.merge(CLASS_PATH_VARIABLE, AWS_SDK, (endpointClassPath, sdk) -> endpointClassPath + ":" + sdk);
        environment.put(PROGRAM_VARIABLE, PROGRAM);
        List<String> command = List.of("/bin/bash", "-c", "mkdir -p " + HOME + " && printf '%s' \"$"
                + PROGRAM_VARIABLE + "\" > " + HOME + "/gateway.py && exec python3 " + HOME + "/gateway.py");
        return new SparkJobRunner.Job(EmrContainersService.SERVICE, endpoint.get("id").textValue(), arn.accountId(),
                arn.region(), releaseLabel, SparkJobRunner.Engine.SPARK, image(imageTemplate, releaseLabel), null,
                dockerNetwork, endpoint.get("executionRoleArn").textValue(), sparkDefaults, List.of(), command,
                Duration.ZERO, null, null, environment, List.of(), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null).listeningOn(PORT);
    }

    /**
     * Why no gateway answers at {@code host} and {@code port}, or null once one does: any HTTP answer to its API's
     * root says jupyter_server runs. A port Docker publishes takes a connection before the server in the container
     * listens, so a connection alone says nothing.
     */
    static String unanswered(HttpClient client, String host, int port) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/api"))
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build();
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return null;
        } catch (IOException e) {
            return e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}

package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What runs a managed endpoint's Jupyter Enterprise Gateway in the docker runner mode. "The interactive endpoint is
 * backed by a Jupyter Enterprise Gateway that provides the remote kernel lifecycle management capability that
 * interactive clients need", with the kernels "Python 3", "PySpark on Kubernetes" and "Apache Spark with Scala" (EMR
 * on EKS Development Guide, "Overview of interactive endpoints"), which run in the release's {@code notebook-spark}
 * image ("the base image URI in your Docker file must use notebook-spark in place of spark", "Custom kernel image with
 * interactive endpoint"). That image holds jupyter_server and ipykernel but no Enterprise Gateway, so a Python
 * program of Floci's own serves the gateway's kernels API on jupyter_server in a container of it, its kernels in the
 * same container: the image's {@code python3} kernel; a {@code spark-python-kubernetes} one, the name the guide's
 * {@code jupyter-kernel-overrides} example gives the PySpark kernel; and a {@code spark-scala-kubernetes} one, a name
 * of Floci's beside it, as the guide gives the Scala kernel none, which runs the image's Apache Toree, beside Jupyter
 * Enterprise Gateway's Scala launcher. The Spark kernels' {@code spark} and {@code sc} are a Spark session in Spark's
 * local mode, on Floci's S3, with the endpoint's execution role's credentials.
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
 *
 * <p>The PySpark kernel loads the image's {@code %%configure} magic ({@code configure_magic.py}, beside Jupyter
 * Enterprise Gateway's kernel launchers), with which "you can adjust the Spark configuration associated with a
 * PySpark session" ("Modifying PySpark session parameters"). The magic turns its cell's settings into
 * {@code KERNEL_EXTRA_SPARK_OPTS} and posts them, signed with the AES key the program gives its kernel, to its own
 * host's port 18888 when no {@code ENDPOINT_IP} names its gateway. The program serves that port in the container
 * alone: it restarts the kernel, keeping its id, with those options in place of the ones it started with, and, once
 * the restarted kernel answers, ends the cell with the replies the magic prepared, as the restart takes the kernel
 * down before it can; a kernel that dies first, or does not answer in jupyter_server's time for a restart, ends it
 * with an error. A kernel's requests take their turn.
 *
 * <p>A kernel the endpoint's {@code jupyter-kernel-overrides} gives an image of its own runs in a container of that
 * image, as on EMR on EKS each kernel is a pod of its image: the program's provisioner asks Floci for it
 * ({@link EmrContainersKernelContainersRoute}), and the image's Jupyter Enterprise Gateway launcher sends back where
 * the kernel listens, encrypted for the provisioner's key, and takes its signals on its comm port. Its
 * {@code %%configure} magic posts to the program's port 9547 over TLS, as its {@code ENDPOINT_IP} says; the restart
 * gives the kernel new ports and a new key, which the program moves the kernel's clients onto.
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
    // The classification of Jupyter Enterprise Gateway's settings, and those the guide allow-lists ("Jupyter
    // Enterprise Gateway (JEG) configuration options"), which go to the program as its arguments.
    static final String JEG_CONFIG = "jeg-config";
    static final List<String> JEG_PROPERTIES = List.of("RemoteMappingKernelManager.cull_idle_timeout",
            "RemoteMappingKernelManager.cull_interval");
    // The classification that gives a kernel an image of its own, a configuration per kernel naming it, its
    // container-image property the image ("Custom kernel image with interactive endpoint"); the kernels its example
    // names, as "You can't add new kernel image types".
    static final String KERNEL_OVERRIDES = "jupyter-kernel-overrides";
    static final String CONTAINER_IMAGE = "container-image";
    static final String PYTHON_KERNEL = "python3";
    static final String PYSPARK_KERNEL = "spark-python-kubernetes";
    static final List<String> OVERRIDABLE_KERNELS = List.of(PYTHON_KERNEL, PYSPARK_KERNEL);
    // Where the program learns which kernels run in containers of their own, and the secret it asks Floci for them
    // with, in SECRET_HEADER.
    static final String OVERRIDES_VARIABLE = "FLOCI_KERNEL_OVERRIDES";
    static final String SECRET_VARIABLE = "FLOCI_KERNEL_CONTAINERS_SECRET";
    static final String KERNEL_CONTAINERS_VARIABLE = "FLOCI_KERNEL_CONTAINERS_PATH";
    static final String SECRET_HEADER = "X-Floci-Kernel-Containers-Secret";
    // Jupyter Enterprise Gateway's Python kernel launcher, in the image, which a kernel's own container runs.
    static final String LAUNCHER = "/usr/local/bin/kernel-launchers/python/scripts/launch_ipykernel.py";
    // What the launcher's additional-info says to load the %%configure magic.
    private static final String CONFIGURE_MAGIC_ENABLED = Base64.getEncoder()
            .encodeToString("{\"configure_magic_enabled\": \"true\"}".getBytes(StandardCharsets.UTF_8));

    /** The servers' kind, in the answers to the calls they cannot take. */
    static final SessionServers.Kind KIND = new SessionServers.Kind("Jupyter Enterprise Gateway", "Endpoint",
            "GetManagedEndpointSessionCredentials");

    // Python 3.9's, the image's; jupyter_server 2's kernels and sessions APIs, their handlers replaced by ones that
    // pass on the env and list by session token, its kernel manager by one that records each kernel's token and
    // launch, and beside it the %%configure magic's endpoint.
    static final String PROGRAM = """
            import asyncio
            import base64
            import contextvars
            import json
            import os
            import secrets
            import signal
            import socket
            import ssl
            import subprocess
            import sys
            import uuid
            from importlib.metadata import EntryPoint

            from Cryptodome.Cipher import AES, PKCS1_v1_5
            from Cryptodome.Hash import SHA256
            from Cryptodome.PublicKey import RSA
            from Cryptodome.Util.Padding import unpad
            from jupyter_client.jsonutil import json_default
            from jupyter_client.kernelspec import KernelSpecManager
            from jupyter_client.provisioning import KernelProvisionerBase, KernelProvisionerFactory
            from jupyter_core.utils import ensure_async
            from jupyter_server.auth.decorator import authorized
            from jupyter_server.serverapp import ServerApp
            from jupyter_server.services.kernels import handlers as kernel_handlers
            from jupyter_server.services.kernels.connection.base import serialize_msg_to_ws_v1
            from jupyter_server.services.kernels.connection.channels import ZMQChannelsWebsocketConnection
            from jupyter_server.services.kernels.kernelmanager import AsyncMappingKernelManager
            from jupyter_server.services.sessions import handlers as session_handlers
            from jupyter_server.utils import url_escape, url_path_join
            from tornado import httpserver, web
            from tornado.httpclient import AsyncHTTPClient, HTTPRequest
            from tornado.websocket import WebSocketClosedError
            from traitlets import Unicode

            HOME = "@HOME@"
            # The program's kernel specs come first, for the server and for the overrides' lookups alike.
            os.environ["JUPYTER_PATH"] = HOME
            SESSION_HEADER = "@SESSION_HEADER@"
            # The image's %%configure magic, beside Jupyter Enterprise Gateway's kernel launchers.
            MAGICS = "/usr/local/bin/kernel-launchers/python/scripts"
            # The image's Apache Toree, which Jupyter Enterprise Gateway's Scala launcher runs.
            TOREE = "/usr/local/bin/kernel-launchers/scala/lib/toree-assembly-*.jar"
            # Where the magic posts its cell's settings when no ENDPOINT_IP names its gateway: its own host's port.
            CONFIGURE_PORT = 18888
            # The magic's signature of a post: the SHA-256 of its body, encrypted with its kernel's AES key.
            FINGERPRINT_HEADER = "EMR-Containers-FPAuthorization"
            V1_PROTOCOL = "v1.kernel.websocket.jupyter.org"
            # The session token of the request being served, by the digest of it Floci passes on.
            SESSION = contextvars.ContextVar("session", default=None)
            # The session token each running kernel was started with, by that digest.
            STARTED_WITH = {}
            # Each running kernel's launch.
            LAUNCHED = {}
            # The kernels the endpoint's jupyter-kernel-overrides gives images of their own, each in a container Floci
            # runs, where and with what secret the program asks Floci for those, and how long it waits for one: what
            # KERNEL_LAUNCH_TIMEOUT says, at most its maximum, 400 seconds.
            OVERRIDDEN = [kernel for kernel in os.environ.get("@OVERRIDES_VARIABLE@", "").split(",") if kernel]
            KERNEL_CONTAINERS = (os.environ.get("AWS_ENDPOINT_URL", "").rstrip("/")
                                 + os.environ.get("@KERNEL_CONTAINERS_VARIABLE@", ""))
            KERNEL_CONTAINERS_SECRET = os.environ.pop("@SECRET_VARIABLE@", None)
            SECRET_HEADER = "@SECRET_HEADER@"
            LAUNCH_TIMEOUT = 400
            # Where the magic of a kernel in a container of its own posts its cell's settings: its ENDPOINT_IP, the
            # program's address, on this port, over TLS.
            CONFIGURE_TLS_PORT = 9547
            CONTAINER_PROVISIONER = "floci-kernel-container"

            PYSPARK_KERNEL = '''
            import sys

            from ipykernel.kernelapp import IPKernelApp
            from pyspark.sql import SparkSession

            spark = SparkSession.builder.getOrCreate()
            IPKernelApp.launch_instance(argv=sys.argv[1:], user_ns={"spark": spark, "sc": spark.sparkContext},
                                        extensions=["configure_magic"])
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
                "env": {"PYTHONPATH": MAGICS},
            }))
            # As Toree's own kernel spec starts it, its connection file its profile.
            write(HOME + "/kernels/spark-scala-kubernetes/kernel.json", json.dumps({
                "display_name": "Apache Spark with Scala",
                "language": "scala",
                "argv": ["/bin/bash", "-c",
                         'exec "$SPARK_HOME/bin/spark-submit" $KERNEL_EXTRA_SPARK_OPTS --class org.apache.toree.Main '
                         + TOREE + ' --profile "$0"',
                         "{connection_file}"],
            }))
            # A kernel the overrides give an image of its own runs in a container Floci starts, through the provisioner.
            for kernel in OVERRIDDEN:
                spec = KernelSpecManager().get_kernel_spec(kernel).to_dict()
                spec["metadata"] = dict(spec.get("metadata") or {}, kernel_provisioner={
                    "provisioner_name": CONTAINER_PROVISIONER, "config": {"kernel": kernel}})
                write(HOME + "/kernels/" + kernel + "/kernel.json", json.dumps(spec))


            # Whether the request being served carries the session token the kernel was started with.
            def mine(kernel_id):
                return SESSION.get() is not None and STARTED_WITH.get(kernel_id) == SESSION.get()


            # Whether fingerprint is the magic's signature of body with key.
            def signed(key, body, fingerprint):
                try:
                    encrypted, tag, nonce = (base64.b64decode(part) for part in fingerprint.split(" "))
                    cipher = AES.new(bytes.fromhex(key), AES.MODE_GCM, nonce=nonce)
                    return unpad(cipher.decrypt_and_verify(encrypted, tag), 16) == SHA256.new(body).digest()
                except ValueError:
                    return False


            # Sends the messages to each client connected to the kernel, as jupyter_server sends its own status ones.
            def send(kernel_id, messages):
                for connection in list(ZMQChannelsWebsocketConnection._open_sockets):
                    if connection.kernel_id != kernel_id:
                        continue
                    for message in messages:
                        try:
                            if connection.subprotocol == V1_PROTOCOL:
                                connection.write_message(serialize_msg_to_ws_v1(message, message["channel"],
                                                                                connection.session.pack), binary=True)
                            else:
                                connection.write_message(json.dumps(message, default=json_default))
                        except WebSocketClosedError:
                            break


            # The replies that end the cell with reason, in place of the ones the magic prepared for its success.
            def failed(replies, reason):
                error, reply, idle = (replies.get(name) for name in ("error_reply", "exec_reply", "idle_reply"))
                if error:
                    error["content"]["evalue"] = reason
                if reply:
                    reply["content"].update({"status": "error", "ename": "MagicExecutionError", "evalue": reason,
                                             "traceback": []})
                return [message for message in (error, reply, idle) if message]


            # Asks Floci for a kernel's container, or for its end.
            async def kernel_container(method, kernel_id, body=None):
                await AsyncHTTPClient().fetch(HTTPRequest(
                    KERNEL_CONTAINERS + kernel_id, method=method, body=None if body is None else json.dumps(body),
                    headers={SECRET_HEADER: KERNEL_CONTAINERS_SECRET, "Content-Type": "application/json"},
                    allow_nonstandard_methods=True, request_timeout=60))


            # A kernel in a container of its own, of the image the overrides give it, which Floci starts at the
            # program's request, running Jupyter Enterprise Gateway's launcher: the launcher sends where the kernel
            # listens, encrypted for the program's key, and takes the program's signals on its comm port.
            class ContainerProvisioner(KernelProvisionerBase):

                kernel = Unicode(config=True)

                def __init__(self, **kwargs):
                    super().__init__(**kwargs)
                    self.comm = None
                    self.aes_key = None

                @property
                def has_process(self):
                    return self.comm is not None

                async def pre_launch(self, **kwargs):
                    kwargs = await super().pre_launch(**kwargs)
                    kwargs["cmd"] = []
                    return kwargs

                async def launch_kernel(self, cmd, **kwargs):
                    env = kwargs.get("env") or {}
                    key = RSA.generate(2048)
                    response = asyncio.get_running_loop().create_future()

                    async def respond(reader, writer):
                        data = await reader.read()
                        writer.close()
                        if not response.done():
                            response.set_result((data, writer.get_extra_info("peername")[0]))

                    server = await asyncio.start_server(respond, "0.0.0.0", 0)
                    try:
                        await kernel_container("POST", self.kernel_id, {
                            "kernel": self.kernel,
                            "responseAddress": socket.gethostbyname(socket.gethostname()) + ":"
                                               + str(server.sockets[0].getsockname()[1]),
                            "publicKey": base64.b64encode(key.publickey().export_key("DER")).decode(),
                            "env": {name: value for name, value in env.items() if name.startswith("KERNEL_")}})
                        timeout = min(int(env.get("KERNEL_LAUNCH_TIMEOUT") or LAUNCH_TIMEOUT), LAUNCH_TIMEOUT)
                        data, host = await asyncio.wait_for(response, timeout)
                        payload = json.loads(base64.b64decode(data))
                        aes_key = PKCS1_v1_5.new(key).decrypt(base64.b64decode(payload["key"]), None)
                        cipher = AES.new(aes_key, AES.MODE_GCM, nonce=base64.b64decode(payload["nonce"]))
                        info = json.loads(unpad(cipher.decrypt_and_verify(base64.b64decode(payload["conn_info"]),
                                                                          base64.b64decode(payload["tag"])), 16))
                        connection_info = {
                            "ip": host, "transport": info.get("transport", "tcp"), "key": info["key"].encode(),
                            "signature_scheme": info["signature_scheme"], "kernel_name": self.kernel,
                            **{name: int(info[name]) for name in ("shell_port", "iopub_port", "stdin_port",
                                                                  "hb_port", "control_port")}}
                        comm = (host, int(info["comm_port"]))
                    except BaseException:
                        # A launch that fails after Floci was asked for the container stops it.
                        await self.cleanup()
                        raise
                    finally:
                        server.close()
                    self.aes_key = aes_key.hex()
                    self.comm = comm
                    self.connection_info = connection_info
                    # Each launch's launcher, a restart's too, gives its magic a key of its own.
                    launch = LAUNCHED.get(self.kernel_id)
                    if launch is not None:
                        launch.key = self.aes_key
                    return self.connection_info

                # Sends the launcher a request on its comm port; false where it takes none.
                async def tell(self, request):
                    if self.comm is None:
                        return False
                    try:
                        reader, writer = await asyncio.wait_for(asyncio.open_connection(*self.comm), 5)
                        writer.write(json.dumps(request).encode())
                        await writer.drain()
                        writer.close()
                        await writer.wait_closed()
                        return True
                    except (OSError, asyncio.TimeoutError):
                        return False

                async def poll(self):
                    return None if await self.tell({"signum": 0}) else 1

                async def wait(self):
                    for _ in range(50):
                        if await self.poll() is not None:
                            return 1
                        await asyncio.sleep(0.2)
                    return None

                async def send_signal(self, signum):
                    await self.tell({"signum": signum})

                async def kill(self, restart=False):
                    await self.tell({"signum": signal.SIGKILL})
                    await self.cleanup(restart)

                async def terminate(self, restart=False):
                    await self.tell({"signum": signal.SIGTERM})

                # Best effort, as jupyter_client's shutdown and restart go on whatever a provisioner's cleanup
                # meets: a container Floci fails to stop here still stops with the endpoint's gateway.
                async def cleanup(self, restart=False):
                    self.comm = None
                    try:
                        await kernel_container("DELETE", self.kernel_id)
                    except Exception:
                        self.log.exception("Kernel %s's container could not be stopped", self.kernel_id)


            KernelProvisionerFactory.instance().provisioners[CONTAINER_PROVISIONER] = EntryPoint(
                CONTAINER_PROVISIONER, "__main__:ContainerProvisioner", KernelProvisionerFactory.GROUP_NAME)


            # A kernel's AES key, which only it and the program hold, its launch environment, and the lock its
            # %%configure requests take in turn.
            class Launch:

                def __init__(self, key, env):
                    self.key = key
                    self.env = env
                    self.lock = asyncio.Lock()


            # Records the session token of a kernel the kernels API or the sessions API starts, and gives the kernel
            # the KERNEL_ID and the AES key the magic reads, as Jupyter Enterprise Gateway's launcher does; forgets
            # both once the kernel goes, whether a request, the culler or the kernel's death removes it. Named as
            # Jupyter Enterprise Gateway's, whose name the endpoint's jeg-config properties give, from the arguments.
            class RemoteMappingKernelManager(AsyncMappingKernelManager):

                async def start_kernel(self, *, kernel_id=None, **kwargs):
                    if kernel_id is not None and kernel_id in self:
                        return await super().start_kernel(kernel_id=kernel_id, **kwargs)
                    kernel_id = kernel_id or str(uuid.uuid4())
                    key = secrets.token_hex(32)
                    env = dict(kwargs.pop("env", None) or os.environ)
                    env.pop("@PROGRAM_VARIABLE@", None)
                    # The image's launcher sets it to its gateway's address; the magic posts to its own host without.
                    env.pop("ENDPOINT_IP", None)
                    env.update({"KERNEL_ID": kernel_id, "ADDITIONAL_INFO": json.dumps({"AES_KEY": key})})
                    kernel_id = await super().start_kernel(kernel_id=kernel_id, env=env, **kwargs)
                    STARTED_WITH[kernel_id] = SESSION.get()
                    # A launcher in a container of its own gives its magic a key of its own, and its restarts, the
                    # auto-restarter's too, give the kernel new ports and a new key.
                    provisioner = self.get_kernel(kernel_id).provisioner
                    LAUNCHED[kernel_id] = Launch(getattr(provisioner, "aes_key", None) or key, env)
                    if isinstance(provisioner, ContainerProvisioner):
                        self.add_restart_callback(kernel_id, lambda: self.rebind(kernel_id), "restart")
                    return kernel_id

                async def restart_kernel(self, kernel_id, now=False):
                    restarted = await super().restart_kernel(kernel_id, now=now)
                    self.rebind(kernel_id)
                    return restarted

                # A kernel back on new ports, with a new key, as one in a container of its own after a restart: its
                # clients move onto them, as jupyter_server moves a client that connects again, and so does its
                # activity watch.
                def rebind(self, kernel_id):
                    if kernel_id not in self or not self.ports_changed(kernel_id):
                        return
                    self.stop_watching_activity(kernel_id)
                    self.start_watching_activity(kernel_id)
                    kernel = self.get_kernel(kernel_id)
                    for connection in list(ZMQChannelsWebsocketConnection._open_sockets):
                        if connection.kernel_id == kernel_id:
                            for stream in connection.channels.values():
                                if not stream.closed():
                                    stream.close()
                            connection.session.key = kernel.session.key
                            connection.create_stream()
                            for stream in connection.channels.values():
                                stream.on_recv_stream(connection.handle_outgoing_message)

                def remove_kernel(self, kernel_id):
                    STARTED_WITH.pop(kernel_id, None)
                    LAUNCHED.pop(kernel_id, None)
                    return super().remove_kernel(kernel_id)

                # Restarts the kernel, keeping its id, with env over its launch environment, and waits until it
                # answers, within the time jupyter_server gives a kernel it restarts.
                async def configure(self, kernel_id, env):
                    launch = LAUNCHED[kernel_id]
                    launch.env.update(env)
                    kernel = self.get_kernel(kernel_id)
                    await kernel.restart_kernel(now=True, env=launch.env)
                    client = kernel.client()
                    client.start_channels()
                    try:
                        await client.wait_for_ready(timeout=self.kernel_info_timeout)
                    finally:
                        client.stop_channels()
                    self.rebind(kernel_id)


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


            # A kernel's %%configure: the magic posts the env its cell's settings make, signed with the kernel's key,
            # and the replies that end its cell, which the restart keeps the kernel from sending.
            class ConfigureHandler(web.RequestHandler):

                def initialize(self, kernel_manager):
                    self.kernel_manager = kernel_manager

                async def post(self, kernel_id):
                    launch = LAUNCHED.get(kernel_id)
                    if launch is None or not signed(launch.key, self.request.body,
                                                    self.request.headers.get(FINGERPRINT_HEADER, "")):
                        self.set_status(403)
                        self.finish({"message": "The request is not signed by kernel " + kernel_id + "."})
                        return
                    payload = json.loads(self.request.body)
                    replies = payload.get("zmq_messages") or {}
                    async with launch.lock:
                        try:
                            await self.kernel_manager.configure(kernel_id, payload.get("env") or {})
                        except Exception as e:
                            send(kernel_id, failed(replies, str(e)))
                            self.set_status(500)
                            self.finish({"message": str(e)})
                            return
                        send(kernel_id, [replies[name] for name in ("stream_reply", "exec_reply", "idle_reply")
                                         if replies.get(name)])
                    self.finish({})


            class GatewayApp(ServerApp):

                def init_webapp(self):
                    super().init_webapp()
                    configure = web.Application([(r"/api/kernels/configure/([^/]+)", ConfigureHandler,
                                                  {"kernel_manager": self.kernel_manager})])
                    httpserver.HTTPServer(configure).listen(CONFIGURE_PORT, "127.0.0.1")
                    if OVERRIDDEN:
                        # The magic does not check the certificate; the image's openssl makes one.
                        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650",
                                        "-subj", "/CN=floci-gateway", "-keyout", HOME + "/configure.key",
                                        "-out", HOME + "/configure.crt"], check=True, capture_output=True)
                        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                        tls.load_cert_chain(HOME + "/configure.crt", HOME + "/configure.key")
                        httpserver.HTTPServer(configure, ssl_options=tls).listen(CONFIGURE_TLS_PORT, "0.0.0.0")


            REPLACED = {kernel_handlers.MainKernelHandler: MainKernelHandler,
                        session_handlers.SessionRootHandler: SessionRootHandler,
                        session_handlers.SessionHandler: SessionHandler}
            for module in (kernel_handlers, session_handlers):
                module.default_handlers = [(path, REPLACED.get(handler, handler))
                                           for path, handler in module.default_handlers]
            sys.exit(GatewayApp.launch_instance(argv=[
                "--ServerApp.ip=0.0.0.0", "--ServerApp.port=@PORT@", "--ServerApp.port_retries=0",
                "--ServerApp.open_browser=False", "--ServerApp.allow_origin=*", "--ServerApp.root_dir=/home/hadoop",
                "--IdentityProvider.token=", "--ServerApp.disable_check_xsrf=True",
                "--ServerApp.allow_remote_access=True",
                "--KernelSpecManager.allowed_kernelspecs=python3",
                "--KernelSpecManager.allowed_kernelspecs=spark-python-kubernetes",
                "--KernelSpecManager.allowed_kernelspecs=spark-scala-kubernetes",
            ] + sys.argv[1:], kernel_manager_class=RemoteMappingKernelManager))
            """.replace("@HOME@", HOME).replace("@PORT@", String.valueOf(PORT))
            .replace("@PROGRAM_VARIABLE@", PROGRAM_VARIABLE).replace("@SESSION_HEADER@", SESSION_HEADER)
            .replace("@OVERRIDES_VARIABLE@", OVERRIDES_VARIABLE)
            .replace("@KERNEL_CONTAINERS_VARIABLE@", KERNEL_CONTAINERS_VARIABLE)
            .replace("@SECRET_VARIABLE@", SECRET_VARIABLE).replace("@SECRET_HEADER@", SECRET_HEADER);

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
     * Kubernetes alone. Their class path gains the AWS SDK that S3A runs on, which the image's own leaves out. The
     * allow-listed properties of the endpoint's {@code jeg-config} classification go to the program as its arguments,
     * other ones nowhere. Where its {@code jupyter-kernel-overrides} gives kernels images of their own, the program
     * learns their names, and where and with {@code secret} it asks Floci for their containers
     * ({@link #kernelJob}). As a Spark Connect server does, it checks no token itself: Floci reaches it on the Docker
     * host's loopback interface alone, and checks the session token first.
     */
    static SparkJobRunner.Job job(ObjectNode endpoint, String imageTemplate, String dockerNetwork, String secret) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(endpoint.get("arn").textValue());
        String releaseLabel = endpoint.get("releaseLabel").textValue();
        Map<String, String> sparkDefaults = sparkDefaults(endpoint);
        Map<String, String> environment = environment(sparkDefaults);
        environment.put(PROGRAM_VARIABLE, PROGRAM);
        Map<String, String> kernelImages = kernelImages(endpoint);
        if (!kernelImages.isEmpty()) {
            environment.put(OVERRIDES_VARIABLE, String.join(",", kernelImages.keySet()));
            environment.put(KERNEL_CONTAINERS_VARIABLE,
                    EmrContainersKernelContainersRoute.PATH + endpoint.get("id").textValue() + "/");
            environment.put(SECRET_VARIABLE, secret);
        }
        List<String> command = new ArrayList<>(List.of("/bin/bash", "-c", "mkdir -p " + HOME + " && printf '%s' \"$"
                + PROGRAM_VARIABLE + "\" > " + HOME + "/gateway.py && exec python3 " + HOME + "/gateway.py \"$@\"",
                "gateway"));
        Map<String, String> jegConfig =
                EmrContainersSparkJobs.properties(endpoint.get("configurationOverrides"), JEG_CONFIG);
        for (String property : JEG_PROPERTIES) {
            if (jegConfig.containsKey(property)) {
                command.add("--" + property + "=" + jegConfig.get(property));
            }
        }
        return new SparkJobRunner.Job(EmrContainersService.SERVICE, endpoint.get("id").textValue(), arn.accountId(),
                arn.region(), releaseLabel, SparkJobRunner.Engine.SPARK, image(imageTemplate, releaseLabel), null,
                dockerNetwork, endpoint.get("executionRoleArn").textValue(), sparkDefaults, List.of(), command,
                Duration.ZERO, null, null, environment, List.of(), null, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), null).listeningOn(PORT);
    }

    /**
     * What runs kernel {@code kernelId} of an endpoint's gateway in a container of its own, run {@code runId}: the
     * image its {@code jupyter-kernel-overrides} gives the kernel, running Jupyter Enterprise Gateway's launcher there,
     * which sends where the kernel listens to {@code responseAddress}, encrypted for {@code publicKey}, as it does for
     * Jupyter Enterprise Gateway. It has the gateway's settings and environment, with the {@code KERNEL_} variables of
     * {@code kernelEnvironment}; a PySpark kernel's launcher runs under {@code spark-submit}, after
     * {@code KERNEL_EXTRA_SPARK_OPTS}, and loads the {@code %%configure} magic.
     */
    static SparkJobRunner.Job kernelJob(ObjectNode endpoint, String runId, String kernelName, String kernelId,
                                        String responseAddress, String publicKey,
                                        Map<String, String> kernelEnvironment, String imageTemplate,
                                        String dockerNetwork) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(endpoint.get("arn").textValue());
        String releaseLabel = endpoint.get("releaseLabel").textValue();
        Map<String, String> sparkDefaults = sparkDefaults(endpoint);
        Map<String, String> environment = environment(sparkDefaults);
        kernelEnvironment.forEach((name, value) -> {
            if (name.startsWith("KERNEL_")) {
                environment.put(name, value);
            }
        });
        String launch = " " + LAUNCHER + " --kernel-id \"$1\" --response-address \"$2\" --public-key \"$3\""
                + " --port-range 0..0";
        List<String> command = PYSPARK_KERNEL.equals(kernelName)
                ? List.of("/bin/bash", "-c", "exec \"$SPARK_HOME/bin/spark-submit\" $KERNEL_EXTRA_SPARK_OPTS" + launch
                        + " --spark-context-initialization-mode lazy --cluster-type spark"
                        + " --RemoteProcessProxy.additional-info \"$4\"",
                        "kernel", kernelId, responseAddress, publicKey, CONFIGURE_MAGIC_ENABLED)
                : List.of("/bin/bash", "-c", "exec python3" + launch
                        + " --spark-context-initialization-mode none --cluster-type none",
                        "kernel", kernelId, responseAddress, publicKey);
        return new SparkJobRunner.Job(EmrContainersService.SERVICE, runId, arn.accountId(), arn.region(),
                releaseLabel, SparkJobRunner.Engine.SPARK, image(imageTemplate, releaseLabel),
                kernelImages(endpoint).get(kernelName), dockerNetwork, endpoint.get("executionRoleArn").textValue(),
                sparkDefaults, List.of(), command, Duration.ZERO, null, null, environment, List.of(), null, Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), null);
    }

    /**
     * The images an endpoint's {@code jupyter-kernel-overrides} gives its kernels, by kernel name, a later one's
     * winning; a kernel other than those it may override goes unnamed.
     */
    static Map<String, String> kernelImages(ObjectNode endpoint) {
        Map<String, String> images = new LinkedHashMap<>();
        for (JsonNode configuration : endpoint.path("configurationOverrides").path("applicationConfiguration")) {
            if (!KERNEL_OVERRIDES.equals(configuration.path("classification").textValue())) {
                continue;
            }
            for (JsonNode kernel : configuration.path("configurations")) {
                String name = kernel.path("classification").textValue();
                JsonNode image = kernel.path("properties").path(CONTAINER_IMAGE);
                if (OVERRIDABLE_KERNELS.contains(name) && image.isTextual()) {
                    images.put(name, image.textValue());
                }
            }
        }
        return images;
    }

    // The endpoint's spark-defaults as its kernels' spark-submit gets them: in Spark's local mode and client deploy
    // mode, with no virtualenv.
    private static Map<String, String> sparkDefaults(ObjectNode endpoint) {
        Map<String, String> sparkDefaults =
                new LinkedHashMap<>(EmrContainersSparkJobs.sparkDefaults(endpoint.get("configurationOverrides")));
        sparkDefaults.remove(MASTER);
        sparkDefaults.put(EmrContainersSparkJobs.DEPLOY_MODE, EmrContainersSparkJobs.CLIENT);
        sparkDefaults.put(PYSPARK_VIRTUALENV, "false");
        return sparkDefaults;
    }

    // The environment of the endpoint's driver, with the AWS SDK that S3A runs on on its class path.
    private static Map<String, String> environment(Map<String, String> sparkDefaults) {
        Map<String, String> environment = new LinkedHashMap<>(EmrServerlessJobRunService.environment(sparkDefaults,
                "spark.kubernetes.driverEnv."));
        environment.merge(CLASS_PATH_VARIABLE, AWS_SDK, (endpointClassPath, sdk) -> endpointClassPath + ":" + sdk);
        return environment;
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

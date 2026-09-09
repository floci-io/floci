package io.github.hectorvent.floci.services.cloudformation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the deployment package for a Lambda function declared with CloudFormation's
 * inline {@code ZipFile} property.
 *
 * <p>Matches the real service's special-casing of that code path: AWS injects the
 * {@code cfn-response} (Node.js) / {@code cfnresponse} (Python) module into ZipFile
 * packages so custom-resource handlers can send their CloudFormation callbacks, and
 * Solutions-style templates (e.g. Landing Zone Accelerator's installer) rely on it.
 * Lives outside {@code CloudFormationResourceProvisioner} so the eventual Lambda
 * provisioner extraction can take it along unchanged.</p>
 */
final class InlineZipPackager {

    /**
     * The cfn-response module for Node.js. Unlike AWS's canonical module this one honors
     * the ResponseURL's protocol and port, because Floci's ResponseURL is plain http on
     * the emulator's port.
     */
    private static final String CFN_RESPONSE_JS = """
            exports.SUCCESS = "SUCCESS";
            exports.FAILED = "FAILED";
            exports.send = function (event, context, responseStatus, responseData, physicalResourceId, noEcho) {
                var responseBody = JSON.stringify({
                    Status: responseStatus,
                    Reason: "See the details in CloudWatch Log Stream: " + context.logStreamName,
                    PhysicalResourceId: physicalResourceId || context.logStreamName,
                    StackId: event.StackId,
                    RequestId: event.RequestId,
                    LogicalResourceId: event.LogicalResourceId,
                    NoEcho: noEcho || false,
                    Data: responseData
                });
                console.log("Response body:\\n", responseBody);
                var url = require("url");
                var parsedUrl = url.parse(event.ResponseURL);
                var isHttps = parsedUrl.protocol === "https:";
                var transport = isHttps ? require("https") : require("http");
                var options = {
                    hostname: parsedUrl.hostname,
                    port: parsedUrl.port || (isHttps ? 443 : 80),
                    path: parsedUrl.path,
                    method: "PUT",
                    headers: { "content-type": "", "content-length": Buffer.byteLength(responseBody) }
                };
                var request = transport.request(options, function (response) {
                    console.log("Status code: " + response.statusCode);
                    context.done();
                });
                request.on("error", function (error) {
                    console.log("send(..) failed executing request(..): " + error);
                    context.done();
                });
                request.write(responseBody);
                request.end();
            };
            """;

    /** The Python counterpart AWS injects as {@code cfnresponse}. */
    private static final String CFN_RESPONSE_PY = """
            import http.client
            import json
            import urllib.parse

            SUCCESS = "SUCCESS"
            FAILED = "FAILED"

            def send(event, context, responseStatus, responseData, physicalResourceId=None,
                     noEcho=False, reason=None):
                response_url = event['ResponseURL']
                response_body = json.dumps({
                    'Status': responseStatus,
                    'Reason': reason or "See the details in CloudWatch Log Stream: "
                              + context.log_stream_name,
                    'PhysicalResourceId': physicalResourceId or context.log_stream_name,
                    'StackId': event['StackId'],
                    'RequestId': event['RequestId'],
                    'LogicalResourceId': event['LogicalResourceId'],
                    'NoEcho': noEcho,
                    'Data': responseData,
                })
                print("Response body:", response_body)
                body_bytes = response_body.encode('utf-8')
                parsed = urllib.parse.urlparse(response_url)
                conn_class = http.client.HTTPSConnection if parsed.scheme == 'https' \\
                    else http.client.HTTPConnection
                conn = conn_class(parsed.hostname, parsed.port)
                path = parsed.path + ('?' + parsed.query if parsed.query else '')
                conn.request('PUT', path, body=body_bytes,
                             headers={'content-type': '', 'content-length': str(len(body_bytes))})
                response = conn.getresponse()
                print("Status code:", response.status)
            """;

    private InlineZipPackager() {}

    static String sourceToZipBase64(String source, String handler, String runtime) {
        String module = handler.contains(".") ? handler.substring(0, handler.lastIndexOf('.')) : "index";
        String ext = runtime.startsWith("python") ? ".py" : ".js";
        try {
            var baos = new ByteArrayOutputStream();
            try (var zos = new ZipOutputStream(baos)) {
                zos.putNextEntry(new ZipEntry(module + ext));
                zos.write(source.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                if (runtime.startsWith("nodejs")) {
                    zos.putNextEntry(new ZipEntry("node_modules/cfn-response/package.json"));
                    zos.write("{\"name\":\"cfn-response\",\"main\":\"cfn-response.js\"}"
                            .getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                    zos.putNextEntry(new ZipEntry("node_modules/cfn-response/cfn-response.js"));
                    zos.write(CFN_RESPONSE_JS.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                } else if (runtime.startsWith("python")) {
                    zos.putNextEntry(new ZipEntry("cfnresponse.py"));
                    zos.write(CFN_RESPONSE_PY.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create zip from ZipFile source", e);
        }
    }
}

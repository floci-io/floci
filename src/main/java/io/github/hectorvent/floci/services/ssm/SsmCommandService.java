package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.services.ssm.model.InstanceInformation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Handles SSM agent registration and command execution lifecycle:
 * - UpdateInstanceInformation (agent side, via AmazonSSM target)
 * - SendCommand / GetCommandInvocation / ListCommands / ListCommandInvocations / CancelCommand (public API)
 * - GetMessages / AcknowledgeMessage / SendReply / FailMessage / DeleteMessage (ec2messages, agent side)
 */
@ApplicationScoped
public class SsmCommandService {

    private static final Logger LOG = Logger.getLogger(SsmCommandService.class);
    private static final int MAX_OUTPUT_CHARS = 24000;

    private final StorageBackend<String, InstanceInformation> instanceStore;
    private final StorageBackend<String, Command> commandStore;
    private final StorageBackend<String, CommandInvocation> invocationStore;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    // In-memory queues: instanceId → pending messages. Not persisted — lost on restart.
    private final ConcurrentHashMap<String, Queue<PendingMessage>> messageQueues = new ConcurrentHashMap<>();
    // messageId → (commandId, instanceId) for correlating SendReply back to an invocation
    private final ConcurrentHashMap<String, String[]> messageIndex = new ConcurrentHashMap<>();

    @Inject
    public SsmCommandService(StorageFactory storageFactory, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.instanceStore = storageFactory.create("ssm", "ssm-instances.json", new TypeReference<>() {});
        this.commandStore = storageFactory.create("ssm", "ssm-commands.json", new TypeReference<>() {});
        this.invocationStore = storageFactory.create("ssm", "ssm-invocations.json", new TypeReference<>() {});
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    // ── Agent registration ──────────────────────────────────────────────────

    public void updateInstanceInformation(JsonNode request, String region) {
        String instanceId = request.path("InstanceId").asText("");
        if (instanceId.isEmpty()) {
            // Some older agent versions don't send InstanceId; fall back to a generated key
            instanceId = "mi-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        }

        InstanceInformation info = instanceStore.get(instanceKey(region, instanceId))
                .orElse(new InstanceInformation());

        info.setInstanceId(instanceId);
        info.setAgentName(request.path("AgentName").asText("amazon-ssm-agent"));
        info.setAgentVersion(request.path("AgentVersion").asText("3.0.0.0"));
        info.setPingStatus("Online");
        info.setLastPingDateTime(Instant.now());
        info.setPlatformType(request.path("PlatformType").asText("Linux"));
        info.setPlatformName(request.path("PlatformName").asText(""));
        info.setPlatformVersion(request.path("PlatformVersion").asText(""));
        info.setIpAddress(request.path("IPAddress").asText(""));
        info.setComputerName(request.path("Hostname").asText(instanceId));
        info.setRegion(region);

        if (info.getRegistrationDate() == null) {
            info.setRegistrationDate(Instant.now());
        }

        instanceStore.put(instanceKey(region, instanceId), info);
        LOG.infov("SSM agent registered: instanceId={0} platform={1}/{2}", instanceId, info.getPlatformType(), info.getPlatformName());
    }

    public List<InstanceInformation> describeInstanceInformation(String region) {
        String prefix = region + "::";
        return instanceStore.scan(k -> k.startsWith(prefix));
    }

    // ── Public SendCommand API ──────────────────────────────────────────────

    public Command sendCommand(JsonNode request, String region) {
        String documentName = request.path("DocumentName").asText();
        if (documentName.isEmpty()) {
            throw new AwsException("InvalidDocument", "DocumentName is required.", 400);
        }

        List<String> instanceIds = new ArrayList<>();
        request.path("InstanceIds").forEach(n -> instanceIds.add(n.asText()));
        if (instanceIds.isEmpty()) {
            throw new AwsException("InvalidInstanceId", "At least one InstanceId is required.", 400);
        }

        Map<String, List<String>> parameters = parseParameters(request.path("Parameters"));
        String comment = request.path("Comment").asText("");
        int timeoutSeconds = request.path("TimeoutSeconds").asInt(3600);
        String documentVersion = request.path("DocumentVersion").asText("$DEFAULT");
        String outputS3Bucket = request.path("OutputS3BucketName").asText("");
        String outputS3Prefix = request.path("OutputS3KeyPrefix").asText("");

        String commandId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Command command = new Command();
        command.setCommandId(commandId);
        command.setDocumentName(documentName);
        command.setDocumentVersion(documentVersion);
        command.setComment(comment);
        command.setParameters(parameters);
        command.setInstanceIds(new ArrayList<>(instanceIds));
        command.setRequestedDateTime(now);
        command.setStatus("Pending");
        command.setStatusDetails("Pending");
        command.setTimeoutSeconds(timeoutSeconds);
        command.setTargetCount(instanceIds.size());
        command.setOutputS3BucketName(outputS3Bucket.isEmpty() ? null : outputS3Bucket);
        command.setOutputS3KeyPrefix(outputS3Prefix.isEmpty() ? null : outputS3Prefix);
        command.setOutputS3Region(region);
        command.setRegion(region);
        command.setExpiresAfter(now.plusSeconds(timeoutSeconds));

        commandStore.put(commandKey(region, commandId), command);

        // Create invocations and queue messages for each target instance
        for (String instanceId : instanceIds) {
            CommandInvocation inv = new CommandInvocation();
            inv.setCommandId(commandId);
            inv.setInstanceId(instanceId);
            inv.setComment(comment);
            inv.setDocumentName(documentName);
            inv.setDocumentVersion(documentVersion);
            inv.setRequestedDateTime(now);
            inv.setStatus("Pending");
            inv.setStatusDetails("Pending");
            inv.setRegion(region);
            invocationStore.put(invocationKey(region, commandId, instanceId), inv);

            queueMessage(commandId, instanceId, documentName, parameters, timeoutSeconds, region);
        }

        // Transition command to InProgress since messages are queued
        command.setStatus("InProgress");
        command.setStatusDetails("InProgress");
        commandStore.put(commandKey(region, commandId), command);

        LOG.infov("SendCommand: commandId={0} document={1} targets={2}", commandId, documentName, instanceIds);
        return command;
    }

    public CommandInvocation getCommandInvocation(String commandId, String instanceId, String region) {
        return invocationStore.get(invocationKey(region, commandId, instanceId))
                .orElseThrow(() -> new AwsException("InvocationDoesNotExist",
                        "Command " + commandId + " on instance " + instanceId + " does not exist.", 400));
    }

    public List<Command> listCommands(String commandId, String instanceId, String region) {
        String prefix = region + "::";
        return commandStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (commandId != null && !k.equals(commandKey(region, commandId))) return false;
            if (instanceId != null) {
                Command cmd = commandStore.get(k).orElse(null);
                return cmd != null && cmd.getInstanceIds() != null && cmd.getInstanceIds().contains(instanceId);
            }
            return true;
        });
    }

    public List<CommandInvocation> listCommandInvocations(String commandId, String instanceId, String region) {
        String prefix = region + "::";
        return invocationStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            if (commandId != null && !k.contains("::" + commandId + "::")) return false;
            if (instanceId != null && !k.endsWith("::" + instanceId)) return false;
            return true;
        });
    }

    public void cancelCommand(String commandId, List<String> targetInstanceIds, String region) {
        Command command = commandStore.get(commandKey(region, commandId))
                .orElseThrow(() -> new AwsException("InvalidCommandId",
                        "Command " + commandId + " does not exist.", 400));

        List<String> targets = (targetInstanceIds != null && !targetInstanceIds.isEmpty())
                ? targetInstanceIds
                : command.getInstanceIds();

        for (String instanceId : targets) {
            String invKey = invocationKey(region, commandId, instanceId);
            invocationStore.get(invKey).ifPresent(inv -> {
                if ("Pending".equals(inv.getStatus()) || "InProgress".equals(inv.getStatus())) {
                    inv.setStatus("Cancelled");
                    inv.setStatusDetails("Cancelled");
                    invocationStore.put(invKey, inv);
                }
                // Remove any queued (not-yet-polled) messages for this instance
                Queue<PendingMessage> q = messageQueues.get(instanceId);
                if (q != null) {
                    q.removeIf(m -> m.commandId().equals(commandId));
                }
            });
        }

        command.setStatus("Cancelled");
        command.setStatusDetails("Cancelled");
        commandStore.put(commandKey(region, commandId), command);
        LOG.infov("CancelCommand: commandId={0}", commandId);
    }

    // ── ec2messages agent protocol ──────────────────────────────────────────

    public List<Map<String, Object>> getMessages(String instanceId, String messagesRequestId, int visibilityTimeout) {
        Queue<PendingMessage> queue = messageQueues.get(instanceId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (queue == null || queue.isEmpty()) {
            return result;
        }

        PendingMessage msg = queue.poll();
        if (msg == null) {
            return result;
        }

        // Track for AcknowledgeMessage / SendReply correlation
        messageIndex.put(msg.messageId(), new String[]{msg.commandId(), instanceId, msg.region()});

        result.add(Map.of(
                "MessageId", msg.messageId(),
                "Destination", instanceId,
                "CreatedDate", msg.createdDate().toString(),
                "Topic", "aws.ssm.sendCommand." + msg.region(),
                "Payload", msg.payload()
        ));

        LOG.infov("GetMessages: instanceId={0} returned messageId={1}", instanceId, msg.messageId());
        return result;
    }

    public void acknowledgeMessage(String messageId) {
        // Message was already removed from queue on GetMessages. Just update invocation to InProgress.
        String[] meta = messageIndex.get(messageId);
        if (meta == null) {
            return;
        }
        String commandId = meta[0];
        String instanceId = meta[1];
        String region = meta[2];

        String invKey = invocationKey(region, commandId, instanceId);
        invocationStore.get(invKey).ifPresent(inv -> {
            if ("Pending".equals(inv.getStatus())) {
                inv.setStatus("InProgress");
                inv.setStatusDetails("InProgress");
                inv.setExecutionStartDateTime(Instant.now());
                invocationStore.put(invKey, inv);
            }
        });
        LOG.debugv("AcknowledgeMessage: messageId={0} commandId={1}", messageId, commandId);
    }

    public void sendReply(String messageId, String payloadBase64) {
        String[] meta = messageIndex.remove(messageId);
        if (meta == null) {
            LOG.warnv("SendReply: unknown messageId={0}", messageId);
            return;
        }
        String commandId = meta[0];
        String instanceId = meta[1];
        String region = meta[2];

        try {
            byte[] decoded = Base64.getDecoder().decode(payloadBase64);
            JsonNode payload = objectMapper.readTree(decoded);

            String status = "Success";
            int returnCode = 0;
            String stdout = "";
            String stderr = "";
            Instant endTime = Instant.now();

            // Parse runtimeStatus or pluginResults — take the first plugin entry found
            JsonNode statusNode = payload.has("runtimeStatus") ? payload.get("runtimeStatus")
                    : payload.get("pluginResults");
            if (statusNode != null && statusNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = statusNode.fields();
                if (it.hasNext()) {
                    JsonNode plugin = it.next().getValue();
                    status = plugin.path("status").asText("Success");
                    returnCode = plugin.path("returnCode").asInt(plugin.path("code").asInt(0));
                    stdout = plugin.path("standardOutput").asText(plugin.path("output").asText(""));
                    stderr = plugin.path("standardError").asText("");
                }
            }

            // Trim output to AWS limits
            if (stdout.length() > MAX_OUTPUT_CHARS) {
                stdout = stdout.substring(stdout.length() - MAX_OUTPUT_CHARS);
            }
            if (stderr.length() > MAX_OUTPUT_CHARS) {
                stderr = stderr.substring(stderr.length() - MAX_OUTPUT_CHARS);
            }

            String invKey = invocationKey(region, commandId, instanceId);
            CommandInvocation inv = invocationStore.get(invKey).orElse(null);
            if (inv != null) {
                inv.setStatus(toInvocationStatus(status));
                inv.setStatusDetails(toInvocationStatus(status));
                inv.setStandardOutputContent(stdout);
                inv.setStandardErrorContent(stderr);
                inv.setResponseCode(returnCode);
                inv.setExecutionEndDateTime(endTime);
                invocationStore.put(invKey, inv);
            }

            // Recalculate command status
            updateCommandStatus(commandId, region);
            LOG.infov("SendReply: commandId={0} instanceId={1} status={2} rc={3}", commandId, instanceId, status, returnCode);
        } catch (Exception e) {
            LOG.warnv(e, "Failed to parse SendReply payload for messageId={0}", messageId);
        }
    }

    public void failMessage(String messageId, String failureType) {
        String[] meta = messageIndex.remove(messageId);
        if (meta == null) {
            return;
        }
        String commandId = meta[0];
        String instanceId = meta[1];
        String region = meta[2];

        String invKey = invocationKey(region, commandId, instanceId);
        invocationStore.get(invKey).ifPresent(inv -> {
            inv.setStatus("Failed");
            inv.setStatusDetails("Failed: " + failureType);
            inv.setExecutionEndDateTime(Instant.now());
            invocationStore.put(invKey, inv);
        });
        updateCommandStatus(commandId, region);
        LOG.warnv("FailMessage: commandId={0} instanceId={1} failureType={2}", commandId, instanceId, failureType);
    }

    public void deleteMessage(String messageId) {
        messageIndex.remove(messageId);
    }

    // ── CodeDeploy integration helpers ─────────────────────────────────────

    public boolean isInstanceRegistered(String instanceId, String region) {
        return instanceStore.get(instanceKey(region, instanceId)).isPresent();
    }

    public String sendCommandToInstance(String instanceId, String documentName,
                                        Map<String, List<String>> parameters,
                                        int timeoutSeconds, String region) {
        String commandId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        Command command = new Command();
        command.setCommandId(commandId);
        command.setDocumentName(documentName);
        command.setDocumentVersion("$DEFAULT");
        command.setParameters(parameters);
        command.setInstanceIds(List.of(instanceId));
        command.setRequestedDateTime(now);
        command.setStatus("InProgress");
        command.setStatusDetails("InProgress");
        command.setTimeoutSeconds(timeoutSeconds);
        command.setTargetCount(1);
        command.setRegion(region);
        command.setExpiresAfter(now.plusSeconds(timeoutSeconds));
        commandStore.put(commandKey(region, commandId), command);

        CommandInvocation inv = new CommandInvocation();
        inv.setCommandId(commandId);
        inv.setInstanceId(instanceId);
        inv.setDocumentName(documentName);
        inv.setDocumentVersion("$DEFAULT");
        inv.setRequestedDateTime(now);
        inv.setStatus("Pending");
        inv.setStatusDetails("Pending");
        inv.setRegion(region);
        invocationStore.put(invocationKey(region, commandId, instanceId), inv);

        queueMessage(commandId, instanceId, documentName, parameters, timeoutSeconds, region);
        return commandId;
    }

    public String getCommandInvocationStatus(String commandId, String instanceId, String region) {
        return invocationStore.get(invocationKey(region, commandId, instanceId))
                .map(CommandInvocation::getStatus)
                .orElse("Failed");
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private void queueMessage(String commandId, String instanceId, String documentName,
                              Map<String, List<String>> parameters, int timeoutSeconds, String region) {
        String messageId = UUID.randomUUID().toString();
        String payload = buildCommandPayload(commandId, documentName, documentVersion(parameters), parameters, timeoutSeconds, region);
        PendingMessage msg = new PendingMessage(messageId, commandId, region, Instant.now(), payload);
        messageQueues.computeIfAbsent(instanceId, k -> new ConcurrentLinkedQueue<>()).add(msg);
    }

    private String buildCommandPayload(String commandId, String documentName, String docVersion,
                                       Map<String, List<String>> parameters, int timeoutSeconds, String region) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("DocumentName", documentName);
            payload.put("DocumentVersion", docVersion);
            payload.put("CommandId", commandId);
            payload.put("OutputS3BucketName", "");
            payload.put("OutputS3KeyPrefix", "");
            payload.put("OutputS3Region", region);
            payload.put("CloudWatchLogGroupName", "");
            payload.put("CloudWatchLogStreamName", "");

            ObjectNode params = objectMapper.createObjectNode();
            if (parameters != null) {
                for (Map.Entry<String, List<String>> e : parameters.entrySet()) {
                    ArrayNode arr = objectMapper.createArrayNode();
                    e.getValue().forEach(arr::add);
                    params.set(e.getKey(), arr);
                }
            }
            payload.set("Parameters", params);
            payload.set("DocumentContent", buildDocumentContent(documentName, parameters, timeoutSeconds));

            return Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build command payload", e);
        }
    }

    private JsonNode buildDocumentContent(String documentName, Map<String, List<String>> parameters, int timeoutSeconds) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("schemaVersion", "2.2");
        doc.put("description", documentName);

        ObjectNode docParams = objectMapper.createObjectNode();
        ObjectNode commandsParam = objectMapper.createObjectNode();
        commandsParam.put("type", "StringList");
        commandsParam.put("description", "Commands to run.");
        docParams.set("commands", commandsParam);

        ObjectNode wdParam = objectMapper.createObjectNode();
        wdParam.put("type", "String");
        wdParam.put("default", "");
        wdParam.put("description", "Working directory.");
        docParams.set("workingDirectory", wdParam);

        ObjectNode toParam = objectMapper.createObjectNode();
        toParam.put("type", "String");
        toParam.put("default", String.valueOf(timeoutSeconds));
        toParam.put("description", "Execution timeout in seconds.");
        docParams.set("executionTimeout", toParam);
        doc.set("parameters", docParams);

        ArrayNode mainSteps = objectMapper.createArrayNode();
        ObjectNode step = objectMapper.createObjectNode();
        step.put("action", resolveAction(documentName));
        step.put("name", "runShellScript");
        ObjectNode inputs = objectMapper.createObjectNode();
        inputs.put("runCommand", "{{ commands }}");
        inputs.put("workingDirectory", "{{ workingDirectory }}");
        inputs.put("timeoutSeconds", "{{ executionTimeout }}");
        step.set("inputs", inputs);
        mainSteps.add(step);
        doc.set("mainSteps", mainSteps);

        return doc;
    }

    private String resolveAction(String documentName) {
        return switch (documentName) {
            case "AWS-RunPowerShellScript" -> "aws:runPowerShellScript";
            default -> "aws:runShellScript";
        };
    }

    private String documentVersion(Map<String, List<String>> parameters) {
        return "$DEFAULT";
    }

    private void updateCommandStatus(String commandId, String region) {
        Command command = commandStore.get(commandKey(region, commandId)).orElse(null);
        if (command == null) {
            return;
        }

        List<String> instanceIds = command.getInstanceIds();
        int completed = 0;
        int errors = 0;
        boolean anyInProgress = false;

        for (String iid : instanceIds) {
            CommandInvocation inv = invocationStore.get(invocationKey(region, commandId, iid)).orElse(null);
            if (inv == null) continue;
            String s = inv.getStatus();
            if ("Success".equals(s)) {
                completed++;
            } else if ("Failed".equals(s) || "TimedOut".equals(s) || "Cancelled".equals(s)) {
                completed++;
                errors++;
            } else if ("InProgress".equals(s) || "Pending".equals(s)) {
                anyInProgress = true;
            }
        }

        command.setCompletedCount(completed);
        command.setErrorCount(errors);

        if (!anyInProgress && completed == instanceIds.size()) {
            command.setStatus(errors == 0 ? "Success" : (errors == instanceIds.size() ? "Failed" : "Success"));
            command.setStatusDetails(command.getStatus());
        }

        commandStore.put(commandKey(region, commandId), command);
    }

    private static String toInvocationStatus(String agentStatus) {
        return switch (agentStatus) {
            case "Success" -> "Success";
            case "Failed" -> "Failed";
            case "TimedOut" -> "TimedOut";
            case "Cancelled", "Canceled" -> "Cancelled";
            default -> "Failed";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseParameters(JsonNode parametersNode) {
        if (parametersNode == null || parametersNode.isNull() || !parametersNode.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(parametersNode,
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String instanceKey(String region, String instanceId) {
        return region + "::" + instanceId;
    }

    private static String commandKey(String region, String commandId) {
        return region + "::" + commandId;
    }

    private static String invocationKey(String region, String commandId, String instanceId) {
        return region + "::" + commandId + "::" + instanceId;
    }

    record PendingMessage(String messageId, String commandId, String region, Instant createdDate, String payload) {}
}

package io.floci.aws.services.ssmmessages;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlBuilder;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.function.Consumer;

@ApplicationScoped
public class SsmMessageGatewayRoutes implements DataChannelHandler {

    private static final Logger LOG = Logger.getLogger(SsmMessageGatewayRoutes.class);
    private static final String CONTROL_PATH = "/v1/control-channel/:instanceId";
    private static final String DATA_PATH = DataChannelRouter.CHANNEL_PATH_PREFIX + ":sessionId";
    private static final byte[] EMPTY_BODY = new byte[0];

    private final SsmMessageGateway gateway;
    private final ChannelAuthentication authentication;
    private final ObjectMapper mapper;
    private final boolean enabled;

    @Inject
    public SsmMessageGatewayRoutes(SsmMessageGateway gateway, ChannelAuthentication authentication,
                                   ObjectMapper mapper, EmulatorConfig config) {
        this(gateway, authentication, mapper, config.services().ssm().enabled());
    }

    SsmMessageGatewayRoutes(SsmMessageGateway gateway, ChannelAuthentication authentication,
                            ObjectMapper mapper, boolean enabled) {
        this.gateway = gateway;
        this.authentication = authentication;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    void init(@Observes Router router) {
        if (!enabled) {
            return;
        }
        BodyHandler body = BodyHandler.create().setBodyLimit(16 * 1024).setHandleFileUploads(false);
        router.post(CONTROL_PATH).handler(body).handler(this::createControl);
        router.get(CONTROL_PATH).handler(this::openControl);
        router.post(DATA_PATH).handler(body).handler(this::createData);
    }

    @Override
    public boolean supportsSession(String sessionId) {
        return enabled && gateway.sessionTarget(sessionId).isPresent();
    }

    @Override
    public void open(String sessionId, RoutingContext context) {
        long generation = gateway.generation();
        try {
            SsmMessageGateway.SessionTarget target = gateway.sessionTarget(sessionId)
                    .orElseThrow(SsmMessageGatewayRoutes::closedSession);
            if (!"publish_subscribe".equals(context.request().getParam("role"))) {
                throw invalidRequest();
            }
            boolean agentAllowed = canOpenAgent(context, target.node());
            boolean clientAllowed = canOpenClient(context, target, agentAllowed);
            upgrade(context, socket ->
                    gateway.openData(sessionId, target, agentAllowed, clientAllowed, socket, generation));
        } catch (AwsException e) {
            error(context, e);
        }
    }

    private boolean canOpenAgent(RoutingContext context, SsmMessageGateway.Node target) {
        try {
            return authentication.authenticateAgentUpgradeIfClaimed(context.request(), "ssmmessages:OpenDataChannel")
                    .map(SsmMessageGatewayRoutes::node).filter(target::equals).isPresent();
        } catch (AwsException expected) {
            if (expected.getHttpStatus() != 403) {
                throw expected;
            }
            // A denied agent claim may still be an authorised client; its grant selects the side.
            return false;
        }
    }

    private boolean canOpenClient(RoutingContext context, SsmMessageGateway.SessionTarget target, boolean agentAllowed) {
        try {
            authentication.authorizeClient(context.request(), target.node().accountId(),
                    target.node().region(), target.sessionArn());
            return true;
        } catch (AwsException expected) {
            if (!agentAllowed || expected.getHttpStatus() != 403) {
                throw expected;
            }
            // An authorised agent need not also hold client permission on the session.
            return false;
        }
    }

    private void createControl(RoutingContext context) {
        try {
            byte[] body = context.body().buffer().getBytes();
            ChannelAuthentication.AgentIdentity identity = authentication.authenticateAgent(
                    context.request(), body, "ssmmessages:CreateControlChannel");
            requireInstance(context, identity);
            readRequest(body);
            created(context, gateway.createControlGrant(node(identity)));
        } catch (AwsException e) {
            error(context, e);
        }
    }

    private void openControl(RoutingContext context) {
        if (!"websocket".equalsIgnoreCase(context.request().getHeader("Upgrade"))) {
            context.next();
            return;
        }
        long generation = gateway.generation();
        try {
            ChannelAuthentication.AgentIdentity identity = authentication.authenticateAgent(
                    context.request(), EMPTY_BODY, "ssmmessages:OpenControlChannel");
            requireInstance(context, identity);
            if (!"subscribe".equals(context.request().getParam("role"))) {
                throw invalidRequest();
            }
            upgrade(context, socket -> gateway.openControl(node(identity), socket, generation));
        } catch (AwsException e) {
            error(context, e);
        }
    }

    private void createData(RoutingContext context) {
        try {
            byte[] body = context.body().buffer().getBytes();
            ChannelAuthentication.AgentIdentity identity = authentication.authenticateAgent(
                    context.request(), body, "ssmmessages:CreateDataChannel");
            JsonNode clientId = readRequest(body).path("ClientId");
            if (!clientId.isMissingNode() && !clientId.isNull() && !clientId.isTextual()) {
                throw invalidRequest();
            }
            String sessionId = context.pathParam("sessionId");
            SsmMessageGateway.SessionTarget target = gateway.sessionTarget(sessionId)
                    .orElseThrow(SsmMessageGatewayRoutes::closedSession);
            if (!target.node().equals(node(identity))) {
                throw accessDenied();
            }
            created(context, gateway.createAgentGrant(node(identity), sessionId, clientId.textValue())
                    .orElseThrow(SsmMessageGatewayRoutes::closedSession));
        } catch (AwsException e) {
            error(context, e);
        }
    }

    private JsonNode readRequest(byte[] body) {
        try {
            JsonNode request = mapper.readTree(body);
            if (request == null || !"1.0".equals(request.path("MessageSchemaVersion").textValue())
                    || request.path("RequestId").asText("").isBlank()) {
                throw invalidRequest();
            }
            return request;
        } catch (JsonProcessingException e) {
            throw invalidRequest();
        } catch (IOException e) {
            throw new IllegalStateException("In-memory request body could not be read", e);
        }
    }

    private static void upgrade(RoutingContext context, Consumer<ServerWebSocket> accepted) {
        context.request().toWebSocket().onSuccess(accepted::accept)
                .onFailure(cause -> LOG.debug("Message gateway WebSocket upgrade failed", cause));
    }

    private static SsmMessageGateway.Node node(ChannelAuthentication.AgentIdentity identity) {
        return new SsmMessageGateway.Node(identity.accountId(), identity.region(), identity.instanceId());
    }

    private static void requireInstance(RoutingContext context, ChannelAuthentication.AgentIdentity identity) {
        if (!identity.instanceId().equals(context.pathParam("instanceId"))) {
            throw accessDenied();
        }
    }

    private static void created(RoutingContext context, String token) {
        String body = new XmlBuilder().start("ChannelOutput")
                .elem("MessageSchemaVersion", "1.0").elem("TokenValue", token)
                .end("ChannelOutput").build();
        context.response().setStatusCode(201).putHeader("Content-Type", "application/xml").end(body);
    }

    private static void error(RoutingContext context, AwsException e) {
        context.response().setStatusCode(e.getHttpStatus())
                .putHeader("Content-Type", "application/json")
                .putHeader("x-amzn-errortype", e.getErrorCode())
                .end(new JsonObject().put("message", e.getMessage()).encode());
    }

    private static AwsException invalidRequest() {
        return new AwsException("ValidationException", "Invalid message gateway request.", 400);
    }

    private static AwsException accessDenied() {
        return new AwsException("AccessDeniedException", "The instance does not own this channel.", 403);
    }

    private static AwsException closedSession() {
        return new AwsException("ResourceNotFoundException", "Session is already terminated", 404);
    }
}

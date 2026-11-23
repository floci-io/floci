package io.floci.aws.services.ssmmessages;

import io.quarkus.arc.All;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class DataChannelRouter {

    public static final String CHANNEL_PATH_PREFIX = "/v1/data-channel/";
    private static final Logger LOG = Logger.getLogger(DataChannelRouter.class);

    private final List<DataChannelHandler> handlers;

    @Inject
    public DataChannelRouter(@All List<DataChannelHandler> handlers) {
        this.handlers = handlers;
    }

    public void init(@Observes Router router) {
        router.route(CHANNEL_PATH_PREFIX + ":sessionId").handler(this::upgrade);
    }

    private void upgrade(RoutingContext context) {
        if (!"websocket".equalsIgnoreCase(context.request().getHeader("Upgrade"))) {
            context.next();
            return;
        }

        String sessionId = context.pathParam("sessionId");
        List<DataChannelHandler> owners = handlers.stream()
                .filter(handler -> handler.supportsSession(sessionId))
                .toList();
        if (owners.isEmpty()) {
            context.response().setStatusCode(404).end();
            return;
        }
        if (owners.size() > 1) {
            LOG.errorv("More than one data channel handler owns session {0}", sessionId);
            context.response().setStatusCode(500).end();
            return;
        }

        owners.getFirst().open(sessionId, context);
    }
}

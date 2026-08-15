package io.github.hectorvent.floci.services.connect;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Amazon Connect REST-JSON controller.
 *
 * <p>Only the operations declared below are served; anything else falls through to the
 * emulator's not-found handling rather than a stub success.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectController {
}

package io.floci.aws.services.emr;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Html;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.emr.model.EmrCluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * The page a presigned URL of {@code GetPersistentAppUIPresignedURL} or {@code GetOnClusterAppUIPresignedURL} opens
 * ({@link EmrAppUiService}) for an interface Floci does not run here: EMR opens the cluster's Spark History Server,
 * Tez UI, YARN timeline server or on-cluster UI, and the page shows the cluster as DescribeCluster gives it and which
 * interface was asked for. A link that is not one of this process's, or whose hour has passed, opens a refusal
 * instead, and so does one whose cluster has gone. Like the rest of Floci the page takes no AWS credentials: the link
 * is all it asks for.
 */
@Path(EmrAppUiService.PATH)
public class EmrAppUiController {

    // No source gives AWS's wording for these; they are Floci's.
    static final String INVALID = "This application UI link is not valid, or its hour has passed. Call"
            + " GetPersistentAppUIPresignedURL or GetOnClusterAppUIPresignedURL for a new one.";
    static final String NO_UI = "Floci does not run this interface here, so this page stands in for the interface EMR"
            + " opens; in the docker runner mode, a cluster whose primary node runs it opens it for real.";

    private final EmrAppUiService appUis;

    @Inject
    public EmrAppUiController(EmrAppUiService appUis) {
        this.appUis = appUis;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response page(@QueryParam("account") String accountId, @QueryParam("region") String region,
                         @QueryParam("cluster") String clusterId, @QueryParam("type") String type,
                         @QueryParam("application") String applicationId, @QueryParam("expires") String expires,
                         @QueryParam("signature") String signature) {
        EmrAppUiService.Link link = appUis.verified(accountId, region, clusterId, type, applicationId, expires,
                signature);
        if (link == null) {
            return page(403, "Application UI", List.of("<p>" + Html.escape(INVALID) + "</p>"));
        }
        EmrCluster cluster;
        try {
            cluster = RequestScopes.callAs(link.accountId(), link.region(), () -> appUis.clusterOf(link));
        } catch (AwsException e) {
            return page(404, "Cluster " + link.clusterId(), List.of("<p>" + Html.escape(e.getMessage()) + "</p>"));
        }
        List<String> lines = new ArrayList<>();
        lines.add("<table>");
        Html.row(lines, "Cluster", cluster.getId());
        Html.row(lines, "Name", cluster.getName());
        Html.row(lines, "State", cluster.getState());
        Html.row(lines, "Release label", cluster.getReleaseLabel());
        Html.row(lines, "Interface", link.type());
        Html.row(lines, "Application", link.applicationId());
        lines.add("</table>");
        lines.add("<p>" + Html.escape(NO_UI) + "</p>");
        return page(200, link.type() + " of cluster " + cluster.getId(), lines);
    }

    private static Response page(int status, String title, List<String> body) {
        return Response.status(status).type(MediaType.TEXT_HTML).entity(Html.page(title, body)).build();
    }
}

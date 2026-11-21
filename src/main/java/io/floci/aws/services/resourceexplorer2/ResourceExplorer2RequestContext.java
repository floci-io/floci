package io.floci.aws.services.resourceexplorer2;

import jakarta.enterprise.context.RequestScoped;

import java.util.HashMap;
import java.util.Map;

/**
 * What this request's permission check resolved, kept for the request.
 *
 * <p>A {@code Search} or {@code ListResources} naming no view searches the Region's default view, and another
 * request may associate another default while this one is running. The permission check reads the default before
 * the request runs, and the request must search the view that was checked.
 */
@RequestScoped
public class ResourceExplorer2RequestContext {

    /**
     * The default view the permission check found, by Region: its ARN, the ARN of the baseline view about to be
     * provisioned with its id open, or an empty one for none.
     */
    private final Map<String, String> checkedDefaultViews = new HashMap<>();

    /** The default view the check found in the Region, as {@link #checkedDefaultViews} holds it; null when it found none. */
    String checkedDefaultView(String region) {
        return checkedDefaultViews.get(region);
    }

    /** Remembers the default view the check found, so that the request searches that one and not another. */
    void pinCheckedDefaultView(String region, String viewArn) {
        checkedDefaultViews.put(region, viewArn);
    }
}

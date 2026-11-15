package io.floci.aws.services.codeartifact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Flattens a repository's {@code upstreams} graph into the order AWS searches it in when resolving a
 * package-manager request. The User Guide's "Upstream repository priority order": a repository's
 * upstreams are searched in the order they were listed, and "if an upstream repository has its own
 * upstream repositories, the same logic is used to find a package version before moving to the next
 * upstream repository". That is a depth-first walk in list order: its own example, {@code my_repo}
 * with upstreams {@code A} and {@code B}, {@code A} with {@code C} and {@code D}, and {@code D} with
 * {@code E}, is searched {@code my_repo, A, C, D, E, B}.
 *
 * <p>Cycle-safe by construction: a repository is searched at most once (the {@code visited} set),
 * where it is first reached, so a cycle anywhere in the graph simply stops expanding rather than
 * looping. {@code validateUpstreams} independently rejects a cycle at the point it would be introduced
 * (see its own javadoc), but this walk does not rely on that validation having run, since an upstream
 * list can only be validated against the graph as it existed at that moment, not against every later
 * edit to a different repository's own upstreams.
 */
final class UpstreamRepositoryResolver {

    /**
     * A defensive bound adapted from, not identical to, AWS's own documented region-wide quota of
     * 25 repositories searched in a single resolution (the "Service quotas" page): that page
     * states a region-wide ceiling, not a literal guarantee about any one resolution's own walk,
     * but a resolution that would otherwise need to visit more repositories than AWS's own total
     * region-wide limit allows is not a real scenario this emulator needs to search further on.
     */
    static final int MAX_RESOLUTION_REPOSITORIES = 25;

    private UpstreamRepositoryResolver() {
    }

    /**
     * The priority-ordered, deduplicated, cycle-safe list of repositories reachable from {@code
     * startingRepository}'s own {@code upstreams} graph (the starting repository itself is never
     * included). {@code upstreamsOf} reads one repository's current, stored {@code upstreams} list
     * (an empty list for a repository with none, or one this emulator has never heard of).
     */
    static List<String> resolveSearchOrder(String startingRepository, Function<String, List<String>> upstreamsOf) {
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(startingRepository);
        // A stack of each repository's remaining upstreams: the top is the list being walked, so a
        // repository's own upstreams are searched before the next of its siblings.
        Deque<Iterator<String>> pending = new ArrayDeque<>();
        pending.push(upstreamsOf.apply(startingRepository).iterator());
        while (!pending.isEmpty() && order.size() < MAX_RESOLUTION_REPOSITORIES) {
            Iterator<String> siblings = pending.peek();
            if (!siblings.hasNext()) {
                pending.pop();
                continue;
            }
            String upstream = siblings.next();
            if (!visited.add(upstream)) {
                continue;
            }
            order.add(upstream);
            pending.push(upstreamsOf.apply(upstream).iterator());
        }
        return order;
    }

    /**
     * Whether {@code target} is reachable from {@code startingRepository}'s own {@code upstreams}
     * graph, with no cap: unlike {@link #resolveSearchOrder}, which exists to answer "where does a
     * package-manager request actually search" and is capped to match AWS's own documented
     * region-wide resolution quota, {@code validateUpstreams}'s own cycle check needs a correct
     * answer for the whole graph as stored, however large a domain's own repository count grows
     * (up to 1,000, AWS's own documented per-domain quota, far more than {@link
     * #MAX_RESOLUTION_REPOSITORIES}). Sharing that cap here would let a cycle whose closing edge
     * sits beyond it pass validation and persist. Still cycle-safe and guaranteed to terminate via
     * the same {@code visited} set, since a domain has a bounded, finite number of repositories.
     */
    static boolean isReachable(String startingRepository, String target, Function<String, List<String>> upstreamsOf) {
        Set<String> visited = new HashSet<>();
        visited.add(startingRepository);
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(startingRepository);
        while (!frontier.isEmpty()) {
            String current = frontier.removeFirst();
            for (String upstream : upstreamsOf.apply(current)) {
                if (upstream.equals(target)) {
                    return true;
                }
                if (visited.add(upstream)) {
                    frontier.addLast(upstream);
                }
            }
        }
        return false;
    }
}

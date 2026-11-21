package io.floci.aws.services.iam;

import io.floci.aws.core.common.RequestResources;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ARN template a simulation names for an action, which is what an evaluation result's
 * {@code EvalResourceName} carries.
 *
 * <p>The field's reference says what it holds: "The ARN template for the simulated resource type
 * (for example, {@code arn:${Partition}:s3:::${BucketName}/${KeyName}}), or {@code *} if no ARN
 * format is defined for the action. This is not a specific customer-provided resource ARN. To find
 * the decision for a specific resource, use {@code ResourceSpecificResults}." So it describes the
 * action the result answers for rather than anything the call named, and the resources a call did
 * name are answered one by one under {@code ResourceSpecificResults} beside it.
 *
 * <p>The templates come from the two generated tables, both built from AWS's Service Reference,
 * which publishes the resource types each action acts on and each type's ARN formats: IAM's own
 * actions from {@link IamActionResources}, every other namespace the table reads from
 * {@link RequestResources#arnFormatsOf}.
 *
 * <p>An action whose published types carry more than one format answers {@code *}, the value the
 * field publishes for an action with no format at all. Which of several AWS names here is
 * published nowhere a table can be generated from: {@code s3:GetObject} acts on an object and on
 * an access point object, and the reference's own example for this field is the object's template,
 * a choice no machine-readable source carries. An action of a namespace no table reads answers
 * {@code *} for the same reason, having no published format here to name.
 *
 * <p>Outside IAM the table is keyed by the operation a request is dispatched as, so an action
 * whose name is not its operation's answers {@code *} as well: {@code lambda:InvokeFunction} is
 * the action of the {@code Invoke} operation, and what joins the two names is published in no
 * machine-readable source either. A name is read whatever case it is written in, policy action
 * names being case-insensitive.
 */
final class SimulatedResourceTemplates {

    /** The value the field publishes for an action with no ARN format, which is also its own type. */
    static final String NO_FORMAT = "*";

    private static final String IAM_NAMESPACE = "iam";

    private SimulatedResourceTemplates() {
    }

    /**
     * The template for {@code actionName}, as {@code <namespace>:<Operation>}, or {@code *} when
     * the published types carry no single format.
     */
    static String of(String actionName) {
        List<String> formats = publishedFormats(actionName);
        return formats.size() == 1 ? formats.getFirst() : NO_FORMAT;
    }

    /**
     * Every format the action's published resource types carry, each once. IAM's own actions are
     * read from IAM's table, whose types may each publish several formats, and the rest from the
     * table that reads the other namespaces, where a type publishes one.
     */
    private static List<String> publishedFormats(String actionName) {
        int colon = actionName == null ? -1 : actionName.indexOf(':');
        if (colon <= 0 || colon == actionName.length() - 1) {
            return List.of();
        }
        if (!IAM_NAMESPACE.equalsIgnoreCase(actionName.substring(0, colon))) {
            return RequestResources.arnFormatsOf(actionName);
        }
        List<String> published = new ArrayList<>();
        for (String type : iamTypesOf(actionName.substring(colon + 1))) {
            for (String format : IamActionResources.arnFormats().getOrDefault(type, List.of())) {
                if (!published.contains(format)) {
                    published.add(format);
                }
            }
        }
        return List.copyOf(published);
    }

    /** The types IAM publishes for one of its own actions, named in any case. */
    private static List<String> iamTypesOf(String action) {
        List<String> types = IamActionResources.typesOf(action);
        if (!types.isEmpty()) {
            return types;
        }
        String named = IamActionNames.INDEX.get(action.toLowerCase(Locale.ROOT));
        return named == null ? List.of() : IamActionResources.typesOf(named);
    }

    /** Every IAM action under its own name lowercased, built once on first use. */
    private static final class IamActionNames {

        private static final Map<String, String> INDEX = index();

        private static Map<String, String> index() {
            Map<String, String> index = new HashMap<>();
            for (String action : IamActionResources.all().keySet()) {
                index.putIfAbsent(action.toLowerCase(Locale.ROOT), action);
            }
            return Map.copyOf(index);
        }

        private IamActionNames() {
        }
    }
}

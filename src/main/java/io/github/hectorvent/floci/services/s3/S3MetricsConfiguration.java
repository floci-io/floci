package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.S3FilterGrammar;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;

import java.util.List;

/**
 * A parsed {@code MetricsConfiguration} request body, reduced to its id and a canonical
 * serialization of its contents.
 *
 * <p>The body is re-serialized rather than stored verbatim, so that what a later
 * GetBucketMetricsConfiguration or ListBucketMetricsConfigurations returns is built by floci
 * instead of being whatever XML the caller sent, and so that the same stored form can be wrapped
 * in either response.
 */
record S3MetricsConfiguration(String id, String innerXml) {

    private static final String ROOT = "MetricsConfiguration";

    /** AWS reports a body that does not match the published schema this way. */
    private static AwsException malformed() {
        return new AwsException("MalformedXML",
                "The XML you provided was not well-formed or did not validate against our published schema", 400);
    }

    static S3MetricsConfiguration parse(String xml) {
        if (xml == null || xml.isBlank() || !ROOT.equals(XmlParser.rootElementName(xml))) {
            throw malformed();
        }

        // The configuration is one Id and at most one Filter, so anything else under the root —
        // a stray element, a second Id, a second Filter — is a body AWS would not have accepted.
        List<String> children = XmlParser.childElementNames(xml, ROOT);
        long ids = children.stream().filter("Id"::equals).count();
        long filters = children.stream().filter("Filter"::equals).count();
        if (ids != 1 || filters > 1 || children.size() != ids + filters) {
            throw malformed();
        }
        boolean hasFilter = filters == 1;

        String id = XmlParser.extractFirst(xml, "Id", null);
        if (id == null || id.isBlank()) {
            throw malformed();
        }

        XmlBuilder inner = new XmlBuilder().elem("Id", id);
        if (hasFilter) {
            inner.start("Filter").raw(S3FilterGrammar.filterXml(xml, List.of("AccessPointArn"))).end("Filter");
        }
        return new S3MetricsConfiguration(id, inner.build());
    }

}

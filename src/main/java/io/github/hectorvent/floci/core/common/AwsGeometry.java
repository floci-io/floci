package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the AWS Geometry response shape, a BoundingBox plus the 4-point Polygon tracing its
 * corners, that Rekognition and Textract both return for detected text and labels. The two
 * services' Geometry shapes are structurally identical; RekognitionService and TextractService
 * each implemented this independently before this class existed.
 *
 * @see <a href="https://docs.aws.amazon.com/rekognition/latest/APIReference/API_Geometry.html">Rekognition Geometry</a>
 * @see <a href="https://docs.aws.amazon.com/textract/latest/dg/API_Geometry.html">Textract Geometry</a>
 */
public final class AwsGeometry {

    private AwsGeometry() {
    }

    public static ObjectNode buildGeometry(double left, double top, double width, double height) {
        ObjectNode geometry = JsonNodeFactory.instance.objectNode();
        ObjectNode bbox = geometry.putObject("BoundingBox");
        bbox.put("Width", width);
        bbox.put("Height", height);
        bbox.put("Left", left);
        bbox.put("Top", top);
        ArrayNode polygon = geometry.putArray("Polygon");
        addPoint(polygon, left, top);
        addPoint(polygon, left + width, top);
        addPoint(polygon, left + width, top + height);
        addPoint(polygon, left, top + height);
        return geometry;
    }

    private static void addPoint(ArrayNode polygon, double x, double y) {
        ObjectNode point = polygon.addObject();
        point.put("X", x);
        point.put("Y", y);
    }
}

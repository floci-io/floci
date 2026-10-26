package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.github.hectorvent.floci.core.common.RequestShapeModel;
import io.github.hectorvent.floci.services.emrserverless.model.CreateApplicationRequest;
import io.github.hectorvent.floci.services.emrserverless.model.StartJobRunRequest;
import io.github.hectorvent.floci.services.emrserverless.model.UpdateApplicationRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The request classes against botocore's model: a member a class has no property for is one the API
 * takes that Floci would drop, or refuse when a Step Functions Task sends it.
 */
class EmrServerlessRequestModelTest {

    private static final RequestShapeModel SHAPES =
            RequestShapeModel.load("aws/emr-serverless-shapes.json", "EMR Serverless shapes");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void theRequestClassesTakeEveryMemberOfTheModel() {
        List<String> missing = new ArrayList<>();
        collectMissing("CreateApplicationRequest", type(CreateApplicationRequest.class), "CreateApplication", missing);
        collectMissing("UpdateApplicationRequest", type(UpdateApplicationRequest.class), "UpdateApplication", missing);
        collectMissing("StartJobRunRequest", type(StartJobRunRequest.class), "StartJobRun", missing);

        assertEquals(List.of(), missing);
    }

    /**
     * The members under {@code path} that {@code type} has no property for, at every level. A map, or
     * a class that takes any property, holds whatever it is given.
     */
    private static void collectMissing(String shapeName, JavaType type, String path, List<String> missing) {
        RequestShapeModel.Shape shape = SHAPES.shapes().get(shapeName);
        if (shape == null) {
            return;
        }
        switch (shape.type()) {
            case "list", "map" -> {
                if (type.getContentType() != null) {
                    collectMissing(shape.element(), type.getContentType(), path + "[]", missing);
                }
            }
            case "structure" -> {
                if (type.isContainerType() || type.getRawClass() == Object.class) {
                    return;
                }
                BeanDescription bean = MAPPER.getDeserializationConfig().introspect(type);
                if (bean.findAnySetterAccessor() != null) {
                    return;
                }
                Map<String, BeanPropertyDefinition> properties = new HashMap<>();
                bean.findProperties().forEach(property -> properties.put(property.getName(), property));
                shape.members().forEach((member, memberShape) -> {
                    BeanPropertyDefinition property = properties.get(member);
                    if (property == null) {
                        missing.add(path + "." + member);
                    } else {
                        collectMissing(memberShape, property.getPrimaryType(), path + "." + member, missing);
                    }
                });
            }
            default -> {
            }
        }
    }

    private static JavaType type(Class<?> type) {
        return MAPPER.constructType(type);
    }
}

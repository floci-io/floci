package io.floci.aws.lifecycle;

import io.floci.aws.services.ecr.registry.EcrGcController;
import io.floci.aws.services.s3.S3Controller;
import io.floci.aws.services.signin.SigninController;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneNotFoundFilterTest {

    @Test
    void controlPlanePaths() {
        assertTrue(ControlPlaneNotFoundFilter.isControlPlanePath("/_floci"));
        assertTrue(ControlPlaneNotFoundFilter.isControlPlanePath("/_floci/health"));
        assertTrue(ControlPlaneNotFoundFilter.isControlPlanePath("_localstack/init"));
        assertFalse(ControlPlaneNotFoundFilter.isControlPlanePath("/_flocix/health"));
        assertFalse(ControlPlaneNotFoundFilter.isControlPlanePath("/_Floci/health"));
        assertFalse(ControlPlaneNotFoundFilter.isControlPlanePath("/mybucket/_floci/health"));
        assertFalse(ControlPlaneNotFoundFilter.isControlPlanePath("/"));
    }

    @Test
    void routesDeclaredUnderThePrefixAreControlPlane() {
        assertTrue(ControlPlaneNotFoundFilter.isControlPlaneRoute(EmulatorInfoController.class, method(EmulatorInfoController.class, "health")));
        assertTrue(ControlPlaneNotFoundFilter.isControlPlaneRoute(UiController.class, null));
        assertTrue(ControlPlaneNotFoundFilter.isControlPlaneRoute(EcrGcController.class, null));
        assertTrue(ControlPlaneNotFoundFilter.isControlPlaneRoute(SigninController.class, method(SigninController.class, "consentPage")));
    }

    @Test
    void s3PathStyleRoutesAreNot() {
        assertFalse(ControlPlaneNotFoundFilter.isControlPlaneRoute(S3Controller.class, method(S3Controller.class, "getObject")));
        assertFalse(ControlPlaneNotFoundFilter.isControlPlaneRoute(SigninController.class, method(SigninController.class, "authorize")));
    }

    @Test
    void notFoundBodyNamesThePath() {
        Response response = ControlPlaneNotFoundFilter.notFound("/_floci/helth");
        assertEquals(404, response.getStatus());
        assertEquals(Map.of("error", "NotFound", "path", "/_floci/helth"), response.getEntity());
    }

    private static Method method(Class<?> type, String name) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }
}

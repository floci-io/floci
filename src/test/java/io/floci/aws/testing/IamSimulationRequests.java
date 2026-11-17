package io.floci.aws.testing;

import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;

import java.util.List;

import static io.restassured.RestAssured.given;

/**
 * What the policy simulator's cases share: the request a simulation arrives as, the member names
 * its organization levels arrive under, and the two readers that take the answer apart.
 *
 * <p>One copy of each, so a change to the request shape or to how an answer is read is made once
 * rather than in every class that simulates something. What a case still writes for itself is the
 * policy documents it is about and the paths into the answer it asserts on.
 */
public final class IamSimulationRequests {

    /** The member the ordered organization levels arrive under, one list of policies per level. */
    public static final String LEVELS = "OrderedOrganizationPolicyInputList.member.";

    private IamSimulationRequests() {
    }

    /** The single service control policy of the level at {@code index}, as a parameter name. */
    public static String level(int index) {
        return LEVELS + index + ".ServiceControlPolicyInputList.member.1";
    }

    /** An IAM Query request signed as {@code accessKeyId}, which is who the call is made by. */
    public static RequestSpecification iam(String accessKeyId, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(accessKeyId, "iam"))
                .formParam("Action", action)
                .formParam("Version", "2010-05-08");
    }

    /** The XML a simulation answers with, which is what a case reads its members out of. */
    public static XmlPath answer(RequestSpecification simulation) {
        return simulation.when().post("/").then().statusCode(200).extract().xmlPath();
    }

    /** Every member at {@code path}, which is the empty list where the answer carries none. */
    public static List<String> strings(XmlPath answer, String path) {
        return answer.getList(path, String.class);
    }
}

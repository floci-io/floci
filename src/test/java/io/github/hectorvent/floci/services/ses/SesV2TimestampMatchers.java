package io.github.hectorvent.floci.services.ses;

import io.restassured.config.JsonConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.path.json.config.JsonPathConfig;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.math.BigDecimal;

/**
 * SES v2 emits timestamps as epoch seconds with a millisecond fraction, e.g.
 * {@code 1.790312384592E9}, as a real-AWS probe of the raw response body showed.
 * Under {@link #DECIMAL_NUMBERS} RestAssured reads a JSON integer token as {@code Integer} or
 * {@code Long} and anything with a fraction or exponent as {@link BigDecimal}, so
 * {@link #epochSecondsWithMillis()} tells the two wire shapes apart.
 */
final class SesV2TimestampMatchers {

    static final RestAssuredConfig DECIMAL_NUMBERS = RestAssuredConfig.config()
            .jsonConfig(JsonConfig.jsonConfig().numberReturnType(JsonPathConfig.NumberReturnType.BIG_DECIMAL));

    private SesV2TimestampMatchers() {
    }

    static Matcher<Object> epochSecondsWithMillis() {
        return new TypeSafeDiagnosingMatcher<>(Object.class) {
            @Override
            protected boolean matchesSafely(Object item, Description mismatch) {
                if (!(item instanceof BigDecimal seconds)) {
                    mismatch.appendText("was a ").appendText(item.getClass().getSimpleName())
                            .appendText(" ").appendValue(item);
                    return false;
                }
                if (seconds.signum() <= 0 || seconds.stripTrailingZeros().scale() > 3) {
                    mismatch.appendText("was ").appendValue(seconds);
                    return false;
                }
                return true;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("positive epoch seconds as a JSON decimal with at most millisecond precision");
            }
        };
    }
}

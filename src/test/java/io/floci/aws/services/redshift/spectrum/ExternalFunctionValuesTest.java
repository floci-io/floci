package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalFunctionValuesTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void integerTypesTravelAsJsonNumbers() {
        assertThat(ExternalFunctionValues.toJson("integer", "42").isIntegralNumber(), is(true));
        assertThat(ExternalFunctionValues.toJson("bigint", "-9223372036854775808").longValue(),
                equalTo(Long.MIN_VALUE));
    }

    @Test
    void numericKeepsItsScaleAndFloatsAreNumbers() {
        assertThat(ExternalFunctionValues.toJson("numeric(10,2)", "1.50").decimalValue().toPlainString(),
                equalTo("1.50"));
        assertThat(ExternalFunctionValues.toJson("double precision", "2.5").doubleValue(), equalTo(2.5));
    }

    @Test
    void anInfiniteFloatTravelsAsItsPostgresText() {
        assertThat(ExternalFunctionValues.toJson("real", "Infinity").textValue(), equalTo("Infinity"));
    }

    @Test
    void booleanCharacterDateAndTimestampValuesMapToTheirJsonTypes() {
        assertThat(ExternalFunctionValues.toJson("boolean", "t").booleanValue(), is(true));
        assertThat(ExternalFunctionValues.toJson("boolean", "false").booleanValue(), is(false));
        assertThat(ExternalFunctionValues.toJson("varchar(20)", "abc").textValue(), equalTo("abc"));
        assertThat(ExternalFunctionValues.toJson("date", "2026-11-23").textValue(), equalTo("2026-11-23"));
        assertThat(ExternalFunctionValues.toJson("timestamp", "2026-11-23 10:15:00").textValue(),
                equalTo("2026-11-23 10:15:00"));
    }

    @Test
    void aSqlNullIsAJsonNullBothWays() {
        assertThat(ExternalFunctionValues.toJson("integer", null).isNull(), is(true));
        assertThat(ExternalFunctionValues.fromJson("integer", JSON.nullNode()), is(nullValue()));
        assertThat(ExternalFunctionValues.fromJson("integer", null), is(nullValue()));
    }

    @Test
    void resultsAreReadBackAsTheTextOfTheReturnType() {
        assertThat(ExternalFunctionValues.fromJson("smallint", JSON.numberNode(7)), equalTo("7"));
        assertThat(ExternalFunctionValues.fromJson("numeric(5,2)", JSON.numberNode(new BigDecimal("3.10"))),
                equalTo("3.10"));
        assertThat(ExternalFunctionValues.fromJson("boolean", JSON.booleanNode(true)), equalTo("true"));
        assertThat(ExternalFunctionValues.fromJson("date", JSON.textNode("2026-11-23")), equalTo("2026-11-23"));
        assertThat(ExternalFunctionValues.fromJson("timestamp", JSON.textNode("2026-11-23T10:15:00")),
                equalTo("2026-11-23 10:15:00"));
        assertThat(ExternalFunctionValues.fromJson("varchar", JSON.textNode("x")), equalTo("x"));
    }

    @Test
    void anIntegerResultMayCarryAZeroFraction() {
        assertThat(ExternalFunctionValues.fromJson("integer", JSON.numberNode(new BigDecimal("3.0"))), equalTo("3"));
        assertThat(ExternalFunctionValues.fromJson("bigint", JSON.numberNode(new BigDecimal("1E+3"))),
                equalTo("1000"));
    }

    @Test
    void theNonFiniteValuesAFloatTravelsAsComeBack() {
        assertThat(ExternalFunctionValues.fromJson("double precision", JSON.textNode("Infinity")), equalTo("Infinity"));
        assertThat(ExternalFunctionValues.fromJson("real", JSON.textNode("NaN")), equalTo("NaN"));
        assertThrows(SpectrumSqlException.class,
                () -> ExternalFunctionValues.fromJson("real", JSON.textNode("a lot")));
    }

    @Test
    void aNumericNanRoundTripsAsItsText() {
        assertThat(ExternalFunctionValues.toJson("numeric", "NaN").textValue(), equalTo("NaN"));
        assertThat(ExternalFunctionValues.fromJson("numeric", JSON.textNode("NaN")), equalTo("NaN"));
        assertThat(ExternalFunctionValues.fromJson("numeric(10,2)", JSON.textNode("nan")), equalTo("NaN"));
        assertThrows(SpectrumSqlException.class,
                () -> ExternalFunctionValues.fromJson("numeric", JSON.textNode("Infinity")));
        assertThrows(SpectrumSqlException.class,
                () -> ExternalFunctionValues.fromJson("numeric", JSON.textNode("not-a-number")));
    }

    @Test
    void anArgumentThatIsNotAValueOfItsTypeIsAnSqlError() {
        for (String[] bad : new String[][] {{"integer", "abc"}, {"numeric", "1.2.3"}, {"double precision", "x"},
                {"boolean", "yes"}}) {
            SpectrumSqlException failed = assertThrows(SpectrumSqlException.class,
                    () -> ExternalFunctionValues.toJson(bad[0], bad[1]));
            assertThat(failed.sqlState(), equalTo("22P02"));
        }
    }

    @Test
    void aResultOfTheWrongJsonTypeFailsTheCall() {
        SpectrumSqlException integerFromString = assertThrows(SpectrumSqlException.class,
                () -> ExternalFunctionValues.fromJson("integer", JSON.textNode("1")));
        assertThat(integerFromString.sqlState(), equalTo("22P02"));
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("integer",
                JSON.numberNode(1.5)));
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("boolean",
                JSON.textNode("true")));
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("varchar",
                JSON.numberNode(1)));
    }

    @Test
    void aResultOutsideTheRangeOfTheReturnTypeFailsTheCall() {
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("smallint",
                JSON.numberNode(40_000)));
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("integer",
                JSON.numberNode(3_000_000_000L)));
    }

    @Test
    void aMalformedDateOrTimestampFailsTheCall() {
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("date",
                JSON.textNode("yesterday")));
        assertThrows(SpectrumSqlException.class, () -> ExternalFunctionValues.fromJson("timestamp",
                JSON.textNode("noon")));
    }
}

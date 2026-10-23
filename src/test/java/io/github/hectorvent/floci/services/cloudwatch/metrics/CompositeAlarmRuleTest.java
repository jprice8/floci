package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompositeAlarmRuleTest {

    private static final String OPERAND = "'(', 'NOT', AT_LEAST, TRUE or FALSE, ALARM, OK, or INSUFFICIENT_DATA";

    /**
     * Rules and the replies CloudWatch gave them: the first five from a public probe in
     * eu-central-1 with a six-character prefix, the last from a CloudFormation deploy of an
     * unquoted alarm name with spaces.
     */
    static Stream<Arguments> cloudWatchSyntaxErrors() {
        return Stream.of(
                Arguments.of("ALARM(\"abcdef-m-a\") AND",
                        "Error in AlarmRule [Unsupported token '<EOF>' at char 23, must be: " + OPERAND + "]"),
                Arguments.of("ALARM(\"abcdef-m-a\"", "Error in AlarmRule [Unsupported token '<EOF>' at char 18]"),
                Arguments.of("ALARM(\"abcdef-m-a\") XOR TRUE",
                        "Error in AlarmRule [Unsupported token 'XOR' at char 20, must be: <EOF>, 'AND', 'OR']"),
                Arguments.of("BOGUS(\"abcdef-m-a\")",
                        "Error in AlarmRule [Unsupported token 'BOGUS' at char 0, must be: " + OPERAND + "]"),
                Arguments.of("false", "Error in AlarmRule [Unsupported token 'false' at char 0, must be: " + OPERAND + "]"),
                Arguments.of("ALARM(SampleQueue Oldest Age Alarm)",
                        "Error in AlarmRule [Unsupported token 'Oldest' at char 18, must be: ')']"));
    }

    @ParameterizedTest
    @MethodSource("cloudWatchSyntaxErrors")
    void syntaxErrorsNameTheTokenAndPositionAsCloudWatchDoes(String rule, String message) {
        AwsException error = assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(rule));
        assertEquals("ValidationError", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals(message, error.getMessage());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            TRUE OR TRUE AND FALSE     | true
            NOT TRUE AND FALSE         | false
            NOT NOT TRUE               | true
            (TRUE OR FALSE) AND FALSE  | false
            FALSE OR NOT (FALSE AND TRUE) | true
            """)
    void notBindsTighterThanAndWhichBindsTighterThanOr(String rule, boolean expected) {
        assertEquals(expected, CompositeAlarmRule.parse(rule).evaluate(reference -> null));
    }

    @Test
    void referencesMayBeQuotedOrNotNamesOrArnsAndAreListedOnce() {
        String arn = "arn:aws:cloudwatch:eu-central-1:123456789012:alarm:abcdef-m-a";
        CompositeAlarmRule rule = CompositeAlarmRule.parse(
                "ALARM(" + arn + ") OR ALARM (\"abcdef-m-b\")\n AND OK(abcdef-m-b) OR ALARM(\"say \\\"hi\\\"\")");
        assertEquals(List.of(arn, "abcdef-m-b", "say \"hi\""), rule.references());
        assertTrue(rule.evaluate(Map.of(arn, "ALARM")::get));
        assertFalse(rule.evaluate(Map.of("abcdef-m-b", "ALARM")::get));
        assertTrue(rule.evaluate(Map.of("say \"hi\"", "ALARM")::get));
    }

    @Test
    void aBackslashOnlyEscapesAQuoteOrABackslash() {
        assertEquals(List.of("a\\b"), CompositeAlarmRule.parse("ALARM(\"a\\b\")").references());
        assertEquals(List.of("a\\b"), CompositeAlarmRule.parse("ALARM(\"a\\\\b\")").references());
        assertEquals(List.of("a\"b"), CompositeAlarmRule.parse("ALARM(\"a\\\"b\")").references());
        assertEquals(List.of("a\\"), CompositeAlarmRule.parse("ALARM(\"a\\\\\")").references());
    }

    @Test
    void atLeastCountsAbsoluteAndPercentageThresholdsOverItsList() {
        CompositeAlarmRule two = CompositeAlarmRule.parse("AT_LEAST(2, ALARM, (\"a\", \"b\", c))");
        assertEquals(List.of("a", "b", "c"), two.references());
        assertFalse(two.evaluate(Map.of("a", "ALARM")::get));
        assertTrue(two.evaluate(Map.of("a", "ALARM", "c", "ALARM")::get));

        // As probed on AWS: 50% of two children is one.
        CompositeAlarmRule half = CompositeAlarmRule.parse("AT_LEAST(50%, NOT OK, (a, b))");
        assertTrue(half.evaluate(Map.of("a", "OK", "b", "ALARM")::get));
        assertFalse(half.evaluate(Map.of("a", "OK", "b", "OK")::get));
        assertTrue(half.evaluate(reference -> "INSUFFICIENT_DATA"));
        assertFalse(CompositeAlarmRule.parse("AT_LEAST(50%, OK, (a, b, c))").evaluate(Map.of("a", "OK")::get));
    }

    /** The children AWS listed as triggering, for the same rules and child states. */
    @Test
    void agreeingChildrenAreThoseWhoseOwnConditionMatchesTheResult() {
        Function<String, String> insufficient = reference -> "INSUFFICIENT_DATA";
        assertEquals(Set.of("a"), CompositeAlarmRule.parse("NOT ALARM(a)").agreeing(insufficient, true));
        assertEquals(Set.of("a"), CompositeAlarmRule.parse("OK(a)").agreeing(insufficient, false));
        assertEquals(Set.of("a", "b"), CompositeAlarmRule.parse("(ALARM(a) OR OK(b)) AND NOT INSUFFICIENT_DATA(b)")
                .agreeing(insufficient, false));
        assertEquals(Set.of("a", "b"), CompositeAlarmRule.parse("AT_LEAST(50%, NOT OK, (a, b))")
                .agreeing(insufficient, true));
        assertEquals(Set.of(), CompositeAlarmRule.parse("TRUE OR FALSE").agreeing(insufficient, true));

        Map<String, String> states = Map.of("a", "ALARM", "b", "INSUFFICIENT_DATA");
        assertEquals(Set.of("a"), CompositeAlarmRule.parse("ALARM(a) OR ALARM(b)").agreeing(states::get, true));
        assertEquals(Set.of("b"), CompositeAlarmRule.parse("ALARM(a) AND ALARM(b)").agreeing(states::get, false));
        assertEquals(Set.of("a"), CompositeAlarmRule.parse("AT_LEAST(2, ALARM, (a, b))").agreeing(states::get, true));
    }

    @Test
    void ruleLengthAndElementCountAreBounded() {
        assertEquals("1 validation error detected: Value null at 'alarmRule' failed to satisfy constraint: "
                + "Member must not be null", assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(null))
                .getMessage());
        assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(""));
        String longest = "TRUE" + " ".repeat(CompositeAlarmRule.MAX_LENGTH - 4);
        assertTrue(CompositeAlarmRule.parse(longest).evaluate(reference -> null));
        assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(longest + " "));

        assertFalse(CompositeAlarmRule.parse("FALSE OR ".repeat(499) + "FALSE").evaluate(reference -> null));
        assertThrows(AwsException.class, () -> CompositeAlarmRule.parse("FALSE OR ".repeat(500) + "FALSE"));
        // Each grouping parenthesis is an element, so 250 nested pairs around one more element is 501.
        assertThrows(AwsException.class,
                () -> CompositeAlarmRule.parse("(".repeat(250) + "TRUE" + ")".repeat(250)));
        assertTrue(CompositeAlarmRule.parse("(".repeat(249) + "TRUE" + ")".repeat(249)).evaluate(reference -> null));
        // NOT is an operator, not an element.
        assertTrue(CompositeAlarmRule.parse("NOT ".repeat(2000) + "TRUE").evaluate(reference -> null));
    }

    static Stream<Arguments> otherSyntaxErrors() {
        return Stream.of(
                Arguments.of("ALARM()", "Error in AlarmRule [Unsupported token ')' at char 6]"),
                Arguments.of("ALARM(a) AND (TRUE",
                        "Error in AlarmRule [Unsupported token '<EOF>' at char 18, must be: ')', 'AND', 'OR']"),
                Arguments.of("ALARM a", "Error in AlarmRule [Unsupported token 'a' at char 6, must be: '(']"),
                Arguments.of("ALARM(\"a", "Error in AlarmRule [Unsupported token '<EOF>' at char 8]"),
                Arguments.of("AT_LEAST(two, ALARM, (a))",
                        "Error in AlarmRule [Unsupported token 'two' at char 9, must be: a number or a percentage]"),
                Arguments.of("AT_LEAST(1, BAD, (a))", "Error in AlarmRule [Unsupported token 'BAD' at char 12, "
                        + "must be: 'NOT', ALARM, OK, or INSUFFICIENT_DATA]"),
                Arguments.of("AT_LEAST(1, ALARM, (a b))",
                        "Error in AlarmRule [Unsupported token 'b' at char 22, must be: ')']"));
    }

    @ParameterizedTest
    @MethodSource("otherSyntaxErrors")
    void otherSyntaxErrorsUseTheSameShape(String rule, String message) {
        assertEquals(message, assertThrows(AwsException.class, () -> CompositeAlarmRule.parse(rule)).getMessage());
    }
}

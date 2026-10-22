package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code AlarmRule} of a composite alarm, parsed in full before the alarm is stored.
 *
 * <p>The language is {@code ALARM(x)}, {@code OK(x)} and {@code INSUFFICIENT_DATA(x)} over an
 * alarm name or ARN, quoted or not, {@code TRUE}, {@code FALSE}, {@code NOT}, {@code AND},
 * {@code OR}, parentheses and {@code AT_LEAST(M, [NOT] STATE, (x, ...))}, where M is a count or
 * a percentage. {@code NOT} binds tighter than {@code AND}, and {@code AND} tighter than
 * {@code OR}. Keywords are case sensitive. A syntax error reports the offending token and its
 * zero-based position in the words CloudWatch uses.
 */
final class CompositeAlarmRule {

    static final int MAX_LENGTH = 10240;
    static final int MAX_ELEMENTS = 500;

    private static final String OPERAND = "'(', 'NOT', AT_LEAST, TRUE or FALSE, ALARM, OK, or INSUFFICIENT_DATA";
    private static final String STATE = "ALARM, OK, or INSUFFICIENT_DATA";
    private static final Pattern AMOUNT = Pattern.compile("(\\d{1,9})(%?)");

    private final Node root;
    private final List<String> references;

    private CompositeAlarmRule(Node root, List<String> references) {
        this.root = root;
        this.references = references;
    }

    static CompositeAlarmRule parse(String rule) {
        if (rule == null) {
            throw validation("1 validation error detected: Value null at 'alarmRule' failed to satisfy constraint: "
                    + "Member must not be null");
        }
        if (rule.isEmpty()) {
            throw validation("1 validation error detected: Value '' at 'alarmRule' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1");
        }
        if (rule.length() > MAX_LENGTH) {
            throw validation("1 validation error detected: Value at 'alarmRule' failed to satisfy constraint: "
                    + "Member must have length less than or equal to " + MAX_LENGTH);
        }
        Parser parser = new Parser(rule);
        Node root = parser.or();
        parser.end();
        return new CompositeAlarmRule(root, List.copyOf(parser.references));
    }

    /** Every alarm the rule names, as written and in the order it first appears. */
    List<String> references() {
        return references;
    }

    /** Whether the rule holds, given each referenced alarm's state ({@code null} if unknown). */
    boolean evaluate(Function<String, String> stateOf) {
        return evaluate(root, stateOf);
    }

    /**
     * The references whose own condition agrees with {@code result}: a condition under an odd
     * number of {@code NOT}s agrees when it is false. These are the children CloudWatch lists as
     * triggering a transition to that result.
     */
    Set<String> agreeing(Function<String, String> stateOf, boolean result) {
        Set<String> agreeing = new LinkedHashSet<>();
        collectAgreeing(root, stateOf, result, agreeing);
        return agreeing;
    }

    private static boolean evaluate(Node node, Function<String, String> stateOf) {
        return switch (node) {
            case Constant(var value) -> value;
            case StateOf(var state, var reference) -> state.equals(stateOf.apply(reference));
            case Not(var operand) -> !evaluate(operand, stateOf);
            case And(var terms) -> terms.stream().allMatch(term -> evaluate(term, stateOf));
            case Or(var terms) -> terms.stream().anyMatch(term -> evaluate(term, stateOf));
            case AtLeast atLeast -> atLeast.holds(stateOf);
        };
    }

    private static void collectAgreeing(Node node, Function<String, String> stateOf, boolean target,
                                        Set<String> agreeing) {
        switch (node) {
            case Constant ignored -> {
                // TRUE and FALSE name no alarm.
            }
            case StateOf(var state, var reference) -> {
                if (state.equals(stateOf.apply(reference)) == target) {
                    agreeing.add(reference);
                }
            }
            case Not(var operand) -> collectAgreeing(operand, stateOf, !target, agreeing);
            case And(var terms) -> terms.forEach(term -> collectAgreeing(term, stateOf, target, agreeing));
            case Or(var terms) -> terms.forEach(term -> collectAgreeing(term, stateOf, target, agreeing));
            case AtLeast atLeast -> {
                for (String reference : atLeast.references()) {
                    if (atLeast.matches(reference, stateOf) == target) {
                        agreeing.add(reference);
                    }
                }
            }
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    sealed interface Node permits Constant, StateOf, Not, And, Or, AtLeast {
    }

    record Constant(boolean value) implements Node {
    }

    record StateOf(String state, String reference) implements Node {
    }

    record Not(Node operand) implements Node {
    }

    record And(List<Node> terms) implements Node {
    }

    record Or(List<Node> terms) implements Node {
    }

    /** {@code AT_LEAST(amount, [NOT] state, (references))}; a percentage amount is of the list's size. */
    record AtLeast(int amount, boolean percent, boolean negated, String state, List<String> references)
            implements Node {

        boolean matches(String reference, Function<String, String> stateOf) {
            return state.equals(stateOf.apply(reference)) != negated;
        }

        boolean holds(Function<String, String> stateOf) {
            long matching = references.stream().filter(reference -> matches(reference, stateOf)).count();
            return percent ? matching * 100 >= (long) amount * references.size() : matching >= amount;
        }
    }

    /** Recursive descent over the rule text; positions in errors are zero-based character offsets. */
    private static final class Parser {
        private final String input;
        private final Set<String> references = new LinkedHashSet<>();
        private int position;
        private int elements;

        Parser(String input) {
            this.input = input;
        }

        Node or() {
            List<Node> terms = new ArrayList<>();
            terms.add(and());
            while (keyword("OR")) {
                terms.add(and());
            }
            return terms.size() == 1 ? terms.getFirst() : new Or(terms);
        }

        private Node and() {
            List<Node> terms = new ArrayList<>();
            terms.add(unary());
            while (keyword("AND")) {
                terms.add(unary());
            }
            return terms.size() == 1 ? terms.getFirst() : new And(terms);
        }

        private Node unary() {
            boolean negated = false;
            while (keyword("NOT")) {
                negated = !negated;
            }
            Node operand = primary();
            return negated ? new Not(operand) : operand;
        }

        private Node primary() {
            skipWhitespace();
            int start = position;
            if (at('(')) {
                position++;
                element();
                Node inner = or();
                skipWhitespace();
                if (!at(')')) {
                    throw unsupported(position, "')', 'AND', 'OR'");
                }
                position++;
                element();
                return inner;
            }
            String word = word();
            return switch (word) {
                case "TRUE", "FALSE" -> {
                    element();
                    yield new Constant("TRUE".equals(word));
                }
                case "ALARM", "OK", "INSUFFICIENT_DATA" -> {
                    expect('(');
                    String reference = name();
                    closeReference();
                    element();
                    references.add(reference);
                    yield new StateOf(word, reference);
                }
                case "AT_LEAST" -> atLeast();
                default -> throw unsupported(start, OPERAND);
            };
        }

        private Node atLeast() {
            expect('(');
            skipWhitespace();
            int start = position;
            Matcher amount = AMOUNT.matcher(word());
            if (!amount.matches()) {
                throw unsupported(start, "a number or a percentage");
            }
            expect(',');
            boolean negated = keyword("NOT");
            skipWhitespace();
            int stateStart = position;
            String state = word();
            if (!List.of("ALARM", "OK", "INSUFFICIENT_DATA").contains(state)) {
                throw unsupported(stateStart, negated ? STATE : "'NOT', " + STATE);
            }
            expect(',');
            expect('(');
            List<String> members = new ArrayList<>();
            do {
                String reference = name();
                element();
                references.add(reference);
                members.add(reference);
                skipWhitespace();
            } while (consume(','));
            closeReference();
            expect(')');
            return new AtLeast(Integer.parseInt(amount.group(1)), !amount.group(2).isEmpty(), negated, state,
                    List.copyOf(members));
        }

        /** An alarm name or ARN: double-quoted with backslash escapes, or a run without spaces,
         * round brackets, commas or quotes. */
        private String name() {
            skipWhitespace();
            if (position >= input.length()) {
                throw unsupported(position, null);
            }
            if (!at('"')) {
                int start = position;
                String name = word();
                if (name.isEmpty()) {
                    throw unsupported(start, null);
                }
                return name;
            }
            StringBuilder name = new StringBuilder();
            position++;
            while (position < input.length()) {
                char next = input.charAt(position++);
                if (next == '"') {
                    return name.toString();
                }
                if (next == '\\' && position < input.length()) {
                    next = input.charAt(position++);
                }
                name.append(next);
            }
            throw unsupported(input.length(), null);
        }

        /** The ')' after an alarm reference; CloudWatch names no alternatives when the rule ends instead. */
        private void closeReference() {
            skipWhitespace();
            if (position >= input.length()) {
                throw unsupported(position, null);
            }
            if (!at(')')) {
                throw unsupported(position, "')'");
            }
            position++;
        }

        void end() {
            skipWhitespace();
            if (position < input.length()) {
                throw unsupported(position, "<EOF>, 'AND', 'OR'");
            }
        }

        private void element() {
            if (++elements > MAX_ELEMENTS) {
                throw validation("Error in AlarmRule [the rule has more than " + MAX_ELEMENTS
                        + " elements; child alarms, TRUE or FALSE statements and parentheses count]");
            }
        }

        private boolean keyword(String keyword) {
            skipWhitespace();
            int start = position;
            if (keyword.equals(word())) {
                return true;
            }
            position = start;
            return false;
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!consume(expected)) {
                throw unsupported(position, "'" + expected + "'");
            }
        }

        private boolean consume(char expected) {
            if (at(expected)) {
                position++;
                return true;
            }
            return false;
        }

        private boolean at(char expected) {
            return position < input.length() && input.charAt(position) == expected;
        }

        /** The longest run from here of characters that cannot end a token. */
        private String word() {
            int start = position;
            while (position < input.length() && !isDelimiter(input.charAt(position))) {
                position++;
            }
            return input.substring(start, position);
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) {
                position++;
            }
        }

        private static boolean isDelimiter(char c) {
            return Character.isWhitespace(c) || c == '(' || c == ')' || c == ',' || c == '"';
        }

        private AwsException unsupported(int at, String expected) {
            String message = "Error in AlarmRule [Unsupported token '" + token(at) + "' at char " + at
                    + (expected == null ? "" : ", must be: " + expected) + "]";
            return validation(message);
        }

        private String token(int at) {
            if (at >= input.length()) {
                return "<EOF>";
            }
            char first = input.charAt(at);
            if (first == '(' || first == ')' || first == ',' || first == '"') {
                return String.valueOf(first);
            }
            int end = at;
            while (end < input.length() && !isDelimiter(input.charAt(end))) {
                end++;
            }
            return input.substring(at, end);
        }
    }
}

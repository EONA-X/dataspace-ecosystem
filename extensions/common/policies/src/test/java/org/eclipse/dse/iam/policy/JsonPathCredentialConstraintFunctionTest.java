package org.eclipse.dse.iam.policy;

import org.assertj.core.api.ThrowingConsumer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.CredentialSubject;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.Issuer;
import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyContextImpl;
import org.eclipse.edc.policy.engine.spi.PolicyScope;
import org.eclipse.edc.policy.model.Operator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dse.iam.policy.CredentialsUtils.VC_CLAIM;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_GENERIC_CLAIM_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;

class JsonPathCredentialConstraintFunctionTest {

    private static final String ISSUER = "did:web:issuer";
    private static final String SUBJECT = "did:web:subject";

    private final JsonPathCredentialConstraintFunction function = new JsonPathCredentialConstraintFunction();

    @ParameterizedTest
    @ValueSource(strings = {DSE_GENERIC_CLAIM_CONSTRAINT + ".$.type.foo"})
    void canHandle(String leftOperand) {
        assertThat(function.canHandle(leftOperand)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.type.foo"})
    void canNotHandle(String leftOperand) {
        assertThat(function.canHandle(leftOperand)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GT", "GEQ", "LT", "LEQ", "IS_A", "IS_ALL_OF", "IS_ANY_OF"})
    void unsupportedOperator(String operator) {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(null, Operator.valueOf(operator), null, null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Invalid operator: this constraint only allows the following operators: [EQ, NEQ, IN, HAS_PART, IS_NONE_OF], but received '%s'.".formatted(operator)));
    }

    @ParameterizedTest
    @ArgumentsSource(EvaluationScenarioProvider.class)
    void evaluate(Map<String, Object> claims, String leftOperand, Operator operator, Object rightOperand, boolean expected) {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, claims);
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand, operator, rightOperand, null, policyContext);

        assertThat(result).isEqualTo(expected);
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_operatorIn_rightOperandNotCollection() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("foo"), Operator.IN, "test", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Array string must be enclosed in square brackets: test"));
    }

    @Test
    void evaluate_operatorIn_arrayStringWithSingleQuotes() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IN, "['foo', 'world', 'bar']", null, policyContext);

        assertThat(result).isTrue();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_operatorIn_arrayStringWithDoubleQuotes() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IN, "[\"foo\", \"world\", \"bar\"]", null, policyContext);

        assertThat(result).isTrue();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_operatorIn_arrayStringWithoutQuotes() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IN, "[foo, world, bar]", null, policyContext);

        assertThat(result).isTrue();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_operatorIn_arrayStringNotFound() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IN, "['foo', 'bar']", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_operatorIn_emptyArrayString() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IN, "[]", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_pathNotFound_shouldReturnFalse() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("coucou"), Operator.EQ, "world", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Missing string or list field 'coucou' in claims"));
    }

    @Test
    void evaluate_noMembershipCredential_shouldReturnFalse() {
        var vc = createVc("unknown", Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.EQ, "world", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Failed to find %s.".formatted(MEMBERSHIP_CREDENTIAL_TYPE)));
    }

    @Test
    void evaluate_hasPartOnScalarClaim_matching_shouldReturnTrue() {
        // JSON-LD can collapse a singleton-list claim to a bare scalar upstream.
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.HAS_PART, "world", null, policyContext);

        assertThat(result).isTrue();
    }

    @Test
    void evaluate_hasPartOnScalarClaim_notMatching_shouldReturnFalse() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.HAS_PART, "other", null, policyContext);

        assertThat(result).isFalse();
    }

    @Test
    void evaluate_eqOnListClaim_shouldReturnFalse() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("groups", List.of("EMDS", "ETDS")));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("groups"), Operator.EQ, "EMDS", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Operator 'EQ' is not supported for a list claim value, only HAS_PART and IS_NONE_OF are"));
    }

    @Test
    void evaluate_hasPartOnPostgresArrayLiteralStringClaim_shouldParseAndMatch() {
        // #595: upstream's naive DB reader can surface a Postgres array column as its raw literal string.
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("groups", "{EMDS,ETDS}"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("groups"), Operator.HAS_PART, "EMDS", null, policyContext);

        assertThat(result).isTrue();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_hasPartOnJsonArrayStringClaim_shouldParseAndMatch() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("groups", "[\"EMDS\",\"ETDS\"]"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("groups"), Operator.HAS_PART, "EMDS", null, policyContext);

        assertThat(result).isTrue();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_hasPartOnListEncodedStringClaim_notMatching_shouldReturnFalse() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("groups", "{EMDS,ETDS}"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("groups"), Operator.HAS_PART, "ERDS", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext.getProblems()).isEmpty();
    }

    @Test
    void evaluate_hasPartOnEmptyPostgresArrayLiteralStringClaim_shouldReturnFalse() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("groups", "{}"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("groups"), Operator.HAS_PART, "EMDS", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext.getProblems()).isEmpty();
    }


    @Test
    void evaluate_isNoneOf_missingClaim_failsClosed() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("coucou"), Operator.IS_NONE_OF, List.of("world"), null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Missing string or list field 'coucou' in claims"));
    }

    @Test
    void evaluate_isNoneOf_noCredential_failsClosed() {
        var vc = createVc("unknown", Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IS_NONE_OF, List.of("other"), null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Failed to find %s.".formatted(MEMBERSHIP_CREDENTIAL_TYPE)));
    }

    @Test
    void evaluate_isNoneOf_emptyRightOperand_failsClosed() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("hello", "world"));
        var policyContext = createPolicyContext(List.of(vc));

        var result = function.evaluate(leftOperand("hello"), Operator.IS_NONE_OF, "[]", null, policyContext);

        assertThat(result).isFalse();
        assertThat(policyContext).satisfies(expectedProblem("Right operand of IS_NONE_OF must list at least one value."));
    }

    private static ParticipantAgentPolicyContext createPolicyContext(List<VerifiableCredential> vcs) {
        return new TestPolicyContext(Map.of(VC_CLAIM, vcs));
    }

    private static VerifiableCredential createVc(String type, Map<String, Object> claims) {
        return VerifiableCredential.Builder.newInstance()
                .type(type)
                .issuer(new Issuer(ISSUER))
                .issuanceDate(Instant.now())
                .credentialSubject(CredentialSubject.Builder.newInstance().claims(claims).id(SUBJECT).build())
                .build();
    }

    private static String leftOperand(String path) {
        return "%s.$.%s.%s".formatted(DSE_GENERIC_CLAIM_CONSTRAINT, MEMBERSHIP_CREDENTIAL_TYPE, path);
    }

    private static ThrowingConsumer<PolicyContext> expectedProblem(String problem) {
        return policyContext -> assertThat(policyContext.getProblems()).containsExactly(problem);
    }

    public static final class EvaluationScenarioProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext extensionContext) {
            return Stream.of(
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.EQ, "bar", true),
                    Arguments.of(Map.of("my/namespace/" + "foo", "bar"), leftOperand("foo"), Operator.EQ, "bar", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.NEQ, "xxx", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IN, List.of("hello", "bar", "world"), true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IN, "['hello', 'bar', 'world']", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IN, "[\"hello\", \"bar\", \"world\"]", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IN, "[hello, bar, world]", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IN, "['hello', 'world']", false),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.EQ, "barr", false),
                    Arguments.of(Map.of("foo", Map.of("hello", "world")), leftOperand("foo.hello"), Operator.EQ, "world", true),
                    Arguments.of(Map.of("foo", Map.of("hello", "world")), leftOperand("foo.hello"), Operator.EQ, "world", true),
                    Arguments.of(Map.of("groups", List.of("EMDS", "ETDS")), leftOperand("groups"), Operator.HAS_PART, "EMDS", true),
                    Arguments.of(Map.of("groups", List.of("EMDS", "ETDS")), leftOperand("groups"), Operator.HAS_PART, "ERDS", false),
                    Arguments.of(Map.of("groups", List.of()), leftOperand("groups"), Operator.HAS_PART, "EMDS", false),
                    Arguments.of(Map.of("my/namespace/" + "groups", List.of("EMDS")), leftOperand("groups"), Operator.HAS_PART, "EMDS", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IS_NONE_OF, List.of("hello", "world"), true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IS_NONE_OF, List.of("hello", "bar"), false),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IS_NONE_OF, "['hello', 'world']", true),
                    Arguments.of(Map.of("foo", "bar"), leftOperand("foo"), Operator.IS_NONE_OF, "['hello', 'bar']", false),
                    Arguments.of(Map.of("groups", List.of("EMDS", "ETDS")), leftOperand("groups"), Operator.IS_NONE_OF, List.of("SANCTIONED"), true),
                    Arguments.of(Map.of("groups", List.of("EMDS", "ETDS")), leftOperand("groups"), Operator.IS_NONE_OF, List.of("ETDS", "SANCTIONED"), false),
                    Arguments.of(Map.of("groups", List.of()), leftOperand("groups"), Operator.IS_NONE_OF, List.of("SANCTIONED"), true),
                    Arguments.of(Map.of("groups", "{EMDS,ETDS}"), leftOperand("groups"), Operator.IS_NONE_OF, "['EMDS']", false)
            );
        }

    }

    public static class TestPolicyContext extends PolicyContextImpl implements ParticipantAgentPolicyContext {

        @PolicyScope
        public static final String SCOPE = "test.scope";

        private final ParticipantAgent agent;

        TestPolicyContext(Map<String, Object> claims) {
            agent = new ParticipantAgent(claims, emptyMap());
        }

        @Override
        public ParticipantAgent participantAgent() {
            return agent;
        }

        @Override
        public String scope() {
            return SCOPE;
        }
    }
}
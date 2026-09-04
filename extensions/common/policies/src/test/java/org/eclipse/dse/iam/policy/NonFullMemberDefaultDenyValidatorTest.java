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
import org.eclipse.edc.policy.model.Action;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.OrConstraint;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.policy.model.Policy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dse.iam.policy.CredentialsUtils.VC_CLAIM;
import static org.eclipse.dse.iam.policy.NonFullMemberDefaultDenyValidator.ALLOWED_LEFT_OPERAND;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;

class NonFullMemberDefaultDenyValidatorTest {

    private static final String ISSUER = "did:web:issuer";
    private static final String SUBJECT = "did:web:subject";

    private final NonFullMemberDefaultDenyValidator<TestPolicyContext> validator = new NonFullMemberDefaultDenyValidator<>();

    @Test
    void apply_fullMember_noAllow_isAllowed() {
        var context = contextFor(membershipCredential("FullMember"));
        var policy = policyWithConstraints();

        assertThat(validator.apply(policy, context)).isTrue();
        assertThat(context.getProblems()).isEmpty();
    }

    @Test
    void apply_fullMember_withAllow_isAllowed() {
        var context = contextFor(membershipCredential("FullMember"));
        var policy = policyWithConstraints(allowConstraint());

        assertThat(validator.apply(policy, context)).isTrue();
    }

    @Test
    void apply_externalMember_noAllow_isDenied() {
        var context = contextFor(membershipCredential("External"));
        var policy = policyWithConstraints();

        assertThat(validator.apply(policy, context)).isFalse();
        assertThat(context).satisfies(expectedProblem(
                "Denied: no explicit allow for non-FullMember participant"));
    }

    @Test
    void apply_externalMember_withMatchingAllow_isAllowed() {
        var context = contextFor(membershipCredential("External"));
        var policy = policyWithConstraints(allowConstraint());

        assertThat(validator.apply(policy, context)).isTrue();
    }

    @Test
    void apply_externalMember_withNonMatchingOperatorAllow_isDenied() {
        var context = contextFor(membershipCredential("External"));
        var nonEqConstraint = AtomicConstraint.Builder.newInstance()
                .leftExpression(new LiteralExpression(ALLOWED_LEFT_OPERAND))
                .operator(Operator.NEQ)
                .rightExpression(new LiteralExpression("External"))
                .build();
        var policy = policyWithConstraints(nonEqConstraint);

        assertThat(validator.apply(policy, context)).isFalse();
    }

    @Test
    void apply_externalMember_withNonMatchingRightOperandAllow_isDenied() {
        var context = contextFor(membershipCredential("External"));
        var wrongValueConstraint = AtomicConstraint.Builder.newInstance()
                .leftExpression(new LiteralExpression(ALLOWED_LEFT_OPERAND))
                .operator(Operator.EQ)
                .rightExpression(new LiteralExpression("Partner"))
                .build();
        var policy = policyWithConstraints(wrongValueConstraint);

        assertThat(validator.apply(policy, context)).isFalse();
    }

    @Test
    void apply_externalMember_withAllowNestedInMultiplicityConstraint_isDenied() {
        var context = contextFor(membershipCredential("External"));
        var or = OrConstraint.Builder.newInstance().constraint(allowConstraint()).build();
        var policy = policyWithConstraints(or);

        assertThat(validator.apply(policy, context)).isFalse();
    }

    @Test
    void apply_noMembershipCredential_noAllow_isDenied() {
        var context = contextFor(otherCredential());
        var policy = policyWithConstraints();

        assertThat(validator.apply(policy, context)).isFalse();
        assertThat(context).satisfies(expectedProblem("Denied: no MembershipCredential presented"));
    }

    @Test
    void apply_noMembershipCredential_withAllow_stillDenied() {
        var context = contextFor(otherCredential());
        var policy = policyWithConstraints(allowConstraint());

        assertThat(validator.apply(policy, context)).isFalse();
        assertThat(context).satisfies(expectedProblem("Denied: no MembershipCredential presented"));
    }

    @Test
    void apply_emptyVcClaim_isDenied() {
        var context = new TestPolicyContext(Map.of(VC_CLAIM, List.of()));
        var policy = policyWithConstraints(allowConstraint());

        assertThat(validator.apply(policy, context)).isFalse();
        assertThat(context).satisfies(expectedProblem("Denied: no MembershipCredential presented"));
    }

    @Test
    void apply_malformedVcClaim_wrongType_isDenied() {
        var context = new TestPolicyContext(Map.of(VC_CLAIM, "not-a-list"));
        var policy = policyWithConstraints(allowConstraint());

        assertThat(validator.apply(policy, context)).isFalse();
        assertThat(context).satisfies(expectedProblem("Denied: no MembershipCredential presented"));
    }

    @Test
    void apply_noVcClaimAtAll_isDenied() {
        var context = new TestPolicyContext(Map.of());
        var policy = policyWithConstraints(allowConstraint());

        assertThat(validator.apply(policy, context)).isFalse();
    }

    @Test
    void apply_membershipCredentialMissingMembershipTypeClaim_treatedAsNonFullMember() {
        var vc = createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("unrelated", "value"));
        var context = contextFor(vc);
        var policy = policyWithConstraints();

        assertThat(validator.apply(policy, context)).isFalse();
    }

    private static VerifiableCredential membershipCredential(String membershipType) {
        return createVc(MEMBERSHIP_CREDENTIAL_TYPE, Map.of("membership", Map.of("membershipType", membershipType)));
    }

    private static VerifiableCredential otherCredential() {
        return createVc("DomainCredential", Map.of("hello", "world"));
    }

    private static AtomicConstraint allowConstraint() {
        return AtomicConstraint.Builder.newInstance()
                .leftExpression(new LiteralExpression(ALLOWED_LEFT_OPERAND))
                .operator(Operator.EQ)
                .rightExpression(new LiteralExpression("External"))
                .build();
    }

    private static Policy policyWithConstraints(org.eclipse.edc.policy.model.Constraint... constraints) {
        var permissionBuilder = Permission.Builder.newInstance().action(Action.Builder.newInstance().type("USE").build());
        for (var constraint : constraints) {
            permissionBuilder.constraint(constraint);
        }
        return Policy.Builder.newInstance().permission(permissionBuilder.build()).build();
    }

    private static TestPolicyContext contextFor(VerifiableCredential vc) {
        return new TestPolicyContext(Map.of(VC_CLAIM, List.of(vc)));
    }

    private static VerifiableCredential createVc(String type, Map<String, Object> claims) {
        return VerifiableCredential.Builder.newInstance()
                .type(type)
                .issuer(new Issuer(ISSUER))
                .issuanceDate(Instant.now())
                .credentialSubject(CredentialSubject.Builder.newInstance().claims(claims).id(SUBJECT).build())
                .build();
    }

    private static ThrowingConsumer<PolicyContext> expectedProblem(String problem) {
        return policyContext -> assertThat(policyContext.getProblems()).containsExactly(problem);
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

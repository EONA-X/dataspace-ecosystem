package org.eclipse.dse.iam.policy;

import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyValidatorRule;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Policy;
import org.eclipse.edc.spi.core.MembershipTypes;

import java.util.Optional;

import static org.eclipse.dse.iam.policy.CredentialsUtils.evaluateClaims;
import static org.eclipse.dse.iam.policy.CredentialsUtils.getCredentialList;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;
import static org.eclipse.edc.policy.model.Operator.EQ;

/**
 * Pre-validator that inverts the default catalog discovery/negotiation/transfer outcome for non-{@code FullMember}
 * participants: denied unless the dataset's policy explicitly allows a {@code membershipType} value, and hard-denied
 * regardless of any allow when no {@code MembershipCredential} is presented at all.
 */
public class NonFullMemberDefaultDenyValidator<C extends ParticipantAgentPolicyContext> implements PolicyValidatorRule<C> {

    static final String MEMBERSHIP_TYPE_PATH = "membership.membershipType";
    static final String ALLOWED_LEFT_OPERAND =
            "%s.$.%s.%s".formatted(DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT, MEMBERSHIP_CREDENTIAL_TYPE, MEMBERSHIP_TYPE_PATH);

    @Override
    public Boolean apply(Policy policy, C context) {
        Optional<VerifiableCredential> membershipCredential = findMembershipCredential(context);
        if (membershipCredential.isEmpty()) {
            context.reportProblem("Denied: no MembershipCredential presented");
            return false;
        }

        boolean isFullMember = membershipCredential.stream()
                .flatMap(vc -> vc.getCredentialSubject().stream())
                .anyMatch(subject -> evaluateClaims(MEMBERSHIP_TYPE_PATH, subject.getClaims(), EQ, MembershipTypes.FULL_MEMBER.getName(), context));

        if (isFullMember || isExplicitlyAllowed(policy)) {
            return true;
        }

        context.reportProblem("Denied: no explicit allow for non-FullMember participant");
        return false;
    }

    private Optional<VerifiableCredential> findMembershipCredential(C context) {
        var credentials = getCredentialList(context.participantAgent());
        if (credentials.failed()) {
            return Optional.empty();
        }
        return credentials.getContent().stream()
                .filter(new CredentialTypePredicate(MEMBERSHIP_CREDENTIAL_TYPE))
                .findFirst();
    }

    private boolean isExplicitlyAllowed(Policy policy) {
        return policy.getPermissions().stream()
                .flatMap(permission -> permission.getConstraints().stream())
                .filter(AtomicConstraint.class::isInstance)
                .map(AtomicConstraint.class::cast)
                .anyMatch(this::isAllowConstraint);
    }

    private boolean isAllowConstraint(AtomicConstraint constraint) {
        Object left = constraint.getLeftExpression() instanceof LiteralExpression le ? le.getValue() : null;
        Object right = constraint.getRightExpression() instanceof LiteralExpression re ? re.getValue() : null;
        return ALLOWED_LEFT_OPERAND.equals(left) && constraint.getOperator() == Operator.EQ && MembershipTypes.EXTERNAL.getName().equals(right);
    }
}

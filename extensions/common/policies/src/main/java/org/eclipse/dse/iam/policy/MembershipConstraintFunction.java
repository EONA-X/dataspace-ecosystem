package org.eclipse.dse.iam.policy;

import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CONSTRAINT;
import static org.eclipse.dse.iam.policy.PolicyConstants.MEMBERSHIP_CREDENTIAL_TYPE;

public class MembershipConstraintFunction<C extends ParticipantAgentPolicyContext> extends AbstractDynamicCredentialConstraintFunction<C> {

    public static final String ACTIVE = "active";

    @Override
    public boolean evaluate(Object leftOperand, Operator operator, Object rightOperand, Permission rule, C context) {

        if (!ACTIVE.equals(rightOperand)) {
            context.reportProblem("Right-operand must be equal to '%s', but was '%s'".formatted(ACTIVE, rightOperand));
            return false;
        }
        if (!canHandle(leftOperand)) {
            context.reportProblem("Invalid left-operand '%s'".formatted(leftOperand));
            return false;
        }

        return getCredentialList(context.participantAgent())
                .map(verifiableCredentials -> verifiableCredentials.stream().anyMatch(new CredentialTypePredicate(MEMBERSHIP_CREDENTIAL_TYPE)))
                .orElse(failure -> {
                    context.reportProblem(failure.getFailureDetail());
                    return false;
                });
    }

    @Override
    public boolean canHandle(Object leftOperand) {
        return leftOperand instanceof String left && PolicyConstants.termIris(MEMBERSHIP_CONSTRAINT).stream().anyMatch(left::equalsIgnoreCase);
    }
}
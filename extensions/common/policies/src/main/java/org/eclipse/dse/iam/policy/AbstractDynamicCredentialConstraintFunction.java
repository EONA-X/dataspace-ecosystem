package org.eclipse.dse.iam.policy;

import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.engine.spi.DynamicAtomicConstraintRuleFunction;
import org.eclipse.edc.policy.engine.spi.PolicyContext;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.result.Result;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public abstract class AbstractDynamicCredentialConstraintFunction<C extends ParticipantAgentPolicyContext> implements DynamicAtomicConstraintRuleFunction<Permission, C> {
    protected boolean checkOperator(Operator actual, PolicyContext context, Collection<Operator> expectedOperators) {
        return CredentialsUtils.checkOperator(actual, context, expectedOperators);
    }

    protected boolean checkNoneOfRightOperand(List<String> right, PolicyContext context) {
        return CredentialsUtils.checkNoneOfRightOperand(right, context);
    }

    /**
     * Extracts a {@link List} of {@link VerifiableCredential} objects from the {@link ParticipantAgent}. Credentials must be
     * stored in the agent's claims map using the "vc" key.
     */
    protected Result<List<VerifiableCredential>> getCredentialList(ParticipantAgent agent) {
        return CredentialsUtils.getCredentialList(agent);
    }

    protected String sanitizeLeftOperand(String leftOperand, String constraintPrefix) {
        return CredentialsUtils.sanitizeLeftOperand(leftOperand, constraintPrefix);
    }

    protected Map<String, Object> sanitizeClaims(Map<String, Object> claims) {
        return CredentialsUtils.sanitizeClaims(claims);
    }

    protected boolean evaluateClaims(String path, Map<String, Object> claims, Operator operator, Object right, PolicyContext policyContext) {
        return CredentialsUtils.evaluateClaims(path, claims, operator, right, policyContext);
    }

    protected boolean evaluateField(String left, Operator operator, Object right, PolicyContext policyContext) {
        return CredentialsUtils.evaluateField(left, operator, right, policyContext);
    }

    protected boolean evaluateCollectionField(Collection<?> left, Operator operator, Object right, PolicyContext policyContext) {
        return CredentialsUtils.evaluateCollectionField(left, operator, right, policyContext);
    }

}

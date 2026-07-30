package org.eclipse.dse.iam.policy;

import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.engine.spi.DynamicAtomicConstraintRuleFunction;
import org.eclipse.edc.policy.engine.spi.PolicyContext;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;
import org.eclipse.edc.spi.result.Result;
import org.eclipse.edc.util.reflection.ReflectionUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.eclipse.edc.policy.model.Operator.EQ;
import static org.eclipse.edc.policy.model.Operator.HAS_PART;
import static org.eclipse.edc.policy.model.Operator.IN;
import static org.eclipse.edc.policy.model.Operator.NEQ;

public abstract class AbstractDynamicCredentialConstraintFunction<C extends ParticipantAgentPolicyContext> implements DynamicAtomicConstraintRuleFunction<Permission, C> {
    public static final String VC_CLAIM = "vc";
    protected static final Collection<Operator> EQUALITY_OPERATORS = List.of(Operator.EQ, Operator.NEQ);

    protected boolean checkOperator(Operator actual, PolicyContext context, Collection<Operator> expectedOperators) {
        if (!expectedOperators.contains(actual)) {
            context.reportProblem("Invalid operator: this constraint only allows the following operators: %s, but received '%s'.".formatted(expectedOperators, actual));
            return false;
        }
        return true;
    }

    /**
     * Extracts a {@link List} of {@link VerifiableCredential} objects from the {@link ParticipantAgent}. Credentials must be
     * stored in the agent's claims map using the "vc" key.
     */
    protected Result<List<VerifiableCredential>> getCredentialList(ParticipantAgent agent) {
        var vcListClaim = agent.getClaims().get(VC_CLAIM);

        if (vcListClaim == null) {
            return Result.failure("ParticipantAgent did not contain a '%s' claim.".formatted(VC_CLAIM));
        }
        if (!(vcListClaim instanceof List)) {
            return Result.failure("ParticipantAgent contains a '%s' claim, but the type is incorrect. Expected %s, received %s.".formatted(VC_CLAIM, List.class.getName(), vcListClaim.getClass().getName()));
        }
        var vcList = (List<VerifiableCredential>) vcListClaim;
        if (vcList.isEmpty()) {
            return Result.failure("ParticipantAgent contains a '%s' claim but it did not contain any VerifiableCredentials.".formatted(VC_CLAIM));
        }
        return Result.success(vcList);
    }

    /** Parses a list claim serialized as a string ({@code {a,b}}/{@code ["a","b"]}, see #595); null for a scalar. */
    protected static List<String> parseListEncodedClaim(String value) {
        String trimmed = value.trim();
        boolean isBraced = trimmed.startsWith("{") && trimmed.endsWith("}");
        boolean isBracketed = trimmed.startsWith("[") && trimmed.endsWith("]");
        if (!isBraced && !isBracketed) {
            return null;
        }

        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            return List.of();
        }

        return Arrays.stream(content.split(","))
                .map(part -> part.trim().replaceAll("^['\"]|['\"]$", ""))
                .collect(Collectors.toList());
    }

    protected String sanitizeLeftOperand(String leftOperand, String constraintPrefix) {
        return leftOperand.replace("%s.$.".formatted(constraintPrefix), "");
    }

    protected Map<String, Object> sanitizeClaims(Map<String, Object> claims) {
        var sanitized = new HashMap<String, Object>();
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String key = entry.getKey();
            int lastSlashIndex = key.lastIndexOf('/');
            if (lastSlashIndex != -1) {
                key = key.substring(lastSlashIndex + 1);
            }
            sanitized.put(key, entry.getValue());
        }
        return sanitized;
    }

    protected boolean evaluateClaims(String path, Map<String, Object> claims, Operator operator, Object right, PolicyContext policyContext) {
        var sanitized = sanitizeClaims(claims);
        var leftValue = ReflectionUtil.getFieldValue(path, sanitized);
        if (leftValue instanceof String stringValue) {
            List<String> listEncoded = parseListEncodedClaim(stringValue);
            if (listEncoded != null) {
                return evaluateCollectionField(listEncoded, operator, right, policyContext);
            }
            return evaluateField(stringValue, operator, right, policyContext);
        }
        if (leftValue instanceof Collection<?> collectionValue) {
            return evaluateCollectionField(collectionValue, operator, right, policyContext);
        }
        policyContext.reportProblem("Missing string or list field '%s' in claims".formatted(path));
        return false;
    }

    protected boolean evaluateField(String left, Operator operator, Object right, PolicyContext policyContext) {
        return switch (operator) {
            case EQ -> left.equals(right);
            case NEQ -> !left.equals(right);
            case IN -> ((List<String>) right).contains(left);
            // JSON-LD can collapse a singleton-list claim to a bare scalar, so treat HAS_PART here as a singleton match.
            case HAS_PART -> left.equals(right);
            default -> {
                policyContext.reportProblem("Operator '%s' is not supported for a scalar claim value".formatted(operator));
                yield false;
            }
        };
    }

    protected boolean evaluateCollectionField(Collection<?> left, Operator operator, Object right, PolicyContext policyContext) {
        if (operator != HAS_PART) {
            policyContext.reportProblem("Operator '%s' is not supported for a list claim value, only HAS_PART is".formatted(operator));
            return false;
        }
        return left.contains(right);
    }

}
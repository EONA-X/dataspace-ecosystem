package org.eclipse.dse.iam.policy;

import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.policy.model.Permission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT;
import static org.eclipse.edc.policy.model.Operator.EQ;
import static org.eclipse.edc.policy.model.Operator.HAS_PART;
import static org.eclipse.edc.policy.model.Operator.IN;
import static org.eclipse.edc.policy.model.Operator.IS_NONE_OF;
import static org.eclipse.edc.policy.model.Operator.NEQ;


public class CatalogDiscoveryConstraintFunction<C extends ParticipantAgentPolicyContext> extends AbstractDynamicCredentialConstraintFunction<C> {

    private static final List<Operator> SUPPORTED_OPERATORS = List.of(EQ, NEQ, IN, HAS_PART, IS_NONE_OF);
    private static final Pattern STRING_VALUE_PATTERN = Pattern.compile("string=([^,}\\]]+)");

    @Override
    public boolean evaluate(Object leftOperand, Operator operator, Object rightOperand, Permission rule, C policyContext) {
        if (!checkOperator(operator, policyContext, SUPPORTED_OPERATORS)) {
            return false;
        }

        if (!canHandle(leftOperand)) {
            policyContext.reportProblem("Invalid left-operand '%s'".formatted(leftOperand));
            return false;
        }

        Object processedRightOperand;
        if (operator.equals(IN) || operator.equals(IS_NONE_OF)) {
            List<String> parsedRightOperand = parseToList(rightOperand);
            if (!checkNonEmptyListRightOperand(operator, parsedRightOperand, policyContext)) {
                return false;
            }
            processedRightOperand = parsedRightOperand;
        } else {
            processedRightOperand = rightOperand;
        }

        var sanitizedLeftOperand = sanitizeLeftOperand((String) leftOperand, DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT);
        var parts = sanitizedLeftOperand.split("\\.");
        if (parts.length < 2) {
            policyContext.reportProblem("Left operand must contain at least two parts but was '%s'.".formatted(sanitizedLeftOperand));
            return false;
        }

        var credentialType = parts[0];
        var path = String.join(".", Arrays.copyOfRange(parts, 1, parts.length));
        var credentials = getCredentialList(policyContext.participantAgent());
        if (credentials.failed()) {
            policyContext.reportProblem("Failed to get Credentials list.");
            return false;
        }

        var credential = credentials.getContent().stream()
                .filter(new CredentialTypePredicate(credentialType))
                .findFirst()
                .orElse(null);
        if (credential == null) {
            policyContext.reportProblem("Failed to find %s.".formatted(credentialType));
            return false;
        }

        return credential.getCredentialSubject().stream()
                .findFirst()
                .map(credentialSubject -> evaluateClaims(path, credentialSubject.getClaims(), operator, processedRightOperand, policyContext))
                .orElseGet(() -> {
                    policyContext.reportProblem("No credential subject found in Credential");
                    return false;
                });
    }

    @Override
    public boolean canHandle(Object leftOperand) {
        return (leftOperand instanceof String) && ((String) leftOperand).startsWith("%s.$".formatted(DSE_RESTRICTED_CATALOG_DISCOVERY_CONSTRAINT));
    }

    public static List<String> parseToList(Object input) {
        if (input == null) {
            return Collections.emptyList();
        }

        String raw = input.toString().trim();
        List<String> result = new ArrayList<>();

        Matcher matcher = STRING_VALUE_PATTERN.matcher(raw);

        while (matcher.find()) {
            result.add(matcher.group(1));
        }

        if (!result.isEmpty()) {
            return result;
        }

        if (raw.length() <= 2) {
            return Collections.emptyList();
        }

        raw = raw.substring(1, raw.length() - 1).trim();

        String[] parts = raw.split("\\s*,\\s*");

        for (String part : parts) {
            part = part.replaceAll("^['\"]|['\"]$", "");
            if (!part.isEmpty()) {
                result.add(part);
            }
        }

        return result;
    }

}
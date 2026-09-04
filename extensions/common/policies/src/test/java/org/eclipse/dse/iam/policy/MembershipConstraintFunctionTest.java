package org.eclipse.dse.iam.policy;

import org.eclipse.edc.iam.verifiablecredentials.spi.model.VerifiableCredential;
import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.participant.spi.ParticipantAgent;
import org.eclipse.edc.participant.spi.ParticipantAgentPolicyContext;
import org.eclipse.edc.policy.engine.spi.PolicyContextImpl;
import org.eclipse.edc.policy.engine.spi.PolicyScope;
import org.eclipse.edc.policy.model.Operator;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.dse.iam.policy.CredentialsUtils.VC_CLAIM;
import static org.eclipse.dse.iam.policy.MembershipConstraintFunction.ACTIVE;
import static org.eclipse.dse.iam.policy.PolicyConstants.DSE_MEMBERSHIP_CONSTRAINT;

class MembershipConstraintFunctionTest {

    private static final TypeManager TYPE_MANAGER = new JacksonTypeManager();
    private static final String TEST_VC = "{\n" +
            "      \"credentialSubject\": [\n" +
            "        {\n" +
            "          \"id\": \"did:web:subject\",\n" +
            "          \"membership\": {\n" +
            "            \"membershipType\": \"FullMember\",\n" +
            "            \"website\": \"www.some-other-website.com\",\n" +
            "            \"contact\": \"bar.baz@company.com\",\n" +
            "            \"since\": \"2024-02-20T12:37:06Z\"\n" +
            "          }\n" +
            "        }\n" +
            "      ],\n" +
            "      \"id\": \"88f2e6a3-a382-4c57-b9bb-b833525c650d\",\n" +
            "      \"type\": [\n" +
            "        \"VerifiableCredential\",\n" +
            "        \"https://w3id.org/eonax/credentials/MembershipCredential\"\n" +
            "      ],\n" +
            "      \"issuer\": {\n" +
            "        \"id\": \"did:web:issuer\",\n" +
            "        \"additionalProperties\": {}\n" +
            "      },\n" +
            "      \"issuanceDate\": \"2024-02-20T12:37:06Z\",\n" +
            "      \"expirationDate\": null,\n" +
            "      \"credentialStatus\": null,\n" +
            "      \"description\": null,\n" +
            "      \"name\": null\n" +
            "    }";

    private final MembershipConstraintFunction function = new MembershipConstraintFunction();

    @Test
    void evaluate_success() {
        var vc = TYPE_MANAGER.readValue(TEST_VC, VerifiableCredential.class);
        Map<String, Object> claims = Map.of(VC_CLAIM, List.of(vc));
        var context = new TestPolicyContext(claims);

        var result = function.evaluate(DSE_MEMBERSHIP_CONSTRAINT, Operator.EQ, ACTIVE, null, context);

        assertThat(result).isTrue();
    }

    @Test
    void evaluate_noMembershipClaim_shouldReturnFalse() {
        var context = new TestPolicyContext(Map.of());

        var result = function.evaluate(DSE_MEMBERSHIP_CONSTRAINT, Operator.EQ, ACTIVE, null, context);

        assertThat(result).isFalse();
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
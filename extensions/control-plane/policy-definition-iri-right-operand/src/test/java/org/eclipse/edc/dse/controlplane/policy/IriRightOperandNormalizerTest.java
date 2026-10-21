package org.eclipse.edc.dse.controlplane.policy;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.eclipse.edc.connector.controlplane.api.management.policy.validation.PolicyDefinitionValidator;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToConstraintTransformer;
import org.eclipse.edc.connector.controlplane.transform.odrl.to.JsonObjectToOperatorTransformer;
import org.eclipse.edc.policy.model.AtomicConstraint;
import org.eclipse.edc.policy.model.LiteralExpression;
import org.eclipse.edc.transform.TransformerContextImpl;
import org.eclipse.edc.transform.TypeTransformerRegistryImpl;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IriRightOperandNormalizerTest {

    private static final String ODRL = "http://www.w3.org/ns/odrl/2/";
    private static final String TERMS = "https://provider.example/terms/api-1.1";

    /** The expanded form of a policy definition whose obligation constraint has an IRI right operand. */
    private static final String IRI_RIGHT_OPERAND_POLICY = """
            {
              "@id": "terms-gate",
              "@type": ["https://w3id.org/edc/v0.0.1/ns/PolicyDefinition"],
              "https://w3id.org/edc/v0.0.1/ns/policy": [{
                "@type": ["http://www.w3.org/ns/odrl/2/Set"],
                "http://www.w3.org/ns/odrl/2/obligation": [{
                  "http://www.w3.org/ns/odrl/2/action": [{"@id": "http://www.w3.org/ns/odrl/2/reviewPolicy"}],
                  "http://www.w3.org/ns/odrl/2/constraint": [{
                    "http://www.w3.org/ns/odrl/2/leftOperand": [{"@id": "https://w3id.org/eonax/policy/acceptedTerms"}],
                    "http://www.w3.org/ns/odrl/2/operator": [{"@id": "http://www.w3.org/ns/odrl/2/eq"}],
                    "http://www.w3.org/ns/odrl/2/rightOperand": [{"@id": "%s"}]
                  }]
                }]
              }]
            }
            """.formatted(TERMS);

    /** The guided editor's "Membership active" template: a value right operand, already accepted. */
    private static final String VALUE_RIGHT_OPERAND_POLICY = """
            {
              "@id": "membership",
              "@type": ["https://w3id.org/edc/v0.0.1/ns/PolicyDefinition"],
              "https://w3id.org/edc/v0.0.1/ns/policy": [{
                "@type": ["http://www.w3.org/ns/odrl/2/Set"],
                "http://www.w3.org/ns/odrl/2/permission": [{
                  "http://www.w3.org/ns/odrl/2/action": [{"@id": "http://www.w3.org/ns/odrl/2/use"}],
                  "http://www.w3.org/ns/odrl/2/constraint": [{
                    "http://www.w3.org/ns/odrl/2/leftOperand": [{"@id": "https://w3id.org/eonax/policy/Membership"}],
                    "http://www.w3.org/ns/odrl/2/operator": [{"@id": "http://www.w3.org/ns/odrl/2/eq"}],
                    "http://www.w3.org/ns/odrl/2/rightOperand": [{"@value": "active"}]
                  }]
                }]
              }]
            }
            """;

    @Test
    void edcValidatorThrowsOnAnIriRightOperand_whichIsWhyThisExtensionExists() {
        var policy = parse(IRI_RIGHT_OPERAND_POLICY);

        // eclipse-edc/Connector#6066: an exception here is what the management API turns into a 500.
        assertThatThrownBy(() -> PolicyDefinitionValidator.instance().validate(policy))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void normalizedIriRightOperand_passesEdcValidation_andTransformsToTheIri() {
        var normalized = IriRightOperandNormalizer.normalize(parse(IRI_RIGHT_OPERAND_POLICY));

        assertThat(PolicyDefinitionValidator.instance().validate(normalized).succeeded()).isTrue();

        var constraint = firstConstraint(normalized, "obligation");
        assertThat(constraint.getJsonArray(ODRL + "rightOperand").getJsonObject(0))
                .isEqualTo(Json.createObjectBuilder()
                        .add("@value", TERMS)
                        .add("@type", IriRightOperandNormalizer.XSD_ANY_URI)
                        .build());

        var registry = new TypeTransformerRegistryImpl();
        registry.register(new JsonObjectToConstraintTransformer());
        registry.register(new JsonObjectToOperatorTransformer());
        var transformed = (AtomicConstraint) new TransformerContextImpl(registry).transform(constraint, org.eclipse.edc.policy.model.Constraint.class);
        assertThat(((LiteralExpression) transformed.getRightExpression()).getValue()).isEqualTo(TERMS);
    }

    @Test
    void valueRightOperand_isLeftUntouched() {
        var policy = parse(VALUE_RIGHT_OPERAND_POLICY);

        assertThat(IriRightOperandNormalizer.normalize(policy)).isSameAs(policy);
    }

    @Test
    void iriRightOperandsAreRewrittenAtEveryDepth() {
        var nested = parse("""
                {
                  "http://www.w3.org/ns/odrl/2/prohibition": [{
                    "http://www.w3.org/ns/odrl/2/action": [{
                      "http://www.w3.org/ns/odrl/2/refinement": [{
                        "http://www.w3.org/ns/odrl/2/rightOperand": [{"@id": "https://provider.example/party/a"}]
                      }]
                    }],
                    "http://www.w3.org/ns/odrl/2/remedy": [{
                      "http://www.w3.org/ns/odrl/2/constraint": [{
                        "http://www.w3.org/ns/odrl/2/or": [{"@list": [{
                          "http://www.w3.org/ns/odrl/2/rightOperand": [{"@id": "https://provider.example/b"}]
                        }]}]
                      }]
                    }]
                  }]
                }
                """);

        var normalized = IriRightOperandNormalizer.normalize(nested).toString();

        assertThat(normalized)
                .doesNotContain("{\"@id\":\"https://provider.example/party/a\"}")
                .doesNotContain("{\"@id\":\"https://provider.example/b\"}")
                .contains("{\"@value\":\"https://provider.example/party/a\",\"@type\":\"" + IriRightOperandNormalizer.XSD_ANY_URI + "\"}")
                .contains("{\"@value\":\"https://provider.example/b\",\"@type\":\"" + IriRightOperandNormalizer.XSD_ANY_URI + "\"}");
    }

    @Test
    void arrayOfIriRightOperands_withIsNoneOf_isRewrittenItemByItem() {
        var policy = parse("""
                {
                  "@id": "blocklist",
                  "@type": ["https://w3id.org/edc/v0.0.1/ns/PolicyDefinition"],
                  "https://w3id.org/edc/v0.0.1/ns/policy": [{
                    "@type": ["http://www.w3.org/ns/odrl/2/Set"],
                    "http://www.w3.org/ns/odrl/2/prohibition": [{
                      "http://www.w3.org/ns/odrl/2/action": [{"@id": "http://www.w3.org/ns/odrl/2/use"}],
                      "http://www.w3.org/ns/odrl/2/constraint": [{
                        "http://www.w3.org/ns/odrl/2/leftOperand": [{"@id": "https://w3id.org/eonax/policy/Membership"}],
                        "http://www.w3.org/ns/odrl/2/operator": [{"@id": "http://www.w3.org/ns/odrl/2/isNoneOf"}],
                        "http://www.w3.org/ns/odrl/2/rightOperand": [
                          {"@id": "https://provider.example/party/a"},
                          {"@id": "https://provider.example/party/b"},
                          {"@id": "https://provider.example/party/c"}
                        ]
                      }]
                    }]
                  }]
                }
                """);

        var constraint = firstConstraint(IriRightOperandNormalizer.normalize(policy), "prohibition");

        var rightOperands = constraint.getJsonArray(ODRL + "rightOperand");
        assertThat(rightOperands).hasSize(3);
        assertThat(rightOperands).containsExactly(
                typedIri("https://provider.example/party/a"),
                typedIri("https://provider.example/party/b"),
                typedIri("https://provider.example/party/c"));
        assertThat(constraint.getJsonArray(ODRL + "operator").getJsonObject(0).getString("@id"))
                .isEqualTo(ODRL + "isNoneOf");
    }

    @Test
    void arrayOfIriRightOperands_withIsNoneOf_passesEdcValidationOnlyOnceNormalized() {
        var policy = parse("""
                {
                  "@id": "blocklist",
                  "@type": ["https://w3id.org/edc/v0.0.1/ns/PolicyDefinition"],
                  "https://w3id.org/edc/v0.0.1/ns/policy": [{
                    "@type": ["http://www.w3.org/ns/odrl/2/Set"],
                    "http://www.w3.org/ns/odrl/2/prohibition": [{
                      "http://www.w3.org/ns/odrl/2/action": [{"@id": "http://www.w3.org/ns/odrl/2/use"}],
                      "http://www.w3.org/ns/odrl/2/constraint": [{
                        "http://www.w3.org/ns/odrl/2/leftOperand": [{"@id": "https://w3id.org/eonax/policy/Membership"}],
                        "http://www.w3.org/ns/odrl/2/operator": [{"@id": "http://www.w3.org/ns/odrl/2/isNoneOf"}],
                        "http://www.w3.org/ns/odrl/2/rightOperand": [
                          {"@id": "https://provider.example/party/a"},
                          {"@id": "https://provider.example/party/b"}
                        ]
                      }]
                    }]
                  }]
                }
                """);

        assertThatThrownBy(() -> PolicyDefinitionValidator.instance().validate(policy))
                .isInstanceOf(NullPointerException.class);
        assertThat(PolicyDefinitionValidator.instance().validate(IriRightOperandNormalizer.normalize(policy)).succeeded()).isTrue();
    }

    @Test
    void idOutsideRightOperand_isLeftUntouched() {
        var policy = parse(IRI_RIGHT_OPERAND_POLICY);

        var constraint = firstConstraint(IriRightOperandNormalizer.normalize(policy), "obligation");

        assertThat(constraint.getJsonArray(ODRL + "leftOperand").getJsonObject(0).getString("@id"))
                .isEqualTo("https://w3id.org/eonax/policy/acceptedTerms");
        assertThat(constraint.getJsonArray(ODRL + "operator").getJsonObject(0).getString("@id"))
                .isEqualTo(ODRL + "eq");
    }

    private static JsonObject typedIri(String iri) {
        return Json.createObjectBuilder()
                .add("@value", iri)
                .add("@type", IriRightOperandNormalizer.XSD_ANY_URI)
                .build();
    }

    private static JsonObject firstConstraint(JsonObject policyDefinition, String ruleKind) {
        return policyDefinition.getJsonArray("https://w3id.org/edc/v0.0.1/ns/policy").getJsonObject(0)
                .getJsonArray(ODRL + ruleKind).getJsonObject(0)
                .getJsonArray(ODRL + "constraint").getJsonObject(0);
    }

    static JsonObject parse(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            return reader.readObject();
        }
    }
}

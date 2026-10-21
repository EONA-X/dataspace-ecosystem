package org.eclipse.edc.dse.controlplane.policy;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.ID;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.TYPE;
import static org.eclipse.edc.jsonld.spi.JsonLdKeywords.VALUE;
import static org.eclipse.edc.jsonld.spi.PropertyAndTypeNames.ODRL_RIGHT_OPERAND_ATTRIBUTE;

/**
 * Rewrites every IRI right operand ({@code "odrl:rightOperand": {"@id": X}}) of an expanded JSON-LD
 * document into the typed value {@code {"@value": X, "@type": xsd:anyURI}}.
 * <p>
 * EDC 0.15 only accepts value right operands: its policy-definition validator throws a
 * NullPointerException (HTTP 500) on a node without {@code @value}, and its constraint transformer
 * would keep the raw node instead of the IRI (eclipse-edc/Connector#6066). After this rewrite the
 * constraint holds the IRI {@code X} as a string, which is what the constraint functions compare.
 * <p>
 * The walk is recursive, so constraints in permissions, prohibitions, obligations, duties, remedies,
 * consequences, action refinements and logical constraints are all covered. A document without an
 * IRI right operand is returned unchanged (same instance).
 */
public final class IriRightOperandNormalizer {

    static final String XSD_ANY_URI = "http://www.w3.org/2001/XMLSchema#anyURI";

    private IriRightOperandNormalizer() {
    }

    public static JsonObject normalize(JsonObject expanded) {
        return (JsonObject) normalizeValue(expanded);
    }

    private static JsonValue normalizeValue(JsonValue value) {
        return switch (value.getValueType()) {
            case OBJECT -> normalizeObject(value.asJsonObject());
            case ARRAY -> normalizeArray(value.asJsonArray(), false);
            default -> value;
        };
    }

    private static JsonObject normalizeObject(JsonObject object) {
        var builder = Json.createObjectBuilder();
        var changed = false;
        for (var entry : object.entrySet()) {
            var original = entry.getValue();
            var normalized = ODRL_RIGHT_OPERAND_ATTRIBUTE.equals(entry.getKey()) && original.getValueType() == JsonValue.ValueType.ARRAY
                    ? normalizeArray(original.asJsonArray(), true)
                    : normalizeValue(original);
            changed |= normalized != original;
            builder.add(entry.getKey(), normalized);
        }
        return changed ? builder.build() : object;
    }

    private static JsonArray normalizeArray(JsonArray array, boolean rightOperand) {
        var builder = Json.createArrayBuilder();
        var changed = false;
        for (var item : array) {
            var normalized = rightOperand && isIriNode(item) ? typedIri(item.asJsonObject()) : normalizeValue(item);
            changed |= normalized != item;
            builder.add(normalized);
        }
        return changed ? builder.build() : array;
    }

    private static boolean isIriNode(JsonValue item) {
        if (item.getValueType() != JsonValue.ValueType.OBJECT) {
            return false;
        }
        var object = item.asJsonObject();
        return object.size() == 1 && object.get(ID) != null && object.get(ID).getValueType() == JsonValue.ValueType.STRING;
    }

    private static JsonObject typedIri(JsonObject node) {
        return Json.createObjectBuilder()
                .add(VALUE, node.getString(ID))
                .add(TYPE, XSD_ANY_URI)
                .build();
    }
}

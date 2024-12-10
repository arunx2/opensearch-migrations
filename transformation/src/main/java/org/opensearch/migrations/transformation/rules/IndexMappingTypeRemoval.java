package org.opensearch.migrations.transformation.rules;

import org.opensearch.migrations.transformation.CanApplyResult;
import org.opensearch.migrations.transformation.CanApplyResult.Unsupported;
import org.opensearch.migrations.transformation.TransformationRule;
import org.opensearch.migrations.transformation.entity.Index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Supports transformation of the Index Mapping types that were changed from mutliple types to a single type between ES 6 to ES 7
 *
 * Example:
 * Starting state (ES 6):
 * {
 *   "mappings": [
 *     {
 *       "foo": {
 *         "properties": {
 *           "field1": { "type": "text" },
 *           "field2": { "type": "keyword" }
 *         }
 *       }
 *     }
 *   ]
 * }
 *
 * Ending state (ES 7):
 * {
 *   "mappings": {
 *     "properties": {
 *       "field1": { "type": "text" },
 *       "field2": { "type": "keyword" },
 *     }
 *   }
 * }
 */
@Slf4j
@AllArgsConstructor
public class IndexMappingTypeRemoval implements TransformationRule<Index> {
    public enum MultiTypeResolutionBehavior {
        UNION,
        SPLIT
    }

    public static final String PROPERTIES_KEY = "properties";
    public static final String MAPPINGS_KEY = "mappings";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public final MultiTypeResolutionBehavior multiTypeResolutionBehavior;

    // Default with SPLIT
    public IndexMappingTypeRemoval() {
        this(MultiTypeResolutionBehavior.SPLIT);
    }

    @Override
    public CanApplyResult canApply(final Index index) {
        final var mappingNode = index.getRawJson().get(MAPPINGS_KEY);

        if (mappingNode == null || mappingNode.size() == 0) {
            return CanApplyResult.NO;
        }

        // Check for absence of intermediate type node
        // 1. <pre>{"mappings": {"properties": {...} }}</pre>
        if (mappingNode.isObject() && mappingNode.get("properties") != null) {
            return CanApplyResult.NO;
        }

        // Detect multiple type mappings:
        // 1. <pre>{"mappings": [{ "foo": {...} }, { "bar": {...} }]}</pre>
        // 2. <pre>{"mappings": [{ "foo": {...}, "bar": {...}  }]}</pre>
        if (mappingNode.isArray() && (mappingNode.size() > 1 || mappingNode.get(0).size() > 1)) {
            if (MultiTypeResolutionBehavior.SPLIT.equals(multiTypeResolutionBehavior)) {
                return new Unsupported("Split on multiple mapping types is not supported");
            }
            // Support UNION
        }

        // There is a type under mappings
        // 1. <pre>{ "mappings": [{ "foo": {...} }] }</pre>
        return CanApplyResult.YES;
    }

    @Override
    public boolean applyTransformation(final Index index) {
        if (CanApplyResult.YES != canApply(index)) {
            return false;
        }

        final var mappingsNode = index.getRawJson().get(MAPPINGS_KEY);
        // Handle array case
        if (mappingsNode.isArray()) {
            final var resolvedMappingsNode = MAPPER.createObjectNode();
            if (mappingsNode.size() < 2) {
                final var mappingsInnerNode = (ObjectNode) mappingsNode.get(0);
                var properties = mappingsInnerNode.get(PROPERTIES_KEY);
                resolvedMappingsNode.set(PROPERTIES_KEY, properties);
            } else if (MultiTypeResolutionBehavior.UNION.equals(multiTypeResolutionBehavior)) {
                var resolvedProperties = resolvedMappingsNode.withObjectProperty(PROPERTIES_KEY);
                var mappings = (ArrayNode) mappingsNode;
                mappings.forEach(
                        typeNodeEntry -> {
                            var typeNode = typeNodeEntry.properties().stream().findFirst().orElseThrow();
                            var type = typeNode.getKey();
                            var node = typeNode.getValue();
                            var properties = node.get(PROPERTIES_KEY);
                            properties.properties().forEach(propertyEntry -> {
                                var fieldName = propertyEntry.getKey();
                                var fieldType = propertyEntry.getValue();

                                if (resolvedProperties.has(fieldName)) {
                                    var existingFieldType = resolvedProperties.get(fieldName);
                                    if (existingFieldType != fieldType) {
                                        log.atWarn().setMessage("Conflict during type union with index: {}\n" +
                                                        "field: {}\n" +
                                                        "existingFieldType: {}\n" +
                                                        "type: {}" +
                                                        "secondFieldType: {}")
                                                .addArgument(index.getName())
                                                .addArgument(fieldName)
                                                .addArgument(existingFieldType)
                                                .addArgument(type)
                                                .addArgument(fieldType)
                                                .log();
                                        throw new IllegalStateException("Cannot union index multi type mappings with conflicting field mapping types");
                                    }
                                } else {
                                    resolvedProperties.set(fieldName, fieldType);
                                }
                            });
                        }
                );
                index.getRawJson().set(MAPPINGS_KEY, resolvedMappingsNode);
            }
        }

        if (mappingsNode.isObject()) {
            var mappingsObjectNode = (ObjectNode) mappingsNode;
            var typeNode = mappingsNode.fields().next();
            var typeNodeChildren = typeNode.getValue().fields();
            // Check if the type node is empty, then there is nothing to move
            if (typeNodeChildren.hasNext()) {
                var propertiesNode = typeNodeChildren.next();

                mappingsObjectNode.set(propertiesNode.getKey(), propertiesNode.getValue());
            }
            mappingsObjectNode.remove(typeNode.getKey());
        }
        return true;
    }
}


/*
 * Copyright 2020 LINE Corporation
 * ... (Copyright header is the same) ...
 */
package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;

/**
 * Generates a JSON Schema from the given service specification.
 *
 * @see <a href="https://json-schema.org/">JSON schema</a>
 */
final class JsonSchemaGenerator {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    // [수정] 이 클래스는 이제 상태를 가지지 않는 유틸리티 클래스로 변경합니다.
    private JsonSchemaGenerator() {}

    /**
     * Generate an array of json schema specifications for each method inside the service.
     *
     * @param serviceSpecification the service specification to generate the json schema from.
     * @return ArrayNode that contains service specifications
     */
    static ArrayNode generate(ServiceSpecification serviceSpecification) {
        requireNonNull(serviceSpecification, "serviceSpecification");

        // [추가] 모든 Struct와 Enum에 대한 공용 definitions를 먼저 생성합니다.
        final ObjectNode definitions = generateDefinitions(serviceSpecification);

        final ArrayNode methodSchemas = mapper.createArrayNode();
        for (ServiceInfo serviceInfo : serviceSpecification.services()) {
            for (MethodInfo methodInfo : serviceInfo.methods()) {
                final ObjectNode schema = generateForMethod(methodInfo, definitions);
                methodSchemas.add(schema);
            }
        }
        // 실제 DocService UI는 각 메소드별 스키마를 사용하므로 최종 반환값은 이전과 같이 ArrayNode를 유지합니다.
        // 하지만 각 스키마는 이제 내부에 definitions를 포함하게 됩니다.
        return methodSchemas;
    }

    /**
     * [추가] 모든 Struct와 Enum에 대한 공용 'definitions' JSON 객체를 생성합니다.
     * 다형성 처리는 이 메소드에서 이루어집니다.
     */
    private static ObjectNode generateDefinitions(ServiceSpecification serviceSpecification) {
        final ObjectNode definitions = mapper.createObjectNode();

        for (StructInfo structInfo : serviceSpecification.structs()) {
            final ObjectNode schemaNode = mapper.createObjectNode();
            schemaNode.put("type", "object");
            schemaNode.put("title", structInfo.name());
            final String docString = structInfo.descriptionInfo().docString();
            if (!docString.isEmpty()) {
                schemaNode.put("description", docString);
            }

            // [추가] 다형성 처리의 핵심 로직
            final List<TypeSignature> oneOf = structInfo.oneOf();
            if (!oneOf.isEmpty()) {
                final ArrayNode oneOfNode = schemaNode.putArray("oneOf");
                oneOf.forEach(subType -> {
                    final ObjectNode refNode = mapper.createObjectNode();
                    refNode.put("$ref", "#/definitions/" + subType.name());
                    oneOfNode.add(refNode);
                });

                final DiscriminatorInfo discriminator = structInfo.discriminator();
                if (discriminator != null) {
                    final ObjectNode discriminatorNode = schemaNode.putObject("discriminator");
                    discriminatorNode.put("propertyName", discriminator.propertyName());
                }
            } else {
                // 다형성 타입이 아닐 경우에만 properties를 정의합니다.
                final ObjectNode propertiesNode = mapper.createObjectNode();
                final ArrayNode requiredNode = mapper.createArrayNode();

                for (FieldInfo field : structInfo.fields()) {
                    propertiesNode.set(field.name(), generateForField(field));
                    if (field.requirement() == FieldRequirement.REQUIRED) {
                        requiredNode.add(field.name());
                    }
                }

                if (!propertiesNode.isEmpty()) {
                    schemaNode.set("properties", propertiesNode);
                }
                if (!requiredNode.isEmpty()) {
                    schemaNode.set("required", requiredNode);
                }
            }
            definitions.set(structInfo.name(), schemaNode);
        }

        for (EnumInfo enumInfo : serviceSpecification.enums()) {
            final ObjectNode schemaNode = mapper.createObjectNode();
            schemaNode.put("type", "string");
            schemaNode.put("title", enumInfo.name());
            final String docString = enumInfo.descriptionInfo().docString();
            if (!docString.isEmpty()) {
                schemaNode.put("description", docString);
            }
            final ArrayNode enumValues = mapper.createArrayNode();
            enumInfo.values().forEach(value -> enumValues.add(value.name()));
            schemaNode.set("enum", enumValues);
            definitions.set(enumInfo.name(), schemaNode);
        }

        return definitions;
    }

    /**
     * [추가] 특정 메소드에 대한 최종 스키마를 생성합니다.
     */
    private static ObjectNode generateForMethod(MethodInfo methodInfo, ObjectNode definitions) {
        final ObjectNode root = mapper.createObjectNode();
        root.put("$id", methodInfo.id())
                .put("title", methodInfo.name())
                .put("description", methodInfo.descriptionInfo().docString())
                .put("type", "object");

        final ObjectNode properties = mapper.createObjectNode();
        final ArrayNode required = mapper.createArrayNode();

        for (FieldInfo field : methodInfo.parameters()) {
            // 메소드 파라미터는 대부분 BODY에 있는 객체 하나를 참조하는 형태가 됩니다.
            if (field.location() == FieldLocation.BODY || field.location() == FieldLocation.UNSPECIFIED) {
                properties.set(field.name(), generateForField(field));
                if (field.requirement() == FieldRequirement.REQUIRED) {
                    required.add(field.name());
                }
            }
        }

        root.set("properties", properties);
        if (!required.isEmpty()) {
            root.set("required", required);
        }
        // 생성된 공용 definitions를 각 메소드 스키마에 추가합니다.
        root.set("definitions", definitions);
        return root;
    }

    /**
     * [추가] 특정 필드에 대한 스키마(또는 참조)를 생성합니다.
     */
    private static ObjectNode generateForField(FieldInfo field) {
        final ObjectNode fieldNode = mapper.createObjectNode();
        final String docString = field.descriptionInfo().docString();
        if (!docString.isEmpty()) {
            fieldNode.put("description", docString);
        }

        final TypeSignature typeSignature = field.typeSignature();
        switch (typeSignature.type()) {
            case STRUCT:
            case ENUM:
                // Struct나 Enum은 definitions를 참조하도록 $ref를 생성합니다.
                fieldNode.put("$ref", "#/definitions/" + typeSignature.name());
                break;
            case ITERABLE:
                fieldNode.put("type", "array");
                final TypeSignature itemType = ((ContainerTypeSignature) typeSignature).typeParameters().get(0);
                fieldNode.set("items", generateForField(FieldInfo.of("items", itemType)));
                break;
            case MAP:
                fieldNode.put("type", "object");
                final TypeSignature valueType = ((MapTypeSignature) typeSignature).valueTypeSignature();
                fieldNode.set("additionalProperties",
                        generateForField(FieldInfo.of("additionalProperties", valueType)));
                break;
            case BASE:
            case UNRESOLVED:
            default:
                fieldNode.put("type", getSchemaType(typeSignature));
                break;
        }
        return fieldNode;
    }

    /**
     * Get the JSON type for the given type. Unknown types are returned as `object`.
     * This list can be extended to support more types.
     *
     * @see <a href="https://json-schema.org/understanding-json-schema/reference/type.html">JSON Schema</a>
     */
    private static String getSchemaType(TypeSignature typeSignature) {
        if (typeSignature.type() == TypeSignatureType.ENUM) {
            return "string";
        }

        if (typeSignature.type() == TypeSignatureType.ITERABLE) {
            switch (typeSignature.name().toLowerCase()) {
                case "repeated":
                case "list":
                case "array":
                case "set":
                    return "array";
                default:
                    return "object";
            }
        }

        if (typeSignature.type() == TypeSignatureType.MAP) {
            return "object";
        }

        if (typeSignature.type() == TypeSignatureType.BASE) {
            switch (typeSignature.name().toLowerCase()) {
                case "boolean":
                case "bool":
                    return "boolean";
                case "short":
                case "number":
                case "float":
                case "double":
                    return "number";
                case "i":
                case "i8":
                case "i16":
                case "i32":
                case "i64":
                case "integer":
                case "int":
                case "l32":
                case "l64":
                case "long":
                case "long32":
                case "long64":
                case "int32":
                case "int64":
                case "uint32":
                case "uint64":
                case "sint32":
                case "sint64":
                case "fixed32":
                case "fixed64":
                case "sfixed32":
                case "sfixed64":
                    return "integer";
                case "binary":
                case "byte":
                case "bytes":
                case "string":
                    return "string";
                default:
                    return "object";
            }
        }

        return "object";
    }
}
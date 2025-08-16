/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.docs.DocServiceTypeUtil.toTypeSignature;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.server.annotation.Description;

/**
 * A {@link DescriptiveTypeInfoProvider} that provides {@link DescriptiveTypeInfo} for a polymorphic
 * type by inspecting Jackson annotations such as {@link JsonTypeInfo} and {@link JsonSubTypes}.
 */
public final class JacksonPolymorphismTypeInfoProvider implements DescriptiveTypeInfoProvider {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    @Override
    @Nullable
    public DescriptiveTypeInfo newDescriptiveTypeInfo(Object typeDescriptor) {
        requireNonNull(typeDescriptor, "typeDescriptor");
        if (!(typeDescriptor instanceof Class)) {
            return null;
        }

        final Class<?> clazz = (Class<?>) typeDescriptor;
        final JsonTypeInfo jsonTypeInfo = clazz.getAnnotation(JsonTypeInfo.class);
        final JsonSubTypes jsonSubTypes = clazz.getAnnotation(JsonSubTypes.class);

        if (jsonTypeInfo == null || jsonSubTypes == null) {
            return null;
        }

        final String propertyName = jsonTypeInfo.property();
        if (propertyName.isEmpty()) {
            return null;
        }

        final DiscriminatorInfo discriminator = new DiscriminatorInfo(propertyName);

        final List<TypeSignature> oneOf =
                Arrays.stream(jsonSubTypes.value())
                        .map(subType -> TypeSignature.ofStruct(subType.value()))
                        .collect(toImmutableList());

        final JavaType javaType = mapper.constructType(clazz);
        final BeanDescription description = mapper.getSerializationConfig().introspect(javaType);
        final List<BeanPropertyDefinition> properties = description.findProperties();

        final List<FieldInfo> fields = properties.stream()
                .map(prop -> FieldInfo.of(prop.getName(),
                        toTypeSignature(prop.getPrimaryType())))
                .collect(toImmutableList());

        final Description classDescription = clazz.getAnnotation(Description.class);

        // [수정] null 체크를 추가하여 NullPointerException을 방지합니다.
        final DescriptionInfo descriptionInfo = classDescription != null ?
                DescriptionInfo.from(classDescription) :
                DescriptionInfo.empty();

        return new StructInfo(clazz.getName(), null, fields,
                descriptionInfo, oneOf, discriminator);
    }
}
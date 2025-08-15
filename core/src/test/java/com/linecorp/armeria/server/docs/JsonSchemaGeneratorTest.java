/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.linecorp.armeria.common.MediaType;
import org.hamcrest.CustomTypeSafeMatcher;
import org.junit.jupiter.api.Test;

import net.javacrumbs.jsonunit.core.internal.Node.JsonMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;

class JsonSchemaGeneratorTest {

    // Common Fixtures
    private static final String methodName = "test-method";
    private static final DescriptionInfo methodDescription = DescriptionInfo.of("test method");

    // Generate a fake ServiceSpecification that only contains the happy path to parameters
    private static StructInfo newStructInfo(String name, List<FieldInfo> parameters) {
        return new StructInfo(name, parameters);
    }

    private static FieldInfo newFieldInfo() {
        return FieldInfo.of("request", TypeSignature.ofStruct(methodName, new Object()));
    }

    private static MethodInfo newMethodInfo(FieldInfo... parameters) {
        return new MethodInfo(
                "test-service",
                methodName,
                TypeSignature.ofBase("void"),
                Arrays.asList(parameters),
                true,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                HttpMethod.POST,
                methodDescription
        );
    }

    private static ServiceSpecification generateServiceSpecification(StructInfo... structInfos) {
        return new ServiceSpecification(
                ImmutableList.of(
                        new ServiceInfo(
                                "test-service",
                                ImmutableList.of(newMethodInfo(newFieldInfo())),
                                DescriptionInfo.empty()
                        )
                ),
                ImmutableList.of(),
                Arrays.stream(structInfos).collect(Collectors.toList()),
                ImmutableList.of()
        );
    }

    @Test
    void testGenerateSimpleMethodWithoutParameters() {
        final List<FieldInfo> parameters = ImmutableList.of();
        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        // Base properties
        assertThatJson(jsonSchema).node("title").isEqualTo(methodName);
        assertThatJson(jsonSchema).node("description").isEqualTo(methodDescription.docString());
        assertThatJson(jsonSchema).node("type").isEqualTo("object");

        // Method specific properties
        assertThatJson(jsonSchema).node("properties").matches(
                new CustomTypeSafeMatcher<JsonMap>("has no key") {
                    @Override
                    protected boolean matchesSafely(JsonMap item) {
                        return item.keySet().size() == 0;
                    }
                });
        assertThatJson(jsonSchema).node("additionalProperties").isEqualTo(false);
    }

    @Test
    void testGenerateSimpleMethodWithPrimitiveParameters() {
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.of("param2", TypeSignature.ofBase("double"),
                             DescriptionInfo.of("param2 description")),
                FieldInfo.of("param3", TypeSignature.ofBase("string"),
                             DescriptionInfo.of("param3 description")),
                FieldInfo.of("param4", TypeSignature.ofBase("boolean"),
                             DescriptionInfo.of("param4 description")));
        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        // Base properties
        assertThatJson(jsonSchema).node("title").isEqualTo(methodName);
        assertThatJson(jsonSchema).node("description").isEqualTo(methodDescription.docString());
        assertThatJson(jsonSchema).node("type").isEqualTo("object");

        // Method specific properties
        assertThatJson(jsonSchema).node("properties").matches(
                new CustomTypeSafeMatcher<JsonMap>("has 4 keys") {
                    @Override
                    protected boolean matchesSafely(JsonMap item) {
                        return item.keySet().size() == 4;
                    }
                });
        assertThatJson(jsonSchema).node("properties.param1.type").isEqualTo("integer");
        assertThatJson(jsonSchema).node("properties.param2.type").isEqualTo("number");
        assertThatJson(jsonSchema).node("properties.param3.type").isEqualTo("string");
        assertThatJson(jsonSchema).node("properties.param4.type").isEqualTo("boolean");
    }

    @Test
    void testMethodWithRecursivePath() {
        final Object commonTypeObjectForRecursion = new Object();
        final List<FieldInfo> parameters = ImmutableList.of(
                FieldInfo.of("param1", TypeSignature.ofBase("int"), DescriptionInfo.of("param1 description")),
                FieldInfo.builder("paramRecursive", TypeSignature.ofStruct("rec", commonTypeObjectForRecursion))
                         .build()
        );

        final StructInfo structInfo = newStructInfo(methodName, parameters);

        final List<FieldInfo> parametersOfRec = ImmutableList.of(
                FieldInfo.of("inner-param1", TypeSignature.ofBase("int32")),
                FieldInfo.of("inner-recurse", TypeSignature.ofStruct("rec", commonTypeObjectForRecursion))
        );
        final StructInfo rec = newStructInfo("rec", parametersOfRec);

        final ServiceSpecification serviceSpecification = generateServiceSpecification(structInfo, rec);
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(serviceSpecification).get(0);

        assertThatJson(jsonSchema).node("properties.paramRecursive.properties.inner-param1").isPresent();
        assertThatJson(jsonSchema).node("properties.paramRecursive.properties.inner-recurse.$ref").isEqualTo(
                "#/properties/paramRecursive");
    }




    /**
     * A top-level interface for all animals in the test, defining the polymorphism contract.
     */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            property = "species"
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Dog.class, name = "dog"),
            @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    interface Animal {
        String name();
    }

    /**
     * An abstract class for mammals, providing common properties.
     */
    abstract static class Mammal implements Animal {
        private final String name;

        protected Mammal(String name) {
            this.name = requireNonNull(name, "name");
        }

        @Override
        public String name() {
            return name;
        }

        /**
         * Returns the sound this mammal makes.
         */
        public abstract String sound();
    }

    /**
     * A simple DTO representing a toy, used as a nested object.
     */
    static final class Toy {
        private final String toyName;
        private final String color;

        Toy(String toyName, String color) {
            this.toyName = requireNonNull(toyName, "toyName");
            this.color = requireNonNull(color, "color");
        }

        public String toyName() {
            return toyName;
        }

        public String color() {
            return color;
        }
    }

    /**
     * A concrete implementation representing a Dog.
     */
    static final class Dog extends Mammal {
        private final int age;
        private final String[] favoriteFoods;
        private final Toy favoriteToy;

        Dog(String name, int age, String[] favoriteFoods, Toy favoriteToy) {
            super(name);
            this.age = age;
            this.favoriteFoods = requireNonNull(favoriteFoods, "favoriteFoods");
            this.favoriteToy = requireNonNull(favoriteToy, "favoriteToy");
        }

        @Override
        public String sound() {
            return "woof woof";
        }

        public int age() {
            return age;
        }

        public String[] favoriteFoods() {
            return favoriteFoods;
        }

        public Toy favoriteToy() {
            return favoriteToy;
        }
    }

    /**
     * A concrete implementation representing a Cat.
     */
    static final class Cat extends Mammal {
        private final boolean likesTuna;

        Cat(String name, boolean likesTuna) {
            super(name);
            this.likesTuna = likesTuna;
        }

        @Override
        public String sound() {
            return "meow meow";
        }

        public boolean likesTuna() {
            return likesTuna;
        }
    }



    /**
     * Verifies that the JSON schema for a polymorphic type is correctly generated
     * with 'oneOf' and 'discriminator' properties according to the OpenAPI 3.0 specification.
     */
    @Test
    void shouldGenerateOneOfForPolymorphicType() {
        // 1. Arrange (Given): Build the ServiceSpecification manually.
        final StructInfo toyInfo = new StructInfo(
                Toy.class.getName(),
                ImmutableList.of(
                        FieldInfo.of("toyName", TypeSignature.ofBase("string")),
                        FieldInfo.of("color", TypeSignature.ofBase("string"))
                )
        );

        final StructInfo dogInfo = new StructInfo(
                Dog.class.getName(),
                ImmutableList.of(
                        FieldInfo.of("name", TypeSignature.ofBase("string")),
                        FieldInfo.of("sound", TypeSignature.ofBase("string")),
                        FieldInfo.of("age", TypeSignature.ofBase("int")),
                        FieldInfo.of("favoriteFoods", TypeSignature.ofList(TypeSignature.ofBase("string"))),
                        FieldInfo.of("favoriteToy", TypeSignature.ofStruct(Toy.class))
                )
        );

        final StructInfo catInfo = new StructInfo(
                Cat.class.getName(),
                ImmutableList.of(
                        FieldInfo.of("name", TypeSignature.ofBase("string")),
                        FieldInfo.of("sound", TypeSignature.ofBase("string")),
                        FieldInfo.of("likesTuna", TypeSignature.ofBase("boolean"))
                )
        );

        // The current system doesn't know the relationship between Animal, Dog, and Cat.
        final StructInfo animalInfo = new StructInfo(
                Animal.class.getName(),
                ImmutableList.of(FieldInfo.of("name", TypeSignature.ofBase("string")))
        );

        final EndpointInfo endpoint = EndpointInfo.builder("*", "/test-polymorphism")
                .defaultMimeType(MediaType.JSON_UTF_8).build();

        final MethodInfo testMethod = new MethodInfo(
                "animal-service",                                                                 // serviceName
                "animalMethod",                                                                   // name
                0,                                                                              // overloadId
                TypeSignature.ofBase("void"),                                                  // returnType
                ImmutableList.of(FieldInfo.of("animal", TypeSignature.ofStruct(Animal.class))), // parameters
                ImmutableList.of(),                                                             // exampleHeaders
                ImmutableList.of(endpoint),                                                     // endpoints
                HttpMethod.POST,                                                                // httpMethod
                DescriptionInfo.empty()                                                         // descriptionInfo
        );

        final ServiceSpecification specification = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("test-service", ImmutableList.of(testMethod))),
                ImmutableList.of(), // enums
                ImmutableList.of(animalInfo, dogInfo, catInfo, toyInfo), // all structs
                ImmutableList.of()  // exceptions
        );

        // 2. Act (When): Generate the JSON schema.
        final JsonNode jsonSchema = JsonSchemaGenerator.generate(specification);

        // 3. Assert (Then): Verify the generated schema.
        final JsonNode animalSchema = findSchema(jsonSchema, Animal.class.getName());
        assertThat(animalSchema).isNotNull();

        // This is the part that will FAIL.
        assertThatJson(animalSchema).node("oneOf").isArray().ofLength(2);
        assertThatJson(animalSchema).node("oneOf[0].$ref")
                .isEqualTo("#/definitions/" + Dog.class.getName());
        assertThatJson(animalSchema).node("oneOf[1].$ref")
                .isEqualTo("#/definitions/" + Cat.class.getName());

        assertThatJson(animalSchema).node("discriminator.propertyName").isEqualTo("species");
    }

    private static JsonNode findSchema(JsonNode jsonSchema, String name) {
        for (JsonNode schema : jsonSchema) {
            if (schema.get("title").asText().equals(name)) {
                return schema;
            }
        }
        return null;
    }


}

/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.server.annotation;

import static java.util.Objects.requireNonNull;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.testing.TestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class PolymorphismDocServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(PolymorphismDocServiceTest.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String dogExampleRequest =
            "{\"species\":\"dog\", \"name\":\"Buddy\", \"age\":5, \"favoriteFoods\":[\"beef\"]," +
            "\"favoriteToy\":{\"toyName\":\"ball\", \"color\":\"red\"}}";

    private static final String catExampleRequest =
            "{\"species\":\"cat\", \"name\":\"Lucy\", \"likesTuna\":true," +
            "\"scratchPost\":{\"toyName\":\"tower\", \"color\":\"beige\"}}";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            if (TestUtil.isDocServiceDemoMode()) {
                sb.http(8081);
            }
            sb.annotatedService("/api", new AnimalService());
            sb.serviceUnder("/docs",
                            DocService.builder()
                                      .exampleRequests(AnimalService.class, "processAnimal",
                                                       dogExampleRequest, catExampleRequest)
                                      .build());
        }
    };

    /**
     * Test 1: Verifies specification generation, including polymorphism and example requests.
     */
    @Test
    void specificationShouldBeGeneratedCorrectly() throws Exception {
        if (TestUtil.isDocServiceDemoMode()) {
            Thread.sleep(Long.MAX_VALUE);
        }

        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final String specificationJson = res.contentUtf8();

        final String animalClassName = Animal.class.getName();
        final String dogClassName = Dog.class.getName();
        final String catClassName = Cat.class.getName();

        final JsonNode specNode = mapper.readTree(specificationJson);
        final JsonNode structsNode = specNode.path("structs");

        boolean animalStructFound = false;
        for (final JsonNode struct : structsNode) {
            if (animalClassName.equals(struct.path("name").asText())) {
                animalStructFound = true;

                final JsonNode oneOfNode = struct.path("oneOf");
                assertThat(oneOfNode.isArray()).isTrue();

                final List<String> oneOfList = new ArrayList<>();
                oneOfNode.forEach(node -> oneOfList.add(node.asText()));

                assertThat(oneOfList).containsExactlyInAnyOrder(dogClassName, catClassName);

                assertThatJson(struct).node("discriminator.propertyName").isStringEqualTo("species");

                break;
            }
        }

        assertThat(animalStructFound)
                .as("Animal struct with polymorphism info not found in specification.json")
                .isTrue();
    }

    /**
     * Test 2: Verifies runtime deserialization of a single polymorphic object.
     */
    @Test
    void shouldDeserializePolymorphicObject() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();

        // Test Dog deserialization
        final AggregatedHttpResponse responseForDog = client.post("/api/animal", dogExampleRequest).aggregate()
                                                            .join();
        assertThat(responseForDog.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForDog.contentUtf8()).contains("woof");

        // Test Cat deserialization
        final AggregatedHttpResponse responseForCat = client.post("/api/animal", catExampleRequest).aggregate()
                                                            .join();
        assertThat(responseForCat.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForCat.contentUtf8()).contains("meow");
    }

    /**
     * Test 3: Verifies runtime deserialization of a complex object containing a list of polymorphic objects.
     */
    @Test
    void shouldDeserializeNestedPolymorphicList() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();

        final String zooRequest = "{" +
                                  "  \"animals\": [" +
                                  "    " + dogExampleRequest + "," +
                                  "    " + catExampleRequest +
                                  "  ]" +
                                  "}";

        final AggregatedHttpResponse response = client.post("/api/zoo", zooRequest).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("Received 1 dogs and 1 cats");
    }

    /**
     * Test 4: Verifies that the deserialized object is an instance of the correct class.
     */
    @Test
    void shouldDeserializeToObjectOfCorrectClass() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();

        final AggregatedHttpResponse responseForDog = client.post("/api/animal/class", dogExampleRequest)
                                                            .aggregate().join();
        assertThat(responseForDog.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForDog.contentUtf8()).isEqualTo(Dog.class.getSimpleName());

        final AggregatedHttpResponse responseForCat = client.post("/api/animal/class", catExampleRequest)
                                                            .aggregate().join();
        assertThat(responseForCat.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForCat.contentUtf8()).isEqualTo(Cat.class.getSimpleName());
    }

    @SuppressWarnings("checkstyle:CommentsIndentation")
    @Test
    void specificationForEmptySubTypes() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);

        final String specificationJson = res.contentUtf8();
        final JsonNode specNode = mapper.readTree(specificationJson);

        final String misconfiguredClassName = MisconfiguredAnimal.class.getName();

        boolean structFound = false;
        for (final JsonNode struct : specNode.path("structs")) {
            if (misconfiguredClassName.equals(struct.path("name").asText())) {
                structFound = true;

//                // 시나리오 1 ("As-Is"): 현재 코드는 oneOf 필드를 생성하지만, 내용은 비어있을 것입니다.
//                assertThatJson(struct).node("oneOf").isAbsent(); // oneOf 필드는 없는 것을 검증
//                assertThatJson(struct).node("discriminator").isObject(); // discriminator는 객체로 존재하는 것을 검증
//                assertThatJson(struct).node("discriminator.mapping").isEqualTo("{}");// mapping은 비어있음

                assertThatJson(struct).node("oneOf").isAbsent();
                assertThatJson(struct).node("discriminator").isAbsent();

                assertThatJson(struct).node("fields").isArray().isEmpty();
                break;
            }
        }
        assertThat(structFound).isTrue();
    }

    // --- DTOs and Service for the test ---

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "species")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Dog.class, name = "dog"),
            @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    interface Animal {
        String name();
    }

    abstract static class Mammal implements Animal {
        @JsonProperty
        private final String name;

        protected Mammal(String name) {
            this.name = requireNonNull(name, "name");
        }

        @Override
        public String name() {
            return name;
        }

        public abstract String sound();
    }

    static final class Toy {
        @JsonProperty
        private final String toyName;
        @JsonProperty
        private final String color;

        @JsonCreator
        Toy(@JsonProperty("toyName") String toyName, @JsonProperty("color") String color) {
            this.toyName = requireNonNull(toyName, "toyName");
            this.color = requireNonNull(color, "color");
        }
    }

    static final class Dog extends Mammal {
        @JsonProperty
        private final int age;
        @JsonProperty
        private final String[] favoriteFoods;
        @JsonProperty
        private final Toy favoriteToy;

        @JsonCreator
        Dog(@JsonProperty("name") String name, @JsonProperty("age") int age,
            @JsonProperty("favoriteFoods") String[] favoriteFoods, @JsonProperty("favoriteToy") Toy toy) {
            super(name);
            this.age = age;
            this.favoriteFoods = requireNonNull(favoriteFoods, "favoriteFoods");
            this.favoriteToy = requireNonNull(toy, "favoriteToy");
        }

        @Override
        public String sound() {
            return "woof";
        }
    }

    static final class Cat extends Mammal {
        @JsonProperty
        private final boolean likesTuna;
        @JsonProperty
        private final Toy scratchPost;

        @JsonCreator
        Cat(@JsonProperty("name") String name, @JsonProperty("likesTuna") boolean likesTuna,
            @JsonProperty("scratchPost") Toy scratchPost) {
            super(name);
            this.likesTuna = likesTuna;
            this.scratchPost = requireNonNull(scratchPost, "scratchPost");
        }

        @Override
        public String sound() {
            return "meow";
        }
    }

    static class Zoo {
        @JsonProperty
        private final List<Animal> animals;

        @JsonCreator
        Zoo(@JsonProperty("animals") List<Animal> animals) {
            this.animals = ImmutableList.copyOf(requireNonNull(animals, "animals"));
        }
    }

    // Test DTO for misconfigured @JsonSubTypes
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({}) // Intentionally empty
    interface MisconfiguredAnimal {
        String name();
    }

    static class Lion implements MisconfiguredAnimal {
        @Override
        public String name() {
            return "Simba";
        }
    }

    public static class AnimalService {
        @Post("/animal")
        public String processAnimal(Animal animal) {
            String response = "Received animal named: " + animal.name();
            if (animal instanceof Mammal) {
                response += ". It says: " + ((Mammal) animal).sound();
            }
            logger.info(response);
            return response;
        }

        @Post("/zoo")
        public String processZoo(Zoo zoo) {
            final long dogCount = zoo.animals.stream().filter(Dog.class::isInstance).count();
            final long catCount = zoo.animals.stream().filter(Cat.class::isInstance).count();
            final String response = String.format("Received %d dogs and %d cats", dogCount, catCount);
            logger.info(response);
            return response;
        }

        @Post("/animal/class")
        public String checkAnimalClass(Animal animal) {
            return animal.getClass().getSimpleName();
        }

        @Post("/misconfigured")
        public String processMisconfigured(MisconfiguredAnimal misconfigured) {
            return "Received: " + misconfigured.name();
        }
    }
}

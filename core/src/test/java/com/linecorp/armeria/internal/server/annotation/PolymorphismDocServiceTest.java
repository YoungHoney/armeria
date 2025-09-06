package com.linecorp.armeria.internal.server.annotation;
import static java.util.Objects.requireNonNull;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class PolymorphismDocServiceTest {

    private static final Logger logger = LoggerFactory.getLogger(PolymorphismDocServiceTest.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/api", new AnimalService());
            sb.serviceUnder("/docs", new DocService());
        }
    };

    @Test
    void specificationShouldBeGeneratedCorrectly() throws JsonProcessingException {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/docs/specification.json").aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        final String specificationJson = res.contentUtf8();

        final String animalClassName = Animal.class.getName().replace('$', '.');
        final String dogClassName = Dog.class.getName().replace('$', '.');
        final String catClassName = Cat.class.getName().replace('$', '.');

        final JsonNode specNode = mapper.readTree(specificationJson);
        final JsonNode structsNode = specNode.path("structs");

        boolean animalStructFound = false;
        for (final JsonNode struct : structsNode) {
            if (animalClassName.equals(struct.path("name").asText())) {
                animalStructFound = true;


                assertThatJson(struct).node("oneOf[0]").isStringEqualTo(dogClassName);
                assertThatJson(struct).node("oneOf[1]").isStringEqualTo(catClassName);
                assertThatJson(struct).node("discriminator.propertyName").isStringEqualTo("species");
                break;
            }
        }
        assertThat(animalStructFound).withFailMessage("Animal struct not found in specification.json").isTrue();
    }


    @Test
    void shouldDeserializePolymorphicObject() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .addHeader("Content-Type", MediaType.JSON_UTF_8.toString())
                                          .build();

        final String dogJsonRequest = "{\"species\":\"dog\", \"name\":\"Buddy\", \"age\":5, \"favoriteFoods\":[\"beef\"], \"favoriteToy\":{\"toyName\":\"ball\", \"color\":\"red\"}}";
        AggregatedHttpResponse responseForDog = client.post("/api/animal", dogJsonRequest).aggregate().join();
        assertThat(responseForDog.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForDog.contentUtf8()).contains("woof");

        final String catJsonRequest = "{\"species\":\"cat\", \"name\":\"Lucy\", \"likesTuna\":true, \"scratchPost\":{\"toyName\":\"tower\", \"color\":\"beige\"}}";
        AggregatedHttpResponse responseForCat = client.post("/api/animal", catJsonRequest).aggregate().join();
        assertThat(responseForCat.status()).isEqualTo(HttpStatus.OK);
        assertThat(responseForCat.contentUtf8()).contains("meow");
    }

    // --- DTOs and Service ---
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "species")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Dog.class, name = "dog"),
            @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    interface Animal { String name(); }
    abstract static class Mammal implements Animal {
        @JsonProperty
        private final String name;
        protected Mammal(String name) { this.name = requireNonNull(name, "name"); }
        @Override
        public String name() { return name; }
        public abstract String sound();
    }
    static final class Toy {
        @JsonProperty private final String toyName;
        @JsonProperty private final String color;
        @JsonCreator
        Toy(@JsonProperty("toyName") String toyName, @JsonProperty("color") String color) {
            this.toyName = requireNonNull(toyName, "toyName");
            this.color = requireNonNull(color, "color");
        }
    }
    static final class Dog extends Mammal {
        @JsonProperty private final int age;
        @JsonProperty private final String[] favoriteFoods;
        @JsonProperty private final Toy favoriteToy;
        @JsonCreator
        Dog(@JsonProperty("name") String name, @JsonProperty("age") int age,
            @JsonProperty("favoriteFoods") String[] favoriteFoods,
            @JsonProperty("favoriteToy") Toy favoriteToy) {
            super(name);
            this.age = age;
            this.favoriteFoods = requireNonNull(favoriteFoods, "favoriteFoods");
            this.favoriteToy = requireNonNull(favoriteToy, "favoriteToy");
        }
        @Override
        public String sound() { return "woof"; }
    }
    static final class Cat extends Mammal {
        @JsonProperty private final boolean likesTuna;
        @JsonProperty private final Toy scratchPost;
        @JsonCreator
        Cat(@JsonProperty("name") String name, @JsonProperty("likesTuna") boolean likesTuna,
            @JsonProperty("scratchPost") Toy scratchPost) {
            super(name);
            this.likesTuna = likesTuna;
            this.scratchPost = requireNonNull(scratchPost, "scratchPost");
        }
        @Override
        public String sound() { return "meow"; }
    }
    public static class AnimalService {
        @Post("/api/animal")
        public String processAnimal(Animal animal) {
            String response = "Received animal named: " + animal.name();
            if (animal instanceof Mammal) {
                response += ". It says: " + ((Mammal) animal).sound();
            }
            logger.info(response);
            return response;
        }
    }
}
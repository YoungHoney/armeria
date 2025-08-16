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
package example.armeria.server.animal;

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.docs.DocService;

public class PolymorphismDocServiceExample {

    private static final Logger logger = LoggerFactory.getLogger(PolymorphismDocServiceExample.class);

    // --- Data Transfer Objects (DTOs) ---
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "species")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Dog.class, name = "dog"),
            @JsonSubTypes.Type(value = Cat.class, name = "cat")
    })
    interface Animal {
        String name();
    }

    static final class Dog implements Animal {
        private final String name;
        private final int age;

        @JsonCreator
        Dog(@JsonProperty("name") String name, @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public String name() {
            return name;
        }

        public int age() {
            return age;
        }
    }

    static final class Cat implements Animal {
        private final String name;
        private final boolean likesTuna;

        @JsonCreator
        Cat(@JsonProperty("name") String name, @JsonProperty("likesTuna") boolean likesTuna) {
            this.name = name;
            this.likesTuna = likesTuna;
        }

        @Override
        public String name() {
            return name;
        }

        public boolean likesTuna() {
            return likesTuna;
        }
    }

    // --- Annotated Service ---
    public static class AnimalService {
        @Post("/animal")
        public String processAnimal(Animal animal) {
            // This method uses the polymorphic Animal interface.
            // DocService will analyze this and generate documentation.
            return "Received animal named: " + animal.name();
        }
    }

    public static void main(String[] args) throws Exception {
        final Server server = Server.builder()
                .http(8080)
                .annotatedService("/api", new AnimalService())
                .serviceUnder("/docs", new DocService()) // Add DocService
                .build();

        server.start().join();

        logger.info("Server has been started. You can view the documentation at:");
        logger.info("UI: http://127.0.0.1:8080/docs/");
        logger.info("JSON Specification: http://127.0.0.1:8080/docs/specification.json");
    }
}
package com.linecorp.armeria.server.docs;

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


import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about a discriminator object, which is used for polymorphism.
 */
@UnstableApi
public final class DiscriminatorInfo {

    private final String propertyName;

    /**
     * Creates a new instance.
     */
    public DiscriminatorInfo(String propertyName) {
        this.propertyName = requireNonNull(propertyName, "propertyName");
    }

    /**
     * Returns the name of the property that is used to differentiate between schemas.
     */
    @JsonProperty
    public String propertyName() {
        return propertyName;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscriminatorInfo)) {
            return false;
        }
        return propertyName.equals(((DiscriminatorInfo) o).propertyName);
    }

    @Override
    public int hashCode() {
        return propertyName.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("propertyName", propertyName)
                .toString();
    }
}
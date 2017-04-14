/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 */

package com.palantir.conjure.defs.types.reference;

import com.palantir.conjure.defs.ConjureImmutablesStyle;
import com.palantir.conjure.defs.types.ConjureType;
import com.palantir.conjure.defs.types.ConjureTypeVisitor;
import com.palantir.conjure.defs.types.names.Namespace;
import com.palantir.conjure.defs.types.names.TypeName;
import com.palantir.conjure.defs.types.primitive.PrimitiveType;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@ConjureImmutablesStyle
public interface ReferenceType extends ConjureType {

    Optional<Namespace> namespace();

    TypeName type();

    @Override
    default <T> T visit(ConjureTypeVisitor<T> visitor) {
        return visitor.visit(this);
    }

    static ReferenceType of(TypeName type) {
        Optional<PrimitiveType> primitiveType = PrimitiveType.fromTypeName(type);
        if (primitiveType.isPresent()) {
            return primitiveType.get();
        }
        return ImmutableReferenceType.builder().type(type).build();
    }

    static ReferenceType of(Namespace namespace, TypeName type) {
        return ImmutableReferenceType.builder().namespace(namespace).type(type).build();
    }

}
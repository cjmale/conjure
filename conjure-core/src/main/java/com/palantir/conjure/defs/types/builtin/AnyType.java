/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.defs.types.builtin;

import com.palantir.conjure.defs.ConjureImmutablesStyle;
import com.palantir.conjure.defs.types.ConjureTypeVisitor;
import com.palantir.conjure.defs.types.Type;
import org.immutables.value.Value;

@Value.Immutable
@ConjureImmutablesStyle
public interface AnyType extends Type {

    @Override
    default <T> T visit(ConjureTypeVisitor<T> visitor) {
        return visitor.visitAny(this);
    }

    // marker interface

    static AnyType of() {
        return ImmutableAnyType.builder().build();
    }

}

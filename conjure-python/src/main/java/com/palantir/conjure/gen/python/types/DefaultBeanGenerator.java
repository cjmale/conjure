/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.gen.python.types;

import com.google.common.collect.ImmutableSet;
import com.palantir.conjure.defs.types.BaseObjectTypeDefinition;
import com.palantir.conjure.defs.types.Type;
import com.palantir.conjure.defs.types.TypesDefinition;
import com.palantir.conjure.defs.types.complex.EnumTypeDefinition;
import com.palantir.conjure.defs.types.complex.ObjectTypeDefinition;
import com.palantir.conjure.defs.types.complex.UnionTypeDefinition;
import com.palantir.conjure.defs.types.names.ConjurePackage;
import com.palantir.conjure.defs.types.names.FieldName;
import com.palantir.conjure.defs.types.reference.AliasTypeDefinition;
import com.palantir.conjure.gen.python.PackageNameProcessor;
import com.palantir.conjure.gen.python.poet.PythonAlias;
import com.palantir.conjure.gen.python.poet.PythonBean;
import com.palantir.conjure.gen.python.poet.PythonBean.PythonField;
import com.palantir.conjure.gen.python.poet.PythonClass;
import com.palantir.conjure.gen.python.poet.PythonEnum;
import com.palantir.conjure.gen.python.poet.PythonEnum.PythonEnumValue;
import com.palantir.conjure.gen.python.poet.PythonImport;
import com.palantir.conjure.gen.python.poet.PythonUnionTypeDefinition;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class DefaultBeanGenerator implements PythonBeanGenerator {

    private final Set<ExperimentalFeatures> enabledExperimentalFeatures;

    public DefaultBeanGenerator(Set<ExperimentalFeatures> enabledExperimentalFeatures) {
        this.enabledExperimentalFeatures = ImmutableSet.copyOf(enabledExperimentalFeatures);
    }

    @Override
    public PythonClass generateObject(TypesDefinition types,
            PackageNameProcessor packageNameProcessor,
            BaseObjectTypeDefinition typeDef) {
        if (typeDef instanceof ObjectTypeDefinition) {
            return generateObject(types, packageNameProcessor, (ObjectTypeDefinition) typeDef);
        } else if (typeDef instanceof EnumTypeDefinition) {
            return generateObject(packageNameProcessor, (EnumTypeDefinition) typeDef);
        } else if (typeDef instanceof UnionTypeDefinition) {
            return generateObject(types, packageNameProcessor, (UnionTypeDefinition) typeDef);
        } else if (typeDef instanceof AliasTypeDefinition) {
            return generateObject(types, packageNameProcessor, (AliasTypeDefinition) typeDef);
        } else {
            throw new UnsupportedOperationException("cannot generate type for type def: " + typeDef);
        }
    }

    private PythonClass generateObject(
            TypesDefinition types, PackageNameProcessor packageNameProcessor, UnionTypeDefinition typeDef) {

        TypeMapper mapper = new TypeMapper(new DefaultTypeNameVisitor(types));
        TypeMapper myPyMapper = new TypeMapper(new MyPyTypeNameVisitor(types));

        ReferencedTypeNameVisitor referencedTypeNameVisitor = new ReferencedTypeNameVisitor(
                types, packageNameProcessor);

        ConjurePackage packageName = packageNameProcessor.getPackageName(typeDef.typeName().conjurePackage());

        List<PythonField> options = typeDef.union()
                .stream()
                .map(unionMember -> {
                    Type conjureType = unionMember.type();
                    return PythonField.builder()
                            .attributeName(unionMember.fieldName().toCase(FieldName.Case.SNAKE_CASE).name())
                            .docs(unionMember.docs())
                            .jsonIdentifier(unionMember.fieldName().name())
                            .myPyType(myPyMapper.getTypeName(conjureType))
                            .pythonType(mapper.getTypeName(conjureType))
                            .build();
                })
                .collect(Collectors.toList());

        Set<PythonImport> imports = typeDef.union()
                .stream()
                .flatMap(entry -> entry.type().visit(referencedTypeNameVisitor).stream())
                .filter(entry -> !entry.conjurePackage().equals(packageName)) // don't need to import if in this file
                .map(referencedClassName -> PythonImport.of(referencedClassName, Optional.empty()))
                .collect(Collectors.toSet());

        return PythonUnionTypeDefinition.builder()
                .packageName(packageName.name())
                .className(typeDef.typeName().name())
                .docs(typeDef.docs())
                .addAllOptions(options)
                .addAllRequiredImports(imports)
                .build();
    }

    private PythonEnum generateObject(PackageNameProcessor packageNameProcessor, EnumTypeDefinition typeDef) {

        ConjurePackage packageName = packageNameProcessor.getPackageName(typeDef.typeName().conjurePackage());

        return PythonEnum.builder()
                .packageName(packageName.name())
                .className(typeDef.typeName().name())
                .docs(typeDef.docs())
                .values(typeDef.values().stream()
                        .map(value -> PythonEnumValue.of(value.value(), value.docs()))
                        .collect(Collectors.toList()))
                .build();
    }

    private PythonBean generateObject(
            TypesDefinition types, PackageNameProcessor packageNameProcessor, ObjectTypeDefinition typeDef) {

        TypeMapper mapper = new TypeMapper(new DefaultTypeNameVisitor(types));
        TypeMapper myPyMapper = new TypeMapper(new MyPyTypeNameVisitor(types));
        ReferencedTypeNameVisitor referencedTypeNameVisitor = new ReferencedTypeNameVisitor(
                types, packageNameProcessor);

        ConjurePackage packageName = packageNameProcessor.getPackageName(typeDef.typeName().conjurePackage());

        Set<PythonImport> imports = typeDef.fields()
                .stream()
                .flatMap(entry -> entry.type().visit(referencedTypeNameVisitor).stream())
                .filter(entry -> !entry.conjurePackage().equals(packageName)) // don't need to import if in this file
                .map(referencedClassName -> PythonImport.of(referencedClassName, Optional.empty()))
                .collect(Collectors.toSet());

        return PythonBean.builder()
                .packageName(packageName.name())
                .addAllRequiredImports(PythonBean.DEFAULT_IMPORTS)
                .addAllRequiredImports(imports)
                .className(typeDef.typeName().name())
                .docs(typeDef.docs())
                .fields(typeDef.fields()
                        .stream()
                        .map(entry -> PythonField.builder()
                                .attributeName(entry.fieldName().toCase(FieldName.Case.SNAKE_CASE).name())
                                .jsonIdentifier(entry.fieldName().name())
                                .docs(entry.docs())
                                .pythonType(mapper.getTypeName(entry.type()))
                                .myPyType(myPyMapper.getTypeName(entry.type()))
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }

    private PythonAlias generateObject(
            TypesDefinition types, PackageNameProcessor packageNameProcessor, AliasTypeDefinition typeDef) {
        TypeMapper mapper = new TypeMapper(new DefaultTypeNameVisitor(types));
        ReferencedTypeNameVisitor referencedTypeNameVisitor = new ReferencedTypeNameVisitor(
                types, packageNameProcessor);
        ConjurePackage packageName = packageNameProcessor.getPackageName(typeDef.typeName().conjurePackage());

        Set<PythonImport> imports = typeDef.alias().visit(referencedTypeNameVisitor)
                .stream()
                .filter(entry -> !entry.conjurePackage().equals(packageName)) // don't need to import if in this file
                .map(referencedClassName -> PythonImport.of(referencedClassName, Optional.empty()))
                .collect(Collectors.toSet());

        return PythonAlias.builder()
                .aliasName(typeDef.typeName().name())
                .aliasTarget(mapper.getTypeName(typeDef.alias()))
                .packageName(packageName.name())
                .addAllRequiredImports(imports)
                .build();
    }

}

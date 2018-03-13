/*
 * (c) Copyright 2017 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.conjure.gen.java.types;

import com.google.common.base.CaseFormat;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.palantir.conjure.defs.types.Documentation;
import com.palantir.conjure.defs.types.complex.ErrorTypeDefinition;
import com.palantir.conjure.defs.types.complex.FieldDefinition;
import com.palantir.conjure.defs.types.names.ConjurePackage;
import com.palantir.conjure.defs.types.names.ErrorNamespace;
import com.palantir.conjure.gen.java.ConjureAnnotations;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.remoting.api.errors.ErrorType;
import com.palantir.remoting.api.errors.ServiceException;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import org.apache.commons.lang3.StringUtils;

public final class ErrorGenerator {

    private ErrorGenerator() {}

    public static Set<JavaFile> generateErrorTypes(
            TypeMapper typeMapper, List<ErrorTypeDefinition> errorTypeNameToDef) {

        return splitErrorDefsByNamespace(errorTypeNameToDef)
                .entrySet()
                .stream()
                .flatMap(entry ->
                        entry.getValue()
                                .entrySet()
                                .stream()
                                .map(innerEntry -> generateErrorTypesForNamespace(
                                        typeMapper,
                                        ConjurePackage.of(entry.getKey()),
                                        innerEntry.getKey(),
                                        innerEntry.getValue()))
                ).collect(Collectors.toSet());
    }

    private static Map<String, Map<ErrorNamespace, List<ErrorTypeDefinition>>> splitErrorDefsByNamespace(
            List<ErrorTypeDefinition> errorTypeNameToDef) {

        Map<String, Map<ErrorNamespace, List<ErrorTypeDefinition>>> pkgToNamespacedErrorDefs =
                Maps.newHashMap();
        errorTypeNameToDef.stream().forEach(errorDef -> {
            ConjurePackage errorPkg = errorDef.typeName().conjurePackage();
            pkgToNamespacedErrorDefs.computeIfAbsent(errorPkg.name(), key -> Maps.newHashMap());

            Map<ErrorNamespace, List<ErrorTypeDefinition>> namespacedErrorDefs =
                    pkgToNamespacedErrorDefs.get(errorPkg.name());
            ErrorNamespace namespace = errorDef.namespace();
            // TODO(rfink): Use Multimap?
            namespacedErrorDefs.computeIfAbsent(namespace, key -> new ArrayList<>());
            namespacedErrorDefs.get(namespace).add(errorDef);
        });
        return pkgToNamespacedErrorDefs;
    }

    private static JavaFile generateErrorTypesForNamespace(
            TypeMapper typeMapper,
            ConjurePackage conjurePackage,
            ErrorNamespace namespace,
            List<ErrorTypeDefinition> errorTypeDefinitions) {

        ClassName className = errorTypesClassName(conjurePackage, namespace);

        // Generate ErrorType definitions
        List<FieldSpec> fieldSpecs = errorTypeDefinitions.stream().map(errorDef -> {
            CodeBlock initializer = CodeBlock.of("ErrorType.create(ErrorType.Code.$L, \"$L:$L\")",
                    errorDef.code().name(),
                    namespace.name(),
                    errorDef.typeName().name());
            FieldSpec.Builder fieldSpecBuilder = FieldSpec.builder(
                    ClassName.get(ErrorType.class),
                    CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, errorDef.typeName().name()),
                    Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                    .initializer(initializer);
            errorDef.docs().ifPresent(docs -> fieldSpecBuilder.addJavadoc(docs.value()));
            return fieldSpecBuilder.build();
        }).collect(Collectors.toList());

        // Generate ServiceException factory methods
        List<MethodSpec> methodSpecs = errorTypeDefinitions.stream()
                .map(entry -> {
                    MethodSpec withoutCause = generateExceptionFactory(typeMapper, entry, false);
                    MethodSpec withCause = generateExceptionFactory(typeMapper, entry, true);
                    return Stream.of(withoutCause, withCause);
                })
                .flatMap(Function.identity())
                .collect(Collectors.toList());

        // Generate ServiceException factory check methods
        List<MethodSpec> checkMethodSpecs = errorTypeDefinitions.stream().map(entry -> {
            String exceptionMethodName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, entry.typeName().name());
            String methodName = "throwIf" + entry.typeName().name();

            String shouldThrowVar = "shouldThrow";

            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addParameter(TypeName.BOOLEAN, shouldThrowVar);

            methodBuilder.addJavadoc("Throws a {@link ServiceException} of type $L when {@code $L} is true.\n",
                    entry.typeName().name(), shouldThrowVar);
            methodBuilder.addJavadoc("@param $L $L\n", shouldThrowVar, "Cause the method to throw when true");
            Streams.concat(
                    entry.safeArgs().stream(),
                    entry.unsafeArgs().stream()).forEach(arg -> {
                        methodBuilder.addParameter(typeMapper.getClassName(arg.type()), arg.fieldName().name());
                        methodBuilder.addJavadoc("@param $L $L", arg.fieldName().name(),
                                        StringUtils.appendIfMissing(
                                                arg.docs().map(Documentation::value).orElse(""), "\n"));
                    });

            methodBuilder.addCode("if ($L) {", shouldThrowVar);
            methodBuilder.addCode("throw $L;",
                    Expressions.localMethodCall(exceptionMethodName,
                            Streams.concat(
                                    entry.safeArgs().stream(),
                                    entry.unsafeArgs().stream()).map(arg -> arg.fieldName().name())
                                    .collect(Collectors.toList())));
            methodBuilder.addCode("}");
            return methodBuilder.build();
        }).collect(Collectors.toList());

        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(className)
                .addMethod(privateConstructor())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addFields(fieldSpecs)
                .addMethods(methodSpecs)
                .addMethods(checkMethodSpecs)
                .addAnnotation(ConjureAnnotations.getConjureGeneratedAnnotation(ErrorGenerator.class));

        return JavaFile.builder(conjurePackage.name(), typeBuilder.build())
                .skipJavaLangImports(true)
                .indent("    ")
                .build();
    }

    private static MethodSpec generateExceptionFactory(
            TypeMapper typeMapper, ErrorTypeDefinition entry, boolean withCause) {
        String methodName = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_CAMEL, entry.typeName().name());
        String typeName = CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, entry.typeName().name());

        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(ClassName.get(ServiceException.class));

        methodBuilder.addCode("return new $T($L", ServiceException.class, typeName);

        if (withCause) {
            methodBuilder.addParameter(Throwable.class, "cause");
            methodBuilder.addCode(", cause");
        }

        entry.safeArgs().stream().forEach(arg ->
                processArg(typeMapper, methodBuilder, arg, true));

        entry.unsafeArgs().stream().forEach(arg ->
                processArg(typeMapper, methodBuilder, arg, false));
        methodBuilder.addCode(");");

        return methodBuilder.build();
    }

    private static void processArg(
            TypeMapper typeMapper, MethodSpec.Builder methodBuilder, FieldDefinition argDefinition, boolean isSafe) {

        String argName = argDefinition.fieldName().name();
        com.squareup.javapoet.TypeName argType = typeMapper.getClassName(argDefinition.type());
        methodBuilder.addParameter(argType, argName);
        Class<?> clazz = isSafe ? SafeArg.class : UnsafeArg.class;
        methodBuilder.addCode(",\n    $T.of($S, $L)", clazz, argName, argName);
        argDefinition.docs().ifPresent(docs ->
                methodBuilder.addJavadoc("@param $L $L", argName, StringUtils.appendIfMissing(docs.value(), "\n")));
    }

    private static ClassName errorTypesClassName(ConjurePackage conjurePackage, ErrorNamespace namespace) {
        return ClassName.get(conjurePackage.name(), namespace.name() + "Errors");
    }

    private static MethodSpec privateConstructor() {
        return MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build();
    }

}

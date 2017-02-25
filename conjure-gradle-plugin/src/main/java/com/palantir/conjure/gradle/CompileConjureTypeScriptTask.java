/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 */

package com.palantir.conjure.gradle;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.io.Files;
import com.palantir.conjure.defs.Conjure;
import com.palantir.conjure.defs.ConjureDefinition;
import com.palantir.conjure.gen.typescript.ConjureTypescriptClientGenerator;
import com.palantir.conjure.gen.typescript.services.ServiceGenerator;
import com.palantir.conjure.gen.typescript.types.TypeGenerator;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.function.Supplier;
import org.gradle.api.Project;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;

public class CompileConjureTypeScriptTask extends SourceTask {

    private Supplier<File> outputDirectory;

    @Input
    private ServiceGenerator serviceGenerator;

    @Input
    private TypeGenerator typeGenerator;

    public final void setOutputDirectory(Supplier<File> outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public final void setServiceGenerator(ServiceGenerator serviceGenerator) {
        this.serviceGenerator = serviceGenerator;
    }

    public final void setTypeGenerator(TypeGenerator typeGenerator) {
        this.typeGenerator = typeGenerator;
    }

    @OutputDirectory
    public final File getOutputDirectory() {
        return outputDirectory.get();
    }

    @TaskAction
    public final void compileFiles() throws IOException {
        Project project = getProject();
        File baseDir = new File(project.getBuildDir(), "conjure");

        compileFiles(getSource().getFiles(), baseDir.toPath());

        // write a gitignore to prevent the generated files ending up in source control
        Files.write("*.ts\n", new File(getOutputDirectory(), ".gitignore"), StandardCharsets.UTF_8);
    }

    private void compileFiles(Collection<File> files, Path baseDir) {
        files.forEach(f -> compileFile(f.toPath(), baseDir));
    }

    private void compileFile(Path path, Path baseDir) {
        ConjureDefinition conjure = Conjure.parse(path.toFile());

        ConjureTypescriptClientGenerator generator = new ConjureTypescriptClientGenerator(
                serviceGenerator, typeGenerator);

        File outputDir = getOutputDirectory();
        checkState(outputDir.exists() || outputDir.mkdirs(), "Unable to make directory tree %s", outputDir);
        generator.emit(conjure, outputDir);
    }

}

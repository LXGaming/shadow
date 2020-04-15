package com.github.jengelman.gradle.plugins.shadow.internal

import com.android.tools.r8.*
import com.android.tools.r8.origin.Origin
import com.android.tools.r8.utils.StringUtils
import groovy.transform.CompileStatic
import org.apache.commons.io.FilenameUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.SourceSet

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static com.android.tools.r8.utils.FileUtils.isClassFile

/**
 * An implementation of an UnusedTracker using R8.
 * <p>
 * This class is written mainly in Java syntax and uses @CompileStatic deliberately
 * to avoid groovy madness especially in combination with R8.
 * <p>
 * It is an extension of the existing UnusedTracker to make it easy to exchange them,
 * if the pull request is accepted, a common base interface should be extracted.
 */
class UnusedTrackerWithR8 extends UnusedTracker {
    private static String TMP_DIR = "tmp/shadowJar/minimize"

    private final Path             tmpDir
    private final Collection<File> projectFiles
    private final Collection<File> dependencies

    private UnusedTrackerWithR8(Path tmpDir, List<File> classDirs, FileCollection classJars, FileCollection toMinimize) {
        super(classDirs, classJars, toMinimize)

        this.tmpDir = tmpDir

        this.projectFiles = []
        this.dependencies = []

        for (File dir : classDirs) {
            Path path = Paths.get(dir.getAbsolutePath())
            collectClassFiles(path, projectFiles)
        }

        for (File jar : classJars.getFiles()) {
            projectFiles.add(jar)
        }
    }

    @Override
    @CompileStatic
    boolean performsFullShrinking() {
        return true
    }

    @Override
    @CompileStatic
    Path getPathToProcessedClass(String classname) {
        final String className = FilenameUtils.removeExtension(classname).replace('/' as char, '.' as char)
        Path processedFile = Paths.get(tmpDir.toString(), className.replaceAll("\\.", "/") + ".class")
        return processedFile
    }

    @Override
    @CompileStatic
    Set<String> findUnused() {
        // We effectively run R8 twice:
        //  * first time disabling any processing, to retrieve all project classes
        //  * second time with a full shrink run to get all unused classes
        R8Command.Builder builder = R8Command.builder()

        populateBuilderWithProjectFiles(builder)

        // add all dependency jars
        for (File dep : dependencies) {
            Path path = Paths.get(dep.getAbsolutePath())
            builder.addProgramFiles(path)
        }

        final Set<String> removedClasses = new HashSet<>()

        // Add any class from the usage list to the list of removed classes.
        // This is a bit of a hack but the best things I could think of so far.
        builder.setProguardUsageConsumer(new StringConsumer() {
            private final String LINE_SEPARATOR = StringUtils.LINE_SEPARATOR
            private String  lastString = LINE_SEPARATOR
            private String  classString
            private boolean expectingSeparator

            @Override
            void accept(String s, DiagnosticsHandler handler) {
                if (classString == null && lastString == LINE_SEPARATOR) {
                    classString        = s
                    expectingSeparator = true
                } else if (expectingSeparator && s == LINE_SEPARATOR) {
                    removedClasses.add(classString)
                    classString        = null
                    expectingSeparator = false
                } else {
                    classString        = null
                    expectingSeparator = false
                }

                lastString = s
            }
        })

        List<String> proguardConfig = getKeepRules()
        proguardConfig.add("-dontoptimize")
        proguardConfig.add("-dontobfuscate")
        proguardConfig.add("-ignorewarnings")

        builder.addProguardConfiguration(proguardConfig, Origin.unknown())

        builder.setProgramConsumer(new ClassFileConsumer() {
            @Override
            void accept(ByteDataView byteDataView, String s, DiagnosticsHandler diagnosticsHandler) {
                String name = typeNameToExternalClassName(s)

                // any class that is actually going to be written to the output
                // must not be present in the set of removed classes.
                // Should not really be needed but we prefer to be paranoid.
                removedClasses.remove(name)

                Path classFile = Paths.get(tmpDir.toString(), externalClassNameToInternal(name) + ".class")
                Files.createDirectories(classFile.getParent())
                Files.write(classFile, byteDataView.getBuffer())
            }

            @Override
            void finished(DiagnosticsHandler diagnosticsHandler) {}
        })

        R8.run(builder.build())
        return removedClasses
    }

    @CompileStatic
    static String typeNameToExternalClassName(String typeName) {
        String className = typeName.startsWith("L") && typeName.endsWith(";") ?
            typeName.substring(1, typeName.length() - 1) :
            typeName

        return className.replaceAll("/", ".")
    }

    @CompileStatic
    static String externalClassNameToInternal(String className) {
        return className.replaceAll("\\.", "/")
    }

    @CompileStatic
    List<String> getKeepRules() {
        R8Command.Builder builder = R8Command.builder()

        populateBuilderWithProjectFiles(builder)

        // add all dependencies as library jars to avoid warnings
        for (File dep : dependencies) {
            Path path = Paths.get(dep.getAbsolutePath())
            builder.addLibraryFiles(path)
        }

        // disable everything, we just want to get a list of
        // all project classes.
        List<String> configs = new ArrayList<>()
        configs.add("-dontshrink")
        configs.add("-dontoptimize")
        configs.add("-dontobfuscate")
        configs.add("-ignorewarnings")
        configs.add("-dontwarn")

        builder.addProguardConfiguration(configs, Origin.unknown())

        final List<String> keepRules = new ArrayList<>()

        builder.setProgramConsumer(new ClassFileConsumer() {
            @Override
            void accept(ByteDataView byteDataView, String s, DiagnosticsHandler diagnosticsHandler) {
                String name = typeNameToExternalClassName(s)
                keepRules.add("-keep class " + name + " { *; }")
            }

            @Override
            void finished(DiagnosticsHandler diagnosticsHandler) {}
        })

        R8.run(builder.build())
        return keepRules
    }

    @CompileStatic
    void populateBuilderWithProjectFiles(BaseCommand.Builder builder) {
        addJDKLibrary(builder)

        for (File f : projectFiles) {
            Path path = Paths.get(f.getAbsolutePath())

            if (f.getAbsolutePath().endsWith(".class")) {
                byte[] bytes = Files.readAllBytes(Paths.get(f.getAbsolutePath()))
                builder.addClassProgramData(bytes, Origin.unknown())
            } else if (Files.isDirectory(path)) {
                builder.addClasspathResourceProvider(DirectoryClassFileProvider.fromDirectory(path))
            } else {
                builder.addProgramFiles(path)
            }
        }
    }

    @CompileStatic
    private void collectClassFiles(Path dir, Collection<File> result) {
        File file = dir.toFile()
        if (file.exists()) {
            File[] files = file.listFiles()
            if (files != null) {
                for (File child : files) {
                    if (child.isDirectory()) {
                        collectClassFiles(child.toPath(), result)
                    } else {
                        Path relative = child.toPath()
                        if (isClassFile(relative)) {
                            result.add(child.getAbsoluteFile())
                        }
                    }
                }
            }
        }
    }

    @CompileStatic
    static void addJDKLibrary(BaseCommand.Builder builder) {
        String JAVA_HOME = System.getenv("JAVA_HOME")
        builder.addLibraryResourceProvider(JdkClassFileProvider.fromJdkHome(Paths.get(JAVA_HOME)))
    }

    @Override
    void addDependency(File jarOrDir) {
        if (toMinimize.contains(jarOrDir)) {
            dependencies.add(jarOrDir)
        }
    }

    static UnusedTracker forProject(Project project, List<Configuration> configurations, DependencyFilter dependencyFilter) {
        Path tmpDir = Paths.get(project.buildDir.absolutePath, TMP_DIR)
        Files.createDirectories(tmpDir)

        FileCollection apiJars = getApiJarsFromProject(project)
        FileCollection toMinimize = dependencyFilter.resolve(configurations) - apiJars

        final List<File> classDirs = new ArrayList<>()
        for (SourceSet sourceSet in project.sourceSets) {
            Iterable<File> classesDirs = sourceSet.output.classesDirs
            classDirs.addAll(classesDirs.findAll { it.isDirectory() })
        }
        return new UnusedTrackerWithR8(tmpDir, classDirs, apiJars, toMinimize)
    }
}

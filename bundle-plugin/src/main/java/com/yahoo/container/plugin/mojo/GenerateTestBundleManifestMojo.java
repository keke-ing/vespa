// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin.mojo;

import com.yahoo.container.plugin.bundle.AnalyzeBundle;
import com.yahoo.container.plugin.classanalysis.Analyze;
import com.yahoo.container.plugin.classanalysis.ClassFileMetaData;
import com.yahoo.container.plugin.classanalysis.PackageTally;
import com.yahoo.container.plugin.osgi.ExportPackageParser;
import com.yahoo.container.plugin.osgi.ExportPackages;
import com.yahoo.container.plugin.osgi.ImportPackages;
import com.yahoo.container.plugin.util.Strings;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.yahoo.container.plugin.bundle.AnalyzeBundle.publicPackagesAggregated;
import static com.yahoo.container.plugin.osgi.ExportPackages.exportsByPackageName;
import static com.yahoo.container.plugin.osgi.ImportPackages.calculateImports;
import static com.yahoo.container.plugin.util.Files.allDescendantFiles;
import static com.yahoo.container.plugin.util.IO.withFileOutputStream;
import static java.util.stream.Collectors.toList;

/**
 * @author bjorncs
 */
@Mojo(name = "generate-test-bundle-osgi-manifest", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class GenerateTestBundleManifestMojo extends AbstractMojo {

    @Parameter
    private String testProvidedArtifacts;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(alias = "Bundle-Version", defaultValue = "${project.version}")
    private String bundleVersion;

    @Parameter(alias = "Bundle-SymbolicName", defaultValue = "${project.artifactId}-tests")
    private String bundleSymbolicName;

    @Parameter(alias = "Import-Package")
    private String importPackage;

    public void execute() throws MojoExecutionException {
        try {
            Artifacts.ArtifactSet artifactSet = Artifacts.getArtifacts(project, true, testProvidedArtifacts);

            List<File> providedJars = artifactSet.getJarArtifactsProvided().stream()
                    .map(Artifact::getFile)
                    .collect(toList());

            AnalyzeBundle.PublicPackages publicPackagesFromProvidedJars = publicPackagesAggregated(providedJars);

            PackageTally projectPackages = getProjectMainAndTestClassesTally();

            PackageTally jarArtifactsToInclude = definedPackages(artifactSet.getJarArtifactsToInclude());

            PackageTally includedPackages = projectPackages.combine(jarArtifactsToInclude);

            Map<String, ImportPackages.Import> calculatedImports = calculateImports(includedPackages.referencedPackages(),
                    includedPackages.definedPackages(),
                    exportsByPackageName(publicPackagesFromProvidedJars.exports));

            Map<String, Optional<String>> manualImports = Optional.ofNullable(importPackage)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(GenerateTestBundleManifestMojo:: getManualImports)
                    .orElseGet(HashMap::new);
            for (String packageName : manualImports.keySet()) {
                calculatedImports.remove(packageName);
            }
            Map<String, String> manifestContent =
                    manifestContent(project, artifactSet.getJarArtifactsToInclude(), manualImports, calculatedImports.values(), includedPackages);
            createManifestFile(TestBundleUtils.outputDirectory(project), manifestContent);

        } catch (Exception e) {
            throw new MojoExecutionException("Failed generating osgi manifest", e);
        }
    }

    private Map<String, String> manifestContent(
            MavenProject project,
            Collection<Artifact> jarArtifactsToInclude,
            Map<String, Optional<String>> manualImports,
            Collection<ImportPackages.Import> imports,
            PackageTally pluginPackageTally) {
        Map<String, String> ret = new HashMap<>();
        String importPackage = Stream.concat(manualImports.entrySet().stream().map(e -> {
                    String packageName = e.getKey();
                    return e.getValue().map(s -> packageName + ";version=" + "\"" + s + "\"").orElse(packageName);
                }),
                imports.stream().map(ImportPackages.Import::asOsgiImport)).sorted()
                .collect(Collectors.joining(","));

        List<String> exportPackages = pluginPackageTally.exportedPackages().entrySet().stream()
                .map(entry -> entry.getKey() + ";version=" + entry.getValue().osgiVersion())
                .collect(toList());
        String exportPackage = exportPackages.stream().sorted()
                .collect(Collectors.joining(","));

        ret.put("Created-By", "vespa container maven plugin");
        ret.put("Bundle-ManifestVersion", "2");
        addIfNotEmpty(ret, "Bundle-Name", project.getName() + "-tests");
        addIfNotEmpty(ret, "Bundle-SymbolicName", bundleSymbolicName);
        addIfNotEmpty(ret, "Bundle-Version", asBundleVersion(bundleVersion));
        ret.put("Bundle-Vendor", "Vespa");
        addIfNotEmpty(ret, "Bundle-ClassPath", bundleClassPath(jarArtifactsToInclude));
        addIfNotEmpty(ret, "Import-Package", importPackage);
        addIfNotEmpty(ret, "Export-Package", exportPackage);

        return ret;
    }

    private static void addIfNotEmpty(Map<String, String> map, String key, String value) {
        if (value != null && ! value.isEmpty()) {
            map.put(key, value);
        }
    }

    private static void createManifestFile(Path outputDirectory, Map<String, String> manifestContent) {
        Manifest manifest = toManifest(manifestContent);
        withFileOutputStream(outputDirectory.resolve(JarFile.MANIFEST_NAME).toFile(), out -> {
            manifest.write(out);
            return null;
        });
    }

    private static Manifest toManifest(Map<String, String> manifestContent) {
        Manifest manifest = new Manifest();
        Attributes mainAttributes = manifest.getMainAttributes();

        mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestContent.forEach(mainAttributes::putValue);

        return manifest;
    }

    private static String bundleClassPath(Collection<Artifact> artifactsToInclude) {
        return Stream.concat(Stream.of("."), artifactsToInclude.stream()
                .map(artifact -> "dependencies/" + artifact.getFile().getName()))
                .collect(Collectors.joining(","));
    }

    private static String asBundleVersion(String projectVersion) {
        if (projectVersion == null) {
            throw new IllegalArgumentException("Missing project version.");
        }

        String[] parts = projectVersion.split("-", 2);
        List<String> numericPart = Stream.of(parts[0].split("\\.")).map(s -> Strings.replaceEmptyString(s, "0")).limit(3)
                .collect(toList());
        while (numericPart.size() < 3) {
            numericPart.add("0");
        }

        return String.join(".", numericPart);
    }

    private PackageTally getProjectMainAndTestClassesTally() {
        List<ClassFileMetaData> analyzedClasses =
                Stream.concat(
                        allDescendantFiles(new File(project.getBuild().getOutputDirectory())),
                        allDescendantFiles(new File(project.getBuild().getTestOutputDirectory())))
                        .filter(file -> file.getName().endsWith(".class"))
                        .map(classFile -> Analyze.analyzeClass(classFile, null))
                        .collect(toList());
        return PackageTally.fromAnalyzedClassFiles(analyzedClasses);
    }

    private PackageTally definedPackages(Collection<Artifact> jarArtifacts) {
        List<PackageTally> tallies = new ArrayList<>();
        for (var artifact : jarArtifacts) {
            try {
                tallies.add(definedPackages(new JarFile(artifact.getFile()), null));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return PackageTally.combine(tallies);
    }

    private static PackageTally definedPackages(JarFile jarFile, ArtifactVersion version) throws MojoExecutionException {
        List<ClassFileMetaData> analyzedClasses = new ArrayList<>();
        for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements();) {
            JarEntry entry = entries.nextElement();
            if (! entry.isDirectory() && entry.getName().endsWith(".class")) {
                analyzedClasses.add(analyzeClass(jarFile, entry, version));
            }
        }
        return PackageTally.fromAnalyzedClassFiles(analyzedClasses);
    }

    private static ClassFileMetaData analyzeClass(JarFile jarFile, JarEntry entry, ArtifactVersion version) throws MojoExecutionException {
        try {
            return Analyze.analyzeClass(jarFile.getInputStream(entry), version);
        } catch (Exception e) {
            throw new MojoExecutionException(
                    String.format("While analyzing the class '%s' in jar file '%s'", entry.getName(), jarFile.getName()), e);
        }
    }

    private static Map<String, Optional<String>> getManualImports(String importPackage) {
        try {
            Map<String, Optional<String>> ret = new HashMap<>();
            List<ExportPackages.Export> imports = ExportPackageParser.parseExports(importPackage);
            for (ExportPackages.Export imp : imports) {
                Optional<String> version = getVersionThrowOthers(imp.getParameters());
                imp.getPackageNames().forEach(pn -> ret.put(pn, version));
            }

            return ret;
        } catch (Exception e) {
            throw new RuntimeException("Error in Import-Package:" + importPackage, e);
        }
    }

    private static Optional<String> getVersionThrowOthers(List<ExportPackages.Parameter> parameters) {
        if (parameters.size() == 1 && "version".equals(parameters.get(0).getName())) {
            return Optional.of(parameters.get(0).getValue());
        } else if (parameters.size() == 0) {
            return Optional.empty();
        } else {
            List<String> paramNames = parameters.stream().map(ExportPackages.Parameter::getName).collect(toList());
            throw new RuntimeException("A single, optional version parameter expected, but got " + paramNames);
        }
    }


}

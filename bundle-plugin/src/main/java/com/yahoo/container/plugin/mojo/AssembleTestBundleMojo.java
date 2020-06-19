// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin.mojo;

import com.yahoo.container.plugin.util.Files;
import com.yahoo.container.plugin.util.JarFiles;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.jar.JarArchiver;

import java.io.File;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static com.yahoo.container.plugin.mojo.TestBundleUtils.archiveFile;
import static com.yahoo.container.plugin.mojo.TestBundleUtils.manifestFile;

/**
 * @author bjorncs
 */
@Mojo(name = "assemble-test-bundle", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class AssembleTestBundleMojo extends AbstractMojo {

    @Parameter
    private String testProvidedArtifacts;

    @Parameter(defaultValue = "${project}")
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter
    private MavenArchiveConfiguration archiveConfiguration = new MavenArchiveConfiguration();

    @Override
    public void execute() throws MojoExecutionException {
        archiveConfiguration.setForced(true); // force recreating the archive
        archiveConfiguration.setManifestFile(manifestFile(project).toFile());

        JarArchiver archiver = new JarArchiver();
        addMainAndTestClasses(archiver);
        addDependencies(archiver);
        Path archiveFile = archiveFile(project);
        createArchive(archiveFile.toFile(), archiver);
        project.getArtifact().setFile(archiveFile.toFile());
    }

    private void addMainAndTestClasses(JarArchiver archiver) {
        File classes = new File(project.getBuild().getOutputDirectory());
        if (classes.isDirectory()) {
            archiver.addDirectory(classes);
        }
        File testClasses = new File(project.getBuild().getTestOutputDirectory());
        if (testClasses.isDirectory()) {
            archiver.addDirectory(testClasses);
        }
    }

    private void createArchive(File jarFile, JarArchiver jarArchiver) throws MojoExecutionException {
        MavenArchiver mavenArchiver = new MavenArchiver();
        mavenArchiver.setArchiver(jarArchiver);
        mavenArchiver.setOutputFile(jarFile);
        try {
            mavenArchiver.createArchive(session, project, archiveConfiguration);
        } catch (Exception e) {
            throw new MojoExecutionException("Error creating archive " + jarFile.getName(), e);
        }
    }

    private void addDependencies(JarArchiver jarArchiver) {
        Artifacts.ArtifactSet artifacts = Artifacts.getArtifacts(project, true, testProvidedArtifacts);
        artifacts.getJarArtifactsToInclude().stream()
                .forEach(artifact -> {
                    if ("jar".equals(artifact.getType())) {
                        jarArchiver.addFile(artifact.getFile(), "dependencies/" + artifact.getFile().getName());
                        copyConfigDefinitions(artifact.getFile(), jarArchiver);
                    } else {
                        getLog().warn("Unknown artifact type " + artifact.getType());
                    }
                });
    }

    private void copyConfigDefinitions(File file, JarArchiver jarArchiver) {
        JarFiles.withJarFile(file, jarFile -> {
            for (Enumeration<JarEntry> entries = jarFile.entries(); entries.hasMoreElements();) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("configdefinitions/") && name.endsWith(".def")) {
                    copyConfigDefinition(jarFile, entry, jarArchiver);
                }
            }
            return null;
        });
    }

    private void copyConfigDefinition(JarFile jarFile, ZipEntry entry, JarArchiver jarArchiver) {
        JarFiles.withInputStream(jarFile, entry, input -> {
            String defPath = entry.getName().replace("/", File.separator);
            File destinationFile = new File(project.getBuild().getOutputDirectory(), defPath);
            destinationFile.getParentFile().mkdirs();

            Files.withFileOutputStream(destinationFile, output -> {
                output.getChannel().transferFrom(Channels.newChannel(input), 0, Long.MAX_VALUE);
                return null;
            });
            jarArchiver.addFile(destinationFile, entry.getName());
            return null;
        });
    }
}

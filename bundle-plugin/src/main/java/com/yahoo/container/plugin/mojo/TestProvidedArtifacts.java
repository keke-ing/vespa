// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.container.plugin.mojo;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

/**
 * Contains the dependencies that are provided by the Vespa test runtime.
 *
 * @author bjorncs
 */
class TestProvidedArtifacts {

    private final List<Artifact> artifacts;

    private TestProvidedArtifacts(List<Artifact> artifacts) { this.artifacts = artifacts; }

    boolean isTestProvided(Artifact artifact) { return artifacts.contains(artifact); }

    static TestProvidedArtifacts from(MavenProject project, String configString) {
        if (configString == null || configString.isBlank()) return new TestProvidedArtifacts(List.of());
        return new TestProvidedArtifacts(getTestProvidedArtifacts(project, configString));
    }

    private static List<Artifact> getTestProvidedArtifacts(MavenProject project, String configString) {
        List<String> testProvidedArtifactStringIds = toTestProvidedArtifactStringIds(configString);
        Map<String, Artifact> allArtifacts = project.getArtifactMap();
        List<Artifact> testProvidedArtifacts = new ArrayList<>();
        for (Artifact artifact : allArtifacts.values()) {
            boolean hasTestProvidedArtifactAsParent =
                    dependencyTrail(artifact, allArtifacts)
                            .anyMatch(parent -> testProvidedArtifactStringIds.contains(toArtifactStringId(parent)));
            boolean isBlacklisted = testProvidedArtifactStringIds.contains(toBlacklistedArtifactStringId(artifact));
            if (hasTestProvidedArtifactAsParent && !isBlacklisted) {
                testProvidedArtifacts.add(artifact);
            }
        }
        return testProvidedArtifacts;
    }

    private static List<String> toTestProvidedArtifactStringIds(String commaSeparatedString) {
        if (commaSeparatedString == null || commaSeparatedString.isBlank()) return List.of();
        return Arrays.stream(commaSeparatedString.split(","))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(toList());
    }

    private static Stream<Artifact> dependencyTrail(Artifact artifact, Map<String, Artifact> otherArtifacts) {
        return artifact.getDependencyTrail().stream()
                .map(parentId -> otherArtifacts.get(stripVersionAndScope(parentId)))
                .filter(Objects::nonNull);
    }

    private static String stripVersionAndScope(String fullArtifactIdentifier) {
        int firstDelimiter = fullArtifactIdentifier.indexOf(':');
        int secondDelimiter = fullArtifactIdentifier.indexOf(':', firstDelimiter + 1);
        return fullArtifactIdentifier.substring(0, secondDelimiter);
    }

    private static String toArtifactStringId(Artifact artifact) { return artifact.getGroupId() + ":" + artifact.getArtifactId(); }

    private static String toBlacklistedArtifactStringId(Artifact artifact) { return "!" + toArtifactStringId(artifact); }

}

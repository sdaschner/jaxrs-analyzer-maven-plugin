/*
 * Copyright (C) 2015 Sebastian Daschner, sebastian-daschner.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sebastian_daschner.jaxrs_analyzer.maven;

import com.sebastian_daschner.jaxrs_analyzer.JAXRSAnalyzer;
import com.sebastian_daschner.jaxrs_analyzer.LogProvider;
import com.sebastian_daschner.jaxrs_analyzer.backend.BackendType;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maven goal which analyzes JAX-RS resources.
 *
 * @author Sebastian Daschner
 * @goal analyze-jaxrs
 * @phase process-classes
 */
public class JAXRSAnalyzerMojo extends AbstractMojo {

    /**
     * The chosen backend format. Defaults to plaintext.
     *
     * @parameter default-value="plaintext" property="jaxrs-analyzer.backend"
     */
    private String backend;

    /**
     * The domain where the project will be deployed.
     *
     * @parameter default-value="example.com" property="jaxrs-analyzer.deployedDomain"
     */
    private String deployedDomain;

    /**
     * @parameter property="project.build.outputDirectory"
     * @required
     * @readonly
     */
    private File outputDirectory;

    /**
     * @parameter property="project.build.directory"
     * @required
     * @readonly
     */
    private File buildDirectory;

    /**
     * @parameter property="project"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * The entry point to Aether.
     *
     * @component
     */
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter property="repositorySystemSession"
     * @required
     * @readonly
     */
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of plugins and their dependencies.
     *
     * @parameter property="project.remotePluginRepositories"
     * @required
     * @readonly
     */
    private List<RemoteRepository> remoteRepos;

    private File resourcesDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        injectMavenLoggers();

        // avoid execution if output directory does not exist
        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
            LogProvider.info("skipping non existing directory " + outputDirectory);
            return;
        }

        final BackendType backendType = BackendType.getByNameIgnoreCase(backend);
        if (backendType == null)
            throw new MojoExecutionException("Backend " + backend + " not valid! Valid values are: " +
                    Stream.of(BackendType.values()).map(Enum::name).map(String::toLowerCase).collect(Collectors.joining(", ")));

        LogProvider.info("analyzing JAX-RS resources, using " + backendType.getName() + " backend");

        // add dependencies to analysis class path
        final Set<Path> dependencyPaths = getDependencies();
        LogProvider.debug("Dependency paths are: " + dependencyPaths);

        final Set<Path> projectPaths = Collections.singleton(outputDirectory.toPath());
        LogProvider.debug("Project paths are: " + projectPaths);

        // create target sub-directory
        resourcesDirectory = Paths.get(buildDirectory.getPath(), "jaxrs-analyzer").toFile();
        if (!resourcesDirectory.exists() && !resourcesDirectory.mkdirs())
            throw new MojoExecutionException("Could not create directory " + resourcesDirectory);

        final Path fileLocation = determineFileLocation(backendType);

        // start analysis
        final long start = System.currentTimeMillis();
        new JAXRSAnalyzer(projectPaths, dependencyPaths, backendType, project.getName(), project.getVersion(), deployedDomain, fileLocation).analyze();
        LogProvider.debug("Analysis took " + (System.currentTimeMillis() - start) + " ms");
    }

    private void injectMavenLoggers() {
        LogProvider.injectInfoLogger(getLog()::info);
        LogProvider.injectDebugLogger(getLog()::debug);
        LogProvider.injectErrorLogger(getLog()::error);
    }

    private Set<Path> getDependencies() throws MojoExecutionException {
        project.setArtifactFilter(a -> true);

        Set<Artifact> artifacts = project.getArtifacts();
        if (artifacts.isEmpty()) {
            artifacts = project.getDependencyArtifacts();
        }

        final Set<Path> dependencies = artifacts.stream().map(Artifact::getFile).filter(Objects::nonNull).map(File::toPath).collect(Collectors.toSet());

        // Java EE 7 API is needed internally
        dependencies.add(fetchJavaEEAPI().toPath());
        return dependencies;
    }

    private File fetchJavaEEAPI() throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest();
        final DefaultArtifact artifact = new DefaultArtifact("javax:javaee-api:7.0");
        request.setArtifact(artifact);
        request.setRepositories(remoteRepos);

        LogProvider.debug("Resolving artifact " + artifact + " from " + remoteRepos);

        ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        LogProvider.debug("Resolved artifact " + artifact + " to " + result.getArtifact().getFile() + " from " + result.getRepository());
        return result.getArtifact().getFile();
    }

    private Path determineFileLocation(final BackendType backendType) {
        switch (backendType) {
            case PLAINTEXT:
                return resourcesDirectory.toPath().resolve("rest-resources.txt");
            case ASCIIDOC:
                return resourcesDirectory.toPath().resolve("rest-resources.adoc");
            case SWAGGER:
                return resourcesDirectory.toPath().resolve("swagger.json");
            default:
                throw new IllegalArgumentException("Unknown backend type " + backendType);
        }
    }

}

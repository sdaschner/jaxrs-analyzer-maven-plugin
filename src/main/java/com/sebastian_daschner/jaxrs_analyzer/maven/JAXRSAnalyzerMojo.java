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
import com.sebastian_daschner.jaxrs_analyzer.backend.Backend;
import com.sebastian_daschner.jaxrs_analyzer.backend.StringBackend;
import com.sebastian_daschner.jaxrs_analyzer.backend.swagger.SwaggerOptions;
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
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singleton;
import static java.util.stream.Collectors.joining;

/**
 * Maven goal which analyzes JAX-RS resources.
 *
 * @author Sebastian Daschner
 * @goal analyze-jaxrs
 * @phase process-test-classes
 * @requiresDependencyResolution compile
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
     * @parameter default-value="" property="jaxrs-analyzer.deployedDomain"
     */
    private String deployedDomain;

    /**
     * The Swagger schemes.
     *
     * @parameter default-value="http" property="jaxrs-analyzer.swaggerSchemes"
     */
    private String[] swaggerSchemes;

    /**
     * Specifies if Swagger tags should be generated.
     *
     * @parameter default-value="false" property="jaxrs-analyzer.renderSwaggerTags"
     */
    private Boolean renderSwaggerTags;

    /**
     * The number at which path position the Swagger tags should be extracted.
     *
     * @parameter default-value="0" property="jaxrs-analyzer.swaggerTagsPathOffset"
     */
    private Integer swaggerTagsPathOffset;

    /**
     * For plaintext and asciidoc backends, should they try to prettify inline JSON representation of requests/responses.
     *
     * @parameter default-value="true" property="jaxrs-analyzer.inlinePrettify"
     */
    private Boolean inlinePrettify;

    /**
     * @parameter property="project.build.outputDirectory"
     * @required
     * @readonly
     */
    private File outputDirectory;

    /**
     * @parameter property="project.build.sourceDirectory"
     * @required
     * @readonly
     */
    private File sourceDirectory;

    /**
     * @parameter property="project.build.directory"
     * @required
     * @readonly
     */
    private File buildDirectory;

    /**
     * @parameter property="project.build.sourceEncoding"
     */
    private String encoding;

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


    /**
     * Path, relative to outputDir, to generate resources
     *
     * @parameter default-value="jaxrs-analyzer" property="jaxrs-analyzer.resourcesDir"
     */
    private String resourcesDir;


    /**
     * JAX-RS root resource classes that will be ignored by the analyzer.
     * The fully-qualified class names of classes to be ignored as JAX-RS root resources, separated by comma.
     * Please note that the classes still might be considered as sub-resources, included in other root resources.
     *
     * @parameter default-value="" property="jaxrs-analyzer.ignoredRootResources"
     */
    private String[] ignoredRootResources;

    @Override
    public void execute() throws MojoExecutionException {
        injectMavenLoggers();

        // avoid execution if output directory does not exist
        if (!outputDirectory.exists() || !outputDirectory.isDirectory()) {
            LogProvider.info("skipping non existing directory " + outputDirectory);
            return;
        }

        final JAXRSAnalyzer.Analysis analysis = new JAXRSAnalyzer.Analysis();
        analysis.setProjectName(project.getName());
        analysis.setProjectVersion(project.getVersion());

        final Backend backend = configureBackend(getBackendType());
        analysis.setBackend(backend);

        LogProvider.info("analyzing JAX-RS resources, using " + backend.getName() + " backend");

        // add dependencies to analysis class path
        final Set<Path> classPaths = getDependencies();
        classPaths.forEach(analysis::addClassPath);
        LogProvider.debug("Dependency class paths are: " + classPaths);

        final Set<Path> projectPaths = singleton(outputDirectory.toPath());
        projectPaths.forEach(analysis::addProjectClassPath);
        LogProvider.debug("Project paths are: " + projectPaths);

        final Set<Path> sourcePaths = singleton(sourceDirectory.toPath());
        sourcePaths.forEach(analysis::addProjectSourcePath);
        LogProvider.debug("Source paths are: " + sourcePaths);

        Stream.of(ignoredRootResources).forEach(ignored -> {
            LogProvider.info(String.format("Class %s will be ignored as root resource.", ignored));
            analysis.addIgnoredResource(ignored);
        });

        handleSourceEncoding();

        // create target sub-directory
        final File resourcesDirectory = Paths.get(buildDirectory.getPath(), resourcesDir).toFile();
        if (!resourcesDirectory.exists() && !resourcesDirectory.mkdirs())
            throw new MojoExecutionException("Could not create directory " + resourcesDirectory);

        final Path fileLocation = resourcesDirectory.toPath().resolve(getBackendType().getFileLocation());
        analysis.setOutputLocation(fileLocation);

        LogProvider.info("Generating resources at " + fileLocation.toAbsolutePath());

        // start analysis
        final long start = System.currentTimeMillis();

        new JAXRSAnalyzer(analysis).analyze();

        LogProvider.debug("Analysis took " + (System.currentTimeMillis() - start) + " ms");
    }

    private void handleSourceEncoding() {
        if (encoding != null && System.getProperty("project.build.sourceEncoding") == null)
            System.setProperty("project.build.sourceEncoding", encoding);
    }

    private BackendType getBackendType() {
        switch (backend.toLowerCase()) {
            case "plaintext":
                return BackendType.PLAINTEXT;
            case "asciidoc":
                return BackendType.ASCIIDOC;
            case "markdown":
                return BackendType.MARKDOWN;
            case "swagger":
                return BackendType.SWAGGER;
            default:
                throw new IllegalArgumentException("Backend " + backend + " not valid! Valid values are: " +
                        Stream.of(BackendType.values()).map(Enum::name).map(String::toLowerCase).collect(joining(", ")));
        }
    }

    private Backend configureBackend(final BackendType backendType) throws IllegalArgumentException {
        final Map<String, String> config = new HashMap<>();
        config.put(SwaggerOptions.SWAGGER_SCHEMES, Stream.of(swaggerSchemes).collect(joining(",")));
        config.put(SwaggerOptions.DOMAIN, deployedDomain);
        config.put(SwaggerOptions.RENDER_SWAGGER_TAGS, renderSwaggerTags.toString());
        config.put(SwaggerOptions.SWAGGER_TAGS_PATH_OFFSET, swaggerTagsPathOffset.toString());
        config.put(StringBackend.INLINE_PRETTIFY, inlinePrettify.toString());

        final Backend backend = JAXRSAnalyzer.constructBackend(backendType.name());
        backend.configure(config);

        return backend;
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

        final Set<Path> dependencies = artifacts.stream().filter(a -> !a.getScope().equals(Artifact.SCOPE_TEST)).map(Artifact::getFile)
                .filter(Objects::nonNull).map(File::toPath).collect(Collectors.toSet());

        final String analyzerVersion = project.getPluginArtifactMap().get("com.sebastian-daschner:jaxrs-analyzer-maven-plugin").getVersion();

        // Java EE 7 and JAX-RS Analyzer API is needed internally
        dependencies.add(fetchDependency("javax:javaee-api:7.0"));
        dependencies.add(fetchDependency("com.sebastian-daschner:jaxrs-analyzer:" + analyzerVersion));
        return dependencies;
    }

    private Path fetchDependency(final String artifactIdentifier) throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest();
        final DefaultArtifact artifact = new DefaultArtifact(artifactIdentifier);
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
        return result.getArtifact().getFile().toPath();
    }

}

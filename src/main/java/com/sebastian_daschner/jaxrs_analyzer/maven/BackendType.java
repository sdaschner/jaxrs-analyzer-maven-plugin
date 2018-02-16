package com.sebastian_daschner.jaxrs_analyzer.maven;

/**
 * The backend types available for the Maven plugin.
 *
 * @author Sebastian Daschner
 */
enum BackendType {

    PLAINTEXT("rest-resources.txt"),

    ASCIIDOC("rest-resources.adoc"),

    MARKDOWN("rest-resources.md"),

    SWAGGER("swagger.json");

    private final String fileLocation;

    BackendType(String fileLocation) {
        this.fileLocation = fileLocation;
    }

    public String getFileLocation() {
        return fileLocation;
    }

}

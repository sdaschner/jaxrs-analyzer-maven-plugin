package com.sebastian_daschner.jaxrs_analyzer.maven;

import java.util.Locale;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

/**
 * The backend types available for the Maven plugin.
 *
 * @author Sebastian Daschner
 */
enum BackendType {

    PLAINTEXT("rest-resources.txt"),

    ASCIIDOC("rest-resources.adoc"),

    SWAGGER("swagger.json");

    private final String fileLocation;

    BackendType(String fileLocation) {
        this.fileLocation = fileLocation;
    }

    public String getFileLocation() {
        return fileLocation;
    }

  public static BackendType fromString(String value) {
      try {
        return BackendType.valueOf(value.toUpperCase(Locale.US));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Backend " + value + " not valid! Valid values are: " +
                Stream.of(BackendType.values()).map(Enum::name).map(String::toLowerCase).collect(joining(", ")));
      }
  }
}

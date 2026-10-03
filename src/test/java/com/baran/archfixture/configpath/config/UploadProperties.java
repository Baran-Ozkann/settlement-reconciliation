package com.baran.archfixture.configpath.config;

import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Breaks TDD 11.1: a path bound as a {@code Path}, directly and inside a nested type the binder
 * fills from a list.
 */
@ConfigurationProperties("fixture.upload")
public record UploadProperties(Path directory, List<Mirror> mirrors) {

    public record Mirror(String name, List<Path> roots) {
    }
}

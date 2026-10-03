package com.baran.archfixture.clean.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Allowed: the directory is bound as text, and the code that uses it makes the path. */
@ConfigurationProperties("fixture.upload")
public record UploadProperties(String directory) {
}

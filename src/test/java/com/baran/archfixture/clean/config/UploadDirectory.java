package com.baran.archfixture.clean.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Allowed: configuration sets up the directory uploads are kept in, from its own setting. */
public class UploadDirectory {

    public Path create(Path configured) throws IOException {
        return Files.createDirectories(configured);
    }
}

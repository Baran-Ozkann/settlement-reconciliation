package com.baran.recon.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The directory the servlet container keeps uploads in, {@code recon.ingestion.temp-directory}. It
 * must be the application's own. Startup refuses the system temp directory itself, a filesystem
 * root and the user's home, where other programs keep files: each is checked as configured, then
 * again once the directory exists, through any link, since a link to one of them is the same place.
 *
 * @param path the directory, absolute
 */
record UploadDirectory(Path path) {

    private static final String PROPERTY = "recon.ingestion.temp-directory";
    private static final String OWN = "; it must be a directory of the application's own";

    UploadDirectory {
        Objects.requireNonNull(path, "path");
    }

    /**
     * @param systemTemp the JVM's {@code java.io.tmpdir}
     * @param home       the JVM's {@code user.home}
     */
    static UploadDirectory prepare(Path configured, Path systemTemp, Path home) {
        Path directory = configured.toAbsolutePath().normalize();
        refuseShared(directory, systemTemp.toAbsolutePath().normalize(), home.toAbsolutePath().normalize());
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory);
            }
            refuseShared(directory.toRealPath(), real(systemTemp), real(home));
        } catch (IOException unusable) {
            throw new UncheckedIOException(PROPERTY + " cannot be created", unusable);
        }
        return new UploadDirectory(directory);
    }

    private static void refuseShared(Path directory, Path systemTemp, Path home) {
        if (directory.getParent() == null) {
            throw new IllegalArgumentException(PROPERTY + " is a filesystem root" + OWN);
        }
        if (directory.equals(systemTemp)) {
            throw new IllegalArgumentException(PROPERTY + " is the system temp directory itself" + OWN);
        }
        if (directory.equals(home)) {
            throw new IllegalArgumentException(PROPERTY + " is the user's home directory" + OWN);
        }
    }

    private static Path real(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.exists(absolute) ? absolute.toRealPath() : absolute;
    }
}

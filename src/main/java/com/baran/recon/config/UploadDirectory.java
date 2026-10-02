package com.baran.recon.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The directory the servlet container keeps uploads in, {@code recon.ingestion.temp-directory}. At
 * startup the part files a stopped JVM left in it are deleted, so it must be the application's own,
 * and only one instance's: startup refuses the system temp directory itself, a filesystem
 * root and the user's home, where other programs keep files: each is checked as configured, then
 * again once the directory exists, through any link, since a link to one of them is the same place.
 *
 * @param path the directory, absolute
 */
record UploadDirectory(Path path) {

    private static final Logger LOG = LoggerFactory.getLogger(UploadDirectory.class);
    /** The servlet container's names for the parts it keeps on disk: Tomcat writes upload_<id>_<n>.tmp. */
    private static final String PART_FILES = "upload_*.tmp";
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
        deleteStalePartFiles(directory);
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

    /**
     * Deletes the part files a JVM stopped mid-upload left behind: the container deletes its own when
     * a request ends, but a killed process never gets there. Only regular files directly in the
     * directory whose names are the container's own, {@code upload_*.tmp}, are deleted; a link, a
     * subdirectory and anything under it, or a file named otherwise, are left. Only counts are logged:
     * a part file's name is the container's, but nothing about an upload is written to the log.
     */
    private static void deleteStalePartFiles(Path directory) {
        int deleted = 0;
        int kept = 0;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(directory, PART_FILES)) {
            for (Path part : parts) {
                if (!Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try {
                    Files.delete(part);
                    deleted++;
                } catch (IOException inUse) {
                    kept++;
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(PROPERTY + " cannot be read", unreadable);
        }
        if (deleted > 0) {
            LOG.info("Deleted {} stale upload part files from the upload directory", deleted);
        }
        if (kept > 0) {
            LOG.warn("{} stale upload part files in the upload directory could not be deleted", kept);
        }
    }

    private static Path real(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        return Files.exists(absolute) ? absolute.toRealPath() : absolute;
    }
}

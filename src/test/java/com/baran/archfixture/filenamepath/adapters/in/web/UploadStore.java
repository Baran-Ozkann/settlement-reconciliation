package com.baran.archfixture.filenamepath.adapters.in.web;

import java.nio.file.Path;

/** Breaks FR-ING-9: the web adapter turns the client's file name into a path. */
public class UploadStore {

    public Path target(String originalFilename) {
        return Path.of("uploads", originalFilename);
    }
}

package com.baran.archfixture.usecasefile.application;

import java.io.FileOutputStream;
import java.io.IOException;

/** Breaks FR-ING-9 from the use case: a file opened by the name the upload arrived with. */
public class SpoolUpload {

    public void spool(String originalFilename, byte[] content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(originalFilename)) {
            out.write(content);
        }
    }
}

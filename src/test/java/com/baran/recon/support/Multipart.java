package com.baran.recon.support;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A multipart/form-data body built by hand, since Java's HttpClient has none, so a test controls
 * every byte the server receives: the field names, the file name, and the file's content.
 */
public final class Multipart {

    private final String boundary = "recon-test-" + UUID.randomUUID();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();

    public Multipart field(String name, String value) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(value);
        write("\r\n");
        return this;
    }

    public Multipart file(String name, String filename, byte[] content) {
        write("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n");
        body.writeBytes(content);
        write("\r\n");
        return this;
    }

    public Multipart file(String name, String filename, String content) {
        return file(name, filename, content.getBytes(StandardCharsets.UTF_8));
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public HttpRequest.BodyPublisher publisher() {
        ByteArrayOutputStream complete = new ByteArrayOutputStream();
        complete.writeBytes(body.toByteArray());
        complete.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return HttpRequest.BodyPublishers.ofByteArray(complete.toByteArray());
    }

    private void write(String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}

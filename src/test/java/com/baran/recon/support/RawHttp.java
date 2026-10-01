package com.baran.recon.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * One HTTP/1.1 request written byte for byte on a socket of its own. Java's HttpClient refuses to
 * set {@code Host} and decides itself how a repeated header goes on the wire; a test of what the
 * server does with a forged Host or a header sent twice has to say exactly what is sent.
 */
public final class RawHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private RawHttp() {
    }

    /**
     * Sends the request line, the headers as given, {@code Content-Length} and
     * {@code Connection: close}, then the body, and reads the whole response.
     */
    public static Response send(int port, String requestLine, List<String> headers, String body) throws IOException {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(requestLine).append("\r\n");
        headers.forEach(header -> head.append(header).append("\r\n"));
        head.append("Content-Length: ").append(content.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) TIMEOUT.toMillis());
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(content);
            out.flush();
            return Response.parse(readAll(socket.getInputStream()));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        in.transferTo(response);
        return response.toString(StandardCharsets.UTF_8);
    }

    /** The status code, and everything after the headers as it arrived, chunk framing included. */
    public record Response(int status, String head, String body) {

        static Response parse(String raw) {
            int end = raw.indexOf("\r\n\r\n");
            String head = end < 0 ? raw : raw.substring(0, end);
            String statusLine = head.lines().findFirst().orElse("");
            String[] parts = statusLine.split(" ", 3);
            int status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
            return new Response(status, head, end < 0 ? "" : raw.substring(end + 4));
        }
    }
}

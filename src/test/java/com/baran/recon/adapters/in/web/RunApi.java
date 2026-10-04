package com.baran.recon.adapters.in.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import com.baran.recon.adapters.in.web.StatementApi.Response;
import com.baran.recon.support.ReconUsers;

/** The run endpoints over real HTTP, as a client outside the application calls them. */
final class RunApi implements AutoCloseable {

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    RunApi(int port) {
        this.port = port;
    }

    /** As the operator. */
    Response start(String source, String valueDateFrom, String valueDateTo) throws IOException, InterruptedException {
        return start(Optional.of(ReconUsers.operatorAuthorization()), body(source, valueDateFrom, valueDateTo));
    }

    /** Any body, with any extra headers, each given as name and value in turn. */
    Response start(Optional<String> authorization, String json, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/runs"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        authorization.ifPresent(value -> request.header("Authorization", value));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return Response.of(http.send(request.build(), HttpResponse.BodyHandlers.ofString()));
    }

    static String body(String source, String valueDateFrom, String valueDateTo) {
        return "{\"source\":\"" + source + "\",\"valueDateFrom\":\"" + valueDateFrom + "\",\"valueDateTo\":\""
                + valueDateTo + "\"}";
    }

    Response get(Optional<String> authorization, String id) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/v1/runs/" + id)).GET();
        authorization.ifPresent(value -> request.header("Authorization", value));
        return Response.of(http.send(request.build(), HttpResponse.BodyHandlers.ofString()));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    @Override
    public void close() {
        http.close();
    }
}

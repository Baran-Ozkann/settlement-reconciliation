package com.baran.recon.config;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * This server's own origin, {@code http://<server.address>:<bound port>}, as the cross-site check
 * compares it with a request's {@code Origin}. It comes from the configured loopback address and the
 * port the API connector actually bound, never from the request's {@code Host} header: a DNS
 * rebinding page is served under the attacker's name, sends that name as both its Host and its
 * Origin, and would match itself if the origin were derived from Host.
 *
 * <p>Only the API connector counts. The management connector publishes its own event, and nothing
 * on it accepts a state-changing request.
 */
final class ServerOrigin implements ApplicationListener<ServletWebServerInitializedEvent> {

    private final String host;
    private final AtomicReference<String> origin = new AtomicReference<>();

    ServerOrigin(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("server.address must be set: the API binds to loopback only");
        }
        this.host = address.contains(":") ? "[" + address + "]" : address;
    }

    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        if (event.getApplicationContext().getServerNamespace() == null) {
            origin.set("http://" + host + ":" + event.getWebServer().getPort());
        }
    }

    /** Empty until the API connector has bound its port; no request can arrive before that. */
    Optional<String> value() {
        return Optional.ofNullable(origin.get());
    }
}

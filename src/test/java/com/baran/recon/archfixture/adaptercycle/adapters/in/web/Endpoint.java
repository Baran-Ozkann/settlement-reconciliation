package com.baran.recon.archfixture.adaptercycle.adapters.in.web;

import com.baran.recon.archfixture.adaptercycle.adapters.in.kafka.Listener;

public class Endpoint {

    private final Listener listener = new Listener();

    public Listener listener() {
        return listener;
    }
}

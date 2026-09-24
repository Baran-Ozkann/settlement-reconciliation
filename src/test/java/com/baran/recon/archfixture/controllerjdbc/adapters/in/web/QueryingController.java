package com.baran.recon.archfixture.controllerjdbc.adapters.in.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryingController {

    private final JdbcClient jdbc;

    public QueryingController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM breaks").query(Long.class).single();
    }
}

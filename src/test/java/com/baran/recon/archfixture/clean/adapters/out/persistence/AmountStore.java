package com.baran.recon.archfixture.clean.adapters.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.baran.recon.archfixture.clean.domain.Amount;

public class AmountStore {

    private final JdbcClient jdbc;

    public AmountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Amount amount) {
        jdbc.sql("INSERT INTO amounts (minor_units) VALUES (?)").param(amount.minorUnits()).update();
    }
}

package com.baran.recon.archfixture.postgresconfig.config;

import org.postgresql.ds.PGSimpleDataSource;

public class DriverDataSource {

    public PGSimpleDataSource dataSource() {
        return new PGSimpleDataSource();
    }
}

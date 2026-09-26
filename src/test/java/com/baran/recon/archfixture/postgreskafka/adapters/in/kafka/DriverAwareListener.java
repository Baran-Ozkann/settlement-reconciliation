package com.baran.recon.archfixture.postgreskafka.adapters.in.kafka;

import org.postgresql.util.PSQLException;

public class DriverAwareListener {

    public boolean isUniqueViolation(Throwable failure) {
        return failure instanceof PSQLException psql && "23505".equals(psql.getSQLState());
    }
}

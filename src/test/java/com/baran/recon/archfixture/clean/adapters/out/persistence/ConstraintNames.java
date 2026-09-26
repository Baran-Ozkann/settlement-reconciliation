package com.baran.recon.archfixture.clean.adapters.out.persistence;

import org.postgresql.util.PSQLException;

public class ConstraintNames {

    public String of(PSQLException failure) {
        return failure.getServerErrorMessage() == null ? "" : failure.getServerErrorMessage().getConstraint();
    }
}

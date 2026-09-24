package com.baran.recon.archfixture.clean.domain;

public record Amount(long minorUnits) {

    public Amount plus(Amount other) {
        return new Amount(Math.addExact(minorUnits, other.minorUnits));
    }
}

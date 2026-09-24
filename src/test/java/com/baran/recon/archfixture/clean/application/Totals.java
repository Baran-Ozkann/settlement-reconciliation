package com.baran.recon.archfixture.clean.application;

import com.baran.recon.archfixture.clean.domain.Amount;

public class Totals {

    public Amount sum(Amount a, Amount b) {
        return a.plus(b);
    }
}

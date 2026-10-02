package com.baran.archfixture.clean.application;

import com.baran.archfixture.clean.domain.Amount;

public class Totals {

    public Amount sum(Amount a, Amount b) {
        return a.plus(b);
    }
}

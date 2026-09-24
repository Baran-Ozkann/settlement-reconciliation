package com.baran.recon.archfixture.bigdecimal.adapters.out.persistence;

import java.math.BigDecimal;

public class DecimalStore {

    public BigDecimal scaled(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2);
    }
}

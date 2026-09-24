package com.baran.recon.archfixture.clean.adapters.in.file;

import java.math.BigDecimal;

import com.baran.recon.archfixture.clean.domain.Amount;

public class AmountParser {

    public Amount parse(String text) {
        return new Amount(new BigDecimal(text).movePointRight(2).longValueExact());
    }
}

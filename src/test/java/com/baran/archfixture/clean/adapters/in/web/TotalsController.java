package com.baran.archfixture.clean.adapters.in.web;

import org.springframework.web.bind.annotation.RestController;

import com.baran.archfixture.clean.application.Totals;

@RestController
public class TotalsController {

    private final Totals totals = new Totals();

    public Totals totals() {
        return totals;
    }
}

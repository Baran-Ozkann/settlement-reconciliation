package com.baran.recon.domain.breaks;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.baran.recon.domain.money.CurrencyCode;
import com.baran.recon.domain.money.Money;

/**
 * A batch as one run saw it (TDD 8.2, v1.15): how many lines it had and their net sum in each of
 * their currencies. A batch can mix currencies, so its sum is a list with one entry per currency,
 * kept in currency-code order whatever order it was given in, so the same figures always read the
 * same (INV-5).
 */
public record BatchFigures(int lineCount, List<Money> sums) {

    private static final Comparator<Money> BY_CURRENCY_CODE = Comparator.comparing(sum -> sum.currency().code());

    public BatchFigures {
        if (lineCount < 1) {
            throw new InvalidBreakException("a batch has at least one line");
        }
        sums = sums.stream().map(Objects::requireNonNull).sorted(BY_CURRENCY_CODE).toList();
        if (sums.isEmpty()) {
            throw new InvalidBreakException("a batch's lines sum to an amount in at least one currency");
        }
        if (new HashSet<CurrencyCode>(sums.stream().map(Money::currency).toList()).size() != sums.size()) {
            throw new InvalidBreakException("a batch has one sum per currency");
        }
        if (sums.size() > lineCount) {
            throw new InvalidBreakException("a batch has no more currencies than lines");
        }
    }
}

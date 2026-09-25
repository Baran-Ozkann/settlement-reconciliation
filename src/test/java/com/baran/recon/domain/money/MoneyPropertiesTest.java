package com.baran.recon.domain.money;

import java.math.BigInteger;

import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INV-8 over the whole range of a long, not only the examples in MoneyTest. The reference result is
 * computed with BigInteger, which cannot overflow: an operation must either equal it exactly or
 * throw, and it must throw exactly when the reference does not fit. Seeds are fixed so a failure
 * reproduces (CLAUDE.md 7.3).
 */
@Label("INV-8: money arithmetic is exact or throws")
class MoneyPropertiesTest {

    private static final String SEED = "20260925";
    private static final long REALISTIC = 1_000_000_000_000L;
    private static final CurrencyCode TRY = CurrencyCode.of("TRY");
    private static final BigInteger MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);

    @Property(seed = SEED)
    @Label("addition equals the exact sum, or throws exactly when the sum does not fit")
    void additionIsExactOrThrows(@ForAll long a, @ForAll long b) {
        BigInteger exact = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
        if (fits(exact)) {
            assertThat(Money.of(a, TRY).plus(Money.of(b, TRY)).minorUnits()).isEqualTo(exact.longValueExact());
        } else {
            assertThatThrownBy(() -> Money.of(a, TRY).plus(Money.of(b, TRY)))
                    .isInstanceOf(MoneyOverflowException.class);
        }
    }

    @Property(seed = SEED)
    @Label("subtraction equals the exact difference, or throws exactly when it does not fit")
    void subtractionIsExactOrThrows(@ForAll long a, @ForAll long b) {
        BigInteger exact = BigInteger.valueOf(a).subtract(BigInteger.valueOf(b));
        if (fits(exact)) {
            assertThat(Money.of(a, TRY).minus(Money.of(b, TRY)).minorUnits()).isEqualTo(exact.longValueExact());
        } else {
            assertThatThrownBy(() -> Money.of(a, TRY).minus(Money.of(b, TRY)))
                    .isInstanceOf(MoneyOverflowException.class);
        }
    }

    /** Amounts of a size a ledger holds: sums of three never approach the limits of a long. */
    @Property(seed = SEED)
    @Label("addition of realistic amounts is commutative and associative")
    void additionIsCommutativeAndAssociative(
            @ForAll @LongRange(min = -REALISTIC, max = REALISTIC) long a,
            @ForAll @LongRange(min = -REALISTIC, max = REALISTIC) long b,
            @ForAll @LongRange(min = -REALISTIC, max = REALISTIC) long c) {
        Money x = Money.of(a, TRY);
        Money y = Money.of(b, TRY);
        Money z = Money.of(c, TRY);

        assertThat(x.plus(y)).isEqualTo(y.plus(x));
        assertThat(x.plus(y).plus(z)).isEqualTo(x.plus(y.plus(z)));
    }

    @Property(seed = SEED)
    @Label("subtracting what was added gives back the original amount")
    void subtractionUndoesAddition(
            @ForAll @LongRange(min = -REALISTIC, max = REALISTIC) long a,
            @ForAll @LongRange(min = -REALISTIC, max = REALISTIC) long b) {
        Money x = Money.of(a, TRY);
        Money y = Money.of(b, TRY);

        assertThat(x.plus(y).minus(y)).isEqualTo(x);
        assertThat(x.minus(y)).isEqualTo(x.plus(y.negated()));
    }

    private static boolean fits(BigInteger value) {
        return value.compareTo(MIN) >= 0 && value.compareTo(MAX) <= 0;
    }
}

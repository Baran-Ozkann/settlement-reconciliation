package com.baran.recon.domain.calendar;

import java.time.LocalDate;
import java.util.Set;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;

@Label("TDD 8.1: counting and adding business days agree")
class BusinessCalendarPropertiesTest {

    private static final String SEED = "20260925";
    private static final LocalDate BASE = LocalDate.of(2026, 1, 1);
    private static final BusinessCalendar CALENDAR = new BusinessCalendar(
            Set.of(SATURDAY, SUNDAY),
            Set.of(LocalDate.of(2026, 4, 23), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 19)));

    @Property(seed = SEED)
    @Label("from a business day, counting to the date n business days away gives back n")
    void countingInvertsAdding(@ForAll @IntRange(max = 365) int offset, @ForAll @IntRange(min = -30, max = 30) int days) {
        LocalDate from = BASE.plusDays(offset);
        Assume.that(CALENDAR.isBusinessDay(from));

        LocalDate to = CALENDAR.plusBusinessDays(from, days);

        assertThat(CALENDAR.businessDaysBetween(from, to)).isEqualTo(days);
        assertThat(CALENDAR.isBusinessDay(to)).isTrue();
    }

    @Property(seed = SEED)
    @Label("the count between two business days is antisymmetric")
    void countIsAntisymmetric(@ForAll @IntRange(max = 365) int a, @ForAll @IntRange(max = 365) int b) {
        LocalDate first = BASE.plusDays(a);
        LocalDate second = BASE.plusDays(b);
        Assume.that(CALENDAR.isBusinessDay(first) && CALENDAR.isBusinessDay(second));

        assertThat(CALENDAR.businessDaysBetween(first, second))
                .isEqualTo(-CALENDAR.businessDaysBetween(second, first));
    }
}

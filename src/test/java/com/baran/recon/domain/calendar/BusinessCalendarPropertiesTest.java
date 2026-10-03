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

    @Property(seed = SEED)
    @Label("TDD 8.2: an item is before the first day within grace exactly when more business days than the grace have passed")
    void graceCutoffSplitsByBusinessDaysPassed(@ForAll @IntRange(max = 365) int todayOffset,
                                               @ForAll @IntRange(max = 40) int daysBefore,
                                               @ForAll @IntRange(max = 10) int graceDays) {
        LocalDate today = BASE.plusDays(todayOffset);
        LocalDate valueDate = today.minusDays(daysBefore);

        boolean pastGrace = valueDate.isBefore(CALENDAR.firstDayWithinGrace(today, graceDays));

        assertThat(pastGrace).isEqualTo(CALENDAR.businessDaysBetween(valueDate, today) > graceDays);
    }

    @Property(seed = SEED)
    @Label("TDD 8.2: an index measures any two of its dates as the calendar counts them")
    void indexAgreesWithTheCalendar(@ForAll @IntRange(max = 365) int a, @ForAll @IntRange(max = 60) int span) {
        LocalDate first = BASE.plusDays(a);
        LocalDate second = first.plusDays(span);
        BusinessDayIndex index = BusinessDayIndex.covering(CALENDAR, BASE, BASE.plusDays(430));

        assertThat(index.businessDaysApart(first, second)).isEqualTo(CALENDAR.businessDaysBetween(first, second));
        assertThat(index.businessDaysApart(second, first)).isEqualTo(CALENDAR.businessDaysBetween(first, second));
    }

    @Property(seed = SEED)
    @Label("TDD 8.2: a range's index holds every date within two windows of the range")
    void rangeIndexHoldsTwoWindows(@ForAll @IntRange(max = 365) int fromOffset, @ForAll @IntRange(max = 10) int length,
                                   @ForAll @IntRange(max = 5) int windowDays,
                                   @ForAll @IntRange(min = -30, max = 30) int outside) {
        LocalDate from = BASE.plusDays(fromOffset);
        LocalDate to = from.plusDays(length);
        LocalDate date = outside < 0 ? from.plusDays(outside) : to.plusDays(outside);
        long fromRange = outside < 0 ? CALENDAR.businessDaysBetween(date, from) : CALENDAR.businessDaysBetween(to, date);
        Assume.that(fromRange <= 2L * windowDays);

        assertThat(BusinessDayIndex.forRange(CALENDAR, from, to, windowDays).contains(date)).isTrue();
    }
}

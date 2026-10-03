package com.baran.recon.domain.calendar;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.2: a value-date window is measured in business days through a numbered stretch of dates")
class BusinessDayIndexTest {

    // 2026-10-02 is a Friday; 2026-10-05 the Monday after it.
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    private static final LocalDate SUNDAY_DATE = LocalDate.of(2026, 10, 4);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    private final BusinessCalendar mondayHoliday = new BusinessCalendar(Set.of(SATURDAY, SUNDAY), Set.of(MONDAY));

    @Test
    @DisplayName("each date is numbered with the business days up to it, so weekend and holiday share their predecessor's number")
    void numbersBusinessDays() {
        BusinessDayIndex index = BusinessDayIndex.covering(mondayHoliday, THURSDAY, TUESDAY);

        assertThat(index.days()).containsExactly(THURSDAY, FRIDAY, FRIDAY.plusDays(1), SUNDAY_DATE, MONDAY, TUESDAY);
        assertThat(index.ordinals()).containsExactly(1L, 2L, 2L, 2L, 2L, 3L);
    }

    @Test
    @DisplayName("dates are as many business days apart as the calendar counts, in either order")
    void apartMatchesTheCalendar() {
        BusinessDayIndex index = BusinessDayIndex.covering(mondayHoliday, THURSDAY, TUESDAY);

        assertThat(index.businessDaysApart(FRIDAY, TUESDAY)).as("Monday is a holiday").isEqualTo(1);
        assertThat(index.businessDaysApart(TUESDAY, THURSDAY)).isEqualTo(2);
        assertThat(index.businessDaysApart(SUNDAY_DATE, FRIDAY)).isZero();
    }

    @Test
    @DisplayName("a range's index reaches two windows and a business day past each end")
    void rangeReachesTwoWindowsEachSide() {
        BusinessCalendar weekendOnly = new BusinessCalendar(Set.of(SATURDAY, SUNDAY), Set.of());

        BusinessDayIndex index = BusinessDayIndex.forRange(weekendOnly, MONDAY, MONDAY, 1);

        assertThat(index.first()).isEqualTo(THURSDAY.minusDays(1));
        assertThat(index.last()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    @DisplayName("a date outside the index, an inverted stretch and a negative window are refused")
    void refusesWhatItCannotMeasure() {
        BusinessDayIndex index = BusinessDayIndex.covering(mondayHoliday, THURSDAY, TUESDAY);

        assertThat(index.contains(TUESDAY.plusDays(1))).isFalse();
        assertThatThrownBy(() -> index.businessDaysApart(THURSDAY, TUESDAY.plusDays(1)))
                .isInstanceOf(InvalidCalendarException.class);
        assertThatThrownBy(() -> BusinessDayIndex.covering(mondayHoliday, TUESDAY, THURSDAY))
                .isInstanceOf(InvalidCalendarException.class);
        assertThatThrownBy(() -> BusinessDayIndex.forRange(mondayHoliday, THURSDAY, TUESDAY, -1))
                .isInstanceOf(InvalidCalendarException.class);
    }

    @Test
    @DisplayName("the index's lists cannot be changed by a caller")
    void listsAreUnmodifiable() {
        BusinessDayIndex index = BusinessDayIndex.covering(mondayHoliday, THURSDAY, TUESDAY);

        assertThatThrownBy(() -> index.days().add(TUESDAY)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> index.ordinals().add(9L)).isInstanceOf(UnsupportedOperationException.class);
    }
}

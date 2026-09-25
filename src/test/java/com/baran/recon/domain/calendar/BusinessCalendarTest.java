package com.baran.recon.domain.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TDD 8.1: business days skip the configured weekend and holidays")
class BusinessCalendarTest {

    // 2026-10-02 is a Friday; 2026-10-05 the Monday after it.
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 2);
    private static final LocalDate SATURDAY_DATE = LocalDate.of(2026, 10, 3);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    private final BusinessCalendar weekendOnly = new BusinessCalendar(Set.of(SATURDAY, SUNDAY), Set.of());
    private final BusinessCalendar mondayHoliday = new BusinessCalendar(Set.of(SATURDAY, SUNDAY), Set.of(MONDAY));

    @Test
    @DisplayName("weekend days and holidays are not business days")
    void weekendAndHolidaysAreNotBusinessDays() {
        assertThat(weekendOnly.isBusinessDay(FRIDAY)).isTrue();
        assertThat(weekendOnly.isBusinessDay(SATURDAY_DATE)).isFalse();
        assertThat(weekendOnly.isBusinessDay(MONDAY)).isTrue();
        assertThat(mondayHoliday.isBusinessDay(MONDAY)).isFalse();
    }

    @Test
    @DisplayName("adding business days steps over the weekend and holidays")
    void addingSkipsNonBusinessDays() {
        assertThat(weekendOnly.plusBusinessDays(THURSDAY, 1)).isEqualTo(FRIDAY);
        assertThat(weekendOnly.plusBusinessDays(FRIDAY, 1)).isEqualTo(MONDAY);
        assertThat(mondayHoliday.plusBusinessDays(FRIDAY, 1)).isEqualTo(TUESDAY);
        assertThat(weekendOnly.plusBusinessDays(MONDAY, -1)).isEqualTo(FRIDAY);
        assertThat(mondayHoliday.plusBusinessDays(TUESDAY, -1)).isEqualTo(FRIDAY);
    }

    @Test
    @DisplayName("adding zero business days returns the date itself, even on a weekend")
    void addingZeroIsIdentity() {
        assertThat(weekendOnly.plusBusinessDays(SATURDAY_DATE, 0)).isEqualTo(SATURDAY_DATE);
    }

    @Test
    @DisplayName("a payment on Friday is one business day old on Monday")
    void fridayToMondayIsOneBusinessDay() {
        assertThat(weekendOnly.businessDaysBetween(FRIDAY, MONDAY)).isEqualTo(1);
        assertThat(weekendOnly.businessDaysBetween(MONDAY, FRIDAY)).isEqualTo(-1);
        assertThat(mondayHoliday.businessDaysBetween(FRIDAY, TUESDAY)).isEqualTo(1);
        assertThat(weekendOnly.businessDaysBetween(FRIDAY, FRIDAY)).isZero();
    }

    @Test
    @DisplayName("a weekend covering every day is rejected")
    void allWeekendIsRejected() {
        assertThatThrownBy(() -> new BusinessCalendar(EnumSet.allOf(DayOfWeek.class), Set.of()))
                .isInstanceOf(InvalidCalendarException.class);
    }

    @Test
    @DisplayName("no weekend at all is allowed")
    void emptyWeekendIsAllowed() {
        BusinessCalendar everyDay = new BusinessCalendar(Set.of(), Set.of());

        assertThat(everyDay.plusBusinessDays(FRIDAY, 1)).isEqualTo(SATURDAY_DATE);
    }

    @Test
    @DisplayName("the configuration is copied, so later changes to the caller's sets do not leak in")
    void configurationIsCopied() {
        Set<LocalDate> holidays = new HashSet<>();
        BusinessCalendar calendar = new BusinessCalendar(Set.of(SATURDAY, SUNDAY), holidays);
        holidays.add(MONDAY);

        assertThat(calendar.isBusinessDay(MONDAY)).isTrue();
    }
}

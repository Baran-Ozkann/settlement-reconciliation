package com.baran.recon.domain.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every date of a stretch, each numbered with the business days of the stretch that fall on or
 * before it. Two dates of the stretch are then {@code |ordinal(a) - ordinal(b)|} business days
 * apart, exactly as {@link BusinessCalendar#businessDaysBetween} counts from the earlier to the
 * later, so a value-date window (TDD 8.2) becomes one subtraction. A run hands the index to its
 * SQL as two arrays, so the calendar is defined here once and never again in SQL.
 */
public final class BusinessDayIndex {

    /** Consecutive dates, the first being the index's first. */
    private final List<LocalDate> days;
    private final List<Long> ordinals;

    private BusinessDayIndex(List<LocalDate> days, List<Long> ordinals) {
        this.days = List.copyOf(days);
        this.ordinals = List.copyOf(ordinals);
    }

    /**
     * The stretch a run needs to measure the windows of its value-date range (TDD 8.2): every date
     * within {@code 2 * windowDays} business days of the range. An item in the range has its
     * candidates within one window, and a candidate outside the range is checked against its own
     * candidates within one more, so two windows on each side cover every date a run compares.
     */
    public static BusinessDayIndex forRange(BusinessCalendar calendar, LocalDate from, LocalDate to, int windowDays) {
        if (windowDays < 0) {
            throw new InvalidCalendarException("a window is not negative");
        }
        // One business day more than two windows: from a non-business day, stepping back counts the
        // business day before it as the first step, and stepping forward lands on a business day
        // whose following non-business days share its number.
        int margin = Math.addExact(Math.multiplyExact(2, windowDays), 1);
        return covering(calendar, calendar.plusBusinessDays(from, -margin), calendar.plusBusinessDays(to, margin));
    }

    /** Every date from {@code first} to {@code last}, both included. */
    public static BusinessDayIndex covering(BusinessCalendar calendar, LocalDate first, LocalDate last) {
        Objects.requireNonNull(calendar, "calendar");
        if (last.isBefore(first)) {
            throw new InvalidCalendarException("an index ends on or after the day it starts");
        }
        List<LocalDate> days = new ArrayList<>();
        List<Long> ordinals = new ArrayList<>();
        long ordinal = 0;
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (calendar.isBusinessDay(day)) {
                ordinal++;
            }
            days.add(day);
            ordinals.add(ordinal);
        }
        return new BusinessDayIndex(days, ordinals);
    }

    /** Every date of the index, in order. */
    public List<LocalDate> days() {
        return days;
    }

    /** For each date of {@link #days()}, the business days of the index on or before it. */
    public List<Long> ordinals() {
        return ordinals;
    }

    public LocalDate first() {
        return days.getFirst();
    }

    public LocalDate last() {
        return days.getLast();
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(first()) && !day.isAfter(last());
    }

    /** Business days between the two dates, however they are ordered. Both must be in the index. */
    public long businessDaysApart(LocalDate a, LocalDate b) {
        return Math.abs(ordinalOf(a) - ordinalOf(b));
    }

    private long ordinalOf(LocalDate day) {
        if (!contains(day)) {
            throw new InvalidCalendarException("the date is outside the index");
        }
        return ordinals.get((int) (day.toEpochDay() - first().toEpochDay()));
    }
}

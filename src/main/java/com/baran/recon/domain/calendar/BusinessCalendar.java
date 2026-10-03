package com.baran.recon.domain.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Which days money moves on (TDD 8.1): every day except the configured weekend days and holidays.
 * Value-date windows and grace periods are counted in these days, so a payment made on Friday is
 * one business day old on Monday, not three.
 */
public record BusinessCalendar(Set<DayOfWeek> weekend, Set<LocalDate> holidays) {

    public BusinessCalendar {
        weekend = weekend.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(weekend));
        holidays = Set.copyOf(holidays);
        if (weekend.size() == DayOfWeek.values().length) {
            throw new InvalidCalendarException("every day of the week is a weekend day");
        }
    }

    public boolean isBusinessDay(LocalDate date) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /**
     * The business day {@code days} business days after {@code from}, or before it when negative.
     * {@code from} itself is not counted, so zero returns it unchanged even when it is a holiday.
     */
    public LocalDate plusBusinessDays(LocalDate from, int days) {
        int step = Integer.signum(days);
        LocalDate date = from;
        for (int remaining = Math.abs(days); remaining > 0; ) {
            date = date.plusDays(step);
            if (isBusinessDay(date)) {
                remaining--;
            }
        }
        return date;
    }

    /**
     * Business days crossed going from {@code from} to {@code to}: positive forward, negative
     * backward. Forward it counts the business days after {@code from} up to and including
     * {@code to}; backward, those from {@code to} up to but excluding {@code from}. That makes it
     * the inverse of {@link #plusBusinessDays} whenever {@code from} is a business day.
     */
    public long businessDaysBetween(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return -countBusinessDays(to.minusDays(1), from.minusDays(1));
        }
        return countBusinessDays(from, to);
    }

    /**
     * The earliest value date still inside a grace period of {@code graceDays} business days on
     * {@code today} (TDD 8.2). An item dated before it is past its grace period: more than
     * {@code graceDays} business days lie after its value date, up to and including today. An item
     * dated on it or later is not, so a set of items is split by one comparison of dates.
     */
    public LocalDate firstDayWithinGrace(LocalDate today, int graceDays) {
        if (graceDays < 0) {
            throw new InvalidCalendarException("a grace period is not negative");
        }
        LocalDate lastBusinessDay = today;
        while (!isBusinessDay(lastBusinessDay)) {
            lastBusinessDay = lastBusinessDay.minusDays(1);
        }
        return plusBusinessDays(lastBusinessDay, -graceDays);
    }

    /** Business days in the half-open range (after, upTo]. */
    private long countBusinessDays(LocalDate after, LocalDate upTo) {
        return after.plusDays(1).datesUntil(upTo.plusDays(1)).filter(this::isBusinessDay).count();
    }
}

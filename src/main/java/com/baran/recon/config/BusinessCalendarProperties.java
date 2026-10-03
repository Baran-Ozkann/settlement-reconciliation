package com.baran.recon.config;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.baran.recon.domain.calendar.BusinessCalendar;

/**
 * {@code recon.business-calendar} (TDD 8.1): the days money does not move on. Value-date windows
 * and grace periods are counted in the other days. Holidays are bound as text and parsed here as
 * ISO dates, so a malformed one stops startup with the value that failed.
 */
@ConfigurationProperties("recon.business-calendar")
public record BusinessCalendarProperties(List<DayOfWeek> weekend, List<String> holidays) {

    /** TDD 8.1's weekend, for a configuration that leaves the key out. */
    private static final Set<DayOfWeek> TDD_WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    BusinessCalendar toCalendar() {
        Set<DayOfWeek> weekendDays = weekend == null ? TDD_WEEKEND : Set.copyOf(weekend);
        Set<LocalDate> holidayDates = holidays == null ? Set.of()
                : holidays.stream().map(LocalDate::parse).collect(Collectors.toUnmodifiableSet());
        return new BusinessCalendar(weekendDays, holidayDates);
    }
}

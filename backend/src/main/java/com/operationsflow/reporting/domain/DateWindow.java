package com.operationsflow.reporting.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import com.operationsflow.shared.domain.BusinessException;

public record DateWindow(LocalDate from,LocalDate to,String zone,Instant start,Instant end) {
    public static DateWindow of(LocalDate from,LocalDate to,String zone){return of(from,to,zone,Clock.systemUTC());}
    public static DateWindow of(LocalDate from,LocalDate to,String zone,Clock clock){
        if(!Set.of("Europe/Berlin","UTC").contains(zone))throw invalid("Choose Europe/Berlin or UTC.");
        var tz=ZoneId.of(zone);var today=LocalDate.now(clock.withZone(tz));
        if(to==null)to=today;if(from==null)from=to.minusDays(29);
        if(from.getYear()<1900 || to.getYear()>2100 || from.isAfter(to) || ChronoUnit.DAYS.between(from,to)>365)throw invalid("Use an inclusive date range of 1–366 days within 1900–2100.");
        return new DateWindow(from,to,zone,from.atStartOfDay(tz).toInstant(),to.plusDays(1).atStartOfDay(tz).toInstant());
    }
    public static int threshold(int value){if(value<0||value>1000000)throw invalid("Low-stock threshold must be 0–1,000,000 inclusive.");return value;}
    private static BusinessException invalid(String message){return new BusinessException("INVALID_QUERY",message);}
}

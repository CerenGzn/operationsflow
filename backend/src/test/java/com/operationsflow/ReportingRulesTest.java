package com.operationsflow;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.operationsflow.reporting.domain.*;
import com.operationsflow.reporting.application.CsvWriter;
import com.operationsflow.reporting.api.ReportingContracts.Column;
import com.operationsflow.shared.domain.BusinessException;
import static org.assertj.core.api.Assertions.*;

class ReportingRulesTest {
    @Test void berlinCalendarDaysRespectBothDstTransitions(){
        var spring=DateWindow.of(LocalDate.parse("2026-03-29"),LocalDate.parse("2026-03-29"),"Europe/Berlin");
        assertThat(spring.start()).isEqualTo(Instant.parse("2026-03-28T23:00:00Z"));
        assertThat(spring.end()).isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
        var autumn=DateWindow.of(LocalDate.parse("2026-10-25"),LocalDate.parse("2026-10-25"),"Europe/Berlin");
        assertThat(Duration.between(autumn.start(),autumn.end()).toHours()).isEqualTo(25);
    }
    @Test void defaultsUseSelectedZoneAndIncludeThirtyCalendarDays(){
        var clock=Clock.fixed(Instant.parse("2026-03-31T22:30:00Z"),ZoneOffset.UTC);
        var berlin=DateWindow.of(null,null,"Europe/Berlin",clock);
        assertThat(berlin.to()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(berlin.from()).isEqualTo(LocalDate.parse("2026-03-03"));
        assertThat(DateWindow.of(null,null,"UTC",clock).to()).isEqualTo(LocalDate.parse("2026-03-31"));
    }
    @Test void boundsRejectInvalidZonesRangesAndThresholds(){
        var day=LocalDate.parse("2026-01-01");
        assertThatThrownBy(()->DateWindow.of(day,day,"America/New_York")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->DateWindow.of(day,day.minusDays(1),"UTC")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->DateWindow.of(day,day.plusDays(366),"UTC")).isInstanceOf(BusinessException.class);
        assertThat(DateWindow.of(day,day.plusDays(365),"UTC").to()).isEqualTo(day.plusDays(365));
        assertThatThrownBy(()->DateWindow.threshold(-1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->DateWindow.threshold(1000001)).isInstanceOf(BusinessException.class);
    }
    @Test void csvNeutralizesFormulaPrefixesIncludingHiddenControlCharacters(){
        for(String text:List.of("=1+1","+cmd","-1+2","@SUM(A1)","  =1","\tplain","\r=1","\uFEFF=1","\u200B+1")){
            String csv=CsvWriter.write(List.of(new Column("x","Value",false)),List.of(Map.of("x",text)));
            assertThat(csv).startsWith("\"Value\"\r\n\"'");
        }
    }
    @Test void csvPreservesQuotesNewlinesAndExactSignedIntegers(){
        String csv=CsvWriter.write(List.of(new Column("text","Text",false),new Column("number","Number",true)),List.of(Map.of("text","Synthetic \"quote\",\nnext","number","-9007199254740993")));
        assertThat(csv).isEqualTo("\"Text\",\"Number\"\r\n\"Synthetic \"\"quote\"\",\nnext\",\"-9007199254740993\"\r\n");
        assertThat(CsvWriter.write(List.of(new Column("x","Value",true)),List.of(Map.of("x","=1")))).contains("\"'=1\"");
    }
    @Test void reportFiltersRejectCrossDatasetStatesAndUnsupportedAuditLocation(){
        var range=DateWindow.of(null,null,"UTC");
        assertThatThrownBy(()->new ReportFilter(range,null,null,"SHIPPED","",10).validate(ReportKind.INVENTORY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new ReportFilter(range,UUID.randomUUID(),null,"","",10).validate(ReportKind.AUDIT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->ReportKind.parse("users")).isInstanceOf(BusinessException.class);
    }
}

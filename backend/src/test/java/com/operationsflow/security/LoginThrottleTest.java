package com.operationsflow.security;
import java.time.*;
import org.junit.jupiter.api.Test;
import com.operationsflow.security.application.LoginThrottle;
import static org.assertj.core.api.Assertions.*;
class LoginThrottleTest {
    @Test void accountLimitCannotBeBypassedByChangingAddress(){var limiter=new LoginThrottle();for(int i=0;i<10;i++)assertThat(limiter.acquire("address-"+i,"synthetic@example.invalid").allowed()).isTrue();assertThat(limiter.acquire("new-address","synthetic@example.invalid").allowed()).isFalse();}
    @Test void sourceLimitStopsDistributedAccountGuessing(){var limiter=new LoginThrottle();for(int i=0;i<50;i++)assertThat(limiter.acquire("source","account-"+i).allowed()).isTrue();assertThat(limiter.acquire("source","another").allowed()).isFalse();}
    @Test void windowExpiresAndRejectionIsReportedOnlyOnce(){
        class MutableClock extends Clock { Instant now=Instant.EPOCH;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;} }
        var clock=new MutableClock();var limiter=new LoginThrottle(clock);for(int i=0;i<10;i++)limiter.acquire("source","account");
        assertThat(limiter.acquire("source","account").recordRejection()).isTrue();assertThat(limiter.acquire("source","account").recordRejection()).isFalse();clock.now=clock.now.plusSeconds(901);assertThat(limiter.acquire("source","account").allowed()).isTrue();
    }
}

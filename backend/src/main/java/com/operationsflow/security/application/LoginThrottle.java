package com.operationsflow.security.application;

import java.time.*;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Single-process bounded limiter. Ignores untrusted forwarding headers. */
@Component
public class LoginThrottle {
    private static final Duration WINDOW=Duration.ofMinutes(15);
    private final Map<String,Bucket> buckets=new HashMap<>();
    private final Clock clock;
    public LoginThrottle(){this(Clock.systemUTC());}
    public LoginThrottle(Clock clock){this.clock=clock;}
    private static final class Bucket { Instant start;int attempts;boolean reported;Bucket(Instant now){start=now;} }
    public record Admission(boolean allowed,boolean recordRejection){}
    public synchronized Admission acquire(String address,String login){
        Instant now=clock.instant();buckets.values().removeIf(b -> !b.start.plus(WINDOW).isAfter(now));
        String ip="ip:"+hash(address),account="account:"+hash(login);
        if(buckets.size()>9998 && (!buckets.containsKey(ip)||!buckets.containsKey(account)))return new Admission(false,false);
        var a=buckets.computeIfAbsent(ip,k->new Bucket(now));var b=buckets.computeIfAbsent(account,k->new Bucket(now));
        if(a.attempts>=50 || b.attempts>=10){boolean report=!(a.reported||b.reported);a.reported=true;b.reported=true;return new Admission(false,report);}
        a.attempts++;b.attempts++;return new Admission(true,false);
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
}

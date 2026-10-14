package com.operationsflow.security.application;
import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;
public final class SecurityResponses {
    private SecurityResponses(){}
    public static void write(HttpServletResponse response,int status,String code,String detail)throws IOException{
        response.setStatus(status);response.setContentType("application/problem+json");response.setHeader("Cache-Control","no-store");
        // Only fixed server-defined messages/codes are accepted at call sites.
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":"+status+",\"code\":\""+code+"\",\"detail\":\""+detail+"\"}");
    }
}

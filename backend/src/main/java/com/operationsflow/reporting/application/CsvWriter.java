package com.operationsflow.reporting.application;
import java.util.*;
import com.operationsflow.reporting.api.ReportingContracts.Column;
public final class CsvWriter {
    private CsvWriter(){}
    public static String write(List<Column> columns,List<Map<String,String>> rows){
        var csv=new StringBuilder();csv.append(columns.stream().map(c->cell(c.label(),false)).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        for(var row:rows)csv.append(columns.stream().map(c->cell(row.get(c.key()),c.numeric())).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return csv.toString();
    }
    public static String cell(String value,boolean numeric){
        String s=value==null?"":value;
        if(!(numeric&&s.matches("-?[0-9]+"))){
            int i=0;boolean control=false;
            while(i<s.length()){char c=s.charAt(i);if(Character.isWhitespace(c)||Character.isSpaceChar(c)||Character.isISOControl(c)||Character.getType(c)==Character.FORMAT){control|=Character.isISOControl(c);i++;}else break;}
            if(control || (i<s.length() && "=+-@".indexOf(s.charAt(i))>=0))s="'"+s;
        }
        return "\""+s.replace("\"","\"\"")+"\"";
    }
}

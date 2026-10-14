package com.operationsflow.reporting.domain;
import java.util.*;
import com.operationsflow.shared.domain.BusinessException;
public enum ReportKind {
    ORDERS("orders"),INVENTORY("inventory"),LOW_STOCK("low-stock"),MOVEMENTS("movements"),FULFILLMENT("fulfillment"),SHIPMENTS("shipments"),AUDIT("audit");
    public final String path;ReportKind(String path){this.path=path;}
    public static ReportKind parse(String path){return Arrays.stream(values()).filter(k->k.path.equals(path)).findFirst().orElseThrow(()->new BusinessException("NOT_FOUND","Report not found."));}
    public boolean snapshot(){return this==INVENTORY||this==LOW_STOCK;}
}

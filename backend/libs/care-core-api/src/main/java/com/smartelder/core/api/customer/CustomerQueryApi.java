package com.smartelder.core.api.customer;
import java.util.*;

/** Enterprise-local consumer identity. Modules must not query the customer_account table. */
public interface CustomerQueryApi {
 Optional<CustomerSummary> find(String publicId);
 List<CustomerSummary> search(String query,int limit);
 record CustomerSummary(String publicId,String username,String displayName){}
}

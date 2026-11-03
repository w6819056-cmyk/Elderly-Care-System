package com.smartelder.api.tenant.portal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import com.smartelder.api.tenant.auth.TenantStaffPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class VisitBookingController {
 private final VisitBookingService service;private final StorePortalService portal;
 public VisitBookingController(VisitBookingService service,StorePortalService portal){this.service=service;this.portal=portal;}
 private <T> ApiResponse<T> ok(T value,HttpServletRequest r){Object id=r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME);return ApiResponse.success(value,id==null?"tr_visit":id.toString(),Clock.systemUTC());}
 @PostMapping("/api/public/store-portals/{no}/visit-bookings") public Object create(@PathVariable String no,@Valid @RequestBody Create b,HttpServletRequest r){portal.throttle("visit:"+r.getRemoteAddr());return ok(Map.of("bookingNo",service.create(no,b.visitorName(),b.phone(),b.visitType(),b.preferredDate(),b.remark())),r);}
 @GetMapping("/api/tenant/v1/visit-bookings") public Object list(Authentication auth,HttpServletRequest r){return ok(service.list(((TenantStaffPrincipal)auth.getPrincipal()).accessContext()),r);}
 @PatchMapping("/api/tenant/v1/visit-bookings/{id}/status") public Object status(@PathVariable String id,@Valid @RequestBody Change b,Authentication auth,HttpServletRequest r){service.status(id,b.status(),((TenantStaffPrincipal)auth.getPrincipal()).accessContext(),String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)));return ok(null,r);}
 public record Create(@NotBlank @Size(max=80) String visitorName,@NotBlank @Pattern(regexp="[0-9+() -]{6,32}") String phone,@NotBlank String visitType,@NotNull LocalDate preferredDate,@Size(max=500) String remark){}
 public record Change(@NotBlank String status){}
}

package com.smartelder.api.tenant.portal;
import com.smartelder.common.ApiResponse;
import com.smartelder.common.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/customer")
public class CustomerController {
 private final CustomerService service;private final StorePortalService portal;
 public CustomerController(CustomerService service,StorePortalService portal){this.service=service;this.portal=portal;}
 private <T> ApiResponse<T> ok(T v,HttpServletRequest r){return ApiResponse.success(v,String.valueOf(r.getAttribute(TraceIdFilter.ATTRIBUTE_NAME)),Clock.systemUTC());}
 @PostMapping("/register") public Object register(@Valid @RequestBody Register b,HttpServletRequest r){portal.throttle("customer-register:"+r.getRemoteAddr());return ok(service.register(b.username(),b.password(),b.displayName()),r);}
 @PostMapping("/login") public Object login(@Valid @RequestBody Login b,HttpServletRequest r){portal.throttle("customer-login:"+r.getRemoteAddr());portal.throttle("customer-account:"+b.username().trim().toLowerCase(java.util.Locale.ROOT));return ok(service.login(b.username(),b.password()),r);}
 @GetMapping("/me") public Object me(@RequestHeader(value="X-Customer-Token",required=false) String token,HttpServletRequest r){return ok(service.me(service.resolve(token)),r);}
 @PostMapping("/logout") public Object logout(@RequestHeader(value="X-Customer-Token",required=false) String token,HttpServletRequest r){service.logout(token);return ok(null,r);}
 @GetMapping("/bookings") public Object bookings(@RequestHeader(value="X-Customer-Token",required=false) String token,HttpServletRequest r){return ok(service.bookings(service.resolve(token)),r);}
 @PostMapping("/stores/{no}/bookings") public Object book(@PathVariable String no,@RequestHeader(value="X-Customer-Token",required=false) String token,@Valid @RequestBody VisitBookingController.Create b,HttpServletRequest r){long customer=service.resolve(token);portal.throttle("customer-book:"+customer);return ok(Map.of("bookingNo",service.book(customer,no,b)),r);}
 public record Register(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{3,40}") String username,@NotBlank @Size(min=12,max=64) @Pattern(regexp="[\\x20-\\x7E]+") String password,@NotBlank @Size(max=80) String displayName){}
 public record Login(@NotBlank @Size(max=80) String username,@NotBlank @Size(max=64) String password){}
}

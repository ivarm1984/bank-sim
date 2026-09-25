package io.github.ivarm1984.banksim.customer;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    /** Same as the column default in {@code customer-0003}. */
    private static final BigDecimal DEFAULT_MONTHLY_INCOME = new BigDecimal("3000.00");

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    public ResponseEntity<Customer> create(@Valid @RequestBody CreateCustomerRequest request) {
        Customer created = customerService.create(new NewCustomer(
                request.fullName(),
                request.creditGrade() == null ? CreditGrade.C : request.creditGrade(),
                request.monthlyIncome() == null ? DEFAULT_MONTHLY_INCOME : request.monthlyIncome()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public List<Customer> findAll() {
        return customerService.findAll();
    }

    @GetMapping("/{id}")
    public Customer findById(@PathVariable long id) {
        return customerService.findById(id);
    }

    /** Credit profile is optional - omitted fields fall back to grade C / 3,000 a month. */
    public record CreateCustomerRequest(@NotBlank String fullName, CreditGrade creditGrade, @Positive BigDecimal monthlyIncome) {
    }
}

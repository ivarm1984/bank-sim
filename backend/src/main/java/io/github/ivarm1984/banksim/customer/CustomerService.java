package io.github.ivarm1984.banksim.customer;

import static io.github.ivarm1984.banksim.jooq.customer.tables.Customers.CUSTOMERS;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStep3;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private final DSLContext dsl;

    public CustomerService(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Creates a customer with the default credit profile (grade C, 3,000 a month - see {@code customer-0003}). */
    public Customer create(String fullName) {
        var record = dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.FULL_NAME, fullName)
                .returning()
                .fetchOne();
        return toCustomer(record);
    }

    public Customer create(NewCustomer customer) {
        var record = dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.FULL_NAME, customer.fullName())
                .set(CUSTOMERS.CREDIT_GRADE, customer.creditGrade().name())
                .set(CUSTOMERS.MONTHLY_INCOME, customer.monthlyIncome())
                .returning()
                .fetchOne();
        return toCustomer(record);
    }

    /** Creates many customers in a single multi-row INSERT - for bulk seeding (see {@code DataSeeder}). */
    public List<Customer> createBatch(List<NewCustomer> customers) {
        if (customers.isEmpty()) {
            return List.of();
        }
        InsertValuesStep3<io.github.ivarm1984.banksim.jooq.customer.tables.records.CustomersRecord, String, String, BigDecimal> insert =
                dsl.insertInto(CUSTOMERS, CUSTOMERS.FULL_NAME, CUSTOMERS.CREDIT_GRADE, CUSTOMERS.MONTHLY_INCOME);
        for (NewCustomer customer : customers) {
            insert = insert.values(customer.fullName(), customer.creditGrade().name(), customer.monthlyIncome());
        }
        return insert.returning().fetch().map(CustomerService::toCustomer);
    }

    public Customer findById(long id) {
        var record = dsl.selectFrom(CUSTOMERS)
                .where(CUSTOMERS.ID.eq(id))
                .fetchOne();
        if (record == null) {
            throw new NoSuchElementException("No customer with id " + id);
        }
        return toCustomer(record);
    }

    public List<Customer> findAll() {
        return dsl.selectFrom(CUSTOMERS)
                .orderBy(CUSTOMERS.ID)
                .fetch()
                .map(CustomerService::toCustomer);
    }

    private static Customer toCustomer(io.github.ivarm1984.banksim.jooq.customer.tables.records.CustomersRecord record) {
        return new Customer(
                record.getId(), record.getFullName(), CreditGrade.valueOf(record.getCreditGrade()), record.getMonthlyIncome(),
                record.getCreatedAt());
    }
}

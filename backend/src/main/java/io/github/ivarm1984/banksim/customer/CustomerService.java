package io.github.ivarm1984.banksim.customer;

import static io.github.ivarm1984.banksim.jooq.customer.tables.Customers.CUSTOMERS;

import java.util.List;
import java.util.NoSuchElementException;

import org.jooq.DSLContext;
import org.jooq.InsertValuesStep1;
import org.springframework.stereotype.Service;

@Service
public class CustomerService {

    private final DSLContext dsl;

    public CustomerService(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Customer create(String fullName) {
        var record = dsl.insertInto(CUSTOMERS)
                .set(CUSTOMERS.FULL_NAME, fullName)
                .returning()
                .fetchOne();
        return toCustomer(record);
    }

    /** Creates many customers in a single multi-row INSERT - for bulk seeding (see {@code DataSeeder}). */
    public List<Customer> createBatch(List<String> fullNames) {
        if (fullNames.isEmpty()) {
            return List.of();
        }
        InsertValuesStep1<io.github.ivarm1984.banksim.jooq.customer.tables.records.CustomersRecord, String> insert =
                dsl.insertInto(CUSTOMERS, CUSTOMERS.FULL_NAME);
        for (String fullName : fullNames) {
            insert = insert.values(fullName);
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
        return new Customer(record.getId(), record.getFullName(), record.getCreatedAt());
    }
}

package io.github.ivarm1984.banksim.customer;

import java.time.OffsetDateTime;

public record Customer(Long id, String fullName, OffsetDateTime createdAt) {
}

package io.github.ivarm1984.banksim.policy;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record PolicyLeversRequest(
        @NotNull @DecimalMin("-0.05") @DecimalMax("0.05") BigDecimal savingsRateSpread,
        @NotNull @DecimalMin("-0.05") @DecimalMax("0.05") BigDecimal mortgageSpreadAdjustment,
        @NotNull @DecimalMin("-0.05") @DecimalMax("0.05") BigDecimal consumerSpreadAdjustment,
        @NotNull @DecimalMin("-0.05") @DecimalMax("0.05") BigDecimal businessSpreadAdjustment,
        @NotNull @DecimalMin("0.00") @DecimalMax("0.10") BigDecimal targetCapitalBuffer,
        @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal underwritingLooseness,
        boolean autoTapBorrowingFacility) {
}

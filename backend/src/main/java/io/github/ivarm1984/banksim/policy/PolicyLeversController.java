package io.github.ivarm1984.banksim.policy;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policy-levers")
public class PolicyLeversController {

    private final PolicyLevers policyLevers;

    public PolicyLeversController(PolicyLevers policyLevers) {
        this.policyLevers = policyLevers;
    }

    @GetMapping
    public PolicyLeversSnapshot state() {
        return policyLevers.state();
    }

    @PostMapping
    public PolicyLeversSnapshot update(@Valid @RequestBody PolicyLeversRequest request) {
        return policyLevers.update(new PolicyLeversSnapshot(
                request.savingsRateSpread(), request.mortgageSpreadAdjustment(), request.consumerSpreadAdjustment(),
                request.targetCapitalBuffer(), request.underwritingLooseness(), request.autoTapBorrowingFacility()));
    }
}

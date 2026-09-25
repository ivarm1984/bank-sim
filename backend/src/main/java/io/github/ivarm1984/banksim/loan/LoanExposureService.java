package io.github.ivarm1984.banksim.loan;

import java.util.List;

import org.springframework.stereotype.Service;

/**
 * Read-only view of the active loan book for treasury - kept separate from
 * {@link LoanService}, which itself depends on {@code TreasuryService} (the
 * origination throttle), so treasury can read exposures without a bean cycle.
 */
@Service
public class LoanExposureService {

    private final LoanRepository loanRepository;

    public LoanExposureService(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public List<CreditExposure> activeExposures() {
        return loanRepository.activeExposuresByTypeAndPhase();
    }
}

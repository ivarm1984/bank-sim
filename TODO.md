# Bank Simulation — TODO

Learning project: simulate a bank end-to-end to learn banking-domain concepts
(double-entry ledgers, deposits/loans/transfers, interest, statements, treasury
and EU regulatory ratios) and system-design/tooling concepts (sync vs. async
service interaction, event-driven batch processing, live UIs — and later, real
message brokers, observability/logging, data-lake/CDC). "Customers" are plain
programmed agents (no AI) acting on a controllable simulated clock (step one day,
or run continuously at a chosen speed). A Vue dashboard shows balances and a live
event feed and controls the simulation.

Stack: Java + Spring Boot backend, Vue 3 + TypeScript frontend, Postgres.
Architecture: **modular monolith** first (package-by-domain, single deployable app),
not microservices — but structured so the seams are in the right place for a later
split into real services with a real broker (Kafka/RabbitMQ/NATS) and
observability/logging/data-lake tooling (Prometheus/Grafana, ELK/Loki, Debezium).
That later phase is deliberately **out of scope** for now.

Full design detail (package layout, ledger model, clock design, event flow, API
surface, frontend structure) lives in the plan this was generated from — the
checklist below is the actionable build order.

---

## M0 — Scaffolding
- [x] `backend/`: Gradle (Groovy DSL) Spring Boot skeleton (spring-web, jOOQ,
      postgresql, liquibase, spring-websocket, validation) — see `AGENTS.md`
      for why jOOQ over JPA/Hibernate and Liquibase over Flyway
- [x] `frontend/`: Vite + Vue 3 + TypeScript skeleton
- [x] Root `docker-compose.yml`: Postgres 16, named volume, `POSTGRES_DB=banksim`
- [x] `application.yml`: datasource, Liquibase changelog location, actuator
      health enabled
- [x] Schema-per-module convention established (one Postgres schema per
      top-level domain package: `customer`, `account`, `ledger`,
      `transaction`; no cross-schema FKs) — see `AGENTS.md`
- [x] Verify: `curl localhost:8080/actuator/health` → UP; `docker compose ps`
      healthy; `npm run dev` loads with no console errors; `psql \dn` lists
      the four schemas

## M1 — Ledger core
- [x] `customer`, `account`, `ledger` packages
- [x] Liquibase changesets: customers, accounts, ledger_accounts,
      journal_entries, ledger_lines (see `AGENTS.md` — Liquibase, not Flyway)
- [x] Seed chart of accounts: `BANK_CASH`, `INTEREST_EXPENSE`, `FEE_INCOME`
      (singletons) + one `CUSTOMER_LIABILITY` ledger account per `Account`
- [x] `LedgerService.post(JournalEntryRequest)` — enforce
      `SUM(debits) == SUM(credits)`, throw `UnbalancedJournalEntryException`
      otherwise. **Write this test first.**
- [x] `Account.currentBalance` cached, updated in the same transaction as the
      ledger post; pessimistic locking on `Account` rows during posting
- [x] `LedgerReconciliationService` — recompute balance from ledger lines, compare
      to cached balance
- [x] Minimal REST: customers, accounts, `GET /api/accounts/{id}/balance`
- [x] Verify: `./gradlew test` passes ledger invariant tests (Testcontainers
      Postgres); after posting entries, trial-balance query (`SUM` debits −
      credits per ledger account) nets to zero

## M2 — Transactions via REST
- [x] `transaction` package: `Transaction` entity,
      `TransactionService.deposit/withdraw/transfer` (calls `LedgerService`
      synchronously, in one transaction)
  - Deposit → Debit `BANK_CASH`, Credit customer liability
  - Withdrawal → Debit customer liability, Credit `BANK_CASH`
  - Transfer A→B → Debit `CustomerLiability(A)`, Credit `CustomerLiability(B)`
- [x] Insufficient-funds handling (400 response)
- [x] REST: `POST /api/transactions/{deposit,withdrawal,transfer}`,
      `GET /api/transactions`
- [x] Integration test against real Postgres (not just H2)
- [x] Verify: deposit/withdraw/transfer via curl; over-withdraw → 400; transfer
      conserves total system balance; trial balance still zero

## M3 — Clock + agents (headless)
- [x] `clock` package: `SimulationClock` (`AtomicReference<ClockSnapshot>`),
      `ClockTickScheduler` (`@Scheduled(fixedRate=1000)`), publishes
      `ClockTickedEvent` and `DayRolledOverEvent` (one per day crossed)
- [x] `stepOneDay()` — same advance logic as continuous run, no sleep, loops until
      one day boundary crossed
- [x] All domain timestamps from injected simulated clock, never wall-clock `now()`
      (governs the new clock/agent code; existing ledger/transaction DB-default
      timestamps are unchanged — no M3 schema changes)
- [x] REST: `GET /api/clock/state`, `POST /api/clock/{play,pause,step-day,reset}`,
      `POST /api/clock/speed`
- [x] `agents` package: `Agent` interface (`onTick(simulatedNow, context)`),
      `AgentScheduler` (listens `ClockTickedEvent`, per-agent exception isolation)
- [x] `SalaryAgent`, `BillPayAgent` (publishes `BillPaymentFailedEvent` on
      insufficient funds), `RandomSpenderAgent` (seeded `Random` for
      reproducibility)
- [x] `DataSeeder` (`ApplicationRunner`): 4 demo customers with
      checking+savings accounts, one of each agent type
- [x] Verify: `speed 50` + `play`, wait ~30s, sim date advances and agent
      transactions appear in `GET /api/transactions`; `pause` stops advancement;
      `step-day` from paused advances exactly one day

## M4 — End-of-day batch
- [x] `interest` package: `InterestAccrualService`/`Listener` on
      `DayRolledOverEvent`, per-account `@Transactional` accrual (not one giant
      transaction), posts ledger entry (Debit `INTEREST_EXPENSE`, Credit customer
      liability), writes `InterestAccrual` (unique per account+date), publishes
      `InterestAccrualBatchCompletedEvent`
  - Added a proper `AccountType` enum (`CHECKING`/`SAVINGS`/`TERM_DEPOSIT`,
    replacing the free-text `accountType` field) and an `interest.rate_policies`
    table keyed by account type, seeded with illustrative flat rates - M6
    (treasury) replaces these with central-bank-rate-derived pricing
- [x] `statement` package: `StatementGenerationService`/`Listener` on
      `InterestAccrualBatchCompletedEvent` (chained, not parallel listener),
      generates one `Statement` per account per day (unique per account+date)
- [x] REST: `GET /api/accounts/{id}/interest-accruals`,
      `GET /api/accounts/{id}/statements`
- [x] Verify: computed interest matches a known balance/rate by hand; statement's
      opening balance on day N+1 equals closing balance on day N; trial balance
      still zero

## M5 — WebSocket + Vue dashboard
- [x] `web` package: `WebSocketConfig` (STOMP over SockJS, `/ws`,
      `enableSimpleBroker("/topic")`)
- [x] `EventFeedPublisher`: `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`
      bridge from every domain event → `/topic/events`; clock state → `/topic/clock`
  - Added `TransactionCompletedEvent` (published from `TransactionService`) since
    no event previously existed for deposit/withdraw/transfer; added
    `GET /api/ledger/accounts` and `GET /api/ledger/trial-balance` to back
    `LedgerInspector` (no ledger REST existed before this milestone)
- [x] Frontend: `api/` (REST clients, axios), `ws/stompClient.ts`, Pinia `stores/`
      (accounts, customers, clock, event feed), `views/DashboardView.vue`,
      `components/` (SimulationControls, AccountsList, EventFeed, TransactionLog,
      StatementViewer, LedgerInspector) — styled with Tailwind CSS
- [x] Wire: REST populates initial store state on mount; WS events patch stores
      live; periodic REST re-sync to correct drift
- [x] Vite dev server proxies `/api` and `/ws` to `localhost:8080`
- [x] Verify: Play in browser, EventFeed populates live (check STOMP frames in
      devtools), balances update live, speed/Pause/Step Day work, WS-driven UI
      state matches `GET /api/accounts` REST truth
  - Verified live in Chrome: Play advances the sim-time header via `/topic/clock`;
    Step Day produced "Interest accrual batch completed" and "Simulated day
    rolled over" entries in the live Event feed via `/topic/events`; Pause
    stopped the clock and the UI's displayed state matched `GET
    /api/clock/state` exactly; Ledger's trial-balance banner showed balanced
    throughout. Found and fixed two real bugs during this pass (not caught by
    `./gradlew test` or `npm run build`, since neither exercises the app at
    runtime): (1) sockjs-client references the Node global `global`, which
    Vite doesn't polyfill — fixed with `define: { global: 'globalThis' }` in
    `vite.config.ts`; (2) adding `@EnableWebSocketMessageBroker` introduced
    multiple candidate `Executor` beans, leaving `@Async` unable to pick one
    unambiguously (silently fell back to `SimpleAsyncTaskExecutor` with a
    logged warning) — fixed with an explicit `taskExecutor` bean in the new
    `web/AsyncConfig.java`.

## M6 — Treasury & loans (simplified)

The bank's own balance-sheet management, separate from customer-facing services.
Simple version for now: fixed-rate amortizing loans, no default risk; single
aggregate ratios rather than full EU breakdowns. Deeper versions of both are in
the "Complex additions" section below.

Split into sub-milestones, each intended as its own session (each depends on
the previous one landing first): M6.1 → M6.2 → M6.3 → M6.4 → M6.5 → M6.6.
Before starting a sub-milestone's code, surface any non-obvious design choice
in it with `AskUserQuestion` rather than assuming — see `AGENTS.md` for what's
already decided vs. still open.

### M6.1 — Treasury ledger foundation ✅ done
- [x] New singleton ledger accounts in the existing `ledger` schema (not a
      new `treasury` schema yet — no treasury-owned table exists to justify
      one; per `AGENTS.md`, a schema lands with the code that first needs
      it): `CENTRAL_BANK_RESERVES` (asset), `BANK_CAPITAL` (equity), seeded
      via `ledger/data/0002-seed-treasury-accounts.yaml` (same pattern as
      the M1 chart-of-accounts seed)
- [x] New `treasury` package (flat, no schema of its own yet):
      `TreasuryCapitalSeeder` (`ApplicationRunner`) posts the initial paid-in
      capital as a real journal entry (Debit `CENTRAL_BANK_RESERVES`, Credit
      `BANK_CAPITAL`, 1,000,000.00 — an arbitrary round seed value, easy to
      change later) via `LedgerService.post(...)`, idempotent on
      `LedgerAccountService.hasAnyLedgerLines(bankCapitalId)`
- [ ] `LOAN_RECEIVABLE` (one per loan, analogous to `CUSTOMER_LIABILITY`) is
      *not* created here — deferred to M6.3 where the `loan` schema/table it
      references actually exists
- [x] Verify: schema/accounts exist, trial balance still zero after seeding
      bank capital (the seed posting itself balances) — confirmed live via
      `GET /api/ledger/accounts` (`CENTRAL_BANK_RESERVES` debit 1,000,000.00,
      `BANK_CAPITAL` credit 1,000,000.00) and `GET /api/ledger/trial-balance`
      (`balanced: true`)

### M6.2 — Central bank counterparty ✅ done
- [x] New flat `centralbank` package (no schema — rates are computed, not
      persisted, mirroring how `treasury` started flat in M6.1): policy rate
      (ECB main refinancing rate analog), deposit facility rate (paid on
      excess reserves, policy − 25bps), marginal lending rate (charged when
      the bank borrows to cover a shortfall, policy + 25bps) — this is the
      base rate loan/deposit pricing builds on
  - Design calls made via `AskUserQuestion`: (1) new package rather than
    folding into `treasury`; (2) rates **drift deterministically** over
    simulated time rather than staying static — see below; (3) the existing
    `interest.rate_policies` flat rates (checking/savings/term-deposit) are
    **deliberately left unrewired to the new base rate for now** — M6.2's own
    scope was just making the central bank counterparty exist and be
    queryable, and wiring consumers (savings-rate policy, and M6.3's loan
    pricing `rate = base + risk spread`) is separate follow-up work once
    there's more than one consumer to design the spread convention around
  - Drift design: `CentralBankRateSchedule` reviews the policy rate every 42
    simulated days (an ECB Governing Council meets roughly every six weeks),
    starting from `SimulationClock.epoch()`. Each review holds/cuts/hikes by
    25bps (20%/60%/20%), clamped to [0%, 7.5%]. Deliberately not reactive to
    simulated events yet (loan volume, deposit runs, ...) — real feedback is
    future work (M6.5's throttle/borrow loop, or the CEO-mode rate-shock
    `EventInjector`)
  - Found and fixed a real bug during this pass: the first design re-seeded a
    fresh `java.util.Random` per review index (`SEED + reviewIndex`), which
    looked deterministic and stayed in-bounds but is a real trap — Random's
    first draw from consecutive seeds is strongly correlated, so every review
    silently landed in the "hold" bucket and the rate sat flat at 3.00% for
    15+ simulated years. Caught by a test asserting the rate actually moves
    over a long horizon (not just that it stays in bounds), not by manual
    testing. Fixed by advancing one shared, sequentially-seeded `Random`
    across the whole replay instead of reseeding per review.
  - REST: `GET /api/central-bank/rates`
- [x] Verify: confirmed live via `GET /api/central-bank/rates` — deterministic
      at the epoch date (3.00% / 2.75% / 3.25%), genuinely wanders both up and
      down over a 16-simulated-year run (3.25% → 5.00% → 3.75% → 2.25% →
      3.25%), and a `clock/reset` reproduces the exact epoch-date rates again

### M6.3 — Loan package + origination/repayment ✅ done
- [x] `loan` package + schema: `LoanAccount` (principal, term, rate = base +
      risk spread, amortization schedule)
  - Design calls made via `AskUserQuestion`: (1) installment periods use
    calendar-month arithmetic (`LocalDate.plusMonths(1)`), not fixed 30-day
    periods; (2) the full amortization schedule is precomputed and persisted
    at origination (`loan_installments`, one row per planned payment) and is
    **never modified by later prepayments** - it's the original plan, not a
    live view of what's owed; (3) `repay(loanId, amount)` supports normal
    payment, partial early payoff, and full early payoff via one
    amount-driven method (see below); (4) the risk spread is a flat
    system-wide constant (`LoanService.RISK_SPREAD`, 400bps) added to the
    central bank policy rate - real per-borrower credit risk pricing stays
    deferred to "Complex additions" below
  - Amortization uses a **nominal monthly periodic rate** (`annualRate /
    12`), deliberately *not* the day-count (actual/365) convention
    `InterestAccrualService` uses for savings/checking interest - monthly
    amortizing loans conventionally price off a nominal periodic rate,
    independent of how many calendar days actually fall in a given month.
    Mixing the two conventions in one codebase is intentional, not
    inconsistency - don't "fix" loan interest to use day-count.
  - Prepayment is a **term-reduction model**: `installmentAmount` (fixed at
    origination) never changes; extra principal just drains
    `outstanding_principal` faster than the original plan, so the loan
    finishes ahead of `term_months` on its own rather than lowering future
    installments. `loans.outstanding_principal` (not the persisted schedule)
    is always the live source of truth for what's actually owed.
  - New `loan.loans` / `loan.loan_installments` / `loan.loan_payments`
    tables (own schema, per `AGENTS.md`); `loan_installments` FKs to
    `loan.loans` (same-schema FK is fine), `customer_id`/
    `disbursement_account_id` are soft references (no cross-schema FK)
- [x] Added `LOAN_RECEIVABLE` (one per loan) and `INTEREST_INCOME`
      (singleton, analogous to `FEE_INCOME`) to `LedgerAccountType` - resolved
      the design call deferred from M6.1 by adding a nullable `loan_id`
      column to `ledger_accounts` (mirroring `account_id`) plus a partial
      unique index and a `CHECK (account_id IS NULL OR loan_id IS NULL)`
      constraint
  - Found and fixed a real bug during this pass:
    `idx_ledger_accounts_singleton_type` was `UNIQUE (type) WHERE account_id
    IS NULL` - once `loan_id` existed, every `LOAN_RECEIVABLE` row also has
    `account_id IS NULL`, so the *second* loan ever originated hit a
    duplicate-key error against the *first* loan's receivable row, as if
    `LOAN_RECEIVABLE` were a singleton type. Caught by the integration tests
    (any test creating two loans), not by manual testing. Fixed with a new
    changeset (`ledger-0006-...`, never edit an already-applied changeset)
    narrowing the partial index to `WHERE account_id IS NULL AND loan_id IS
    NULL`.
- [x] `LoanService.originate/disburse/repay` — disbursement: Debit
      `LOAN_RECEIVABLE`, Credit customer's checking account; each repayment
      splits principal vs. interest income, one `@Transactional` posting per
      payment
  - `repay(loanId, amount)` classifies the payment as `NORMAL` (amount ≤ the
    regular installment), `EARLY_PARTIAL` (more than the installment but
    less than a full payoff - extra goes straight to principal), or
    `EARLY_PAYOFF` (amount covers `outstandingPrincipal + interestDue` -
    caps the actual charge at exactly that, ignores any excess, and closes
    the loan). Interest due is always computed live off the loan's *current*
    `outstanding_principal`, not the persisted plan, so it stays correct
    after a prior prepayment.
  - Repaying a loan requires interest on top of principal, so a
    disbursement-account balance that's exactly the disbursed principal is
    *not* enough to fully service the loan to term - by design (a real
    borrower needs income beyond the loan itself to pay interest). This is
    exactly the gap `M6.6`'s `BorrowerAgent` (paired with income from
    something like `SalaryAgent`) needs to close; it's not a bug.
- [x] REST: `POST /api/loans`, `GET /api/accounts/{id}/loans`,
      `GET /api/loans/{id}`
  - Also added `POST /api/loans/{id}/repay` (body: `{amount}`) - not in the
    original bullet above, but needed to manually verify
    normal/partial/full-payoff repayment behavior before `BorrowerAgent`
    exists in M6.6, same rationale M5 used when it added ledger REST beyond
    what was originally listed
- [x] Verify: loan disbursement/repayment keeps trial balance at zero;
      amortization schedule matches a hand-computed example — confirmed via
      `LoanServiceTest` (Testcontainers Postgres): origination's persisted
      schedule fully amortizes to zero and its principal portions sum back
      to the original principal; disbursement and every repayment scenario
      (normal, partial-early-payoff-then-payoff, full-early-payoff, and a
      complete 6-installment origination→repayments→payoff sequence) keep
      `LedgerReconciliationService.trialBalance().isBalanced()` true
      throughout; a `repay()` call on an already-`PAID_OFF` loan throws

### M6.4 — TreasuryService ratios ✅ done
- [x] `TreasuryService` — recomputes simplified ratios from aggregate ledger
      totals (end of day, chained off the existing batch, or on demand):
  - Loan-to-Deposit Ratio (LDR) — internal risk metric, not directly regulated
  - Liquidity Coverage Ratio (LCR), single number: reserves/HQLA ÷ estimated
    30-day net outflow — EU minimum 100% (CRR / Delegated Reg. 2015/61)
  - Net Stable Funding Ratio (NSFR), single number: stable funding ÷ required
    stable funding — EU minimum 100% (CRR2, since June 2021)
  - Minimum reserve requirement — ECB requires ~1% of certain short-term
    liabilities held as central bank reserves (monetary-policy mechanic, not
    solvency)
  - Capital Adequacy Ratio — simplified CET1 vs. a rough risk-weighted-assets
    estimate; EU minimums 4.5% CET1 / 8% total capital + buffers (CRR)
  - Design calls made via `AskUserQuestion`: (1) ratios are persisted as a
    daily snapshot (new `treasury` schema, `ratio_snapshots` table, one row
    per sim-day) chained off the EOD batch, rather than computed purely
    on-demand — gives ratio history for free for later milestones (M6.5's
    feedback loop, a future CEO-mode chart); (2) "capital" for CAR/NSFR is
    `BANK_CAPITAL` + accumulated net income to date
    (`INTEREST_INCOME + FEE_INCOME − INTEREST_EXPENSE`), not paid-in capital
    alone — there's no retained-earnings sweep in this system, so income/
    expense ledger accounts stay permanent rather than closing into
    `BANK_CAPITAL`; without adding net income back in, CAR would never move
    except through RWA and would misstate the bank's real capital position
  - Every other rate/factor (LCR's 10% assumed 30-day deposit runoff, NSFR's
    90% deposit ASF factor / 85% loan RSF factor, 100% loan risk weight for
    RWA) is a flat illustrative constant in `TreasuryService`, same spirit as
    `LoanService.RISK_SPREAD` — real CRR/CRR2 breaks each of these down by
    category/maturity, deferred to "Complex additions" below
  - Ratio fields are `null` (not zero or an exception) when their denominator
    is zero — e.g. NSFR/CAR before any loan has ever been originated, since
    both have the loan book in their denominator
  - Chaining needed a new `StatementGenerationBatchCompletedEvent` (statement
    generation previously had no batch-completion event to chain off of,
    unlike interest accrual) — `StatementGenerationScheduler` now publishes
    it after generating every account's statement for the day, and
    `TreasuryRatioScheduler` listens for it the same way
    `StatementGenerationScheduler` listens for `InterestAccrualBatchCompletedEvent`
- [x] REST: `GET /api/treasury/ratios` — 404 until at least one simulated day
      has passed (no snapshot yet), otherwise the latest snapshot
- [x] Verify: confirmed live via `POST /api/clock/step-day` +
      `GET /api/treasury/ratios` — every field hand-verified against
      `GET /api/ledger/accounts` on a fresh dev DB (LDR/LCR/NSFR/reserve-
      coverage/CAR all matched the formulas above to 6 decimal places);
      NSFR/CAR were `null` before any loan existed; after
      `POST /api/loans` (5,000.00 principal) + another `step-day`, LDR moved
      0 → 0.280616 and NSFR/CAR went from `null` to real numbers, all
      matching hand computation off the same `GET /api/ledger/accounts`
      snapshot. `TreasuryServiceTest` (Testcontainers Postgres) covers the
      same two things at the unit level: a persisted snapshot's ratios are
      internally consistent with its own raw balances, and originating/
      disbursing a loan moves `loansReceivable`/`customerDeposits` by exactly
      the principal while leaving `bankCash`/`centralBankReserves` untouched.

### M6.5 — Feedback loop ✅ done
- [x] Feedback loop: if LCR/NSFR/capital ratio breaches its threshold,
      treasury either throttles new loan origination or auto-borrows reserves
      from the central bank facility; publishes `TreasuryRatiosUpdatedEvent`
  - Design calls made via `AskUserQuestion`: (1) the two responses are split
    by *which* ratio breaches, not "any breach → both" - LCR/reserve-coverage
    (liquidity shortfalls) auto-borrow central bank reserves, since that
    directly fixes those ratios; NSFR/CAR (capital/funding-structure
    shortfalls) throttle loan origination instead, since borrowing reserves
    doesn't fix those - mirrors why a real bank can't borrow its way out of a
    capital problem; (2) the central bank facility is a real interest-bearing
    liability (new singleton `CENTRAL_BANK_BORROWINGS` ledger account,
    reusing the existing `INTEREST_EXPENSE` singleton for its cost) rather
    than a one-shot balance-sheet plug - `TreasuryService.accrueBorrowingInterest`
    capitalizes one day's interest at the marginal lending rate into the
    balance every `DayRolledOverEvent` (day-count convention, same as
    customer interest), and `applyFeedback`'s repay side is the facility's
    repayment path (see below), so there's no separate table/repayment
    endpoint needed; (3) the throttle is a hard block -
    `LoanService.originate()` throws `IllegalStateException` (400) for as
    long as the latest treasury snapshot shows NSFR<100% or CAR<8%
  - `TreasuryService.applyFeedback(snapshot)` recomputes HQLA/outflow/reserve
    headroom from the snapshot's own already-persisted raw balances (not a
    fresh ledger read) - a breach draws exactly the worse of the two
    shortfalls (Debit CENTRAL_BANK_RESERVES, Credit CENTRAL_BANK_BORROWINGS);
    absent a breach, any outstanding facility balance is repaid using only
    the headroom that keeps both ratios at/above 100% afterward (reverse
    posting). The snapshot itself is deliberately left as its "as-detected"
    reading - the correction shows up in *tomorrow's* snapshot, same as a
    real treasury desk reacting overnight to an EOD report
  - `TreasuryService.isLoanOriginationThrottled()` (used by both
    `LoanService.originate()` and a new `GET /api/treasury/loan-origination-status`)
    and `applyFeedback`'s throttled flag share one `isCapitalBreach` check;
    false before any snapshot has ever been computed
  - `TreasuryRatioScheduler` now also listens on `DayRolledOverEvent`
    (alongside, not chained after, `InterestAccrualScheduler`'s customer
    interest accrual) to drive `accrueBorrowingInterest` before the day's
    ratio snapshot/feedback runs off `StatementGenerationBatchCompletedEvent`
  - New `LedgerAccountService.singletonCreditBalance(type)` - the facility's
    outstanding balance, needed by both interest accrual and the repay-cap
    calculation
- [x] Verify: forcing a large loan pushes LCR below 100% and triggers the
      throttle/borrow path — confirmed live: originated a 20,000,000
      principal loan (pre-existing dev DB had ~1,000,000 bank capital, 5,000
      loans receivable), then stepped the clock forward. The EOD snapshot
      showed `capitalAdequacyRatio: 0.049843` (below the 8% minimum) and
      `liquidityCoverageRatio` restored to exactly `1.000000` — confirming
      `centralBankReserves` had jumped from 1,000,000.00 to 1,991,681.29 (an
      auto-borrow of exactly the LCR shortfall) — while
      `GET /api/treasury/loan-origination-status` reported
      `{"throttled":true}` and a second `POST /api/loans` was rejected 400
      ("Loan origination is currently throttled..."). Stepping another day
      showed `CENTRAL_BANK_BORROWINGS` compounding daily (991,681.29 →
      992,034.54 → ...) and `capitalBase` falling by exactly that day's
      interest, with `GET /api/ledger/trial-balance` staying balanced
      throughout. `TreasuryServiceTest` covers the same borrow/repay/throttle
      logic in isolation via hand-crafted snapshots (since `applyFeedback`
      only reads the snapshot it's handed, never the live ledger, to decide
      a breach) - the repay-recovery test deliberately uses a huge headroom
      snapshot so it fully clears the facility (and self-cleans) regardless
      of run order in this shared-Postgres-container suite, and the
      throttle test restores a healthy snapshot in a `finally` so it doesn't
      leave later test classes permanently throttled.

### M6.6 — BorrowerAgent + end-to-end verify ✅ done
- [x] `BorrowerAgent`: applies for a loan occasionally, then pays on schedule
      via `AgentScheduler` (like `BillPayAgent`, but the debt amortizes)
  - Expanded beyond the original one-line scope per a mid-build steer: two
    real loan products instead of one generic loan, and demo seeding at
    ~10,000 customers instead of 4 - see `AskUserQuestion`-driven design
    calls below and the approved plan this milestone was built from.
  - New `LoanType` enum (`MORTGAGE`, `CONSUMER`) on `loans` - a new
    `loan_type` column (`loan-0005-add-loan-type.yaml`), replacing the
    single flat `LoanService.RISK_SPREAD` with `MORTGAGE_RISK_SPREAD`
    (150bps) and `CONSUMER_RISK_SPREAD` (900bps): mortgages price low/long,
    consumer loans price high/short, same "illustrative constant" spirit as
    the spread they replace. `originate()` rejects a second `MORTGAGE` on
    the same account (any status, not just active) - "we can simplify to 1
    mortgage" was explicit. Consumer loans are unrestricted in count; the
    agent (not `LoanService`) is what keeps a borrower to one *active*
    consumer loan at a time.
  - `AgentContext` gained a `loanService` field (and `AgentScheduler`'s
    constructor a matching parameter) so agents can originate/repay loans -
    the M6.3-M6.5 loan work had no agent-facing path before this.
  - `BorrowerAgent` tracks two independent slots: a mortgage (daily
    low-probability roll, taken at most once ever) and a consumer loan
    (daily higher-probability roll, only while it doesn't already have one
    active; the slot frees up once `LoanPayment.outstandingPrincipalAfter()`
    hits zero, allowing another). Missed installments (insufficient funds)
    are skipped silently, same as `RandomSpenderAgent`'s spend-skip -
    delinquency/NPL tracking stays deferred in "Complex additions" below.
    Always paired with a `SalaryAgent` on the same account in `DataSeeder` -
    a borrower needs income to service its debt, flagged back in M6.3.
  - `DataSeeder` now seeds `SEED_CUSTOMER_COUNT = 10_000` customers (the
    original 4 named ones kept for continuity, the rest generated) via new
    batch-insert paths - `CustomerService.createBatch`,
    `AccountService.openBatch`, `LedgerAccountService
    .createCustomerLiabilityAccountsBatch` - chunked multi-row
    `INSERT ... RETURNING` (1,000 rows/statement) instead of one round trip
    per row, since no batch-insert capability existed anywhere in the
    codebase before this and 10k customers × 2 accounts × a ledger account
    each would otherwise be ~50,000 sequential round trips at startup.
    Confirmed live: 10,000 customers + 20,000 accounts seed in ~3 seconds.
  - Dashboard changes for event volume at this scale: `EventFeedPublisher`
    now also bridges `LoanOriginatedEvent`/`LoanRepaidEvent` onto
    `/topic/events`; the frontend `eventFeed` store filters the *visible*
    feed to "big enough" events (large transactions, bill-payment failures,
    batch/day-rollover events, loan originations, and only a payoff-closing
    `LOAN_REPAID`) while still tallying every event into rolling counters
    (`EventStats.vue`: total transactions/volume, loans originated, loan
    payments, and payments in the last real-time minute) - filtering
    happens client-side only, `EventFeedPublisher` keeps broadcasting
    everything unfiltered. `DashboardView.vue` no longer triggers a full
    `accounts.load()` on every `TRANSACTION_COMPLETED` (was a full unbounded
    `GET /api/accounts` per transaction - fine at 4 customers, a runaway
    hammering loop at 10k); the periodic 10s resync covers it instead.
    `GET /api/accounts` is now paginated by default (`limit`/`offset`,
    `all=true` for the old unbounded behavior) and `AccountsList.vue` got
    Prev/Next paging plus a `Map`-based customer-name lookup instead of a
    per-row linear scan.
  - **Found during verification, deliberately not fixed here** (see the new
    "Later phases" item below): the M4 EOD batch
    (`InterestAccrualService`/`StatementGenerationService`) does one
    `@Transactional` accrual/statement *per account, per simulated day*.
    That's invisible at 4 demo accounts but at 20,000 accounts it made one
    simulated day take ~3-4 minutes of real time with the clock running -
    the 10k-customer seed itself is fine, but actually *playing* the
    simulation forward at this scale is currently impractical.
- [x] Verify: full M6 checklist end-to-end — loan lifecycle, ratios, feedback
      loop all observable together over a multi-day simulated run
  - `./gradlew test` (Testcontainers Postgres): new `BorrowerAgentTest`
    (direct `onTick` calls, same pattern as `SalaryAgentTest`) covers
    taking a mortgage + consumer loan on a successful roll and never a
    second mortgage, paying the due installment down, and silently skipping
    an unpayable installment without throwing; `LoanServiceTest` gained
    mortgage-vs-consumer pricing and second-mortgage-rejection cases.
  - Live: reset the dev DB, booted the app, confirmed 10,000 customers /
    20,000 accounts seeded, `GET /api/accounts` paginated at 100/page, and
    `GET /api/treasury/loan-origination-status` reachable. Playing the
    clock forward to actually observe `BorrowerAgent` take/repay loans
    end-to-end live was blocked by the EOD-batch performance issue above
    (one simulated day advancing every few minutes) - the loan
    lifecycle/ratio/feedback-loop interaction itself is exercised by the
    existing `LoanServiceTest`/`TreasuryServiceTest`/`BorrowerAgentTest`
    suites, just not watched live end-to-end at 10k-customer scale.

---

## Later phases (not started yet)
- [x] Batch the EOD interest-accrual/statement-generation jobs. Found during
      M6.6's 10,000-customer seeding: `InterestAccrualService` and
      `StatementGenerationService` each did one `@Transactional`
      accrual/statement per account per simulated day - fine at a handful of
      demo accounts, but at 20,000 accounts one simulated day took ~3-4
      minutes of real time with the clock running.
  - Design calls made via `AskUserQuestion`: (1) chunks of 200 accounts per
    transaction (the user's own suggestion over one mega-transaction for the
    whole day, or reusing the 1,000-row seeding chunk size) - bounded
    transaction/lock duration, and a failing chunk only rolls back its own
    200 accounts, not the whole day; (2) a new bulk-specific ledger-posting
    path, `LedgerService.postBulkAccountCredits`, deliberately bypassing
    `post(JournalEntryRequest)`'s generic per-line lookup/lock loop - `post()`
    is itself O(lines) round trips even inside one transaction, so routing
    200 lines/chunk through it wouldn't have removed the bottleneck. One
    journal entry per chunk (1 debit line for the chunk's total interest
    expense + one credit line per accruing account) is safe without
    `post()`'s per-row `FOR UPDATE` locking/negative-balance guard: a credit
    can never drive a liability negative, and the EOD batch is provably
    single-threaded relative to agent activity (`ClockService.tick()`
    publishes `ClockTickedEvent` - agents run synchronously - before
    `DayRolledOverEvent`, in the same call).
  - New `AccountRepository.adjustBalancesBatch` (one JDBC batch of
    `current_balance = current_balance + delta` updates - safe without
    pre-locking since each is an atomic per-row increment, correct regardless
    of a concurrent REST deposit/withdrawal racing the same row),
    `LedgerAccountService.findCustomerLiabilityAccountIdsByAccountIds`,
    `InterestRatePolicyRepository.findAllRates`,
    `InterestAccrualRepository.insertBatch`,
    `StatementRepository.insertBatch`/`findMostRecentClosingBalances` (bulk
    `DISTINCT ON` query) - all one-query-for-the-whole-chunk versions of the
    existing one-row lookups, mirroring the batch-insert pattern M6.6 already
    established for seeding.
  - `InterestAccrualService.accrueForAccount`/
    `StatementGenerationService.generateForAccount` (single-account) are kept
    alongside the new `accrueForChunk`/`generateForChunk` (bulk) - still
    directly unit-tested for the accrual/statement formulas in isolation from
    the bulk SQL mechanics, even though only the chunked path is used in
    production now.
  - `InterestAccrualScheduler`/`StatementGenerationScheduler` now partition
    `accountService.findAll()` into 200-account chunks (same private
    `partition` helper `DataSeeder` already used for its batch-insert
    chunking) and isolate each chunk's `@Transactional` call in its own
    try/catch, same spirit as the old per-account isolation but at chunk
    granularity.
  - Verify: `./gradlew test` (Testcontainers Postgres) - new
    `LedgerServiceTest`/`InterestAccrualServiceTest`/
    `StatementGenerationServiceTest` cases cover the bulk path directly (a
    chunk with a mix of accruing/non-accruing accounts shares one journal
    entry and keeps the ledger balanced; an all-zero chunk posts no journal
    entry but still records every accrual; a chunk mixing a brand-new account
    with one that already has statement history gets the right opening
    balance for each). Live: reset the dev DB (`docker compose down -v` +
    `up -d`), booted the app fresh (10,000 customers/20,000 accounts
    reseeded), and called `POST /api/clock/step-day` repeatedly - first call
    49s (cold JIT/connection-pool/buffer-cache), subsequent calls 5-15s, down
    from the previous ~180-240s/day; `GET /api/ledger/trial-balance` stayed
    balanced and `GET /api/treasury/ratios` recomputed correctly across every
    day, with exactly 20,000 accrual rows and 20,000 statement rows written
    per simulated day in Postgres.
- [ ] Extract `interest`/`statement` (or others) into real separate services
- [ ] Swap `DomainEventPublisher` for a Kafka/RabbitMQ/NATS producer
- [ ] Observability: Micrometer + Prometheus + Grafana
- [ ] Centralized logging: ELK or Loki
- [ ] Data lake / CDC: Debezium off Postgres into a data lake

## Gamification — CEO mode (later)

A game layer on top of Treasury (M6): you set policy periodically, agents react
autonomously, and regulatory ratios drive win/lose — not something to build until
Treasury/Loans exist to control. Separate milestone once M6 is done.

Split into sub-milestones the same way M6 was, each intended as its own
session: CEO.1 (below) → CEO.2 (`BankHealthService` + win condition) → CEO.3
(`EventInjector`) → CEO.4 (frontend CEO control panel + bank-health view).

### CEO.1 — Policy levers ✅ done
- [x] Policy levers (REST-exposed, like the clock controls): savings rate, loan
      spread, underwriting looseness (risk appetite), target capital buffer vs.
      how much to lend out, whether to tap the central bank borrowing facility
      when short — agents (deposit/loan agents) react to these rather than you
      touching individual transactions
  - New flat `policy` package (no schema — same precedent as `clock`/
    `centralbank`): `PolicyLevers` (`@Component`, `AtomicReference<PolicyLeversSnapshot>`,
    modeled directly on `clock.SimulationClock`) + `GET/POST /api/policy-levers`
    (`PolicyLeversController`, full-replace body via `PolicyLeversRequest`,
    mirroring `ClockController`'s `SpeedRequest` pattern). Pure in-memory
    state, no Liquibase changeset needed.
  - Design calls made via `AskUserQuestion`: (1) the savings-rate lever
    finally does the central-bank-rate rewiring M6.2 deliberately deferred —
    `interest.rate_policies`' SAVINGS rate is now `centralBankService
    .currentRates().policyRate().add(lever.savingsRateSpread())`
    (CHECKING/TERM_DEPOSIT stay on the flat seeded DB rate); the default
    spread (`-0.0150`) reproduces today's exact 1.50% day-1 rate since the
    epoch policy rate is 3.00%, but the lever gains real leverage as the
    policy rate drifts over simulated time; (2) mortgage/consumer loan
    spreads are two independent additive levers over
    `LoanService.MORTGAGE_RISK_SPREAD`/`CONSUMER_RISK_SPREAD`, not one shared
    delta; (3) `targetCapitalBuffer` and `underwritingLooseness` stay
    conceptually separate — the buffer is wired into
    `TreasuryService.isCapitalBreach` (shifts both the CAR-minimum and
    NSFR-minimum throttle thresholds up by the same amount; 0 reproduces
    today's unshifted 8%/100% minimums), while `underwritingLooseness` is a
    real field on `PolicyLeversSnapshot`/the REST contract that is
    **deliberately not consumed anywhere yet** — there's no credit-scoring
    system in this codebase for it to act on (see "Complex additions" —
    "Credit risk pricing" below); it's a placeholder for that future work.
  - `autoTapBorrowingFacility` (boolean, default `true` = today's behavior):
    `TreasuryService.applyFeedback()` skips the auto-borrow entirely when
    false — a liquidity breach (LCR/reserve-coverage) just persists
    untreated into tomorrow's snapshot instead of being auto-corrected,
    same "as-detected, corrected later" spirit the method already used for
    the no-breach/repay case.
  - `AgentContext`/agents needed **no changes** — every lever's effect is
    centralized in `LoanService`/`InterestAccrualService`/`TreasuryService`,
    so any loan/interest an agent triggers automatically picks up the
    current lever state through the services it already calls.
  - Verify: `./gradlew test` — new `PolicyLeversTest` (plain unit, no
    Spring/Postgres, modeled on `SimulationClockTest`) plus new lever-effect
    cases in `LoanServiceTest`/`TreasuryServiceTest`/
    `InterestAccrualServiceTest` (each restoring the default snapshot in a
    `finally` — `PolicyLevers` is a shared Spring singleton across the whole
    Testcontainers-backed suite, same hygiene `TreasuryServiceTest`'s
    throttle test already required). Live (fresh dev DB): `GET
    /api/policy-levers` returned the documented defaults; a SAVINGS
    account's day-1 accrual rate was unchanged at 1.50%, then jumped to the
    full 3.00% policy rate after raising `savingsRateSpread` to `0.00`
    (CHECKING stayed at 0%); a mortgage/consumer loan's priced rate shifted
    by exactly the lever adjustment (`4.50%→5.00%`, `12.00%→11.00%`); an
    8,000,000-principal loan left CAR healthy (9.22%) at `targetCapitalBuffer
    =0` but raising the buffer to `2%` (10% threshold) immediately throttled
    origination (`400`) with no new snapshot needed; a 150,000,000-principal
    loan forced a real LCR/reserve-coverage breach (`0.948083`/`0.562866`)
    that left `CENTRAL_BANK_RESERVES`/`CENTRAL_BANK_BORROWINGS` completely
    untouched with `autoTapBorrowingFacility=false`, then drew the facility
    normally (reserves `1,000,000.00→1,919,975.73`) once flipped back on;
    `GET /api/ledger/trial-balance` stayed balanced throughout.

### CEO.2 — BankHealthService + win/loss state machine ✅ done
- [x] `BankHealthService`: state machine watching `TreasuryRatiosUpdatedEvent` —
      capital ratio below the CRR minimum for N consecutive days → regulator
      warning; a second breach → forced resolution (game over); liquidity
      exhausted against withdrawal demand → "bank run" failure mode
  - Design calls made via `AskUserQuestion`, deliberately picking the
    simplest version of each mechanic that still teaches the underlying
    lesson over a more textbook-accurate one (explicit user steer — see
    `AGENTS.md`-style precedent of illustrative constants over full CRR
    breakdowns): (1) **breach state machine** is a single streak counter per
    ratio family, not separate "episodes" — 5 consecutive breach days →
    `WARNING`, the *same* uninterrupted streak reaching 10 days →
    `GAME_OVER`; a healthy day resets the streak to 0 (and the status back
    off `WARNING`) with no memory of a past warning; (2) **bank run trigger**
    reuses the existing `autoTapBorrowingFacility` lever (CEO.1) rather than
    introducing a new central-bank-borrowing-cap concept — `liquidityBreach`
    (LCR/reserve-coverage below 100%) held for 10 consecutive days →
    `BANK_RUN`; with the lever on, `TreasuryService.applyFeedback` always
    fully corrects the breach the same day, so a real streak only builds
    when the CEO has knowingly switched the safety net off; (3) **win
    condition** is survive `WIN_SURVIVAL_YEARS` (5) simulated years with
    both streaks at zero — no separate profit/capital-growth target layered
    on top, since staying within safe limits (not maximizing profit) is the
    lesson M6/CEO.1 already teaches
  - `TreasuryRatiosUpdatedEvent` gained a `liquidityBreach` field (true
    whenever HQLA/reserve headroom is negative, computed *before* the
    `autoTapBorrowingFacility` gate in `TreasuryService.applyFeedback`) -
    needed because the event's existing `amountBorrowed`/`amountRepaid`
    fields can't distinguish "no breach" from "breach detected but the lever
    left it uncorrected" (both read as zero)
  - New `bankhealth` package + schema (persisted, like `treasury`'s
    `ratio_snapshots` - game state should survive an app restart, unlike
    `PolicyLevers`/`SimulationClock`'s in-memory state): `health_snapshots`
    (one row per simulated day: `status`, `capital_breach_streak`,
    `liquidity_breach_streak`), `BankHealthService.computeAndPersist(event)`
    chained off `TreasuryRatiosUpdatedEvent` via a new `BankHealthScheduler`
    (same listener-chaining pattern `TreasuryRatioScheduler` already uses off
    `StatementGenerationBatchCompletedEvent`)
  - `BankHealthStatus.isTerminal()` (`GAME_OVER`/`BANK_RUN`/`WON`) makes the
    state machine sticky: once a terminal status is reached,
    `computeAndPersist` is a no-op forever after (no new row, no published
    `BankHealthUpdatedEvent`) - the game has ended
  - REST: `GET /api/bank-health/status` — 404 until at least one simulated
    day has passed, otherwise the latest snapshot (mirrors
    `GET /api/treasury/ratios`)
  - Pure threshold/precedence logic pulled into a package-visible
    `BankHealthService.deriveStatus` and covered by a plain unit test
    (`BankHealthStatusTest`, no Spring/Postgres — same precedent as
    `PolicyLeversTest`); `BankHealthServiceTest` (Testcontainers Postgres)
    covers the stateful parts that actually need persistence: streak
    accumulation/reset across real rows, and the terminal-freeze behavior.
  - Verify: `./gradlew test` — 76 tests pass. Live (fresh dev DB, reset via
    `docker compose down -v` + `up -d` since the previous session's dev DB
    already had a multi-day-advanced treasury history that collided with a
    freshly-booted clock's date range): `GET /api/bank-health/status` 404
    before any day had run; after `step-day` showed `PLAYING`/`0`/`0`
    chained correctly off the real `TreasuryRatiosUpdatedEvent`; stepping 4
    more healthy days kept `PLAYING` with both streaks at 0. Originated an
    80,000,000 mortgage to force a real CAR breach, then stepped 10 more
    days one at a time: `capitalBreachStreak` climbed 1→9 with `status`
    flipping to `WARNING` exactly at day 5, then `GAME_OVER` exactly at day
    10; an 11th breached day left the snapshot completely unchanged (same
    `id`, same date) confirming the freeze, with
    `GET /api/ledger/trial-balance` staying balanced throughout.

### CEO.3 — EventInjector + business loan provisioning ✅ done
- [x] `EventInjector`: occasional macro shocks to react to rather than steady-state
      optimization — central-bank rate hike/cut and a recession shock that drives
      loan-loss provisioning. Both fire fully autonomously (random daily rolls off
      `DayRolledOverEvent`) — no manual-trigger endpoint, by explicit design. The
      deposit-run shock originally sketched here was dropped (explicit user call:
      real-world deposit fluctuations are much smaller than a classic bank-run
      panic, so it wasn't worth building for this milestone).
  - Working through the shock types surfaced that this section's original wording
    ("a recession event that spikes loan defaults") didn't fit the codebase — no
    default/delinquency/NPL mechanic exists (still deferred, see "Complex
    additions" below). The user's own framing pointed at real IFRS 9-style
    loan-loss provisioning instead: a loan moves through risk phases, the bank
    sets aside a provision, and a recovered loan releases it back as profit —
    that reshaped this milestone into two coupled pieces (EventInjector itself,
    and a new `BUSINESS` loan type with a phase/provisioning mechanic for the
    recession shock to act on).
  - New flat `eventinjector` package + schema: `RateShockService` (persisted
    append-only `rate_shocks` log — a signed delta per shock date; the effective
    offset as of a date is the sum of every delta on/before it, keeping
    `CentralBankRateSchedule`'s own deterministic-by-date base rate untouched —
    the shock layers on top in `CentralBankService.currentRates()`, not inside
    the schedule) and `RecessionShockService` (persisted `recession_events`
    START/END log rather than a boolean flag, so "active as of a date" stays a
    pure derived read; a recession is a temporary elevated-risk window lasting a
    randomized 6-18 simulated months). `EventInjectorScheduler` is the single
    `@EventListener` on `DayRolledOverEvent`, calling rate shock roll → recession
    tick → the loan phase-transition roll in order (each independently
    try/caught, same isolation style as `TreasuryRatioScheduler`).
  - New `LoanType.BUSINESS` (500bps spread — between MORTGAGE's 150bps and
    CONSUMER's 900bps — 2-7 year terms), repeatable like CONSUMER (no
    one-per-account restriction). `BorrowerAgent` got a third independent loan
    slot for it, mirroring the CONSUMER slot exactly, so the recession shock has
    visible effects in an ordinary autonomous playthrough. Completed CEO.1's
    per-type policy-lever pattern with a `businessSpreadAdjustment` lever.
  - New `LoanPhase` (PERFORMING/UNDERPERFORMING/NON_PERFORMING), meaningful only
    for BUSINESS loans: a new `loan.LoanPhaseTransitionService` rolls daily
    downgrade/recovery probability per active BUSINESS loan (baseline downgrade
    risk even outside a recession — real business loans always carry some risk;
    8x that rate during an active recession; recovery independent of recession
    state) and posts the provisioning delta (10% of outstanding principal at
    UNDERPERFORMING, 50% at NON_PERFORMING) to two new singleton ledger accounts,
    `LOAN_LOSS_PROVISION`/`PROVISION_EXPENSE` — singleton rather than per-loan,
    since `ledger_accounts`' unique `loan_id` index already allows only one
    loan-tagged row per loan (claimed by `LOAN_RECEIVABLE`); per-loan provision
    amount instead lives as a new `loans.provision_amount` column. A recovery
    reverses the entry (released back as profit). New `loan_phase_history` table
    (mirrors `loan_payments`' shape) gives a per-loan audit trail. Deliberately
    accounting-only — a NON_PERFORMING loan still amortizes/repays normally
    through `BorrowerAgent`/`LoanService.repay`, unaffected by its phase; the
    full delinquency/NPL simulation stays deferred (see "Complex additions").
  - Verify: `./gradlew test` — 98 tests pass, including new
    `RateShockServiceTest`/`RecessionShockServiceTest` (Testcontainers Postgres,
    forced via a test-seam `Random`), `LoanPhaseTransitionLogicTest` (plain unit
    test for the pure phase state machine/provisioning formula, same precedent
    as `BankHealthStatusTest`), `LoanPhaseTransitionServiceTest` (a full
    downgrade→downgrade→recover→recover cycle on a real BUSINESS loan, checked
    against `LedgerReconciliationService.trialBalance()` at every step), and new
    cases in `LoanServiceTest`/`BorrowerAgentTest`/`CentralBankServiceTest` (the
    last converted from a plain unit test to Postgres-integration, since
    `CentralBankService` now has a DB-backed collaborator). Live (fresh dev DB):
    originated MORTGAGE/BUSINESS/CONSUMER loans at the same principal/term and
    confirmed BUSINESS priced strictly between the other two (4.50%/8.00%/12.00%
    at the epoch policy rate); raising `businessSpreadAdjustment` by 1% shifted
    the BUSINESS quote by exactly that. Temporarily raised the shock/downgrade
    constants to near-certain to exercise the real random code paths end-to-end
    (no manual-trigger endpoint exists by design): `POST /api/clock/step-day`
    moved `GET /api/central-bank/rates` by exactly the shock magnitude (0.0300 →
    0.0375 → back to 0.0300 on an offsetting shock) and started a recession
    (`GET /api/event-injector/status` → `recessionActive: true`, a 16-month
    window); active BUSINESS loans moved PERFORMING → UNDERPERFORMING →
    NON_PERFORMING with `provisionAmount` at exactly 10%/50% of principal each
    step, matched by `LOAN_LOSS_PROVISION`/`PROVISION_EXPENSE` moving by the same
    deltas on `GET /api/ledger/accounts`; a later recovery roll reversed a loan
    from NON_PERFORMING back to UNDERPERFORMING with provision released back
    down; a MORTGAGE and a CONSUMER loan in the same run never left
    `phase: PERFORMING`/`provisionAmount: 0`. `GET /api/ledger/trial-balance`
    stayed balanced throughout. Reverted every temporarily-raised constant
    afterward and re-ran the full suite to confirm it's still green at the real
    values.

### CEO.4 — not started
- [ ] Frontend: CEO control panel (levers) + bank-health/score view, separate from
      the operational dashboard from M5

## Complex additions (deferred domain depth)

Deliberately simplified in M6 above — revisit once the simple version works
end-to-end.

- [ ] Loan default/delinquency simulation: `BorrowerAgent` that can miss
      payments (income shock, randomized), days-past-due tracking, non-performing
      loan (NPL) classification and NPL ratio
- [ ] Credit risk pricing: risk-based spread per borrower (simple credit score
      or income/debt ratio at origination) instead of a flat spread
- [ ] Loan-loss provisioning: provision expense posted against expected/actual
      defaults (IFRS 9-style expected credit loss, simplified)
- [ ] Full EU LCR breakdown: HQLA tiering (Level 1 / 2A / 2B, with haircuts) and
      CRR outflow/inflow categories instead of one aggregate liquidity number
- [ ] Full NSFR breakdown: ASF (available stable funding) and RSF (required
      stable funding) factor tables by asset/liability category
- [ ] Deposit Guarantee Scheme modeling: €100,000 covered-per-depositor cap
      (Directive 2014/49/EU) as a real constraint, e.g. for a future
      "bank run" / insolvency stress-test scenario
- [ ] Interest rate risk / ALM: duration gap analysis between the loan book and
      deposit book as the central bank policy rate moves over simulated time

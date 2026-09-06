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

### M6.6 — BorrowerAgent + end-to-end verify
- [ ] `BorrowerAgent`: applies for a loan occasionally, then pays on schedule
      via `AgentScheduler` (like `BillPayAgent`, but the debt amortizes)
- [ ] Verify: full M6 checklist end-to-end — loan lifecycle, ratios, feedback
      loop all observable together over a multi-day simulated run

---

## Later phases (not started yet)
- [ ] Extract `interest`/`statement` (or others) into real separate services
- [ ] Swap `DomainEventPublisher` for a Kafka/RabbitMQ/NATS producer
- [ ] Observability: Micrometer + Prometheus + Grafana
- [ ] Centralized logging: ELK or Loki
- [ ] Data lake / CDC: Debezium off Postgres into a data lake

## Gamification — CEO mode (later)

A game layer on top of Treasury (M6): you set policy periodically, agents react
autonomously, and regulatory ratios drive win/lose — not something to build until
Treasury/Loans exist to control. Separate milestone once M6 is done.

- [ ] Policy levers (REST-exposed, like the clock controls): savings rate, loan
      spread, underwriting looseness (risk appetite), target capital buffer vs.
      how much to lend out, whether to tap the central bank borrowing facility
      when short — agents (deposit/loan agents) react to these rather than you
      touching individual transactions
- [ ] `BankHealthService`: state machine watching `TreasuryRatiosUpdatedEvent` —
      capital ratio below the CRR minimum for N consecutive days → regulator
      warning; a second breach → forced resolution (game over); liquidity
      exhausted against withdrawal demand → "bank run" failure mode
- [ ] Win condition: survive a target number of simulated years, or hit a
      profit/capital-growth target
- [ ] `EventInjector`: occasional macro shocks to react to rather than steady-state
      optimization — central-bank rate hike/cut, a recession event that spikes
      loan defaults, a deposit-run event (bad press / a competitor rate war)
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

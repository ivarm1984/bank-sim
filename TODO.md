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

- [ ] `treasury` package: new ledger accounts — `CENTRAL_BANK_RESERVES` (asset),
      `LOAN_RECEIVABLE` per loan (asset), `BANK_CAPITAL` (equity); seed bank
      capital at startup
- [ ] Simulated **central bank counterparty**: policy rate (ECB main
      refinancing rate analog), deposit facility rate (paid on excess reserves),
      marginal lending rate (charged when the bank borrows to cover a shortfall)
      — this is the base rate loan/deposit pricing builds on
- [ ] `loan` package: `LoanAccount` (principal, term, rate = base + risk spread,
      amortization schedule)
- [ ] `LoanService.originate/disburse/repay` — disbursement: Debit
      `LOAN_RECEIVABLE`, Credit customer's checking account; each repayment
      splits principal vs. interest income, one `@Transactional` posting per
      payment
- [ ] `TreasuryService` — recomputes simplified ratios from aggregate ledger
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
- [ ] Feedback loop: if LCR/NSFR/capital ratio breaches its threshold, treasury
      either throttles new loan origination or auto-borrows reserves from the
      central bank facility; publishes `TreasuryRatiosUpdatedEvent`
- [ ] REST: `GET /api/treasury/ratios`, `POST /api/loans`,
      `GET /api/accounts/{id}/loans`, `GET /api/loans/{id}`
- [ ] `BorrowerAgent`: applies for a loan occasionally, then pays on schedule
      via `AgentScheduler` (like `BillPayAgent`, but the debt amortizes)
- [ ] Verify: loan disbursement/repayment keeps trial balance at zero; ratios
      update after a loan is originated (LDR moves, LCR/NSFR react); forcing a
      large loan pushes LCR below 100% and triggers the throttle/borrow path

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

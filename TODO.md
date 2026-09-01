# Bank Simulation — TODO

Learning project: simulate a bank end-to-end to learn banking-domain concepts
(double-entry ledgers, deposits/withdrawals/transfers, interest, statements) and
system-design/tooling concepts (sync vs. async service interaction, event-driven
batch processing, live UIs — and later, real message brokers, observability/logging,
data-lake/CDC). "Customers" are plain programmed agents (no AI) acting on a
controllable simulated clock (step one day, or run continuously at a chosen speed).
A Vue dashboard shows balances and a live event feed and controls the simulation.

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
- [ ] `backend/`: Maven Spring Boot skeleton (spring-web, spring-data-jpa,
      postgresql, flyway, spring-websocket, validation)
- [ ] `frontend/`: Vite + Vue 3 + TypeScript skeleton
- [ ] Root `docker-compose.yml`: Postgres 16, named volume, `POSTGRES_DB=banksim`
- [ ] `application.yml`: datasource, Flyway locations, actuator health enabled
- [ ] Verify: `curl localhost:8080/actuator/health` → UP; `docker compose ps`
      healthy; `npm run dev` loads with no console errors

## M1 — Ledger core
- [ ] `customer`, `account`, `ledger` packages
- [ ] Flyway `V1__init_schema.sql`: customers, accounts, ledger_accounts,
      journal_entries, ledger_lines
- [ ] Seed chart of accounts: `BANK_CASH`, `INTEREST_EXPENSE`, `FEE_INCOME`
      (singletons) + one `CUSTOMER_LIABILITY` ledger account per `Account`
- [ ] `LedgerService.post(JournalEntryRequest)` — enforce
      `SUM(debits) == SUM(credits)`, throw `UnbalancedJournalEntryException`
      otherwise. **Write this test first.**
- [ ] `Account.currentBalance` cached, updated in the same transaction as the
      ledger post; pessimistic locking on `Account` rows during posting
- [ ] `LedgerReconciliationService` — recompute balance from ledger lines, compare
      to cached balance
- [ ] Minimal REST: customers, accounts, `GET /api/accounts/{id}/balance`
- [ ] Verify: `mvn test` passes ledger invariant tests; after posting entries,
      trial-balance query (`SUM` debits − credits per ledger account) nets to zero

## M2 — Transactions via REST
- [ ] `transaction` package: `Transaction` entity,
      `TransactionService.deposit/withdraw/transfer` (calls `LedgerService`
      synchronously, in one transaction)
  - Deposit → Debit `BANK_CASH`, Credit customer liability
  - Withdrawal → Debit customer liability, Credit `BANK_CASH`
  - Transfer A→B → Debit `CustomerLiability(A)`, Credit `CustomerLiability(B)`
- [ ] Insufficient-funds handling (400 response)
- [ ] REST: `POST /api/transactions/{deposit,withdrawal,transfer}`,
      `GET /api/transactions`
- [ ] Integration test against real Postgres (not just H2)
- [ ] Verify: deposit/withdraw/transfer via curl; over-withdraw → 400; transfer
      conserves total system balance; trial balance still zero

## M3 — Clock + agents (headless)
- [ ] `clock` package: `SimulationClock` (`AtomicReference<ClockSnapshot>`),
      `ClockTickScheduler` (`@Scheduled(fixedRate=1000)`), publishes
      `ClockTickedEvent` and `DayRolledOverEvent` (one per day crossed)
- [ ] `stepOneDay()` — same advance logic as continuous run, no sleep, loops until
      one day boundary crossed
- [ ] All domain timestamps from injected simulated clock, never wall-clock `now()`
- [ ] REST: `GET /api/clock/state`, `POST /api/clock/{play,pause,step-day,reset}`,
      `POST /api/clock/speed`
- [ ] `agents` package: `Agent` interface (`onTick(simulatedNow, context)`),
      `AgentScheduler` (listens `ClockTickedEvent`, per-agent exception isolation)
- [ ] `SalaryAgent`, `BillPayAgent` (publishes `BillPaymentFailedEvent` on
      insufficient funds), `RandomSpenderAgent` (seeded `Random` for
      reproducibility)
- [ ] `DataSeeder` (`ApplicationRunner`): 3–5 demo customers with
      checking+savings accounts, one of each agent type
- [ ] Verify: `speed 50` + `play`, wait ~30s, sim date advances and agent
      transactions appear in `GET /api/transactions`; `pause` stops advancement;
      `step-day` from paused advances exactly one day

## M4 — End-of-day batch
- [ ] `interest` package: `InterestAccrualService`/`Listener` on
      `DayRolledOverEvent`, per-account `@Transactional` accrual (not one giant
      transaction), posts ledger entry (Debit `INTEREST_EXPENSE`, Credit customer
      liability), writes `InterestAccrual` (unique per account+date), publishes
      `InterestAccrualBatchCompletedEvent`
- [ ] `statement` package: `StatementGenerationService`/`Listener` on
      `InterestAccrualBatchCompletedEvent` (chained, not parallel listener),
      generates one `Statement` per account per day (unique per account+date)
- [ ] REST: `GET /api/accounts/{id}/interest-accruals`,
      `GET /api/accounts/{id}/statements`
- [ ] Verify: computed interest matches a known balance/rate by hand; statement's
      opening balance on day N+1 equals closing balance on day N; trial balance
      still zero

## M5 — WebSocket + Vue dashboard
- [ ] `web` package: `WebSocketConfig` (STOMP over SockJS, `/ws`,
      `enableSimpleBroker("/topic")`)
- [ ] `EventFeedPublisher`: `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`
      bridge from every domain event → `/topic/events`; clock state → `/topic/clock`
- [ ] Frontend: `api/` (REST clients), `ws/stompClient.ts`, Pinia `stores/`
      (accounts, customers, clock, event feed), `views/DashboardView.vue`,
      `components/` (SimulationControls, AccountsList, EventFeed, TransactionLog,
      StatementViewer, LedgerInspector)
- [ ] Wire: REST populates initial store state on mount; WS events patch stores
      live; periodic REST re-sync to correct drift
- [ ] Vite dev server proxies `/api` and `/ws` to `localhost:8080`
- [ ] Verify: Play in browser, EventFeed populates live (check STOMP frames in
      devtools), balances update live, speed/Pause/Step Day work, WS-driven UI
      state matches `GET /api/accounts` REST truth

---

## Later phases (not started yet)
- [ ] Extract `interest`/`statement` (or others) into real separate services
- [ ] Swap `DomainEventPublisher` for a Kafka/RabbitMQ/NATS producer
- [ ] Observability: Micrometer + Prometheus + Grafana
- [ ] Centralized logging: ELK or Loki
- [ ] Data lake / CDC: Debezium off Postgres into a data lake

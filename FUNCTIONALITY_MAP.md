# household-spending-tracker: functionality map

A high-level map of what `../household-spending-tracker` does, from its code, `CLAUDE.md`, `README.md` and `mcp-server/README.md`. Written as a reference for the new spending tracker.

## 1. What it is

A personal-finance dashboard for a two-person household (plus a "Joint" shared account), with all amounts in CZK. It covers the household's whole money picture:

- what came in (income)
- what went out (spending, split into consumption and investing)
- what they own (net-worth snapshots)
- where they're heading (forecasts, goals, budgets, wishlist)

The original data source was a Google Sheet. It was frozen on 2026-09-25, and Cloudflare D1 has been the system of record since then.

## 2. Architecture

```
Bank (GoCardless PSD2) ──cron──┐
CSV statements → Python parsers┤
                               ▼
 React SPA  ◄──/api──►  Cloudflare Worker  ◄──►  D1 (SQLite)
 (Vite, React 19)       (auth, validation,        money in halíře,
                         versioning, audit)        audit_log triggers
                               ▲
 MCP server (Node) ──service token──┘   (bundles the app's src/utils)
```

| Part | Location | Role |
|---|---|---|
| Web app | `src/` | React 19 + TS + Vite + React Router. A single `Dashboard` loads `SourceRecords` and derives everything client-side through `buildDashboardData`. |
| Backend | `worker/` | Cloudflare Worker + D1. Serves the API and the static build at `old.example.com`. 19 migrations. |
| Auth | `worker/src/auth.ts`, `accessSync.ts` | Cloudflare Access in front. The Worker verifies the JWT and checks its own allowlist (people's emails + a fallback list + service tokens). Writes are people-only and same-origin (CSRF guard). |
| MCP server | `mcp-server/` | Gives Claude and other MCP clients read access to the data. Its only write is `add_to_wishlist`. |
| Import scripts | `scripts/import/*.py` | Per-bank CSV parsers (Revolut, KB, ČSOB, RB current account, RB credit card) used for backfill. |
| Design system | `design-system/` | "Pejsci Finance" tokens and components, ported into `src/components/ui`. |
| CI/CD | `.github/workflows/deploy.yml` | Lint and typecheck on every push. On `main`: apply D1 migrations, then deploy the Worker. |

### Cross-cutting principles

- **One definition per number.** Every shared figure is computed once in `src/utils`. The views, the LLM export and the MCP server all import it, so they always agree.
- **Derivation happens client-side.** The API returns raw records, and the browser computes the rest. One reason: "is this month complete?" depends on Prague time, and Workers run in UTC.
- **Optimistic concurrency.** Every row has a `version`. A stale PUT or DELETE gets a 409 and is never silently merged.
- **Full audit log.** Every create, update and delete is stored with before/after JSON and the actor.
- **Undo instead of confirm dialogs.** After a save or delete, a toast offers Undo.
- **Capital transfers are not consumption.** Rows in the `Investments` category count as invested, not spent.
- **Money is stored as integer halíře.** Foreign-currency amounts are converted at the ČNB fixing for the transaction's date.

## 3. Data model (D1 tables)

| Table | Purpose |
|---|---|
| `spending` | Individual expenses: date, amount, merchant, category/subcategory, who, account, trip, trip_lump, notes, external_ref |
| `income` | Monthly income lines: month, recipient, source, notes, amount |
| `expected_income` | Planned income for future months |
| `asset_snapshots` | Net-worth snapshots: date, owner, asset, category, invested/value, currency, native amounts |
| `fx_rates` | ČNB fixings used for conversions |
| `people` | Household members and shared accounts: name, initial, colour tone, kind, email, active |
| `categories` | Managed two-level category tree |
| `accounts` | Ledger accounts: status (active/closed/not-account), owner, number, IBAN |
| `trips` | Trip name and dates |
| `goals` | Savings goals linked to net-worth assets |
| `budgets` / `budget_months` | Every-month and per-month category budgets |
| `wishlist` | Things to buy: link, image, price, shop, category, who, status |
| `bank_links` / `bank_accounts` / bank transactions / `merchant_aliases` | GoCardless connections, imported accounts and the inbox |
| `audit_log` | Every change, with before/after and the actor |

## 4. Features by view

The app has 11 routes. Wide screens show a sidebar; phones get a scrolling tab strip.

### One pager (`/onepager`, default)
This is the executive summary.

- **Import freshness banner**: warns about accounts whose transactions have stopped arriving (more than 30 days, judged against each account's own usual rate).
- **Monthly cash flow**: averages over the last 6 complete months, with outlier months trimmed (1.5×IQR, high side only).
- **Capital allocation**: liquid vs. illiquid.
- **Decision intelligence**: liquid runway, portfolio return (market assets only), and how much can be invested above a 6-month buffer.
- **Spending anomalies**: each category vs. its average month over the prior 12, with a month picker and pace scaling for the month in progress.
- **Subscription radar**: detects recurring charges.
- **Net-worth milestones**: projected dates for round-number targets, using the compounding forecast.
- **Top income, spending and merchants.**
- **LLM export**: text or JSON covering trends, behaviour patterns, risks, opportunities and projections.

### Net worth (`/networth`)
- Asset list from the latest snapshot, with the liquid/illiquid split.
- **Blended gain vs. market-only portfolio return**, kept separate so the car and loans don't distort the return.
- **History chart**: the x axis is real elapsed time, points are labelled by pay period (payday is the 15th), and with "All" selected it stacks one band per owner.
- **Owner filter**: All / person / Joint.
- **Snapshot editor**:
  - Edit a single row, or create a new snapshot by copying the previous one.
  - Amounts are entered in each row's own currency and converted automatically.

### Accounts (`/accounts`)
- Every ledger account, with its linked bank accounts' balances, sync state, this month's outflow and how many items are waiting in the Inbox.
- Drill into an account to see its bank transactions next to its ledger rows. There's a link to Expenses filtered to that account.

### Inbox (`/inbox`): bank import
- GoCardless Bank Account Data syncs on a daily cron (and "Sync now"). Bank consent lasts up to 180 days, with a warning before it expires.
- **Automatic classification** marks rows as internal transfer, income, spend or flagged. It uses household IBANs and names, Revolut top-ups, card repayments and similar patterns.
- **Duplicate detection** against hand-typed rows: same account, amount within 1 Kč (3% for foreign currency), date within ±3 days.
- **Suggestions that learn from history**:
  - The merchant name comes from past decisions or aliases.
  - The category is the merchant's most common one, shown with percentages.
  - "Who" is chosen per account.
- **Actions**: accept (optionally merging several transactions into one expense), skip or restore, and undo.

### Expenses (`/expenses`)
- Full transaction table with a filter bar: period, months, person, category, merchant, account.
- Breakdowns by category (with subcategory split), merchant, person and month.
- Rolling stats: median, stability score, month-over-month trend.
- A per-row "exclude from stats" toggle.
- **Add or edit expense** in a side-sheet editor:
  - Fields are pre-filled from the merchant's last expense.
  - Near-miss spellings trigger a warning.
  - A trip can be tagged, with a lump/everyday flag.

### Income (`/income`)
- Income table, per-source breakdown, filters and trend analysis.
- **Import typical month**: a checklist of recurring income lines (present in at least 2 of the last 3 months), pre-filled and batch-saved.
- **Expected income plan**: per month or over a range. The chart shows recorded vs. planned vs. typical income for up to 12 months ahead, and the plan feeds into forecasts.

### Budget (`/budget`)
- An every-month budget per top-level category, plus per-month overrides. A budget of 0 Kč means nothing may be spent.
- **Tracking within a month**: pace (ahead-of-pace warning), left to spend per day, and a projected month-end figure. Future months can be planned ahead.
- **"Income and the budget"**: income − budgets − unbudgeted usual spending − investing = what's left to save.
- History table: the last 6 months, each measured against its own budget.

### Forecast (`/forecast`)
- **Assumptions**: return rate (default 7%), savings pace, horizon.
- **Projection**: net-worth curve, milestones, FIRE target. Only market assets compound.
- **Purchase planner**: rates each purchase comfortable, tight or stretch, depending on whether the 6-month buffer survives. Items are queued in order.
- **Scenarios**: compare changes one lever at a time.

### Goals (`/goals`)
- Each goal has a target amount, an optional target month and a status (active/done/dropped).
- Progress = value of the linked net-worth assets + money saved outside tracked assets.
- Shows the required amount per month vs. the actual pace and an ETA, rated on-track or behind.
- A plan view compares what all goals need with typical monthly savings.

### Wishlist (`/wishlist`)
- A grid of items. **Pasting a link reads the product page**: name, image, price and shop, from JSON-LD and Open Graph tags. Foreign prices are converted to CZK, and a category is suggested.
- Each item gets an affordability rating against the emergency buffer.
- **"Bought it"** marks the item bought and opens a pre-filled Add expense form.

### Trips (`/trips`)
- One trip at a time: total, per-day cost (using the real trip length), everyday spending vs. lump costs, spending before/after the trip, a flat 2-way split, and who paid.
- Review panel to tag untagged spending that falls inside the trip's dates.

### Settings (`/settings/*`)
- **People**:
  - Name, initial, colour tone, kind (person or shared).
  - Email, which also grants sign-in and syncs the Cloudflare Access policy.
  - Renaming a person cascades to every row that names them.
  - A person who is still referenced can be retired but not deleted.
- **Categories**:
  - Two-level tree.
  - Renaming a category cascades; a category still in use can only be retired.
  - **Sort into subcategories**: a bulk tool that recategorises using each merchant's history.
- **Accounts & banks**:
  - Ledger account status, owner and number/IBAN, with checksum validation.
  - Bank connections (connect, renew, choose to import or ignore each account).

## 5. API surface (Worker)

- `GET /api/records`: every table as `SourceRecords`, without emails or account numbers.
- `GET /api/people` and `GET /api/accounts`: sensitive details, people only.
- `GET /api/banking`: bank state, people only.
- CRUD + batch endpoints for spending, income, assets, people, categories, accounts, goals and wishlist, all version-guarded.
- Specialised endpoints:
  - `POST /api/spending/recategorize`
  - `POST /api/spending/trip`
  - `PUT /api/budgets`
  - `POST /api/expected-income/replace`
  - `POST /api/banking/accept`, `revert`, `decide`
  - `GET /api/banking/callback`

## 6. MCP server tools

`get_monthly_summary`, `get_cash_flow_stats`, `list_transactions`, `get_category_breakdown`, `get_net_worth`, `get_net_worth_history`, `get_spending_anomalies`, `get_subscriptions`, `get_import_status`, `get_expected_income`, `list_trips`, `get_trip`, `list_goals`, `list_wishlist`, `add_to_wishlist` (the only write), `refresh_data`.

## 7. Supporting pieces

- **Python bank parsers** (`scripts/import/`): exclude internal transfers using `household.json` (matching on the full `number/bankcode`), flag anything ambiguous, and deduplicate against existing spending.
- **Data migration**: `import:sheets` and `verify:import` rebuild or verify a local D1 against the frozen Google Sheet.
- **Tests**: Vitest unit tests across `src/utils` and `src/services` (budgets, forecast, goals, insights, bank import, trips, etc.).

## 8. Known gaps and planned work

- There's no edit screen for trips yet; new trips are inserted with SQL.
- CSV upload into the Inbox is planned, for older history and for accounts that aren't bank-linked.
- The bank source should stay replaceable, because GoCardless has closed new sign-ups.
- Bank balances aren't used by net-worth snapshots yet.

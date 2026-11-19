# 1. Old history and balances

## Problem

The old app never tracked splits or debts: its expenses only say whose they are (`who`: a person or Joint) and which account paid. Once imported, `/balances` works out debts from every expense with an account, so the imported history produces a balance between members that the old app never showed and nobody agreed to. Joint expenses paid from one person's accounts and one person's expenses paid from the other's account both appear in the old data (query `data/legacy/d1.sqlite` for `spending` joined to `accounts.owner`, and `people.kind`, to see how many and how much). With no split rules the shares are equal, which may not be how the couple actually split things.

So "importing the splits" isn't possible: there are none to import. What's needed is a clean starting point for balances.

## Ask first

Show the user what the imported history currently adds up to (per pair, the debt total and the number of expenses behind it, from a `--copy` database), then confirm which of these they want:

1. **Balances start on a date (recommended).** A household setting `debts_from` (date, nullable). Expenses and settlements before it don't count towards balances; everything stays visible elsewhere. Works for re-imports and needs no made-up records.
2. **An opening settlement.** One settlement on the switch date that cancels the computed balance. No code, but it's a fake record that's wrong as soon as an old expense is edited.
3. **Keep the debts.** If the old history is meant to count, the work is only checking split rules for the past (a household default from the first old date) and nothing else.

The rest of this plan assumes option 1.

## Build (option 1)

- **Migration V30**: `households.debts_from date NULL`. Households already have an audit trigger; check it records the change.
- **API**: expose `debtsFrom` on the household (`shared/.../Households.kt`) and accept it on the household update (owners only). Validate it's a real date; null clears it.
- **Balances** (`server/.../debt/DebtRoutes.kt`, `getBalances`): leave out expenses dated before `debtsFrom` and settlements before it. Expense `debts` on each expense (`ExpenseRoutes.kt`, which uses `SplitBook.debtsFor`) should be empty for an expense before the date too, so the expense form doesn't claim it makes someone owe. Put the check in one place (e.g. `loadSplitBook` carries the date, or a helper) rather than in each caller.
- **Import**: `scripts/legacy-import.mjs` can't set a household field, so either the import file gains an optional `debtsFrom` (`HouseholdImport` in `shared/.../Import.kt`, applied only when the household has none) or the user sets it by hand after importing. Prefer the import field set to the day after the newest old expense, and the dry-run preview shows it.
- **Web**: the Balances page shows "Balances count from <date>" with a way for an owner to change it (an inline date field or in the household settings, whichever matches existing patterns in `households/HouseholdPages.tsx`). Entries before the date aren't listed.

## Tests

- `DebtTest`: expense and settlement before, on and after `debtsFrom`; null keeps today's behaviour; an expense's `debts` is empty before the date.
- `ImportTest`: `debtsFrom` set by import, not overwritten when already set, reported by dry run.
- Web: the Balances page shows the date and hides earlier entries (`pnpm --dir web test`).

## Done when

The user's imported household shows a zero (or agreed) balance at the switch date, new expenses after it build debts as before, `./gradlew build` and the web tests pass, and the roadmap's "the old app's import carrying splits" item is replaced by what was built.

# 6. Liability entries from bank transfers

## Problem

Liabilities (`loan`, `card_debt`, `payable`, V27) record borrowing (negative contributions) and principal repayments (positive). A repayment usually shows up as a bank row: money out of a current account to the lender or the card. Today it has to be entered twice: filed in the Inbox (as a transfer or an expense) and added as a contribution on the Assets page. The roadmap says "creating a liability contribution from a bank transfer is not yet wired up".

## Build

- **Link** (migration **V32**): `asset_contributions.transfer_id uuid NULL`, composite FK to `transfers (household_id, id) ON DELETE CASCADE`, unique where not null (one contribution per transfer). Contributions already have an audit trigger; check.
- **When a transfer touches a liability's account** (`assets.account_id`, V28: one asset per account), creating the transfer also creates the contribution on that liability: money into the liability's account is a repayment (positive), money out of it is borrowing (negative), dated and sized as the transfer, `accountId` the other side. Do it in the same transaction, in one place used by every way of creating a transfer (`createTransfer` in `TransferRoutes.kt`, the auto-filing in `inbox/AutoFiling.kt`, and plan 4's `POST /transfers` if it has landed).
  - Deleting the transfer (also by putting its rows back, `/bank-rows/undo`) deletes the contribution through the cascade. Editing a hand-entered transfer's amount or date updates it.
  - A liability with no account: transfers need two accounts, so there are two ways. (a) The user adds an account (kind `other`) for each loan and links the liability to it; then filing a row "against another account" in the Inbox picks it and the rule above applies. (b) A contribution links straight to a bank row (`asset_contributions.bank_row_id` in the same migration, plus a bank-row decision "Repayment of <liability>"). Prefer (a): it needs no new kind of bank-row decision. Ask the user if they'd rather not keep an account per loan.
- **Interest**: a loan payment is often principal plus interest. Keep the transfer at the full amount and let the user split off the interest as an expense? Ask the user how their loan payments look before building anything for it; leave it out if unsure.
- **Card debt**: paying off a credit card is already a transfer to the card account. If the card account is linked to a `card_debt` liability, this plan makes every card repayment a contribution, which is right (it reduces what's owed). Check a bank-fed card debt still reads its value from the bank and the contributions only add Repaid/Borrowed.
- **Web**: the Inbox and the transfer form show "Repays <liability>" when the other side is a liability's account; the liability's history on the Assets page names the transfer and links back; the Assets entry form marks contributions made from transfers as read-only (edit the transfer instead).

## Tests

`AssetTest` / `InboxTest`: transfer into a liability's account makes a repayment, out of it makes borrowing; undoing the rows removes it; auto-filed transfer too; a liability valued from the bank keeps its value; editing a contribution made from a transfer is refused.

## Done when

Repaying a loan or card from the Inbox shows up on the liability without entering it twice, `./gradlew build` and web tests pass, it's checked in the preview, and the roadmap line under Liabilities is updated.

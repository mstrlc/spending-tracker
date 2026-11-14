# UI pass 1 (2026-10-31)

Working notes for the first design pass over `web/`, done with the `ui-taste` skill.
Captures in `before/`, `pass1-after/` and `pass2/` are local evidence, not product assets.

## Limits

- No image-generation tool and no Uizze reference connection in this session, so there is no mockup
  target and no external screen research. The direction below comes from the brief, the existing UI
  and common conventions of personal-finance and ledger apps. No 1:1 claim is made.

## Deficits observed in the baseline (`before/`)

1. **Competing emphasis.** The eight-field add form fills the top of the expenses page, pushing the
   month's spending below the fold. The month total, the most important number, is a small
   right-aligned line.
2. **Row noise.** Every row repeats `Edit` and `Delete` links, ISO dates, a `CZK` prefix and a
   "Paid by" value that is the same for a one-person household.
3. **Flat hierarchy and weak chrome.** All text is about the same size; the top bar's nav is small
   and "New household" is as loud as navigation.

## Target

- **Shell:** top bar with household name (switcher when there are several; "New household" lives in
  the switcher), nav as tabs with an accent underline, user name and Log out at the right.
- **Page header pattern** (all three pages): large title on the left, primary action on the right.
- **Expenses:** title is the month with previous/next buttons; primary action "Add expense".
  A summary row under the header: Spent (largest), Invested, number of expenses. One table surface:
  day ("Thu 29 Oct"), merchant (notes beneath), category ("Parent › Child"), account, paid by
  (only when the household has more than one member), amount without per-row currency, refunds
  shown as `+` in the positive color. Clicking a row opens it for editing; delete moves into the editor.
- **Editor:** right-side sheet (native `<dialog>`, so focus is trapped and Esc closes it); full width
  on phones. Fields in one column, Save as primary, Delete as a quiet danger action when editing.
  Matches the old app's side-sheet editor.
- **Accounts / categories:** same header pattern; compact inline add form; row actions as quiet
  buttons instead of colored link pairs.
- **States:** skeleton rows while loading, composed empty state with the primary action, inline errors.

## System rules

- Neutrals: one warm grey family. **One accent:** deep green, for primary actions, active nav and focus.
  Semantic colors: danger red; positive (refunds) uses the accent's green family.
- **Radii:** controls 6px, surfaces 10px, badges fully round. Nothing else.
- **Type:** system UI stack. 28 page title / 20 summary figure / 15 base / 14 table / 12-13 meta.
  Tabular numerals for all amounts.
- **Surfaces:** page background plus one white content surface per page; no nested cards; borders
  over shadows, a soft shadow only on the sheet.
- **Motion:** only the sheet moves: slide in 200ms `cubic-bezier(0.23, 1, 0.32, 1)`, out faster;
  reduced motion gets a fade.
- **Labels:** one phrasing per intent ("Add expense", "Add account", "Add category", "Save", "Delete").

## Outcome

Implemented as specified, with these adjustments found while inspecting renders:

- Month arrows moved after the title (a leading chevron broke the left edge); "This month" appears when
  viewing another month.
- Accounts: dropped the Status column ("Active" on every row was noise); only closed accounts get a badge.
- Row actions on accounts and categories fade in on hover or keyboard focus with a mouse; touch screens
  always show them.
- Narrow screens: date folds into the merchant line, Add expense goes full width, Spent gets its own row.
- `scrollbar-gutter: stable` stops a sideways jump between short and long pages.
- Bug found: the native dialog focused its Close button, so typing then Enter closed the sheet. The sheet
  now focuses the field marked `data-autofocus` (Amount).

Evidence: `visual-evidence.json` (10 checks, validated with the skill's `verify-visual-evidence.mjs`).

Not verified: signup and onboarding renders (same panel as login, not captured), categories and accounts
at phone width, and the sheet's slide motion (no screen recording available; reduced-motion CSS is in
place but untested).

---

# UI pass 2 (2026-10-31): monochrome, sidebar

User direction: "clean, not many colors, black and white mainly, left sidebar, delightful to use but practical."

## Changes to the target

- **Palette:** neutral greys only (no warm tint). The accent is near-black (white in dark mode): primary
  buttons, active nav, focus rings. Red remains only for destructive actions and errors. Refunds keep
  their `+` sign in the normal text color; no green.
- **Shell:** left sidebar on desktop (≥ 900px): household switcher at the top, nav with icons, user and
  Log out at the bottom. Narrow screens: slim top bar (household, Log out) and a bottom tab bar with
  the three sections, within thumb reach.
- **Delight, kept practical:**
  - Shortcuts: `N` opens Add expense; `←`/`→` change month. Ignored while typing or with the sheet open.
    Shown as small key hints on desktop.
  - The row just added or saved gets a brief highlight that fades out (feedback).
  - Buttons give press feedback (scale 0.97, 100ms).
  - Numbers being read never animate; month changes are instant.

## Pass 2 outcome

Implemented as above. Adjustments found while inspecting renders:

- The `N` key hint was invisible on the white primary button in dark mode; its background now derives
  from the button's text color.
- On phones, Accounts overflowed sideways and both add forms pushed the lists below the fold. The add
  form now follows the list on phones, and the account owner folds under the name.

Evidence: `pass2/visual-evidence.json` (10 checks, validated). `pass1-after/visual-evidence.json` is
superseded: its source hashes predate pass 2, so the integrity check now reports it stale, as intended.

Not verified in pass 2: signup and onboarding renders, the sheet's slide motion and the row highlight's
fade over time (stills only; no screen recording), and reduced-motion behavior.

---

# UI pass 3 (2026-10-31): unslop polish and a ledger moment

Done with the `unslop` skill (Damir Sokolovsky, CC BY 4.0). Audit verdict: light slop, no AI tells;
the monochrome palette from pass 2 is kept as the brand.

Direction: a shared household ledger; exact, domestic, unhurried; a kitchen-table ledger of receipts
and figure columns. Moment: the month's category bar. Signature: every amount in a mono face.

- **Spacing:** everything on a 4px grid. Controls 36 tall (amount input 44), icon buttons 32, table
  cells 12/16, header 8/16; the avatar lines up with the navigation icons.
- **Type:** Geist for the interface, Geist Mono for amounts (bundled with fontsource). Weights 400 and
  500; 600 only for the wordmark and household name. No negative tracking.
- **Category bar:** under Spent, one bar split by top-level category (subcategories roll up, investing
  and net-negative categories left out, past five becomes Other), then a legend with shares. Black for
  the largest, grey steps for the rest; phones show the top three in the legend.
- **Details:** figures read `29,315.40 CZK` with a muted currency, table header lost its fill,
  badges use the control radius, subcategories are muted instead of parents being bold, content cap
  widened to 1280.

Checked in renders at 1440 light and dark, 375 light (no horizontal scroll), the add sheet, categories
and accounts. Not verified: signup and onboarding renders.

## Shell width and the side panel

- The page has no width cap: it fills what the sidebar and side panel leave.
- The editor is no longer a modal `<dialog>` over the page. It renders into a third column of the shell
  (`SheetSlot` in `ui.tsx`), so opening it narrows the page instead of covering it. Esc and Close shut
  it; focus goes to the `data-autofocus` field and back to the opener. On phones it still takes the
  whole screen.
- Nothing in the panel scrolls on its own: a long panel (a holding's full history) scrolls with the page,
  its header and footer sticking to the window's top and bottom.

---

# Rebuild on shadcn/ui (2026-11-14)

The hand-written stylesheet and components were replaced by shadcn/ui (Radix, Nova style) on Tailwind CSS 4.
The design rules above still hold and now live in the theme tokens of `web/src/styles.css`.

- Kept: monochrome palette with a near-black accent, red only for destructive actions and errors, Geist with
  Geist Mono amounts, the category bar shaded by rank, the editor as a column beside the page, the `N` and `←`/`→`
  shortcuts, the saved-row highlight.
- Changed: the sidebar is shadcn's Sidebar, so phones get a drawer behind a menu button instead of the bottom tab bar;
  the household switcher is a dropdown menu; row actions on accounts, categories and trips sit behind a "⋯" menu
  instead of hover buttons; browser confirm and prompt boxes became dialogs; icons are lucide.
- Radii follow shadcn's scale from `--radius: 0.5rem` rather than the fixed 6px / 10px pair.

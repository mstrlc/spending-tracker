import { useState, type FormEvent } from "react";
import { useAccounts, useAssets, useBankFeeds, useCategories, useMembers, useSaveInboxRule } from "../api/hooks";
import type { InboxRule, InboxRuleInput, RuleDirection, RuleOutcome } from "../api/types";
import { categoryTree, incomeCategories, spendingCategories } from "../categories/tree";
import { useHousehold } from "../households/HouseholdPages";
import { Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import { monthLabel, type RuleDraft } from "./ruleDrafts";

const FIELDS = [
  "name",
  "counterpartyIban",
  "merchantKey",
  "textContains",
  "feedId",
  "accountId",
  "direction",
  "merchant",
  "categoryId",
  "personUserId",
  "incomeSource",
  "otherAccountId",
  "settleFromUserId",
  "settleToUserId",
  "assetId",
  "skipReason",
  "monthOffset",
  "priority",
];

/** The months a deduction may be for, from the row's month: the usual advance is paid the month after it's for. */
const OFFSETS = [1, 0, -1, -2, -3];

/**
 * Creates a rule, starting from [draft] (a bank row's decision, say), or edits [rule]. Lives in a side sheet;
 * [onDone] closes it.
 */
export function RuleForm({ rule, draft, onDone }: { rule?: InboxRule; draft?: RuleDraft; onDone: () => void }) {
  const start: RuleDraft = rule ?? draft ?? {};
  const household = useHousehold();
  const feeds = useBankFeeds(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const assets = useAssets(household.id).data ?? [];
  const save = useSaveInboxRule(household.id);

  const [name, setName] = useState(start.name ?? "");
  const [merchantKey, setMerchantKey] = useState(start.merchantKey ?? "");
  const [textContains, setTextContains] = useState(start.textContains ?? "");
  const [counterpartyIban, setCounterpartyIban] = useState(start.counterpartyIban ?? "");
  const [feedId, setFeedId] = useState(start.feedId ?? "");
  const [accountId, setAccountId] = useState(start.accountId ?? "");
  const [direction, setDirection] = useState<RuleDirection | "">(start.direction ?? "");
  const [outcome, setOutcome] = useState<RuleOutcome>(start.outcome ?? "expense");
  const [merchant, setMerchant] = useState(start.merchant ?? "");
  const [categoryId, setCategoryId] = useState(start.categoryId ?? "");
  const [personUserId, setPersonUserId] = useState(start.personUserId ?? "");
  const [incomeSource, setIncomeSource] = useState(start.incomeSource ?? "");
  const [otherAccountId, setOtherAccountId] = useState(start.otherAccountId ?? "");
  const [settleFromUserId, setSettleFromUserId] = useState(start.settleFromUserId ?? "");
  const [settleToUserId, setSettleToUserId] = useState(start.settleToUserId ?? "");
  const [assetId, setAssetId] = useState(start.assetId ?? "");
  const [skipReason, setSkipReason] = useState(start.skipReason ?? "");
  const [monthOffset, setMonthOffset] = useState(start.monthOffset ?? -1);
  const [auto, setAuto] = useState(start.auto ?? false);
  const [priority, setPriority] = useState(String(start.priority ?? 0));

  const visibleCategories = outcome === "income" ? incomeCategories(categories) : spendingCategories(categories);
  const tree = categoryTree(visibleCategories.filter((c) => !c.archived || c.id === categoryId));
  const categoryOptions = tree.map((parent) => (
    <NativeSelectOptGroup key={parent.id} label={parent.name}>
      <NativeSelectOption value={parent.id}>{parent.name}</NativeSelectOption>
      {parent.children.map((child) => (
        <NativeSelectOption key={child.id} value={child.id}>
          {parent.name} › {child.name}
        </NativeSelectOption>
      ))}
    </NativeSelectOptGroup>
  ));
  const noMatch = !merchantKey.trim() && !textContains.trim() && !counterpartyIban.trim();
  const person = (
    <NativeSelect className="w-full" value={personUserId} onChange={(e) => setPersonUserId(e.target.value)}>
      <NativeSelectOption value="">Follow the account</NativeSelectOption>
      {members.map((m) => (
        <NativeSelectOption key={m.userId} value={m.userId}>
          {m.displayName}
        </NativeSelectOption>
      ))}
    </NativeSelect>
  );

  function submit(e: FormEvent) {
    e.preventDefault();
    const input: InboxRuleInput = {
      name,
      merchantKey: merchantKey.trim() || null,
      textContains: textContains.trim() || null,
      counterpartyIban: counterpartyIban.trim() || null,
      feedId: feedId || null,
      accountId: accountId || null,
      direction: direction || null,
      outcome,
      merchant,
      categoryId: categoryId || null,
      personUserId: personUserId || null,
      incomeSource,
      otherAccountId: otherAccountId || null,
      settleFromUserId: settleFromUserId || null,
      settleToUserId: settleToUserId || null,
      assetId: assetId || null,
      skipReason,
      monthOffset,
      auto,
      priority: Number.parseInt(priority, 10) || 0,
      version: rule?.version,
    };
    save.mutate({ id: rule?.id, input }, { onSuccess: onDone });
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={rule ? "Edit rule" : "New rule"}>
      <SheetBody
        footer={
          <>
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <Button type="submit" disabled={save.isPending || noMatch}>
              {rule ? "Save rule" : "Add rule"}
            </Button>
          </>
        }
      >
        <Field label="Name" name="name" error={save.error}>
          <Input required data-autofocus placeholder="e.g. Kaufland" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>

        <fieldset className="grid gap-4 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">When a bank row</legend>
          <Field label="Has the merchant key" name="merchantKey" error={save.error}>
            <Input placeholder="e.g. KAUFLAND CZ" value={merchantKey} onChange={(e) => setMerchantKey(e.target.value)} />
          </Field>
          <Field label="Contains the text" name="textContains" error={save.error}>
            <Input placeholder="e.g. kaufland" value={textContains} onChange={(e) => setTextContains(e.target.value)} />
          </Field>
          <Field label="From or to the IBAN" name="counterpartyIban" error={save.error}>
            <Input placeholder="e.g. CZ65 0800 0000 1920 0014 5399" value={counterpartyIban} onChange={(e) => setCounterpartyIban(e.target.value)} />
          </Field>
          <p className="text-xs text-muted-foreground">
            Fill in at least one of the merchant key, the text and the IBAN; everything you fill in has to match. Case and
            accents don't matter for the text, which is looked for in the row's text, counterparty and message together.
          </p>
          <Field label="In the feed" name="feedId" error={save.error}>
            <NativeSelect className="w-full" value={feedId} onChange={(e) => setFeedId(e.target.value)}>
              <NativeSelectOption value="">Any feed</NativeSelectOption>
              {feeds.map((f) => (
                <NativeSelectOption key={f.id} value={f.id}>
                  {f.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field label="On the account" name="accountId" error={save.error}>
            <NativeSelect className="w-full" value={accountId} onChange={(e) => setAccountId(e.target.value)}>
              <NativeSelectOption value="">Any account</NativeSelectOption>
              {accounts.map((a) => (
                <NativeSelectOption key={a.id} value={a.id}>
                  {a.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          <Field label="Money going" name="direction" error={save.error}>
            <NativeSelect className="w-full" value={direction} onChange={(e) => setDirection(e.target.value as RuleDirection | "")}>
              <NativeSelectOption value="">Either way</NativeSelectOption>
              <NativeSelectOption value="out">Out</NativeSelectOption>
              <NativeSelectOption value="in">In</NativeSelectOption>
            </NativeSelect>
          </Field>
        </fieldset>

        <fieldset className="grid gap-4 rounded-lg border p-3">
          <legend className="px-1 text-sm font-medium">It becomes</legend>
          <Field label="Outcome" name="outcome" error={save.error}>
            <NativeSelect className="w-full" value={outcome} onChange={(e) => setOutcome(e.target.value as RuleOutcome)}>
              <NativeSelectOption value="expense">An expense</NativeSelectOption>
              <NativeSelectOption value="income">An income line</NativeSelectOption>
              <NativeSelectOption value="deduction">A deduction from income</NativeSelectOption>
              <NativeSelectOption value="transfer">A transfer to or from another account</NativeSelectOption>
              <NativeSelectOption value="settlement">A settlement between members</NativeSelectOption>
              <NativeSelectOption value="contribution">An investment into an asset</NativeSelectOption>
              <NativeSelectOption value="skip">Skipped</NativeSelectOption>
            </NativeSelect>
          </Field>
          {outcome === "expense" && (
            <>
              <Field label="Merchant" name="merchant" error={save.error}>
                <Input placeholder="Leave empty to use the bank's descriptor" value={merchant} onChange={(e) => setMerchant(e.target.value)} />
              </Field>
              <Field label="Category" name="categoryId" error={save.error}>
                <NativeSelect className="w-full" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                  <NativeSelectOption value="">The merchant's category</NativeSelectOption>
                  {categoryOptions}
                </NativeSelect>
              </Field>
              <Field label="Whose" name="personUserId" error={save.error}>
                {person}
              </Field>
            </>
          )}
          {outcome === "income" && (
            <>
              <Field label="Source" name="incomeSource" error={save.error}>
                <Input placeholder="Leave empty to use the counterparty's name" value={incomeSource} onChange={(e) => setIncomeSource(e.target.value)} />
              </Field>
              <Field label="Category" name="categoryId" error={save.error}>
                <NativeSelect className="w-full" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
                  <NativeSelectOption value="">Uncategorized</NativeSelectOption>
                  {categoryOptions}
                </NativeSelect>
              </Field>
              <Field label="Received by" name="personUserId" error={save.error}>
                {person}
              </Field>
            </>
          )}
          {outcome === "deduction" && (
            <>
              <Field label="Paid to" name="incomeSource" error={save.error}>
                <Input
                  placeholder="e.g. ZPMV, OSSZ; empty uses the counterparty's name"
                  value={incomeSource}
                  onChange={(e) => setIncomeSource(e.target.value)}
                />
              </Field>
              <Field label="Whose" name="personUserId" error={save.error}>
                {person}
              </Field>
              <Field label="It is for" name="monthOffset" error={save.error}>
                <NativeSelect className="w-full" value={String(monthOffset)} onChange={(e) => setMonthOffset(Number(e.target.value))}>
                  {[...new Set([...OFFSETS, monthOffset])].map((o) => (
                    <NativeSelectOption key={o} value={String(o)}>
                      {monthLabel(o).replace(/^For /, "").replace(/^./, (c) => c.toUpperCase())}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </Field>
              <p className="-mt-2 text-xs text-muted-foreground">
                Money out becomes a deduction of that amount, arrived on the row's date and counted in the month it is for,
                measured from the row's month. The advance paid in November for October is the previous month.
              </p>
            </>
          )}
          {outcome === "transfer" && (
            <Field label="The other account" name="otherAccountId" error={save.error}>
              <NativeSelect required className="w-full" value={otherAccountId} onChange={(e) => setOtherAccountId(e.target.value)}>
                <NativeSelectOption value="">Choose an account</NativeSelectOption>
                {accounts.map((a) => (
                  <NativeSelectOption key={a.id} value={a.id}>
                    {a.name}
                  </NativeSelectOption>
                ))}
              </NativeSelect>
            </Field>
          )}
          {outcome === "settlement" && (
            <div className="grid grid-cols-2 gap-3">
              <Field label="Paid by" name="settleFromUserId" error={save.error}>
                <NativeSelect required className="w-full" value={settleFromUserId} onChange={(e) => setSettleFromUserId(e.target.value)}>
                  <NativeSelectOption value="">Choose a member</NativeSelectOption>
                  {members.map((m) => (
                    <NativeSelectOption key={m.userId} value={m.userId}>
                      {m.displayName}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </Field>
              <Field label="To" name="settleToUserId" error={save.error}>
                <NativeSelect required className="w-full" value={settleToUserId} onChange={(e) => setSettleToUserId(e.target.value)}>
                  <NativeSelectOption value="">Choose a member</NativeSelectOption>
                  {members.map((m) => (
                    <NativeSelectOption key={m.userId} value={m.userId}>
                      {m.displayName}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </Field>
            </div>
          )}
          {outcome === "contribution" && (
            <>
              <Field label="Asset" name="assetId" error={save.error}>
                <NativeSelect required className="w-full" value={assetId} onChange={(e) => setAssetId(e.target.value)}>
                  <NativeSelectOption value="">Choose an asset</NativeSelectOption>
                  {assets.map((a) => (
                    <NativeSelectOption key={a.id} value={a.id}>
                      {a.name}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </Field>
              <p className="-mt-2 text-xs text-muted-foreground">
                Money out is put into the asset, money in is taken out of it. It counts as invested, not spent.
              </p>
            </>
          )}
          {outcome === "skip" && (
            <Field label="Why" name="skipReason" error={save.error}>
              <Input required placeholder="e.g. Between own accounts" value={skipReason} onChange={(e) => setSkipReason(e.target.value)} />
            </Field>
          )}
        </fieldset>

        <div className="flex items-start gap-2">
          <Checkbox id="rule-auto" className="mt-0.5" checked={auto} onCheckedChange={(c) => setAuto(c === true)} />
          <Label htmlFor="rule-auto" className="flex-col items-start gap-0.5 font-normal">
            File matching rows without asking
            <span className="text-xs text-muted-foreground">
              Otherwise the rule only fills in the suggestion. Rows it files are marked automatic and can be put back from
              the Decided tab.
            </span>
          </Label>
        </div>
        <Field label="Priority" name="priority" error={save.error} hint={null}>
          <Input type="number" inputMode="numeric" className="w-28" value={priority} onChange={(e) => setPriority(e.target.value)} />
        </Field>
        <p className="-mt-2 text-xs text-muted-foreground">When several rules match a row, the highest priority wins, then the one with the most conditions.</p>
        <FormError error={save.error} fields={FIELDS} />
      </SheetBody>
    </form>
  );
}

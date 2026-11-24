import { useState, type FormEvent } from "react";
import { useCategories, useDeleteSplitRule, useIncome, useMembers, useSaveSplitRule } from "../api/hooks";
import type { SplitRule } from "../api/types";
import { categoryTree, spendingCategories } from "../categories/tree";
import { formatMonth, monthRange, shiftMonth, todayIso } from "../lib/dates";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Button } from "@/components/ui/button";
import { SubmitButton } from "@/ui";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import { SplitEditor } from "./SplitEditor";
import { draftFrom, sharesFrom, weightsFromIncome } from "./split";

/** Adds or edits a split rule: the household's, or one category's, from a date on. */
export function SplitRuleForm({ rule, onDone }: { rule?: SplitRule; onDone: () => void }) {
  const household = useHousehold();
  const members = useMembers(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const save = useSaveSplitRule(household.id);
  const remove = useDeleteSplitRule(household.id);
  const confirm = useConfirm();

  const [categoryId, setCategoryId] = useState(rule?.categoryId ?? "");
  const [validFrom, setValidFrom] = useState(rule?.validFrom ?? todayIso().slice(0, 8) + "01");
  const [draft, setDraft] = useState(() => draftFrom(rule?.shares ?? members.map((m) => ({ userId: m.userId, weight: 1 })), members));
  const [weightsError, setWeightsError] = useState<string | null>(null);

  // The 12 months before the rule starts, for splitting by income.
  const startMonth = (validFrom || todayIso()).slice(0, 7);
  const incomeFrom = monthRange(shiftMonth(startMonth, -12)).from;
  const incomeTo = monthRange(shiftMonth(startMonth, -1)).to;
  const income = useIncome(household.id, incomeFrom, incomeTo);
  const byIncome = income.data ? weightsFromIncome(income.data, members) : null;

  const tree = categoryTree(spendingCategories(categories).filter((c) => !c.archived || c.id === rule?.categoryId));

  function submit(e: FormEvent) {
    e.preventDefault();
    const shares = sharesFrom(draft);
    if (!shares) {
      setWeightsError("Give at least one person a share, in whole numbers.");
      return;
    }
    setWeightsError(null);
    save.mutate(
      { id: rule?.id, input: { validFrom, categoryId: categoryId || null, shares, version: rule?.version } },
      { onSuccess: onDone },
    );
  }

  async function confirmDelete() {
    if (!rule) return;
    if (await confirm({ title: "Delete this split?", description: "Spending it covered goes back to the split before it." })) {
      remove.mutate({ id: rule.id, version: rule.version }, { onSuccess: onDone });
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-1 flex-col" aria-label={rule ? "Edit split" : "Add split"}>
      <SheetBody
        footer={
          <>
            {rule && (
              <Button type="button" variant="ghost" className="text-destructive" onClick={confirmDelete} disabled={remove.isPending}>
                Delete
              </Button>
            )}
            <span className="flex-1" />
            <Button type="button" variant="outline" onClick={onDone}>
              Cancel
            </Button>
            <SubmitButton disabled={save.isPending}>
              {rule ? "Save" : "Add split"}
            </SubmitButton>
          </>
        }
      >
        <Field label="Applies to" name="categoryId" error={save.error}>
          <NativeSelect className="w-full" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
            <NativeSelectOption value="">All joint spending</NativeSelectOption>
            {tree.map((parent) => (
              <NativeSelectOptGroup key={parent.id} label={parent.name}>
                <NativeSelectOption value={parent.id}>{parent.name}</NativeSelectOption>
                {parent.children.map((child) => (
                  <NativeSelectOption key={child.id} value={child.id}>
                    {parent.name} › {child.name}
                  </NativeSelectOption>
                ))}
              </NativeSelectOptGroup>
            ))}
          </NativeSelect>
        </Field>
        <Field label="From" name="validFrom" error={save.error}>
          <Input type="date" required value={validFrom} onChange={(e) => setValidFrom(e.target.value)} />
        </Field>
        <SplitEditor label="Shares" members={members} draft={draft} onChange={setDraft} />
        {weightsError && <p className="text-sm text-destructive">{weightsError}</p>}
        <div className="flex flex-col items-start gap-1">
          <Button type="button" variant="outline" size="sm" disabled={!byIncome} onClick={() => byIncome && setDraft(draftFrom(byIncome, members))}>
            Split by income
          </Button>
          <p className="text-xs text-muted-foreground">
            {byIncome
              ? `By what each person earned from ${formatMonth(incomeFrom.slice(0, 7))} to ${formatMonth(incomeTo.slice(0, 7))}.`
              : `No personal income from ${formatMonth(incomeFrom.slice(0, 7))} to ${formatMonth(incomeTo.slice(0, 7))} to split by.`}
          </p>
        </div>
        <FormError error={save.error} fields={["categoryId", "validFrom"]} />
        <ErrorText error={remove.error} />
      </SheetBody>
    </form>
  );
}

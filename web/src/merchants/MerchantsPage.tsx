import { useState, type FormEvent } from "react";
import { useCategories, useDeleteMerchant, useMergeMerchant, useMerchants, useSaveMerchant } from "../api/hooks";
import type { Merchant } from "../api/types";
import { categoryTree } from "../categories/tree";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { EmptyState, ErrorText, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "../undo";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export function MerchantsPage() {
  const household = useHousehold();
  const merchants = useMerchants(household.id);
  const categories = useCategories(household.id).data ?? [];
  const saveMerchant = useSaveMerchant(household.id);
  const deleteMerchant = useDeleteMerchant(household.id);
  const save = useUndoableSave("Merchant", saveMerchant, deleteMerchant);
  const remove = useUndoableDelete("Merchant", deleteMerchant);
  const pendingDeletes = usePendingDeleteIds();
  const [query, setQuery] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);
  const [merging, setMerging] = useState<Merchant | null>(null);

  const tree = categoryTree(categories.filter((c) => !c.archived));
  const all = (merchants.data ?? []).filter((m) => !pendingDeletes.has(m.id));
  const needle = query.trim().toLowerCase();
  const shown = needle ? all.filter((m) => m.name.toLowerCase().includes(needle)) : all;

  const update = (m: Merchant, changes: { name?: string; defaultCategoryId?: string | null }) =>
    save.mutate({
      id: m.id,
      input: { name: m.name, defaultCategoryId: m.defaultCategoryId, version: m.version, ...changes },
      previous: { name: m.name, defaultCategoryId: m.defaultCategoryId, version: m.version },
    });

  function deleteRow(m: Merchant) {
    remove.schedule({ id: m.id, version: m.version });
  }

  return (
    <>
      <PageHeader title="Merchants" />
      <PageIntro>
        Every place you spend at, added as you type it on an expense. Renaming one renames it on all its expenses; two
        spellings of the same place can be merged. A new expense there gets the merchant's category.
      </PageIntro>

      <Surface>
        {all.length > 0 && (
          <div className="border-b p-3">
            <Input
              type="search"
              aria-label="Find a merchant"
              placeholder="Find a merchant"
              className="max-w-xs"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        )}
        <ErrorText error={save.error} className="p-4" />
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={merchants.error} className="p-4" />
        {merchants.isPending && <SkeletonRows rows={4} />}
        {merchants.data?.length === 0 && (
          <EmptyState title="No merchants yet">Type where you paid on an expense and the merchant is added here.</EmptyState>
        )}
        {all.length > 0 && shown.length === 0 && <EmptyState title="No match">No merchant's name contains “{query.trim()}”.</EmptyState>}
        {shown.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Merchant</TableHead>
                <TableHead className="max-md:hidden">Category</TableHead>
                <TableHead className="text-right">Spent</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((m) => (
                <TableRow key={m.id}>
                  <TableCell className="whitespace-normal font-medium">
                    <InlineRename
                      name={m.name}
                      editing={renaming === m.id}
                      onSave={(name) => update(m, { name })}
                      onDone={() => setRenaming(null)}
                    />
                    <div className="text-xs font-normal text-muted-foreground">
                      {m.expenseCount === 0
                        ? "No expenses"
                        : `${m.expenseCount === 1 ? "1 expense" : `${m.expenseCount} expenses`} · last ${formatDay(m.lastDate!)}`}
                    </div>
                  </TableCell>
                  <TableCell className="max-md:hidden">
                    <NativeSelect
                      className="w-56"
                      size="sm"
                      aria-label={`${m.name} category`}
                      value={m.defaultCategoryId ?? ""}
                      onChange={(e) => update(m, { defaultCategoryId: e.target.value || null })}
                    >
                      <NativeSelectOption value="">None</NativeSelectOption>
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
                      {m.defaultCategoryId && !tree.some((p) => p.id === m.defaultCategoryId || p.children.some((c) => c.id === m.defaultCategoryId)) && (
                        <NativeSelectOption value={m.defaultCategoryId}>
                          {categories.find((c) => c.id === m.defaultCategoryId)?.name ?? "Archived category"}
                        </NativeSelectOption>
                      )}
                    </NativeSelect>
                  </TableCell>
                  <TableCell className="amount text-right">{formatMoney(m.spentMinor, household.currency)}</TableCell>
                  <TableCell className="text-right">
                    <RowMenu
                      label={m.name}
                      actions={[
                        { label: "Rename", onSelect: () => setRenaming(m.id) },
                        ...(all.length > 1 ? [{ label: "Merge into…", onSelect: () => setMerging(m) }] : []),
                        ...(m.expenseCount === 0 ? [{ label: "Delete", destructive: true, onSelect: () => deleteRow(m) }] : []),
                      ]}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>

      {merging && <MergeDialog merchant={merging} merchants={all} onClose={() => setMerging(null)} />}
    </>
  );
}

/** Picks the merchant that [merchant]'s expenses move to. */
function MergeDialog({ merchant, merchants, onClose }: { merchant: Merchant; merchants: Merchant[]; onClose: () => void }) {
  const household = useHousehold();
  const merge = useMergeMerchant(household.id);
  const others = merchants.filter((m) => m.id !== merchant.id);
  const [intoId, setIntoId] = useState(() => closest(merchant.name, others)?.id ?? others[0]?.id ?? "");

  function submit(e: FormEvent) {
    e.preventDefault();
    merge.mutate({ id: merchant.id, version: merchant.version, intoId }, { onSuccess: onClose });
  }

  const count = merchant.expenseCount === 1 ? "Its 1 expense moves" : `Its ${merchant.expenseCount} expenses move`;
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={submit} className="grid gap-4">
          <DialogHeader>
            <DialogTitle>Merge {merchant.name}</DialogTitle>
            <DialogDescription>
              {count} to the merchant you pick, and {merchant.name} is deleted.
            </DialogDescription>
          </DialogHeader>
          <div className="grid gap-1.5">
            <Label htmlFor="merge-into" className="text-muted-foreground">
              Merge into
            </Label>
            <NativeSelect id="merge-into" className="w-full" value={intoId} onChange={(e) => setIntoId(e.target.value)}>
              {others.map((m) => (
                <NativeSelectOption key={m.id} value={m.id}>
                  {m.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </div>
          <ErrorText error={merge.error} />
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={merge.isPending || !intoId}>
              Merge
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

/** The likeliest other spelling: the merchant sharing the longest start with [name], ignoring case. */
function closest(name: string, merchants: Merchant[]): Merchant | undefined {
  const lower = name.toLowerCase();
  let best: Merchant | undefined;
  let bestLength = 0;
  for (const m of merchants) {
    const other = m.name.toLowerCase();
    let i = 0;
    while (i < lower.length && i < other.length && lower[i] === other[i]) i++;
    if (i > bestLength) {
      best = m;
      bestLength = i;
    }
  }
  return best;
}

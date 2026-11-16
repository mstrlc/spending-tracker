import { useState } from "react";
import { EmptyFilterRow, SortableHead, useTableSortAndFilter } from "../sorting";
import { PlusIcon } from "lucide-react";
import { useAccounts, useDeleteTransfer, useTransfers } from "../api/hooks";
import type { Transfer } from "../api/types";
import { AccountLabel, KindChip } from "../chips";
import { useHousehold } from "../households/HouseholdPages";
import { TransferForm } from "../inbox/TransferForm";
import { currentMonth, formatDay, formatMonth, todayIso } from "../lib/dates";
import { formatAmount } from "../lib/money";
import { MonthTitle, useMonthParam, useMonthShortcuts } from "../month";
import { EmptyState, ErrorText, PageHeader, RowMenu, Sheet, SkeletonRows, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete } from "../undo";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "edit"; transfer: Transfer };

export function TransfersPage() {
  const household = useHousehold();
  const { month, from, to, goTo } = useMonthParam();
  const [accountId, setAccountId] = useState("");
  const transfers = useTransfers(household.id, from, to, accountId || undefined);
  const accounts = useAccounts(household.id).data ?? [];
  const deleteTransfer = useDeleteTransfer(household.id);
  const remove = useUndoableDelete("Transfer", deleteTransfer);
  const pendingDeletes = usePendingDeleteIds();
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });

  const rows = (transfers.data ?? []).filter((t) => !pendingDeletes.has(t.id));
  const routeText = (t: Transfer) =>
    `${accounts.find((a) => a.id === t.fromAccountId)?.name ?? ""} → ${accounts.find((a) => a.id === t.toAccountId)?.name ?? ""}`;
  const { sort, onSort, clearFilters, shown, filterFor } = useTableSortAndFilter(
    rows,
    {
      day: (t) => t.date,
      route: routeText,
      notes: (t) => t.notes,
      amount: (t) => t.amountMinor,
    },
    {
      day: (t) => formatDay(t.date),
      route: routeText,
      notes: (t) => t.notes ?? "",
      amount: (t) => formatAmount(t.amountMinor, household.currency),
    },
  );
  const defaultDate = month === currentMonth() ? todayIso() : from;
  const openAdd = () => setEditor({ mode: "add" });
  const close = () => setEditor({ mode: "closed" });

  useMonthShortcuts(month, goTo, openAdd);

  function deleteRow(t: Transfer) {
    remove.schedule({ id: t.id, version: t.version });
  }

  return (
    <>
      <PageHeader title={<MonthTitle month={month} goTo={goTo} />}>
        <NativeSelect
          aria-label="Filter by account"
          value={accountId}
          onChange={(e) => setAccountId(e.target.value)}
          className="w-44"
        >
          <NativeSelectOption value="">All accounts</NativeSelectOption>
          {accounts.map((a) => (
            <NativeSelectOption key={a.id} value={a.id}>
              {a.name}
            </NativeSelectOption>
          ))}
        </NativeSelect>
        <Button size="lg" onClick={openAdd} aria-keyshortcuts="N">
          <PlusIcon /> Add transfer
          <Kbd className="hidden bg-primary-foreground/15 text-primary-foreground md:inline-flex">N</Kbd>
        </Button>
      </PageHeader>

      <Surface>
        <ErrorText error={transfers.error} className="p-4" />
        <ErrorText error={remove.error} className="p-4" />
        {transfers.isPending && <SkeletonRows />}
        {transfers.data && rows.length === 0 && (
          <EmptyState
            title={`No transfers in ${formatMonth(month)}`}
            action={
              <Button onClick={openAdd}>
                <PlusIcon /> Add transfer
              </Button>
            }
          >
            Record money moving between your own accounts, such as cash withdrawals, joint top-ups or credit card repayments.
          </EmptyState>
        )}
        {rows.length > 0 && (
          <Table className="table-fixed md:min-w-[40rem]">
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <SortableHead column="day" first="desc" sort={sort} onSort={onSort} filter={filterFor("day")} className="w-28 max-md:hidden">
                  Day
                </SortableHead>
                <SortableHead column="route" sort={sort} onSort={onSort} filter={filterFor("route")}>
                  From → To
                </SortableHead>
                <SortableHead column="notes" sort={sort} onSort={onSort} filter={filterFor("notes")} className="w-[24%] max-md:hidden">
                  Notes
                </SortableHead>
                <SortableHead column="amount" first="desc" sort={sort} onSort={onSort} filter={filterFor("amount")} className="w-36 text-right">
                  Amount ({household.currency})
                </SortableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.length === 0 && <EmptyFilterRow colSpan={5} onClear={clearFilters} />}
              {shown.map((t) => {
                const fromAcc = accounts.find((a) => a.id === t.fromAccountId);
                const toAcc = accounts.find((a) => a.id === t.toAccountId);
                return (
                  <TableRow
                    key={t.id}
                    className="cursor-pointer"
                    onClick={() => setEditor({ mode: "edit", transfer: t })}
                  >
                    <TableCell className="text-muted-foreground max-md:hidden">{formatDay(t.date)}</TableCell>
                    <TableCell className="whitespace-normal">
                      <div className="flex flex-wrap items-center gap-1.5 font-medium">
                        <KindChip kind="transfer" />
                        <AccountLabel account={fromAcc} />
                        <span className="text-muted-foreground">→</span>
                        <AccountLabel account={toAcc} />
                      </div>
                      <div className="text-xs text-muted-foreground md:hidden">
                        {formatDay(t.date)}
                        {t.notes ? ` · ${t.notes}` : ""}
                      </div>
                    </TableCell>
                    <TableCell className="truncate text-muted-foreground max-md:hidden">{t.notes || "–"}</TableCell>
                    <TableCell className="amount text-right font-medium">{formatAmount(t.amountMinor, household.currency)}</TableCell>
                    <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                      <RowMenu
                        label={`${fromAcc?.name ?? "Transfer"} → ${toAcc?.name ?? ""}`}
                        actions={[
                          { label: "Edit", onSelect: () => setEditor({ mode: "edit", transfer: t }) },
                          { label: "Delete", destructive: true, onSelect: () => deleteRow(t) },
                        ]}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Surface>

      <Sheet
        open={editor.mode !== "closed"}
        onClose={close}
        title={editor.mode === "edit" ? "Edit transfer" : "Add transfer"}
      >
        {editor.mode === "add" && <TransferForm defaultDate={defaultDate} onDone={close} />}
        {editor.mode === "edit" && <TransferForm key={editor.transfer.id} transfer={editor.transfer} onDone={close} />}
      </Sheet>
    </>
  );
}

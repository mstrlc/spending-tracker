import { useState } from "react";
import { useCategories, useDeleteRecurring, useMembers, useRecurring, useSaveRecurring } from "../api/hooks";
import type { RecurringHealth, RecurringPayment, RecurringSuggestion } from "../api/types";
import { CategoryLabel, Chip, PersonLabel, type Tone } from "../chips";
import { useHousehold } from "../households/HouseholdPages";
import { formatDate, todayIso } from "../lib/dates";
import { formatAmount, formatMoney } from "../lib/money";
import { EmptyState, ErrorText, Money, PageHeader, PageIntro, RowMenu, Sheet, SkeletonRows, Summary, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "../undo";
import { INTERVALS, paymentToInput, RecurringForm } from "./RecurringForm";
import { Button } from "@/components/ui/button";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PlusIcon } from "lucide-react";

type Editor = { mode: "closed" } | { mode: "add" } | { mode: "confirm"; suggestion: RecurringSuggestion } | { mode: "edit"; payment: RecurringPayment };

const HEALTH: Record<RecurringHealth, { label: string; tone: Tone } | null> = {
  ok: null,
  due: { label: "Due", tone: "neutral" },
  missed: { label: "Missed", tone: "danger" },
  changed: { label: "Amount changed", tone: "warning" },
  upcoming: { label: "Not started", tone: "neutral" },
  inactive: null,
};

export const intervalLabel = (months: number) => INTERVALS.find((i) => i.months === months)?.label ?? `Every ${months} months`;

const NEEDS_LOOK: RecurringHealth[] = ["missed", "changed"];

export function RecurringPage() {
  const household = useHousehold();
  const today = todayIso();
  const recurring = useRecurring(household.id, today);
  const categories = useCategories(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const saveRecurring = useSaveRecurring(household.id);
  const deleteRecurring = useDeleteRecurring(household.id);
  const save = useUndoableSave("Recurring payment", saveRecurring, deleteRecurring);
  const remove = useUndoableDelete("Recurring payment", deleteRecurring);
  const pendingDeletes = usePendingDeleteIds();
  const [editor, setEditor] = useState<Editor>({ mode: "closed" });
  const close = () => setEditor({ mode: "closed" });

  const payments = (recurring.data?.payments ?? []).filter((p) => !pendingDeletes.has(p.id));
  const active = payments.filter((p) => p.status === "active");
  const ended = payments.filter((p) => p.status === "ended");
  const dismissed = payments.filter((p) => p.status === "dismissed");
  const suggestions = recurring.data?.suggestions ?? [];
  const attention = active.filter((p) => NEEDS_LOOK.includes(p.health)).length;
  const monthly = recurring.data?.monthlyMinor ?? 0;
  const showWhose = members.length > 1;

  const update = (p: RecurringPayment, changes: Parameters<typeof paymentToInput>[1]) =>
    save.mutate({ id: p.id, input: paymentToInput(p, changes), previous: paymentToInput(p) });

  function dismiss(s: RecurringSuggestion) {
    save.mutate({
      input: {
        merchantId: s.merchantId,
        expectedAmountMinor: s.amountMinor,
        intervalMonths: s.intervalMonths,
        anchorDate: s.anchorDate,
        status: "dismissed",
      },
    });
  }

  const forget = (p: RecurringPayment) => remove.schedule({ id: p.id, version: p.version });

  return (
    <>
      <PageHeader title="Recurring">
        <Button onClick={() => setEditor({ mode: "add" })}>
          <PlusIcon /> Add
        </Button>
      </PageHeader>
      <PageIntro>
        Subscriptions, rent, insurance: anything a merchant charges on a schedule. Confirm what the app spots in your expenses, and it
        tells you when a charge is missed or its amount changes.
      </PageIntro>

      {active.length > 0 && (
        <Summary
          items={[
            { label: "A month", value: <Money currency={household.currency}>{formatAmount(monthly, household.currency)}</Money> },
            { label: "A year", value: <Money currency={household.currency}>{formatAmount(monthly * 12, household.currency)}</Money> },
            { label: "Need a look", value: attention },
          ]}
        />
      )}

      <ErrorText error={recurring.error} />
      <ErrorText error={save.error} />
      <ErrorText error={remove.error} />

      <Surface>
        {recurring.isPending && <SkeletonRows rows={3} />}
        {recurring.data && active.length === 0 && (
          <EmptyState title="No recurring payments yet">
            {suggestions.length > 0 ? "Confirm the ones below that really are recurring." : "Add one, or keep filing expenses and the app will suggest some."}
          </EmptyState>
        )}
        {active.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Merchant</TableHead>
                <TableHead className="max-md:hidden">How often</TableHead>
                {showWhose && <TableHead className="max-lg:hidden">Whose</TableHead>}
                <TableHead className="max-md:hidden">Last charge</TableHead>
                <TableHead>Next due</TableHead>
                <TableHead className="text-right">Usually</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {active.map((p) => (
                <TableRow key={p.id} className="cursor-pointer" onClick={() => setEditor({ mode: "edit", payment: p })}>
                  <TableCell className="whitespace-normal">
                    <div className="flex flex-wrap items-center gap-2 font-medium">
                      {p.merchant}
                      <HealthChip payment={p} currency={household.currency} />
                    </div>
                    <CategoryLabel categories={categories} id={p.categoryId} className="text-xs text-muted-foreground" />
                  </TableCell>
                  <TableCell className="max-md:hidden">{intervalLabel(p.intervalMonths)}</TableCell>
                  {showWhose && (
                    <TableCell className="max-lg:hidden">
                      <PersonLabel members={members} userId={p.paidByUserId} />
                    </TableCell>
                  )}
                  <TableCell className="max-md:hidden">
                    {p.lastChargeDate ? (
                      <>
                        {formatDate(p.lastChargeDate)}
                        <span className="amount ml-2 text-muted-foreground">{formatAmount(p.lastChargeMinor ?? 0, household.currency)}</span>
                      </>
                    ) : (
                      <span className="text-muted-foreground">None yet</span>
                    )}
                  </TableCell>
                  <TableCell>
                    {formatDate(p.nextDue)}
                    {p.missedDates.length > 0 && (
                      <div className="text-xs text-muted-foreground">
                        Missed {p.missedDates.slice(0, 3).map((d) => formatDate(d)).join(", ")}
                        {p.missedDates.length > 3 && ` and ${p.missedDates.length - 3} more`}
                      </div>
                    )}
                  </TableCell>
                  <TableCell className="amount text-right">{formatAmount(p.expectedAmountMinor, household.currency)}</TableCell>
                  <TableCell className="text-right" onClick={(e) => e.stopPropagation()}>
                    <RowMenu
                      label={p.merchant}
                      actions={[
                        { label: "Edit", onSelect: () => setEditor({ mode: "edit", payment: p }) },
                        ...(p.health === "changed" && p.lastChargeMinor !== null
                          ? [
                              {
                                label: `Expect ${formatMoney(p.lastChargeMinor, household.currency)} from now on`,
                                onSelect: () => update(p, { expectedAmountMinor: p.lastChargeMinor! }),
                              },
                            ]
                          : []),
                        { label: "Mark as ended", onSelect: () => update(p, { status: "ended" }) },
                        { label: "Delete", destructive: true, onSelect: () => forget(p) },
                      ]}
                    />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>

      {suggestions.length > 0 && (
        <section className="flex flex-col gap-3">
          <div>
            <h2 className="font-medium">Looks recurring</h2>
            <p className="text-sm text-muted-foreground">
              Charged by about the same amount most months, or around the same date each year. Confirm the real ones; dismissed ones
              aren't suggested again.
            </p>
          </div>
          <Surface>
            <Table>
              <TableHeader>
                <TableRow className="hover:bg-transparent">
                  <TableHead>Merchant</TableHead>
                  <TableHead className="max-md:hidden">How often</TableHead>
                  <TableHead className="max-md:hidden">Last charge</TableHead>
                  <TableHead className="text-right">Usually</TableHead>
                  <TableHead className="text-right">
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {suggestions.map((s) => (
                  <TableRow key={s.merchantId}>
                    <TableCell className="whitespace-normal">
                      <div className="font-medium">{s.merchant}</div>
                      <div className="text-xs text-muted-foreground">
                        Seen {s.timesSeen} {s.intervalMonths === 12 ? "years" : "months"}
                      </div>
                    </TableCell>
                    <TableCell className="max-md:hidden">{intervalLabel(s.intervalMonths)}</TableCell>
                    <TableCell className="max-md:hidden">{formatDate(s.anchorDate)}</TableCell>
                    <TableCell className="amount text-right">{formatAmount(s.amountMinor, household.currency)}</TableCell>
                    <TableCell className="text-right">
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => dismiss(s)} disabled={save.isPending}>
                          Dismiss
                        </Button>
                        <Button size="sm" variant="outline" onClick={() => setEditor({ mode: "confirm", suggestion: s })}>
                          Confirm…
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Surface>
        </section>
      )}

      {(ended.length > 0 || dismissed.length > 0) && (
        <section className="flex flex-col gap-3">
          <h2 className="font-medium">Ended and dismissed</h2>
          <Surface>
            <Table>
              <TableBody>
                {[...ended, ...dismissed].map((p) => (
                  <TableRow key={p.id}>
                    <TableCell className="whitespace-normal">
                      <span className="font-medium">{p.merchant}</span>
                      <span className="ml-2 text-sm text-muted-foreground">
                        {p.status === "ended" ? `Ended · last charged ${p.lastChargeDate ? formatDate(p.lastChargeDate) : "never"}` : "Dismissed: not recurring"}
                      </span>
                    </TableCell>
                    <TableCell className="amount text-right text-muted-foreground max-md:hidden">
                      {p.status === "ended" && formatAmount(p.expectedAmountMinor, household.currency)}
                    </TableCell>
                    <TableCell className="w-12 text-right">
                      <RowMenu
                        label={p.merchant}
                        actions={
                          p.status === "ended"
                            ? [
                                { label: "Active again", onSelect: () => update(p, { status: "active" }) },
                                { label: "Edit", onSelect: () => setEditor({ mode: "edit", payment: p }) },
                                { label: "Delete", destructive: true, onSelect: () => forget(p) },
                              ]
                            : [{ label: "Suggest again", onSelect: () => forget(p) }]
                        }
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Surface>
        </section>
      )}

      <Sheet
        open={editor.mode !== "closed"}
        onClose={close}
        title={editor.mode === "edit" ? editor.payment.merchant : editor.mode === "confirm" ? `Confirm ${editor.suggestion.merchant}` : "Add recurring payment"}
      >
        {editor.mode === "add" && <RecurringForm onDone={close} />}
        {editor.mode === "confirm" && <RecurringForm key={editor.suggestion.merchantId} suggestion={editor.suggestion} onDone={close} />}
        {editor.mode === "edit" && <RecurringForm key={editor.payment.id} payment={editor.payment} onDone={close} />}
      </Sheet>
    </>
  );
}

function HealthChip({ payment, currency }: { payment: RecurringPayment; currency: string }) {
  const health = HEALTH[payment.health];
  if (!health) return null;
  const title =
    payment.health === "changed" && payment.lastChargeMinor !== null
      ? `Last charged ${formatMoney(payment.lastChargeMinor, currency)} instead of ${formatMoney(payment.expectedAmountMinor, currency)}`
      : undefined;
  return (
    <Chip tone={health.tone} title={title}>
      {health.label}
    </Chip>
  );
}

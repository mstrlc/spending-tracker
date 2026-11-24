import { useState, type FormEvent } from "react";
import { useAssignOrderItems, useCategories, useDeleteOrder, useMatchOrder, useMembers, useReadOrderEmail } from "../api/hooks";
import type { BankRow, Order } from "../api/types";
import { ApiFailure } from "../api/errors";
import { categoryTree, spendingCategories } from "../categories/tree";
import { useHousehold } from "../households/HouseholdPages";
import { useConfirm } from "../dialogs";
import { formatDate, formatDay } from "../lib/dates";
import { formatMoney } from "../lib/money";
import { ErrorText, Field, FormError, SheetBody } from "../ui";
import { Chip } from "../chips";
import { Button } from "@/components/ui/button";
import { SubmitButton } from "@/ui";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { NativeSelect, NativeSelectOptGroup, NativeSelectOption } from "@/components/ui/native-select";

const orderTitle = (order: Pick<Order, "merchant" | "shop" | "number">) => `${order.merchant || order.shop} order ${order.number}`;

type ReadResult = { name: string; order?: Order; error?: string };

/** Reads order emails: `.eml` files saved from a mail app, or an email's text pasted in. */
export function OrderEmailForm({ onDone }: { onDone: () => void }) {
  const household = useHousehold();
  const read = useReadOrderEmail(household.id);
  const [files, setFiles] = useState<File[]>([]);
  const [text, setText] = useState("");
  const [results, setResults] = useState<ReadResult[]>([]);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    const sources: { name: string; email: () => Promise<string> }[] = [
      ...files.map((f) => ({ name: f.name, email: () => f.text() })),
      ...(text.trim() ? [{ name: "Pasted text", email: async () => text }] : []),
    ];
    const done: ReadResult[] = [];
    for (const source of sources) {
      try {
        done.push({ name: source.name, order: await read.mutateAsync(await source.email()) });
      } catch (err) {
        done.push({ name: source.name, error: err instanceof ApiFailure ? err.message : String(err) });
      }
    }
    setResults(done);
    setBusy(false);
    setFiles([]);
    setText("");
  }

  return (
    <form onSubmit={submit} className="contents">
      <SheetBody
        footer={
          <>
            <SubmitButton disabled={busy || (files.length === 0 && !text.trim())}>
              {busy ? "Reading…" : "Read"}
            </SubmitButton>
            <Button type="button" variant="ghost" onClick={onDone}>
              {results.length > 0 ? "Done" : "Cancel"}
            </Button>
          </>
        }
      >
        <p className="text-sm text-muted-foreground">
          An order's email lists what was bought. Once its payment is in the inbox, the payment's expense is drafted split by
          item, with categories learnt from earlier orders.
        </p>
        <Field label="Email files (.eml)" name="files">
          <Input
            type="file"
            multiple
            accept=".eml,message/rfc822"
            onChange={(e) => setFiles(Array.from(e.target.files ?? []))}
          />
        </Field>
        <Field label="Or paste an email's text" name="email">
          <Textarea rows={6} value={text} onChange={(e) => setText(e.target.value)} />
        </Field>
        {results.length > 0 && (
          <ul className="flex flex-col divide-y text-sm" aria-label="Read emails">
            {results.map((r, i) => (
              <li key={i} className="flex flex-col gap-0.5 py-2">
                <span className="truncate font-medium">{r.order ? orderTitle(r.order) : r.name}</span>
                {r.order ? (
                  <span className="text-muted-foreground">
                    {formatMoney(r.order.totalMinor, r.order.currency)} ·{" "}
                    {r.order.bankRowId ? "matched to its payment" : "waiting for its payment"}
                  </span>
                ) : (
                  <span className="text-destructive">{r.error}</span>
                )}
              </li>
            ))}
          </ul>
        )}
      </SheetBody>
    </form>
  );
}

/** Where each item of [order] goes: its category and whose. Saving redrafts the payment's split. */
export function OrderItemsForm({ order, onDone }: { order: Order; onDone: () => void }) {
  const household = useHousehold();
  const categories = useCategories(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const assign = useAssignOrderItems(household.id);
  const [items, setItems] = useState(() =>
    order.items.map((i) => ({ itemId: i.id, categoryId: i.categoryId ?? "", paidByUserId: i.paidByUserId ?? "" })),
  );
  const tree = categoryTree(spendingCategories(categories).filter((c) => !c.archived || items.some((i) => i.categoryId === c.id)));
  const set = (index: number, change: Partial<(typeof items)[number]>) =>
    setItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...change } : item)));

  function submit(e: FormEvent) {
    e.preventDefault();
    assign.mutate(
      {
        id: order.id,
        version: order.version,
        items: items.map((i) => ({ itemId: i.itemId, categoryId: i.categoryId || null, paidByUserId: i.paidByUserId || null })),
      },
      { onSuccess: onDone },
    );
  }

  return (
    <form onSubmit={submit} className="contents">
      <SheetBody
        footer={
          <>
            <SubmitButton disabled={assign.isPending}>
              Save
            </SubmitButton>
            <Button type="button" variant="ghost" onClick={onDone}>
              Cancel
            </Button>
          </>
        }
      >
        <p className="text-sm text-muted-foreground">
          {orderTitle(order)} from {formatDate(order.orderedOn)}, {formatMoney(order.totalMinor, order.currency)}. Later orders
          with the same items start from what you pick here.
        </p>
        <FormError error={assign.error} fields={[]} />
        <ul className="flex flex-col divide-y">
          {order.items.map((item, index) => (
            <li key={item.id} className="flex flex-col gap-2 py-3">
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <span className="min-w-0">
                  {item.quantity !== 1 && <span className="text-muted-foreground">{item.quantity}× </span>}
                  {item.name}
                  {item.kind !== "item" && (
                    <Chip className="ml-2">{item.kind === "shipping" ? "Shipping" : item.kind === "fee" ? "Fee" : "Discount"}</Chip>
                  )}
                </span>
                <span className="amount shrink-0">{formatMoney(item.amountMinor, order.currency)}</span>
              </div>
              <div className="grid grid-cols-2 gap-2">
                <NativeSelect
                  className="w-full"
                  aria-label={`Category of ${item.name}`}
                  value={items[index].categoryId}
                  onChange={(e) => set(index, { categoryId: e.target.value })}
                >
                  <NativeSelectOption value="">{order.merchant ? `${order.merchant}'s default` : "No category"}</NativeSelectOption>
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
                <NativeSelect
                  className="w-full"
                  aria-label={`Whose is ${item.name}`}
                  value={items[index].paidByUserId}
                  onChange={(e) => set(index, { paidByUserId: e.target.value })}
                >
                  <NativeSelectOption value="">Joint</NativeSelectOption>
                  {members.map((m) => (
                    <NativeSelectOption key={m.userId} value={m.userId}>
                      {m.displayName}
                    </NativeSelectOption>
                  ))}
                </NativeSelect>
              </div>
            </li>
          ))}
        </ul>
      </SheetBody>
    </form>
  );
}

/**
 * Orders whose payment isn't matched: each with the pending rows it could be, to match by hand, or to delete. Orders
 * unmatched by hand are listed too, so a wrong unmatch can be undone.
 */
export function WaitingOrders({ orders, rows }: { orders: Order[]; rows: BankRow[] }) {
  const household = useHousehold();
  const match = useMatchOrder(household.id);
  const remove = useDeleteOrder(household.id);
  const confirm = useConfirm();
  const waiting = orders.filter((o) => o.bankRowId === null);
  if (waiting.length === 0) return null;
  const rowOf = (id: string) => rows.find((r) => r.id === id);

  async function del(order: Order) {
    if (
      !(await confirm({
        title: `Delete ${orderTitle(order)}?`,
        description: "Its items are forgotten; reading its email again brings it back.",
        action: "Delete",
      }))
    ) {
      return;
    }
    remove.mutate({ id: order.id, version: order.version });
  }

  return (
    <section className="flex flex-col gap-2 rounded-lg border px-3 py-2 text-sm" aria-label="Orders waiting for their payment">
      <h2 className="font-medium">
        {waiting.length === 1 ? "1 order waits" : `${waiting.length} orders wait`} for its payment
      </h2>
      <ErrorText error={match.error ?? remove.error} />
      <ul className="flex flex-col divide-y">
        {waiting.map((order) => (
          <li key={order.id} className="flex flex-wrap items-center justify-between gap-2 py-2">
            <span className="min-w-0">
              <span className="block truncate">{orderTitle(order)}</span>
              <span className="block text-xs text-muted-foreground">
                {formatDate(order.orderedOn)} · {order.items.filter((i) => i.kind === "item").length} items
                {order.candidates.length === 0 && " · no payment of this amount in the inbox yet"}
              </span>
            </span>
            <span className="flex items-center gap-2">
              <span className="amount">{formatMoney(order.totalMinor, order.currency)}</span>
              {order.candidates.length > 0 && (
                <NativeSelect
                  aria-label={`Payment of ${orderTitle(order)}`}
                  value=""
                  onChange={(e) => {
                    const candidate = order.candidates.find((c) => c.id === e.target.value);
                    if (candidate) match.mutate({ id: order.id, version: order.version, row: candidate });
                  }}
                >
                  <NativeSelectOption value="">Match to…</NativeSelectOption>
                  {order.candidates.map((c) => {
                    const row = rowOf(c.id);
                    return (
                      <NativeSelectOption key={c.id} value={c.id}>
                        {row ? `${formatDay(row.date)} · ${row.text || row.counterpartyName}` : "A pending row"}
                      </NativeSelectOption>
                    );
                  })}
                </NativeSelect>
              )}
              <Button size="sm" variant="ghost" onClick={() => del(order)}>
                Delete
              </Button>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

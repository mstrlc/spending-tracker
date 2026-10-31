import { useState, type FormEvent } from "react";
import { useAccounts, useDeleteAccount, useMembers, useSaveAccount } from "../api/hooks";
import type { Account, AccountInput } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { ErrorText, Field, FormError, PageHeader, SkeletonRows } from "../ui";

export function AccountsPage() {
  const household = useHousehold();
  const accounts = useAccounts(household.id);
  const members = useMembers(household.id).data ?? [];
  const save = useSaveAccount(household.id);
  const remove = useDeleteAccount(household.id);
  const [name, setName] = useState("");
  const [ownerUserId, setOwnerUserId] = useState("");
  const [renaming, setRenaming] = useState<string | null>(null);

  function add(e: FormEvent) {
    e.preventDefault();
    save.mutate({ input: { name, ownerUserId: ownerUserId || null } }, { onSuccess: () => setName("") });
  }

  const update = (account: Account, changes: Partial<AccountInput>) =>
    save.mutate({
      id: account.id,
      input: { name: account.name, ownerUserId: account.ownerUserId, status: account.status, version: account.version, ...changes },
    });

  return (
    <>
      <PageHeader title="Accounts" />
      <p className="page-intro muted">The bank accounts and cards you pay from. Close an account to keep its history but hide it from new expenses.</p>

      <div className="add-block">
        <form onSubmit={add} className="toolbar-form" aria-label="Add account">
          <Field label="Account name" name="name" error={save.error}>
            <input required placeholder="e.g. Revolut" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Owner" name="ownerUserId" error={save.error}>
            <select value={ownerUserId} onChange={(e) => setOwnerUserId(e.target.value)}>
              <option value="">Shared</option>
              {members.map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.displayName}
                </option>
              ))}
            </select>
          </Field>
          <button type="submit" disabled={save.isPending}>
            Add account
          </button>
        </form>
        <FormError error={save.error} fields={["name", "ownerUserId"]} />
      </div>

      <section className="surface">
        <ErrorText error={remove.error} />
        <ErrorText error={accounts.error} />
        {accounts.isPending && <SkeletonRows rows={3} />}
        {accounts.data?.length === 0 && (
          <div className="empty">
            <p className="empty-title">No accounts yet</p>
            <p className="muted">Add the accounts and cards you pay from to see where money goes.</p>
          </div>
        )}
        {accounts.data && accounts.data.length > 0 && (
          <div className="table-wrap">
            <table className="data-table align-middle">
              <thead>
                <tr>
                  <th>Name</th>
                  <th className="hide-narrow">Owner</th>
                  <th className="actions-col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {accounts.data.map((a) => (
                  <tr key={a.id} className={a.status === "closed" ? "dimmed" : undefined}>
                    <td className="strong">
                      <span className="cell-with-badge">
                        <InlineRename
                          name={a.name}
                          editing={renaming === a.id}
                          onSave={(newName) => update(a, { name: newName })}
                          onDone={() => setRenaming(null)}
                        />
                        {a.status === "closed" && <span className="badge badge-muted">Closed</span>}
                      </span>
                      <div className="muted small show-narrow">
                        {members.find((m) => m.userId === a.ownerUserId)?.displayName ?? "Shared"}
                      </div>
                    </td>
                    <td className="muted hide-narrow">{members.find((m) => m.userId === a.ownerUserId)?.displayName ?? "Shared"}</td>
                    <td className="row-actions">
                      <button className="ghost" onClick={() => setRenaming(a.id)}>
                        Rename
                      </button>
                      <button className="ghost" onClick={() => update(a, { status: a.status === "active" ? "closed" : "active" })}>
                        {a.status === "active" ? "Close" : "Reopen"}
                      </button>
                      <button
                        className="ghost danger"
                        onClick={() => window.confirm(`Delete ${a.name}?`) && remove.mutate({ id: a.id, version: a.version })}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

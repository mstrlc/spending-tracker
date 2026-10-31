import { useState, type FormEvent } from "react";
import { useAccounts, useDeleteAccount, useMembers, useSaveAccount } from "../api/hooks";
import type { Account, AccountInput } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { ErrorText, Field, FormError, Loading } from "../ui";

export function AccountsPage() {
  const household = useHousehold();
  const accounts = useAccounts(household.id);
  const members = useMembers(household.id).data ?? [];
  const save = useSaveAccount(household.id);
  const remove = useDeleteAccount(household.id);
  const [name, setName] = useState("");
  const [ownerUserId, setOwnerUserId] = useState("");

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
      <section className="card">
        <h2>Add account</h2>
        <form onSubmit={add} className="row-form">
          <Field label="Name" name="name" error={save.error}>
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
            Add
          </button>
        </form>
        <FormError error={save.error} fields={["name", "ownerUserId"]} />
      </section>

      <section className="card">
        <h2>Accounts</h2>
        <ErrorText error={remove.error} />
        {accounts.isPending && <Loading />}
        <ErrorText error={accounts.error} />
        {accounts.data?.length === 0 && <p className="muted">No accounts yet. Add the bank accounts and cards you pay from.</p>}
        {accounts.data && accounts.data.length > 0 && (
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Owner</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {accounts.data.map((a) => (
                <tr key={a.id} className={a.status === "closed" ? "muted" : undefined}>
                  <td>
                    <InlineRename name={a.name} onSave={(newName) => update(a, { name: newName })} />
                  </td>
                  <td>{members.find((m) => m.userId === a.ownerUserId)?.displayName ?? "Shared"}</td>
                  <td>{a.status === "active" ? "Active" : "Closed"}</td>
                  <td className="row-actions">
                    <button className="link" onClick={() => update(a, { status: a.status === "active" ? "closed" : "active" })}>
                      {a.status === "active" ? "Close" : "Reopen"}
                    </button>
                    <button
                      className="link danger"
                      onClick={() => window.confirm(`Delete ${a.name}?`) && remove.mutate({ id: a.id, version: a.version })}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
import { useAddMember, useCreateInvite, useInvites, useMe, useMembers, useRemoveMember, useRevokeInvite } from "../api/hooks";
import type { HouseholdRole, Invite, Member } from "../api/types";
import { ErrorText, Field, FormError, PageHeader, SkeletonRows } from "../ui";
import { useHousehold } from "./HouseholdPages";

const ROLE_LABEL: Record<HouseholdRole, string> = { owner: "Owner", member: "Member" };

export function inviteLink(token: string) {
  return `${window.location.origin}/invite/${token}`;
}

function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

export function MembersPage() {
  const household = useHousehold();
  const isOwner = household.role === "owner";
  const me = useMe().data;
  const members = useMembers(household.id);
  const invites = useInvites(household.id, isOwner);
  const remove = useRemoveMember(household.id);
  const navigate = useNavigate();

  function removeMember(member: Member) {
    const leaving = member.userId === me?.id;
    const question = leaving
      ? `Leave ${household.name}? You'll lose access until someone invites you again.`
      : `Remove ${member.displayName} from ${household.name}? Their accounts and expenses become shared.` +
        (member.hasLogin ? "" : " They have no login, so they're deleted.");
    if (!window.confirm(question)) return;
    remove.mutate({ userId: member.userId, leaving }, { onSuccess: () => leaving && navigate("/", { replace: true }) });
  }

  return (
    <>
      <PageHeader title="Members" />
      <p className="page-intro muted">
        Everyone here sees and edits the same accounts and expenses. Owners can invite and remove people, and add
        people without a login (a partner who hasn't signed up yet, a child) so things can be theirs; an invite can
        hand such a member over to a real account later.
      </p>

      {isOwner && <InviteForm claimable={(members.data ?? []).filter((m) => !m.hasLogin)} />}
      {isOwner && <AddMemberForm />}

      <section className="surface">
        <ErrorText error={remove.error} />
        <ErrorText error={members.error} />
        {members.isPending && <SkeletonRows rows={2} />}
        {members.data && (
          <div className="table-wrap">
            <table className="data-table align-middle">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Role</th>
                  <th className="actions-col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {members.data.map((m) => (
                  <tr key={m.userId}>
                    <td className="strong">
                      {m.displayName}
                      {m.userId === me?.id && <span className="muted"> (you)</span>}
                      {!m.hasLogin && <span className="badge badge-muted">No login</span>}
                    </td>
                    <td className="muted">{ROLE_LABEL[m.role]}</td>
                    <td className="row-actions">
                      {m.userId === me?.id ? (
                        <button className="ghost danger" onClick={() => removeMember(m)}>
                          Leave
                        </button>
                      ) : (
                        isOwner && (
                          <button className="ghost danger" onClick={() => removeMember(m)}>
                            Remove
                          </button>
                        )
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {isOwner && invites.data && invites.data.length > 0 && <PendingInvites invites={invites.data} />}
    </>
  );
}

/** Adds a member without a login. */
function AddMemberForm() {
  const household = useHousehold();
  const add = useAddMember(household.id);
  const [name, setName] = useState("");

  function submit(e: FormEvent) {
    e.preventDefault();
    add.mutate(name, { onSuccess: () => setName("") });
  }

  return (
    <div className="add-block">
      <form onSubmit={submit} className="toolbar-form" aria-label="Add someone without a login">
        <Field label="Name" name="displayName" error={add.error}>
          <input required placeholder="Without a login" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <button type="submit" className="secondary" disabled={add.isPending}>
          Add person
        </button>
      </form>
      <FormError error={add.error} fields={["displayName"]} />
    </div>
  );
}

/** Invites by email; with members without a login, the invitee can take one's place. */
function InviteForm({ claimable }: { claimable: Member[] }) {
  const household = useHousehold();
  const create = useCreateInvite(household.id);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<HouseholdRole>("member");
  const [claimUserId, setClaimUserId] = useState("");
  const [created, setCreated] = useState<Invite | null>(null);
  const [copied, setCopied] = useState(false);

  function submit(e: FormEvent) {
    e.preventDefault();
    setCreated(null);
    create.mutate(
      { email, role, claimUserId: claimUserId || null },
      {
        onSuccess: (invite) => {
          setCreated(invite);
          setCopied(false);
          setEmail("");
          setClaimUserId("");
        },
      },
    );
  }

  async function copy(token: string) {
    await navigator.clipboard.writeText(inviteLink(token));
    setCopied(true);
  }

  return (
    <div className="add-block">
      <form onSubmit={submit} className="toolbar-form" aria-label="Invite someone">
        <Field label="Email" name="email" error={create.error}>
          <input type="email" required placeholder="partner@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Role" name="role" error={create.error}>
          <select value={role} onChange={(e) => setRole(e.target.value as HouseholdRole)}>
            <option value="member">Member</option>
            <option value="owner">Owner</option>
          </select>
        </Field>
        {claimable.length > 0 && (
          <Field label="Takes the place of" name="claimUserId" error={create.error}>
            <select value={claimUserId} onChange={(e) => setClaimUserId(e.target.value)}>
              <option value="">Nobody (a new member)</option>
              {claimable.map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.displayName}
                </option>
              ))}
            </select>
          </Field>
        )}
        <button type="submit" disabled={create.isPending}>
          Create invite link
        </button>
      </form>
      <FormError error={create.error} fields={["email", "role", "claimUserId"]} />
      {created?.token && (
        <div className="invite-link" role="status">
          <p>
            Send this link to <span className="strong">{created.email}</span>. It works once, for that address, until{" "}
            {formatDate(created.expiresAt)}.
            {created.claimName && <> Accepting it takes over {created.claimName}'s place, with everything that's theirs.</>}
          </p>
          <div className="invite-link-row">
            <input readOnly aria-label="Invite link" value={inviteLink(created.token)} onFocus={(e) => e.target.select()} />
            <button type="button" onClick={() => copy(created.token!)}>
              {copied ? "Copied" : "Copy link"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function PendingInvites({ invites }: { invites: Invite[] }) {
  const household = useHousehold();
  const revoke = useRevokeInvite(household.id);
  const now = Date.now();

  return (
    <section className="surface">
      <h2 className="section-title">Pending invites</h2>
      <ErrorText error={revoke.error} />
      <div className="table-wrap">
        <table className="data-table align-middle">
          <thead>
            <tr>
              <th>Email</th>
              <th className="hide-narrow">Role</th>
              <th>Expires</th>
              <th className="actions-col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {invites.map((invite) => {
              const expired = Date.parse(invite.expiresAt) <= now;
              return (
                <tr key={invite.id} className={expired ? "dimmed" : undefined}>
                  <td className="strong">
                    {invite.email}
                    {invite.claimName && <span className="muted"> · as {invite.claimName}</span>}
                  </td>
                  <td className="muted hide-narrow">{ROLE_LABEL[invite.role]}</td>
                  <td className="muted nowrap">
                    {expired ? <span className="badge badge-muted">Expired</span> : formatDate(invite.expiresAt)}
                  </td>
                  <td className="row-actions">
                    <button className="ghost danger" onClick={() => revoke.mutate(invite.id)}>
                      {expired ? "Remove" : "Revoke"}
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
}

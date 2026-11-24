import { useState, type FormEvent } from "react";
import { Avatar, Chip } from "../chips";
import { ClockAlertIcon, CrownIcon, UserRoundXIcon } from "lucide-react";
import { useNavigate } from "react-router";
import { useAddMember, useCreateInvite, useInvites, useMe, useMembers, useRemoveMember, useRevokeInvite, useUpdateMember } from "../api/hooks";
import type { HouseholdRole, Invite, Member } from "../api/types";
import { useConfirm, usePrompt } from "../dialogs";
import { ErrorText, Field, FormError, PageHeader, PageIntro, SkeletonRows, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { SubmitButton } from "@/ui";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";
import { useHousehold } from "./HouseholdPages";
import { ChangePasswordDialog, SetMemberPasswordDialog } from "../auth/PasswordDialog";

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
  const updateMember = useUpdateMember(household.id);
  const navigate = useNavigate();
  const confirm = useConfirm();
  const prompt = usePrompt();
  const [passwordFor, setPasswordFor] = useState<Member | null>(null);
  const [changingOwn, setChangingOwn] = useState(false);

  async function editLegalName(member: Member) {
    const legalName = await prompt({
      title: `Legal name for ${member.displayName}`,
      description: "Used to spot bank transfers to or from them when banks write their legal name (e.g. NOVÁKOVÁ JANA).",
      label: "Legal name",
      initial: member.legalName ?? "",
      placeholder: "Optional",
    });
    if (legalName === null) return;
    updateMember.mutate({ userId: member.userId, displayName: member.displayName, legalName: legalName.trim() });
  }

  async function removeMember(member: Member) {
    const leaving = member.userId === me?.id;
    const ok = await confirm(
      leaving
        ? {
            title: `Leave ${household.name}?`,
            description: "You'll lose access until someone invites you again.",
            action: "Leave",
          }
        : {
            title: `Remove ${member.displayName} from ${household.name}?`,
            description:
              "Their accounts and expenses become shared." + (member.hasLogin ? "" : " They have no login, so they're deleted."),
            action: "Remove",
          },
    );
    if (!ok) return;
    remove.mutate({ userId: member.userId, leaving }, { onSuccess: () => leaving && navigate("/", { replace: true }) });
  }

  return (
    <>
      <PageHeader title="Members" />
      <PageIntro>
        Everyone here sees and edits the same accounts and expenses. Owners can invite and remove people, and add
        people without a login (a partner who hasn't signed up yet, a child) so things can be theirs; an invite can
        hand such a member over to a real account later.
      </PageIntro>

      {isOwner && <InviteForm claimable={(members.data ?? []).filter((m) => !m.hasLogin)} />}
      {isOwner && <AddMemberForm />}

      <Surface>
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={members.error} className="p-4" />
        {members.isPending && <SkeletonRows rows={2} />}
        {members.data && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Name</TableHead>
                <TableHead>Legal name</TableHead>
                <TableHead>Role</TableHead>
                <TableHead>
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {members.data.map((m) => (
                <TableRow key={m.userId}>
                  <TableCell className="font-medium">
                    <span className="flex items-center gap-2">
                      <Avatar members={members.data} userId={m.userId} className="size-6 text-[0.65rem]" />
                      {m.displayName}
                      {m.userId === me?.id && <span className="font-normal text-muted-foreground">(you)</span>}
                      {!m.hasLogin && <Chip icon={UserRoundXIcon}>No login</Chip>}
                    </span>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {isOwner ? (
                      <button
                        type="button"
                        onClick={() => editLegalName(m)}
                        className="text-left underline-offset-4 hover:underline"
                      >
                        {m.legalName || <span className="text-xs">Set legal name</span>}
                      </button>
                    ) : (
                      m.legalName || "—"
                    )}
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {m.role === "owner" ? <Chip tone="settlement" icon={CrownIcon}>{ROLE_LABEL[m.role]}</Chip> : ROLE_LABEL[m.role]}
                  </TableCell>
                  <TableCell className="text-right whitespace-nowrap">
                    {m.userId === me?.id ? (
                      <Button variant="ghost" size="sm" onClick={() => setChangingOwn(true)}>
                        Change password
                      </Button>
                    ) : (
                      isOwner &&
                      m.hasLogin && (
                        <Button variant="ghost" size="sm" onClick={() => setPasswordFor(m)}>
                          Set password
                        </Button>
                      )
                    )}
                    {(m.userId === me?.id || isOwner) && (
                      <Button variant="ghost" size="sm" className="text-destructive" onClick={() => removeMember(m)}>
                        {m.userId === me?.id ? "Leave" : "Remove"}
                      </Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </Surface>

      {isOwner && invites.data && invites.data.length > 0 && <PendingInvites invites={invites.data} />}
      <SetMemberPasswordDialog householdId={household.id} member={passwordFor} onOpenChange={(o) => !o && setPasswordFor(null)} />
      <ChangePasswordDialog open={changingOwn} onOpenChange={setChangingOwn} />
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
    <Surface className="p-4">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3" aria-label="Add someone without a login">
        <Field label="Name" name="displayName" error={add.error} className="w-56">
          <Input required placeholder="Without a login" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <SubmitButton variant="outline" disabled={add.isPending}>
          Add person
        </SubmitButton>
      </form>
      <FormError error={add.error} fields={["displayName"]} />
    </Surface>
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
    <Surface className="gap-4 p-4">
      <form onSubmit={submit} className="flex flex-wrap items-end gap-3" aria-label="Invite someone">
        <Field label="Email" name="email" error={create.error} className="w-64">
          <Input type="email" required placeholder="partner@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Role" name="role" error={create.error} className="w-36">
          <NativeSelect className="w-full" value={role} onChange={(e) => setRole(e.target.value as HouseholdRole)}>
            <NativeSelectOption value="member">Member</NativeSelectOption>
            <NativeSelectOption value="owner">Owner</NativeSelectOption>
          </NativeSelect>
        </Field>
        {claimable.length > 0 && (
          <Field label="Takes the place of" name="claimUserId" error={create.error} className="w-56">
            <NativeSelect className="w-full" value={claimUserId} onChange={(e) => setClaimUserId(e.target.value)}>
              <NativeSelectOption value="">Nobody (a new member)</NativeSelectOption>
              {claimable.map((m) => (
                <NativeSelectOption key={m.userId} value={m.userId}>
                  {m.displayName}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
        )}
        <SubmitButton disabled={create.isPending}>
          Create invite link
        </SubmitButton>
      </form>
      <FormError error={create.error} fields={["email", "role", "claimUserId"]} />
      {created?.token && (
        <div className="flex flex-col gap-3 rounded-lg bg-muted p-4 text-sm" role="status">
          <p>
            Send this link to <span className="font-medium">{created.email}</span>. It works once, for that address, until{" "}
            {formatDate(created.expiresAt)}.
            {created.claimName && <> Accepting it takes over {created.claimName}'s place, with everything that's theirs.</>}
          </p>
          <div className="flex gap-2">
            <Input
              readOnly
              aria-label="Invite link"
              className="amount bg-background"
              value={inviteLink(created.token)}
              onFocus={(e) => e.target.select()}
            />
            <Button type="button" onClick={() => copy(created.token!)}>
              {copied ? "Copied" : "Copy link"}
            </Button>
          </div>
        </div>
      )}
    </Surface>
  );
}

function PendingInvites({ invites }: { invites: Invite[] }) {
  const household = useHousehold();
  const revoke = useRevokeInvite(household.id);
  const now = Date.now();

  return (
    <Surface>
      <h2 className="border-b px-4 py-3 font-medium">Pending invites</h2>
      <ErrorText error={revoke.error} className="p-4" />
      <Table>
        <TableHeader>
          <TableRow className="hover:bg-transparent">
            <TableHead>Email</TableHead>
            <TableHead className="max-md:hidden">Role</TableHead>
            <TableHead>Expires</TableHead>
            <TableHead>
              <span className="sr-only">Actions</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {invites.map((invite) => {
            const expired = Date.parse(invite.expiresAt) <= now;
            return (
              <TableRow key={invite.id} className={cn(expired && "text-muted-foreground")}>
                <TableCell className="whitespace-normal font-medium">
                  {invite.email}
                  {invite.claimName && <span className="font-normal text-muted-foreground"> · as {invite.claimName}</span>}
                </TableCell>
                <TableCell className="text-muted-foreground max-md:hidden">{ROLE_LABEL[invite.role]}</TableCell>
                <TableCell className="text-muted-foreground">
                  {expired ? <Chip tone="warning" icon={ClockAlertIcon}>Expired</Chip> : formatDate(invite.expiresAt)}
                </TableCell>
                <TableCell className="text-right">
                  <Button variant="ghost" size="sm" className="text-destructive" onClick={() => revoke.mutate(invite.id)}>
                    {expired ? "Remove" : "Revoke"}
                  </Button>
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </Surface>
  );
}

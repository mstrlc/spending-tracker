import { useState, type FormEvent } from "react";
import { toast } from "sonner";
import { useChangePassword, useSetMemberPassword } from "../api/hooks";
import { Field, FormError } from "../ui";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

const MIN_LENGTH = 8;

type Props = { open: boolean; onOpenChange: (open: boolean) => void };

/** Changes the signed-in person's own password, which needs their current one. */
export function ChangePasswordDialog({ open, onOpenChange }: Props) {
  const change = useChangePassword();
  return (
    <PasswordDialog
      open={open}
      onOpenChange={(o) => {
        if (!o) change.reset();
        onOpenChange(o);
      }}
      title="Change your password"
      description="You stay signed in here; everywhere else signs out."
      askCurrent
      pending={change.isPending}
      error={change.error}
      onSubmit={(newPassword, currentPassword) =>
        change.mutate(
          { currentPassword, newPassword },
          {
            onSuccess: () => {
              onOpenChange(false);
              toast("Password changed");
            },
          },
        )
      }
    />
  );
}

/** An owner sets another member's password, e.g. when they've forgotten it. */
export function SetMemberPasswordDialog({
  householdId,
  member,
  onOpenChange,
}: {
  householdId: string;
  member: { userId: string; displayName: string } | null;
  onOpenChange: (open: boolean) => void;
}) {
  const set = useSetMemberPassword(householdId);
  // Keeps the name in the title while the dialog animates closed.
  const [shown, setShown] = useState(member);
  if (member && member !== shown) setShown(member);
  return (
    <PasswordDialog
      open={member !== null}
      onOpenChange={(o) => {
        if (!o) set.reset();
        onOpenChange(o);
      }}
      title={`Set a password for ${shown?.displayName ?? ""}`}
      description="They're signed out everywhere and sign in again with this one. Tell them what it is; they can change it afterwards."
      pending={set.isPending}
      error={set.error}
      onSubmit={(newPassword) =>
        member &&
        set.mutate(
          { userId: member.userId, newPassword },
          {
            onSuccess: () => {
              onOpenChange(false);
              toast(`${member.displayName}'s password set`);
            },
          },
        )
      }
    />
  );
}

type FormProps = {
  title: string;
  description: string;
  askCurrent?: boolean;
  pending: boolean;
  error: unknown;
  onSubmit: (newPassword: string, currentPassword: string) => void;
  onCancel: () => void;
};

function PasswordDialog({ open, onOpenChange, ...form }: Props & Omit<FormProps, "onCancel">) {
  // The content unmounts when closed, so every opening starts with empty fields.
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <PasswordForm {...form} onCancel={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  );
}

function PasswordForm({ title, description, askCurrent = false, pending, error, onSubmit, onCancel }: FormProps) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [repeat, setRepeat] = useState("");
  const [checked, setChecked] = useState(false);

  const tooShort = next.length < MIN_LENGTH ? `At least ${MIN_LENGTH} characters` : null;
  const mismatch = repeat !== next ? "Doesn't match the new password" : null;

  function submit(e: FormEvent) {
    e.preventDefault();
    setChecked(true);
    if (tooShort || mismatch) return;
    onSubmit(next, current);
  }

  return (
    <form onSubmit={submit} className="grid gap-4" noValidate>
      <DialogHeader>
        <DialogTitle>{title}</DialogTitle>
        <DialogDescription>{description}</DialogDescription>
      </DialogHeader>
      {askCurrent && (
        <Field label="Current password" name="currentPassword" error={error}>
          <Input
            type="password"
            autoComplete="current-password"
            autoFocus
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
          />
        </Field>
      )}
      <Field label="New password" name="newPassword" error={error} hint={checked ? tooShort : null}>
        <Input
          type="password"
          autoComplete="new-password"
          autoFocus={!askCurrent}
          value={next}
          onChange={(e) => setNext(e.target.value)}
        />
      </Field>
      <Field label="Repeat new password" name="repeatPassword" hint={checked ? mismatch : null}>
        <Input type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} />
      </Field>
      <FormError error={error} fields={["currentPassword", "newPassword"]} />
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" disabled={pending}>
          {askCurrent ? "Change password" : "Set password"}
        </Button>
      </DialogFooter>
    </form>
  );
}

import { Link, useNavigate, useParams } from "react-router";
import { useAcceptInvite, useInvitePreview, useLogout, useMe } from "../api/hooks";
import { ApiFailure } from "../api/errors";
import { ErrorText, Loading } from "../ui";
import { AuthShell } from "../auth/AuthShell";
import { Button } from "@/components/ui/button";

/** Where an invite link lands. Works logged out: it explains the invite and routes through log in or sign up. */
export function InvitePage() {
  const { token = "" } = useParams();
  const preview = useInvitePreview(token);
  const me = useMe();
  const accept = useAcceptInvite(token);
  const logout = useLogout();
  const navigate = useNavigate();
  const here = `/invite/${token}`;

  let body;
  if (preview.isPending || me.isPending) {
    body = <Loading />;
  } else if (preview.isError) {
    const gone = preview.error instanceof ApiFailure && ["invite_not_found", "invite_expired"].includes(preview.error.code);
    body = (
      <>
        <h1 className="text-xl font-medium">{gone ? "This invite can't be used" : "Something went wrong"}</h1>
        <ErrorText error={preview.error} />
        <Button asChild variant="outline" className="w-full">
          <Link to="/">Go to Spending Tracker</Link>
        </Button>
      </>
    );
  } else {
    const invite = preview.data;
    const user = me.data;
    const intro = (
      <>
        <h1 className="text-xl font-medium">Join {invite.householdName}</h1>
        <p className="text-sm text-muted-foreground">
          {invite.invitedBy ?? "Someone"} invited <span className="font-medium text-foreground">{invite.email}</span> to share this household's
          accounts and expenses{invite.role === "owner" ? " as an owner" : ""}.
          {invite.claimName && (
            <> You'll take over {invite.claimName}'s place: whatever is theirs in the household becomes yours.</>
          )}
        </p>
      </>
    );
    if (!user) {
      body = (
        <>
          {intro}
          <div className="flex flex-col gap-2">
            <Button asChild size="lg" className="w-full">
              <Link to="/signup" state={{ from: here, email: invite.email }}>
                Create an account
              </Link>
            </Button>
            <Button asChild size="lg" variant="outline" className="w-full">
              <Link to="/login" state={{ from: here, email: invite.email }}>
                Log in
              </Link>
            </Button>
          </div>
        </>
      );
    } else if (user.email.toLowerCase() !== invite.email.toLowerCase()) {
      body = (
        <>
          {intro}
          <p className="text-sm">
            You're logged in as <span className="font-medium">{user.email}</span>. Log in as {invite.email} to accept.
          </p>
          <Button
            size="lg"
            variant="outline"
            className="w-full"
            onClick={() => logout.mutate(undefined, { onSuccess: () => navigate("/login", { state: { from: here, email: invite.email } }) })}
          >
            Log in as someone else
          </Button>
        </>
      );
    } else {
      body = (
        <>
          {intro}
          <ErrorText error={accept.error} />
          <Button
            size="lg"
            className="w-full"
            disabled={accept.isPending}
            onClick={() => accept.mutate(undefined, { onSuccess: (h) => navigate(`/h/${h.id}/overview`, { replace: true }) })}
          >
            {accept.isPending ? "Joining…" : `Join ${invite.householdName}`}
          </Button>
        </>
      );
    }
  }

  return (
    <AuthShell>{body}</AuthShell>
  );
}

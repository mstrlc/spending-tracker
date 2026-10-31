import { Link, useNavigate, useParams } from "react-router";
import { useAcceptInvite, useInvitePreview, useLogout, useMe } from "../api/hooks";
import { ApiFailure } from "../api/errors";
import { ErrorText, Loading } from "../ui";

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
        <h1>{gone ? "This invite can't be used" : "Something went wrong"}</h1>
        <ErrorText error={preview.error} />
        <p className="auth-footer">
          <Link to="/">Go to Spending Tracker</Link>
        </p>
      </>
    );
  } else {
    const invite = preview.data;
    const user = me.data;
    const intro = (
      <>
        <h1>Join {invite.householdName}</h1>
        <p className="muted">
          {invite.invitedBy ?? "Someone"} invited <span className="strong">{invite.email}</span> to share this household's
          accounts and expenses{invite.role === "owner" ? " as an owner" : ""}.
        </p>
      </>
    );
    if (!user) {
      body = (
        <>
          {intro}
          <div className="stack">
            <Link className="button block" to="/signup" state={{ from: here, email: invite.email }}>
              Create an account
            </Link>
            <Link className="button secondary block" to="/login" state={{ from: here, email: invite.email }}>
              Log in
            </Link>
          </div>
        </>
      );
    } else if (user.email.toLowerCase() !== invite.email.toLowerCase()) {
      body = (
        <>
          {intro}
          <p>
            You're logged in as <span className="strong">{user.email}</span>. Log in as {invite.email} to accept.
          </p>
          <button
            className="block secondary"
            onClick={() => logout.mutate(undefined, { onSuccess: () => navigate("/login", { state: { from: here, email: invite.email } }) })}
          >
            Log in as someone else
          </button>
        </>
      );
    } else {
      body = (
        <>
          {intro}
          <ErrorText error={accept.error} />
          <button
            className="block"
            disabled={accept.isPending}
            onClick={() => accept.mutate(undefined, { onSuccess: (h) => navigate(`/h/${h.id}/overview`, { replace: true }) })}
          >
            {accept.isPending ? "Joining…" : `Join ${invite.householdName}`}
          </button>
        </>
      );
    }
  }

  return (
    <main className="auth">
      <div className="auth-panel">
        <p className="wordmark">Spending Tracker</p>
        {body}
      </div>
    </main>
  );
}

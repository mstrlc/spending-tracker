import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { useLogin, useMe, useSignup } from "../api/hooks";
import { Field, FormError } from "../ui";

function useAfterAuth() {
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from;
  return () => navigate(from ?? "/", { replace: true });
}

export function LoginPage() {
  const me = useMe();
  const login = useLogin();
  const done = useAfterAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  if (me.data) return <Navigate to="/" replace />;

  function submit(e: FormEvent) {
    e.preventDefault();
    login.mutate({ email, password }, { onSuccess: done });
  }

  return (
    <main className="auth">
      <h1>Log in</h1>
      <form onSubmit={submit} className="stack">
        <Field label="Email" name="email" error={login.error}>
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password" name="password" error={login.error}>
          <input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <FormError error={login.error} fields={["email", "password"]} />
        <button type="submit" disabled={login.isPending}>
          {login.isPending ? "Logging in…" : "Log in"}
        </button>
      </form>
      <p className="muted">
        No account yet? <Link to="/signup">Sign up</Link>
      </p>
    </main>
  );
}

export function SignupPage() {
  const me = useMe();
  const signup = useSignup();
  const done = useAfterAuth();
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  if (me.data) return <Navigate to="/" replace />;

  function submit(e: FormEvent) {
    e.preventDefault();
    signup.mutate({ email, password, displayName }, { onSuccess: done });
  }

  return (
    <main className="auth">
      <h1>Create your account</h1>
      <form onSubmit={submit} className="stack">
        <Field label="Your name" name="displayName" error={signup.error}>
          <input autoComplete="name" required value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </Field>
        <Field label="Email" name="email" error={signup.error}>
          <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password (at least 8 characters)" name="password" error={signup.error}>
          <input
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <FormError error={signup.error} fields={["email", "password", "displayName"]} />
        <button type="submit" disabled={signup.isPending}>
          {signup.isPending ? "Creating account…" : "Sign up"}
        </button>
      </form>
      <p className="muted">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </main>
  );
}

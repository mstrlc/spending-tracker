import { useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { useLogin, useMe, useSignup } from "../api/hooks";
import { Field, FormError } from "../ui";
import { AuthShell } from "./AuthShell";
import { SubmitButton } from "@/ui";
import { Input } from "@/components/ui/input";

/** Router state the auth pages accept: where to go afterwards, and an email to prefill (from an invite). */
type AuthState = { from?: string; email?: string } | null;

function useAuthState(): AuthState {
  return useLocation().state as AuthState;
}

function useAfterAuth() {
  const navigate = useNavigate();
  const from = useAuthState()?.from;
  return () => navigate(from ?? "/", { replace: true });
}

export function LoginPage() {
  const me = useMe();
  const login = useLogin();
  const done = useAfterAuth();
  const state = useAuthState();
  const [email, setEmail] = useState(state?.email ?? "");
  const [password, setPassword] = useState("");

  if (me.data) return <Navigate to={state?.from ?? "/"} replace />;

  function submit(e: FormEvent) {
    e.preventDefault();
    login.mutate({ email, password }, { onSuccess: done });
  }

  return (
    <AuthShell
      title="Log in"
      footer={
        <>
          No account yet?{" "}
          <Link to="/signup" state={state}>
            Sign up
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Email" name="email" error={login.error}>
          <Input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password" name="password" error={login.error}>
          <Input
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <FormError error={login.error} fields={["email", "password"]} />
        <SubmitButton size="lg" className="w-full" disabled={login.isPending}>
          {login.isPending ? "Logging in…" : "Log in"}
        </SubmitButton>
      </form>
    </AuthShell>
  );
}

export function SignupPage() {
  const me = useMe();
  const signup = useSignup();
  const done = useAfterAuth();
  const state = useAuthState();
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState(state?.email ?? "");
  const [password, setPassword] = useState("");

  if (me.data) return <Navigate to={state?.from ?? "/"} replace />;

  function submit(e: FormEvent) {
    e.preventDefault();
    signup.mutate({ email, password, displayName }, { onSuccess: done });
  }

  return (
    <AuthShell
      title="Create your account"
      footer={
        <>
          Already have an account?{" "}
          <Link to="/login" state={state}>
            Log in
          </Link>
        </>
      }
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Your name" name="displayName" error={signup.error}>
          <Input autoComplete="name" required value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </Field>
        <Field label="Email" name="email" error={signup.error}>
          <Input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password (at least 8 characters)" name="password" error={signup.error}>
          <Input
            type="password"
            autoComplete="new-password"
            required
            minLength={8}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <FormError error={signup.error} fields={["email", "password", "displayName"]} />
        <SubmitButton size="lg" className="w-full" disabled={signup.isPending}>
          {signup.isPending ? "Creating account…" : "Sign up"}
        </SubmitButton>
      </form>
    </AuthShell>
  );
}

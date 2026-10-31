import type { ReactNode } from "react";
import { ApiFailure, errorMessage } from "./api/errors";

export function Loading() {
  return <p className="muted">Loading…</p>;
}

/** Inline error for a failed query or mutation; renders nothing when there is no error. */
export function ErrorText({ error }: { error: unknown }) {
  if (!error) return null;
  return (
    <p role="alert" className="error">
      {errorMessage(error)}
    </p>
  );
}

/** A labelled form control. Shows the API error when it is about this field. */
export function Field({ label, name, error, children }: { label: string; name: string; error?: unknown; children: ReactNode }) {
  const fieldError = error instanceof ApiFailure && error.field === name ? error.message : null;
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {fieldError && <span className="field-error">{fieldError}</span>}
    </label>
  );
}

/** The mutation error, unless a Field is already showing it. */
export function FormError({ error, fields }: { error: unknown; fields: string[] }) {
  if (error instanceof ApiFailure && error.field && fields.includes(error.field)) return null;
  return <ErrorText error={error} />;
}

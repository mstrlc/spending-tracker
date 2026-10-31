import { useEffect, useId, useRef, type ReactNode } from "react";
import { ApiFailure, errorMessage } from "./api/errors";
import { Close } from "./icons";

/** Title on the left, actions on the right: the header of every page. */
export function PageHeader({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <div className="page-header">
      <h1>{title}</h1>
      {children && <div className="page-header-actions">{children}</div>}
    </div>
  );
}

export function Loading() {
  return <p className="muted">Loading…</p>;
}

/** Placeholder rows shaped like the table that will replace them. */
export function SkeletonRows({ rows = 6 }: { rows?: number }) {
  return (
    <div className="skeleton" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton-row">
          <span style={{ width: "12%" }} />
          <span style={{ width: `${28 + ((i * 13) % 20)}%` }} />
          <span style={{ width: "16%" }} />
          <span style={{ width: "10%", marginLeft: "auto" }} />
        </div>
      ))}
    </div>
  );
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
export function Field({
  label,
  name,
  error,
  hint,
  children,
}: {
  label: string;
  name: string;
  error?: unknown;
  hint?: string | null;
  children: ReactNode;
}) {
  const fieldError = error instanceof ApiFailure && error.field === name ? error.message : (hint ?? null);
  return (
    <label className="field">
      <span className="field-label">{label}</span>
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

/**
 * A side sheet on a native modal <dialog>: focus stays inside, Esc and the backdrop close it.
 * Children mount only while open, so forms start fresh each time; mark the field to focus with data-autofocus.
 */
export function Sheet({
  open,
  onClose,
  title,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) {
      dialog.showModal();
      // showModal focuses the first control (Close); move to the field the content marks instead.
      dialog.querySelector<HTMLElement>("[data-autofocus]")?.focus();
    }
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="sheet"
      aria-labelledby={titleId}
      onClose={onClose}
      onClick={(e) => {
        // A click on the <dialog> itself (not its content) landed on the backdrop.
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="sheet-body">
        <div className="sheet-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
            <Close />
          </button>
        </div>
        {open && children}
      </div>
    </dialog>
  );
}

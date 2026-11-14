import { createContext, useContext, useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";
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

/** Where the layout puts an open Sheet: a column beside the page that pushes it narrower. */
export const SheetSlot = createContext<HTMLElement | null>(null);

/**
 * A side panel in the layout's sheet column, beside the page rather than over it. Esc and Close close it.
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
  const slot = useContext(SheetSlot);
  const ref = useRef<HTMLElement>(null);
  const titleId = useId();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    const panel = ref.current;
    (panel?.querySelector<HTMLElement>("[data-autofocus]") ?? panel?.querySelector<HTMLElement>("button"))?.focus();
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !e.defaultPrevented) closeRef.current();
    }
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      // Hand focus back to what opened the sheet, unless the person has since moved on to something else.
      if (panel?.contains(document.activeElement) || document.activeElement === document.body) opener?.focus();
    };
  }, [open]);

  if (!open || !slot) return null;
  return createPortal(
    <aside ref={ref} className="sheet" aria-labelledby={titleId}>
      <div className="sheet-body">
        <div className="sheet-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button" aria-label="Close" onClick={onClose}>
            <Close />
          </button>
        </div>
        {children}
      </div>
    </aside>,
    slot,
  );
}

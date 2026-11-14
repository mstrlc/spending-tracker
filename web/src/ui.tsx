import {
  cloneElement,
  createContext,
  isValidElement,
  useContext,
  useEffect,
  useId,
  useRef,
  type ReactElement,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { EllipsisIcon, XIcon } from "lucide-react";
import { ApiFailure, errorMessage } from "./api/errors";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Field as FieldRoot, FieldError, FieldLabel } from "@/components/ui/field";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";

/** Title on the left, actions on the right: the header of every page. */
export function PageHeader({ title, children }: { title: ReactNode; children?: ReactNode }) {
  return (
    <div className="flex min-h-9 flex-wrap items-center justify-between gap-3">
      <h1 className="text-2xl font-medium">{title}</h1>
      {children && <div className="flex items-center gap-2">{children}</div>}
    </div>
  );
}

/** The muted paragraph under a page title saying what the page is for. */
export function PageIntro({ children }: { children: ReactNode }) {
  return <p className="max-w-prose text-sm text-muted-foreground">{children}</p>;
}

export function Loading() {
  return <p className="p-4 text-sm text-muted-foreground">Loading…</p>;
}

/** Placeholder rows shaped like the table that will replace them. */
export function SkeletonRows({ rows = 6 }: { rows?: number }) {
  return (
    <div className="divide-y" aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="flex items-center gap-4 px-4 py-3">
          <Skeleton className="h-4 w-[12%]" />
          <Skeleton className="h-4" style={{ width: `${28 + ((i * 13) % 20)}%` }} />
          <Skeleton className="h-4 w-[16%]" />
          <Skeleton className="ml-auto h-4 w-[10%]" />
        </div>
      ))}
    </div>
  );
}

/** Inline error for a failed query or mutation; renders nothing when there is no error. */
export function ErrorText({ error, className }: { error: unknown; className?: string }) {
  if (!error) return null;
  return (
    <p role="alert" className={cn("text-sm text-destructive", className)}>
      {errorMessage(error)}
    </p>
  );
}

/**
 * A labelled form control. Shows the API error when it is about this field, otherwise [hint] (a local
 * validation message). The single child gets the id the label points at.
 */
export function Field({
  label,
  name,
  error,
  hint,
  className,
  children,
}: {
  label: string;
  name: string;
  error?: unknown;
  hint?: string | null;
  className?: string;
  children: ReactElement<{ id?: string; "aria-invalid"?: boolean; "aria-describedby"?: string }>;
}) {
  const id = useId();
  const errorId = `${id}-error`;
  const fieldError = error instanceof ApiFailure && error.field === name ? error.message : (hint ?? null);
  const control = isValidElement(children)
    ? cloneElement(children, {
        id,
        "aria-invalid": fieldError ? true : children.props["aria-invalid"],
        "aria-describedby": fieldError ? errorId : undefined,
      })
    : children;
  return (
    <FieldRoot className={cn("gap-1.5", className)} data-invalid={fieldError ? true : undefined}>
      <FieldLabel htmlFor={id} className="text-muted-foreground group-data-[invalid=true]/field:text-destructive">
        {label}
      </FieldLabel>
      {control}
      {fieldError && <FieldError id={errorId}>{fieldError}</FieldError>}
    </FieldRoot>
  );
}

/** The mutation error, unless a Field is already showing it. */
export function FormError({ error, fields }: { error: unknown; fields: string[] }) {
  if (error instanceof ApiFailure && error.field && fields.includes(error.field)) return null;
  return <ErrorText error={error} />;
}

/** The card a page's table or list sits on, edge to edge with no padding of its own. */
export function Surface({ className, children }: { className?: string; children: ReactNode }) {
  return <Card className={cn("gap-0 overflow-hidden py-0", className)}>{children}</Card>;
}

/** What a surface shows while it has nothing in it, with the action that fills it. */
export function EmptyState({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-1 px-6 py-12 text-center">
      <p className="font-medium">{title}</p>
      <p className="max-w-sm text-sm text-muted-foreground">{children}</p>
      {action && <div className="mt-3">{action}</div>}
    </div>
  );
}

/** An amount in the mono face, with the currency muted after it. */
export function Money({ children, currency }: { children: ReactNode; currency?: string }) {
  return (
    <span className="amount">
      {children}
      {currency && <span className="ml-1 text-[0.6em] font-normal text-muted-foreground">{currency}</span>}
    </span>
  );
}

/** The figures at the top of a page: the first one large, the rest beside it. */
export function Summary({ items }: { items: ({ label: ReactNode; value: ReactNode } | false | null)[] }) {
  const shown = items.filter((i): i is { label: ReactNode; value: ReactNode } => !!i);
  return (
    <dl className="flex flex-wrap items-end gap-x-10 gap-y-4">
      {shown.map((item, i) => (
        <div key={i} className={cn("flex flex-col gap-1", i === 0 && "basis-full sm:basis-auto")}>
          <dt className="text-sm text-muted-foreground">{item.label}</dt>
          <dd className={i === 0 ? "text-3xl font-medium" : "text-xl"}>{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export type RowAction = { label: string; onSelect: () => void; destructive?: boolean };

/** A row's actions behind a "more" button; destructive ones come last, after a separator. */
export function RowMenu({ label, actions }: { label: string; actions: RowAction[] }) {
  const plain = actions.filter((a) => !a.destructive);
  const destructive = actions.filter((a) => a.destructive);
  return (
    <DropdownMenu modal={false}>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={`Actions for ${label}`} onClick={(e) => e.stopPropagation()}>
          <EllipsisIcon />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        className="min-w-36"
        // An action that moved focus (a rename field, a confirmation) keeps it; otherwise it returns to the button.
        onCloseAutoFocus={(e) => {
          const active = document.activeElement;
          if (active && active !== document.body && !active.closest("[data-slot=dropdown-menu-content]")) e.preventDefault();
        }}
      >
        {plain.map((a) => (
          <DropdownMenuItem key={a.label} onSelect={a.onSelect}>
            {a.label}
          </DropdownMenuItem>
        ))}
        {plain.length > 0 && destructive.length > 0 && <DropdownMenuSeparator />}
        {destructive.map((a) => (
          <DropdownMenuItem key={a.label} variant="destructive" onSelect={a.onSelect}>
            {a.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Where the layout puts an open Sheet: a column beside the page that pushes it narrower. */
export const SheetSlot = createContext<HTMLElement | null>(null);

/** True while a modal dialog (a confirmation over the side panel, say) is open. */
const dialogOpen = () => document.querySelector("[data-slot=alert-dialog-content], [data-slot=dialog-content]") !== null;

/** True while a side panel is open. Page shortcuts stay quiet then. */
export const sidePanelOpen = () => document.querySelector("[data-slot=side-panel]") !== null || dialogOpen();

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
      // A dialog over the panel takes Esc for itself.
      if (e.key === "Escape" && !e.defaultPrevented && !dialogOpen()) closeRef.current();
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
    <aside
      ref={ref}
      data-slot="side-panel"
      aria-labelledby={titleId}
      className="flex min-h-full flex-col bg-background animate-panel-in max-md:fixed max-md:inset-0 max-md:z-40 max-md:overflow-y-auto"
    >
      <div className="sticky top-0 z-10 flex items-center justify-between gap-2 border-b bg-background px-5 py-3">
        <h2 id={titleId} className="truncate text-base font-medium">
          {title}
        </h2>
        <Button type="button" variant="ghost" size="icon" aria-label="Close" onClick={onClose}>
          <XIcon />
        </Button>
      </div>
      {children}
    </aside>,
    slot,
  );
}

/** A form or view inside a Sheet: fields on top, actions in a footer that sticks to the window's bottom. */
export function SheetBody({ children, footer }: { children: ReactNode; footer?: ReactNode }) {
  return (
    <>
      <div className="flex flex-1 flex-col gap-4 px-5 py-5">{children}</div>
      {footer && (
        <div className="sticky bottom-0 flex items-center gap-2 border-t bg-background px-5 py-3">{footer}</div>
      )}
    </>
  );
}

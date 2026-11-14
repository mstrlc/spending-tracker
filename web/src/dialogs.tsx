import { createContext, useCallback, useContext, useRef, useState, type FormEvent, type ReactNode } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

type ConfirmOptions = {
  title: string;
  description?: ReactNode;
  /** The button that goes ahead, "Delete" by default. */
  action?: string;
};

type PromptOptions = {
  title: string;
  description?: ReactNode;
  label: string;
  initial?: string;
  placeholder?: string;
};

type Request =
  | ({ kind: "confirm"; resolve: (ok: boolean) => void } & ConfirmOptions)
  | ({ kind: "prompt"; resolve: (value: string | null) => void } & PromptOptions);

const DialogsContext = createContext<{
  confirm: (options: ConfirmOptions) => Promise<boolean>;
  prompt: (options: PromptOptions) => Promise<string | null>;
} | null>(null);

/** Asks the person to confirm a destructive action; resolves true when they go ahead. */
export function useConfirm() {
  const dialogs = useContext(DialogsContext);
  if (!dialogs) throw new Error("useConfirm outside DialogsProvider");
  return dialogs.confirm;
}

/** Asks for one line of text; resolves null when they cancel. */
export function usePrompt() {
  const dialogs = useContext(DialogsContext);
  if (!dialogs) throw new Error("usePrompt outside DialogsProvider");
  return dialogs.prompt;
}

/** Hosts the confirmation and prompt dialogs that useConfirm and usePrompt open. */
export function DialogsProvider({ children }: { children: ReactNode }) {
  const [request, setRequest] = useState<Request | null>(null);
  const [open, setOpen] = useState(false);
  const [value, setValue] = useState("");
  // Settled once: closing after an answer must not also answer "cancel".
  const settled = useRef(false);

  const confirm = useCallback(
    (options: ConfirmOptions) =>
      new Promise<boolean>((resolve) => {
        settled.current = false;
        setRequest({ kind: "confirm", resolve, ...options });
        setOpen(true);
      }),
    [],
  );
  const prompt = useCallback(
    (options: PromptOptions) =>
      new Promise<string | null>((resolve) => {
        settled.current = false;
        setValue(options.initial ?? "");
        setRequest({ kind: "prompt", resolve, ...options });
        setOpen(true);
      }),
    [],
  );

  function answer(result: boolean | string | null) {
    if (!request || settled.current) return;
    settled.current = true;
    if (request.kind === "confirm") request.resolve(result === true);
    else request.resolve(typeof result === "string" ? result : null);
    setOpen(false);
  }

  function submitPrompt(e: FormEvent) {
    e.preventDefault();
    answer(value);
  }

  return (
    <DialogsContext.Provider value={{ confirm, prompt }}>
      {children}
      {request?.kind === "confirm" && (
        <AlertDialog open={open} onOpenChange={(o) => !o && answer(false)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{request.title}</AlertDialogTitle>
              {request.description && <AlertDialogDescription>{request.description}</AlertDialogDescription>}
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction variant="destructive" onClick={() => answer(true)}>
                {request.action ?? "Delete"}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      )}
      {request?.kind === "prompt" && (
        <Dialog open={open} onOpenChange={(o) => !o && answer(null)}>
          <DialogContent className="sm:max-w-md">
            <form onSubmit={submitPrompt} className="grid gap-4">
              <DialogHeader>
                <DialogTitle>{request.title}</DialogTitle>
                {request.description && <DialogDescription>{request.description}</DialogDescription>}
              </DialogHeader>
              <div className="grid gap-1.5">
                <Label htmlFor="prompt-value" className="text-muted-foreground">
                  {request.label}
                </Label>
                <Input
                  id="prompt-value"
                  autoFocus
                  placeholder={request.placeholder}
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                />
              </div>
              <DialogFooter>
                <Button type="button" variant="outline" onClick={() => answer(null)}>
                  Cancel
                </Button>
                <Button type="submit">Save</Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
      )}
    </DialogsContext.Provider>
  );
}

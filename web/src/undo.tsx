import { useEffect, useRef, useSyncExternalStore } from "react";
import { useLocation } from "react-router";
import { toast } from "sonner";
import { errorMessage } from "./api/errors";

const UNDO_MS = 6000;

export interface SaveMutation<TData extends { id: string; version: number }, TInput> {
  mutate: (
    vars: { id?: string; input: TInput },
    options?: { onSuccess?: (data: TData) => void; onError?: (error: unknown) => void },
  ) => void;
  isPending: boolean;
  error: Error | null;
}

export interface DeleteMutation {
  mutate: (
    vars: { id: string; version: number },
    options?: { onSuccess?: () => void; onError?: (error: unknown) => void },
  ) => void;
  isPending: boolean;
  error: Error | null;
}

const pendingDeletes = new Map<string, () => void>();
let snapshot = new Set<string>();
const listeners = new Set<() => void>();

function notify() {
  snapshot = new Set(pendingDeletes.keys());
  for (const listener of listeners) listener();
}

function addPending(id: string, flush: () => void) {
  pendingDeletes.set(id, flush);
  notify();
}

function removePending(id: string) {
  if (!pendingDeletes.has(id)) return;
  pendingDeletes.delete(id);
  notify();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

function getSnapshot() {
  return snapshot;
}

/** Sends all pending delayed deletes immediately (e.g. on navigation or pagehide). */
export function flushPendingDeletes() {
  for (const flush of [...pendingDeletes.values()]) flush();
}

/** Reset pending deletes without flushing (for test cleanup). */
export function clearPendingDeletes() {
  pendingDeletes.clear();
  notify();
}

/** Returns the set of record IDs currently hidden pending a delayed delete. */
export function usePendingDeleteIds(): Set<string> {
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

/** True while [id] is hidden pending a delayed delete. */
export function useIsPendingDelete(id: string): boolean {
  return usePendingDeleteIds().has(id);
}

/** Flushes pending deletes when the route changes or the page is hidden/unloaded. Mount once in the layout. */
export function UndoFlusher() {
  const { pathname } = useLocation();
  const prevPath = useRef(pathname);

  useEffect(() => {
    if (prevPath.current !== pathname) {
      prevPath.current = pathname;
      flushPendingDeletes();
    }
  }, [pathname]);

  useEffect(() => {
    const onPageHide = () => flushPendingDeletes();
    window.addEventListener("pagehide", onPageHide);
    return () => {
      window.removeEventListener("pagehide", onPageHide);
      flushPendingDeletes();
    };
  }, []);

  return null;
}

/**
 * Wraps a save mutation to show an Undo toast for ~6s after create or edit.
 * Create -> Undo deletes the new record; Edit -> Undo restores [previous] with the new version.
 */
export function useUndoableSave<TData extends { id: string; version: number }, TInput extends { version?: number | null }>(
  label: string,
  save: SaveMutation<TData, TInput>,
  del: DeleteMutation,
) {
  function mutate(
    vars: { id?: string; input: TInput; previous?: TInput },
    options?: { onSuccess?: (data: TData) => void; onError?: (error: unknown) => void },
  ) {
    const { id, input, previous } = vars;
    save.mutate(
      { id, input },
      {
        onError: options?.onError,
        onSuccess: (saved) => {
          options?.onSuccess?.(saved);
          if (id === undefined) {
            toast(`${label} saved`, {
              duration: UNDO_MS,
              action: {
                label: "Undo",
                onClick: () => {
                  del.mutate(
                    { id: saved.id, version: saved.version },
                    { onError: (err) => toast.error(errorMessage(err)) },
                  );
                },
              },
            });
          } else if (previous !== undefined) {
            toast(`${label} saved`, {
              duration: UNDO_MS,
              action: {
                label: "Undo",
                onClick: () => {
                  save.mutate(
                    { id, input: { ...previous, version: saved.version } },
                    { onError: (err) => toast.error(errorMessage(err)) },
                  );
                },
              },
            });
          }
        },
      },
    );
  }

  return { mutate, isPending: save.isPending, error: save.error };
}

/**
 * Wraps a delete mutation so rows are hidden immediately and the DELETE is sent
 * only when the ~6s Undo toast closes, the user navigates away, or the page unloads.
 */
export function useUndoableDelete(label: string, del: DeleteMutation) {
  function schedule(
    vars: { id: string; version: number },
    options?: { onError?: (error: unknown) => void },
  ) {
    const { id, version } = vars;
    let settled = false;
    let toastId: string | number = "";

    const flush = () => {
      if (settled) return;
      settled = true;
      toast.dismiss(toastId);
      del.mutate(
        { id, version },
        {
          onSuccess: () => {
            removePending(id);
          },
          onError: (err) => {
            removePending(id);
            toast.error(errorMessage(err));
            options?.onError?.(err);
          },
        },
      );
    };

    const undo = () => {
      if (settled) return;
      settled = true;
      removePending(id);
      toast.dismiss(toastId);
    };

    addPending(id, flush);
    toastId = toast(`${label} deleted`, {
      duration: UNDO_MS,
      action: {
        label: "Undo",
        onClick: undo,
      },
      onAutoClose: flush,
      onDismiss: flush,
    });
  }

  return { schedule, isPending: del.isPending, error: del.error };
}

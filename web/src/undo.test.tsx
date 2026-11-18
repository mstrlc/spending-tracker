import { afterEach, expect, test, vi } from "vitest";
import { act, cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { Link, Route, Routes } from "react-router";
import { Toaster, toast } from "sonner";
import { useDeleteTrip, useSaveTrip, useTrips } from "./api/hooks";
import type { Trip, TripInput } from "./api/types";
import { DialogsProvider } from "./dialogs";
import { renderWithProviders, stubApi } from "./test-utils";
import { clearPendingDeletes, UndoFlusher, usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "./undo";

afterEach(() => {
  toast.dismiss();
  clearPendingDeletes();
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

function TripHarness() {
  const trips = useTrips("h1");
  const saveTrip = useSaveTrip("h1");
  const deleteTrip = useDeleteTrip("h1");
  const save = useUndoableSave("Trip", saveTrip, deleteTrip);
  const remove = useUndoableDelete("Trip", deleteTrip);
  const pendingDeletes = usePendingDeleteIds();
  const rows = (trips.data ?? []).filter((t) => !pendingDeletes.has(t.id));

  return (
    <div>
      <button
        type="button"
        onClick={() => save.mutate({ input: { name: "Alps", startDate: null, endDate: null } })}
      >
        Create trip
      </button>
      {rows.map((t) => (
        <div key={t.id}>
          <span>{t.name}</span>
          <button
            type="button"
            onClick={() => {
              const previous: TripInput = {
                name: t.name,
                startDate: t.startDate,
                endDate: t.endDate,
                notes: t.notes,
                version: t.version,
              };
              save.mutate({ id: t.id, input: { ...previous, name: "Dolomites" }, previous });
            }}
          >
            Rename {t.name}
          </button>
          <button type="button" onClick={() => remove.schedule({ id: t.id, version: t.version })}>
            Delete {t.name}
          </button>
        </div>
      ))}
      <Link to="/other">Go elsewhere</Link>
    </div>
  );
}

function renderHarness(routes: Record<string, (request: Request) => Response | Promise<Response>>) {
  const fetchMock = stubApi({
    "GET /api/households/h1/trips": () =>
      Response.json([
        { id: "t1", name: "Prague", startDate: null, endDate: null, notes: "", version: 1, spentMinor: 0, expenseCount: 0 } satisfies Trip,
      ]),
    ...routes,
  });

  renderWithProviders(
    <DialogsProvider>
      <Toaster />
      <UndoFlusher />
      <Routes>
        <Route path="/" element={<TripHarness />} />
        <Route path="/other" element={<div>Other page</div>} />
      </Routes>
    </DialogsProvider>,
    "/",
  );

  return fetchMock;
}

test("create then undo calls delete with returned id and version", async () => {
  const deleted: string[] = [];
  renderHarness({
    "POST /api/households/h1/trips": () =>
      Response.json({ id: "t2", name: "Alps", startDate: null, endDate: null, notes: "", version: 1, spentMinor: 0, expenseCount: 0 }),
    "DELETE /api/households/h1/trips/t2": (req) => {
      deleted.push(new URL(req.url).searchParams.get("version") ?? "");
      return new Response(null, { status: 204 });
    },
  });

  fireEvent.click(await screen.findByRole("button", { name: "Create trip" }));
  const undo = await screen.findByRole("button", { name: "Undo" });
  fireEvent.click(undo);

  await waitFor(() => expect(deleted).toEqual(["1"]));
});

test("edit then undo puts previous values with the new version", async () => {
  const puts: TripInput[] = [];
  renderHarness({
    "PUT /api/households/h1/trips/t1": async (req) => {
      const body = (await req.json()) as TripInput;
      puts.push(body);
      return Response.json({
        id: "t1",
        name: body.name,
        startDate: null,
        endDate: null,
        notes: "",
        version: (body.version ?? 1) + 1,
        spentMinor: 0,
        expenseCount: 0,
      });
    },
  });

  fireEvent.click(await screen.findByRole("button", { name: "Rename Prague" }));
  const undo = await screen.findByRole("button", { name: "Undo" });
  fireEvent.click(undo);

  await waitFor(() => expect(puts).toHaveLength(2));
  expect(puts[0]).toMatchObject({ name: "Dolomites", version: 1 });
  expect(puts[1]).toMatchObject({ name: "Prague", version: 2 });
});

test("delete hides the row without sending DELETE immediately, and undo restores it", async () => {
  let deleteCalls = 0;
  renderHarness({
    "DELETE /api/households/h1/trips/t1": () => {
      deleteCalls++;
      return new Response(null, { status: 204 });
    },
  });

  expect(await screen.findByText("Prague")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Delete Prague" }));

  expect(screen.queryByText("Prague")).toBeNull();
  expect(deleteCalls).toBe(0);

  const undo = await screen.findByRole("button", { name: "Undo" });
  fireEvent.click(undo);

  expect(await screen.findByText("Prague")).toBeTruthy();
  expect(deleteCalls).toBe(0);
});

test("delete flushes when the toast closes after 6s", async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  let deleteCalls = 0;
  renderHarness({
    "DELETE /api/households/h1/trips/t1": () => {
      deleteCalls++;
      return new Response(null, { status: 204 });
    },
  });

  expect(await screen.findByText("Prague")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Delete Prague" }));
  expect(deleteCalls).toBe(0);

  await act(async () => {
    await vi.advanceTimersByTimeAsync(6500);
  });

  await waitFor(() => expect(deleteCalls).toBe(1));
});

test("navigating away flushes pending deletes", async () => {
  let deleteCalls = 0;
  renderHarness({
    "DELETE /api/households/h1/trips/t1": () => {
      deleteCalls++;
      return new Response(null, { status: 204 });
    },
  });

  expect(await screen.findByText("Prague")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Delete Prague" }));
  expect(deleteCalls).toBe(0);

  fireEvent.click(screen.getByRole("link", { name: "Go elsewhere" }));
  await screen.findByText("Other page");
  await waitFor(() => expect(deleteCalls).toBe(1));
});

test("failed delayed delete restores the row and shows the error", async () => {
  renderHarness({
    "DELETE /api/households/h1/trips/t1": () =>
      Response.json({ code: "trip_in_use", message: "That trip is used by expenses." }, { status: 409 }),
  });

  expect(await screen.findByText("Prague")).toBeTruthy();
  fireEvent.click(screen.getByRole("button", { name: "Delete Prague" }));
  expect(screen.queryByText("Prague")).toBeNull();

  await act(async () => {
    window.dispatchEvent(new Event("pagehide"));
  });

  expect(await screen.findByText("Prague")).toBeTruthy();
  expect(await screen.findByText("That trip is used by expenses.")).toBeTruthy();
});

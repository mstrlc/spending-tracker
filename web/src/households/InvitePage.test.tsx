import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { renderWithProviders, stubApi } from "../test-utils";
import { InvitePage } from "./InvitePage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const preview = { householdName: "Home", invitedBy: "Alice", email: "bob@example.com", role: "member", expiresAt: "2026-11-07T12:00:00Z" };

function renderInvite() {
  renderWithProviders(
    <Routes>
      <Route path="/invite/:token" element={<InvitePage />} />
      <Route path="/h/:householdId/overview" element={<p>Overview page</p>} />
    </Routes>,
    "/invite/tok",
  );
}

test("logged out, the invite offers sign up and log in", async () => {
  stubApi({
    "GET /api/invites/tok": () => Response.json(preview),
    "GET /api/auth/me": () => Response.json({ code: "unauthorized" }, { status: 401 }),
  });
  renderInvite();

  expect(await screen.findByRole("heading", { name: "Join Home" })).toBeTruthy();
  expect(screen.getByRole("link", { name: "Create an account" })).toBeTruthy();
  expect(screen.getByRole("link", { name: "Log in" })).toBeTruthy();
});

test("the invited user joins and lands in the household", async () => {
  stubApi({
    "GET /api/invites/tok": () => Response.json(preview),
    "GET /api/auth/me": () => Response.json({ id: "u2", email: "Bob@example.com", displayName: "Bob" }),
    "POST /api/invites/tok/accept": () => Response.json({ id: "h1", name: "Home", currency: "CZK", role: "member" }),
  });
  renderInvite();

  fireEvent.click(await screen.findByRole("button", { name: "Join Home" }));

  expect(await screen.findByText("Overview page")).toBeTruthy();
});

test("someone logged in with another address is told to switch accounts", async () => {
  stubApi({
    "GET /api/invites/tok": () => Response.json(preview),
    "GET /api/auth/me": () => Response.json({ id: "u3", email: "carol@example.com", displayName: "Carol" }),
  });
  renderInvite();

  expect(await screen.findByRole("button", { name: "Log in as someone else" })).toBeTruthy();
  expect(screen.queryByRole("button", { name: "Join Home" })).toBeNull();
});

test("a used or expired link says so", async () => {
  stubApi({
    "GET /api/invites/tok": () =>
      Response.json({ code: "invite_expired", message: "This invite has expired. Ask for a new one." }, { status: 410 }),
    "GET /api/auth/me": () => Response.json({ code: "unauthorized" }, { status: 401 }),
  });
  renderInvite();

  expect(await screen.findByText("This invite can't be used")).toBeTruthy();
  expect(screen.getByRole("alert").textContent).toContain("expired");
});

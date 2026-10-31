import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, screen } from "@testing-library/react";
import { Route, Routes } from "react-router";
import { renderWithProviders, stubApi } from "../test-utils";
import { LoginPage } from "./AuthPages";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function fillAndSubmit() {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: "alice@example.com" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "wrong password" } });
  fireEvent.click(screen.getByRole("button", { name: "Log in" }));
}

test("shows the server's message when credentials are wrong", async () => {
  stubApi({
    "GET /api/auth/me": () => Response.json({ code: "unauthorized" }, { status: 401 }),
    "POST /api/auth/login": () =>
      Response.json({ code: "invalid_credentials", message: "Wrong email or password" }, { status: 401 }),
  });
  renderWithProviders(<LoginPage />, "/login");

  fillAndSubmit();

  expect((await screen.findByRole("alert")).textContent).toBe("Wrong email or password");
});

test("goes to the app after logging in", async () => {
  const fetchMock = stubApi({
    "GET /api/auth/me": () => Response.json({ code: "unauthorized" }, { status: 401 }),
    "POST /api/auth/login": () => Response.json({ id: "u1", email: "alice@example.com", displayName: "Alice" }),
  });
  renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={<p>Home</p>} />
    </Routes>,
    "/login",
  );

  fillAndSubmit();

  expect(await screen.findByText("Home")).toBeTruthy();
  const login = fetchMock.mock.calls.map(([r]) => r).find((r) => r.method === "POST")!;
  expect(await login.json()).toEqual({ email: "alice@example.com", password: "wrong password" });
});

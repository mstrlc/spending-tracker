import { afterEach, expect, test, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { SubmitButton } from "./ui";

afterEach(() => cleanup());

function Harness() {
  const [submitted, setSubmitted] = useState(0);
  return (
    <form onSubmit={(e) => (e.preventDefault(), setSubmitted((n) => n + 1))} aria-label="Test form">
      <input aria-label="Name" />
      <SubmitButton>Save</SubmitButton>
      <output aria-label="Submissions">{submitted}</output>
    </form>
  );
}

test("the button shows a keyboard hint that stays out of its accessible name", () => {
  render(<Harness />);
  const button = screen.getByRole("button", { name: "Save" });
  expect(button.querySelector("kbd")?.textContent).toMatch(/↵$/);
  expect(button.querySelector("kbd")?.getAttribute("aria-hidden")).toBe("true");
});

test("Cmd/Ctrl+Enter inside the form submits it", () => {
  render(<Harness />);
  fireEvent.keyDown(screen.getByLabelText("Name"), { key: "Enter", metaKey: true });
  expect(screen.getByLabelText("Submissions").textContent).toBe("1");
  fireEvent.keyDown(screen.getByLabelText("Name"), { key: "Enter", ctrlKey: true });
  expect(screen.getByLabelText("Submissions").textContent).toBe("2");
});

test("plain Enter does not submit", () => {
  render(<Harness />);
  fireEvent.keyDown(screen.getByLabelText("Name"), { key: "Enter" });
  expect(screen.getByLabelText("Submissions").textContent).toBe("0");
});

test("Cmd+Enter does not submit while the button is disabled", () => {
  function Disabled() {
    const [submitted, setSubmitted] = useState(0);
    return (
      <form onSubmit={(e) => (e.preventDefault(), setSubmitted((n) => n + 1))} aria-label="Test form">
        <input aria-label="Name" />
        <SubmitButton disabled>Save</SubmitButton>
        <output aria-label="Submissions">{submitted}</output>
      </form>
    );
  }
  render(<Disabled />);
  fireEvent.keyDown(screen.getByLabelText("Name"), { key: "Enter", metaKey: true });
  expect(screen.getByLabelText("Submissions").textContent).toBe("0");
});

test("requestSubmit runs native validation before submitting", () => {
  const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
  render(
    <form onSubmit={onSubmit} aria-label="Test form">
      <input aria-label="Name" required />
      <SubmitButton>Save</SubmitButton>
    </form>,
  );
  fireEvent.keyDown(screen.getByLabelText("Name"), { key: "Enter", metaKey: true });
  expect(onSubmit).not.toHaveBeenCalled();
});

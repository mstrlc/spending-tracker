import { createContext, useContext, useState, type FormEvent } from "react";
import { Link, Navigate, NavLink, Outlet, useNavigate, useParams } from "react-router";
import { useCreateHousehold, useHouseholds, useLogout, useMe } from "../api/hooks";
import type { Household } from "../api/types";
import { Inbound, LogOut, Receipt, Tag, Users, Wallet } from "../icons";
import { ErrorText, Field, FormError, Loading } from "../ui";

const CURRENCIES = ["CZK", "EUR", "USD", "GBP", "PLN", "CHF", "SEK", "NOK", "DKK", "HUF"];

const HouseholdContext = createContext<Household | null>(null);

/** The household of the current /h/:householdId route. */
export function useHousehold(): Household {
  const household = useContext(HouseholdContext);
  if (!household) throw new Error("useHousehold outside HouseholdLayout");
  return household;
}

/** "/" sends the user to their first household, or to onboarding when they have none. */
export function HomeRedirect() {
  const households = useHouseholds();
  if (households.isPending) return <Loading />;
  if (households.isError) return <ErrorText error={households.error} />;
  const first = households.data[0];
  return <Navigate to={first ? `/h/${first.id}/expenses` : "/onboarding"} replace />;
}

export function OnboardingPage() {
  const create = useCreateHousehold();
  const households = useHouseholds();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("CZK");
  const hasHouseholds = (households.data?.length ?? 0) > 0;

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate({ name, currency }, { onSuccess: (h) => navigate(`/h/${h.id}/expenses`, { replace: true }) });
  }

  return (
    <main className="auth">
      <div className="auth-panel">
        <p className="wordmark">Spending Tracker</p>
        <h1>{hasHouseholds ? "New household" : "Set up your household"}</h1>
        <p className="muted">A household holds the accounts and expenses you track, alone or with others.</p>
        <form onSubmit={submit} className="stack">
          <Field label="Household name" name="name" error={create.error}>
            <input required placeholder="Home" value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Currency" name="currency" error={create.error}>
            <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
              {CURRENCIES.map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </Field>
          <FormError error={create.error} fields={["name", "currency"]} />
          <button type="submit" className="block" disabled={create.isPending}>
            Create household
          </button>
        </form>
        {hasHouseholds && (
          <p className="auth-footer">
            <Link to="/">Back to your household</Link>
          </p>
        )}
      </div>
    </main>
  );
}

const NEW_HOUSEHOLD = "__new";

const SECTIONS = [
  { to: "expenses", label: "Expenses", icon: <Receipt /> },
  { to: "income", label: "Income", icon: <Inbound /> },
  { to: "accounts", label: "Accounts", icon: <Wallet /> },
  { to: "categories", label: "Categories", icon: <Tag /> },
  { to: "members", label: "Members", icon: <Users /> },
];

export function HouseholdLayout() {
  const { householdId } = useParams();
  const households = useHouseholds();
  const me = useMe();
  const logout = useLogout();
  const navigate = useNavigate();

  if (households.isPending) return <Loading />;
  if (households.isError) return <ErrorText error={households.error} />;
  const household = households.data.find((h) => h.id === householdId);
  if (!household) return households.isFetching ? <Loading /> : <Navigate to="/" replace />;

  const switcher = (
    <select
      className="household-switcher"
      aria-label="Household"
      value={household.id}
      onChange={(e) => navigate(e.target.value === NEW_HOUSEHOLD ? "/onboarding" : `/h/${e.target.value}/expenses`)}
    >
      {households.data.map((h) => (
        <option key={h.id} value={h.id}>
          {h.name}
        </option>
      ))}
      <option value={NEW_HOUSEHOLD}>New household…</option>
    </select>
  );
  const logOut = () => logout.mutate(undefined, { onSuccess: () => navigate("/login") });
  const name = me.data?.displayName ?? "";

  return (
    <HouseholdContext.Provider value={household}>
      <div className="app">
        <aside className="sidebar">
          {switcher}
          <nav className="side-nav" aria-label="Sections">
            {SECTIONS.map((s) => (
              <NavLink key={s.to} to={s.to}>
                {s.icon}
                {s.label}
              </NavLink>
            ))}
          </nav>
          <div className="sidebar-user">
            <span className="avatar" aria-hidden="true">
              {name.slice(0, 1).toUpperCase()}
            </span>
            <span className="sidebar-user-name">
              <span>{name}</span>
              <span className="muted small">{me.data?.email}</span>
            </span>
            <button className="icon-button" aria-label="Log out" title="Log out" onClick={logOut}>
              <LogOut />
            </button>
          </div>
        </aside>

        <header className="mobile-bar">
          {switcher}
          <button className="icon-button" aria-label="Log out" onClick={logOut}>
            <LogOut />
          </button>
        </header>

        <main className="page">
          <Outlet />
        </main>

        <nav className="bottom-nav" aria-label="Sections">
          {SECTIONS.map((s) => (
            <NavLink key={s.to} to={s.to}>
              {s.icon}
              <span>{s.label}</span>
            </NavLink>
          ))}
        </nav>
      </div>
    </HouseholdContext.Provider>
  );
}

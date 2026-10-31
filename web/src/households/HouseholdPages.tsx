import { createContext, useContext, useState, type FormEvent } from "react";
import { Link, Navigate, NavLink, Outlet, useNavigate, useParams } from "react-router";
import { useCreateHousehold, useHouseholds, useLogout, useMe } from "../api/hooks";
import type { Household } from "../api/types";
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
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("CZK");

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate({ name, currency }, { onSuccess: (h) => navigate(`/h/${h.id}/expenses`, { replace: true }) });
  }

  return (
    <main className="auth">
      <h1>Set up a household</h1>
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
        <button type="submit" disabled={create.isPending}>
          Create household
        </button>
      </form>
    </main>
  );
}

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

  return (
    <HouseholdContext.Provider value={household}>
      <header className="topbar">
        <div className="topbar-left">
          {households.data.length > 1 ? (
            <select
              aria-label="Household"
              value={household.id}
              onChange={(e) => navigate(`/h/${e.target.value}/expenses`)}
            >
              {households.data.map((h) => (
                <option key={h.id} value={h.id}>
                  {h.name}
                </option>
              ))}
            </select>
          ) : (
            <strong>{household.name}</strong>
          )}
          <nav>
            <NavLink to="expenses">Expenses</NavLink>
            <NavLink to="accounts">Accounts</NavLink>
            <NavLink to="categories">Categories</NavLink>
          </nav>
        </div>
        <div className="topbar-right">
          <Link to="/onboarding" className="muted">
            New household
          </Link>
          <span className="muted">{me.data?.displayName}</span>
          <button className="link" onClick={() => logout.mutate(undefined, { onSuccess: () => navigate("/login") })}>
            Log out
          </button>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </HouseholdContext.Provider>
  );
}

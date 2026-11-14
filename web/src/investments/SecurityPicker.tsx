import { useEffect, useState } from "react";
import { useSecuritySearch } from "../api/hooks";
import type { SecurityMatch } from "../api/types";
import { ErrorText } from "../ui";

export type PickedSecurity = Pick<SecurityMatch, "symbol" | "name" | "exchange"> & { type?: string };

/**
 * Finds a listed security by name, ticker or ISIN, or shows the one picked with a way to drop it.
 * A holding without one is valued by hand.
 */
export function SecurityPicker({ value, onChange }: { value: PickedSecurity | null; onChange: (s: PickedSecurity | null) => void }) {
  const [query, setQuery] = useState("");
  const [debounced, setDebounced] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(query.trim()), 300);
    return () => clearTimeout(timer);
  }, [query]);
  const search = useSecuritySearch(debounced);

  if (value) {
    return (
      <div className="picked-security">
        <span>
          <span className="strong">{value.symbol}</span>
          <span className="muted small block">
            {value.name}
            {value.exchange && ` · ${value.exchange}`}
          </span>
        </span>
        <button type="button" className="ghost" onClick={() => onChange(null)}>
          Remove
        </button>
      </div>
    );
  }

  const matches = search.data ?? [];
  return (
    <div className="security-search">
      <input
        type="search"
        placeholder="Name, ticker or ISIN, e.g. VWCE"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        aria-label="Find a security"
      />
      <ErrorText error={search.error} />
      {debounced.length >= 2 && search.data && matches.length === 0 && <p className="muted small">Nothing found.</p>}
      {matches.length > 0 && (
        <ul className="security-results">
          {matches.map((m) => (
            <li key={m.symbol}>
              <button type="button" onClick={() => onChange(m)}>
                <span>
                  <span className="strong">{m.symbol}</span>
                  <span className="muted small block">{m.name}</span>
                </span>
                <span className="muted small">
                  {m.exchange}
                  {m.type && ` · ${m.type}`}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

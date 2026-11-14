import { useEffect, useState } from "react";
import { useSecuritySearch } from "../api/hooks";
import type { SecurityMatch } from "../api/types";
import { SearchIcon } from "lucide-react";
import { ErrorText } from "../ui";
import { Button } from "@/components/ui/button";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";

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
      <div className="flex items-center justify-between gap-3 rounded-lg border px-3 py-2">
        <span className="min-w-0 text-sm">
          <span className="block font-medium">{value.symbol}</span>
          <span className="block truncate text-xs text-muted-foreground">
            {value.name}
            {value.exchange && ` · ${value.exchange}`}
          </span>
        </span>
        <Button type="button" variant="ghost" size="sm" onClick={() => onChange(null)}>
          Remove
        </Button>
      </div>
    );
  }

  const matches = search.data ?? [];
  return (
    <div className="flex flex-col gap-2">
      <InputGroup>
        <InputGroupInput
          type="search"
          placeholder="Name, ticker or ISIN, e.g. VWCE"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Find a security"
        />
        <InputGroupAddon>
          <SearchIcon />
        </InputGroupAddon>
      </InputGroup>
      <ErrorText error={search.error} />
      {debounced.length >= 2 && search.data && matches.length === 0 && <p className="text-sm text-muted-foreground">Nothing found.</p>}
      {matches.length > 0 && (
        <ul className="flex max-h-72 flex-col overflow-y-auto rounded-lg border p-1">
          {matches.map((m) => (
            <li key={m.symbol}>
              <button
                type="button"
                className="flex w-full items-center justify-between gap-3 rounded-md px-2 py-1.5 text-left text-sm outline-none hover:bg-muted focus-visible:bg-muted"
                onClick={() => onChange(m)}
              >
                <span className="min-w-0">
                  <span className="block font-medium">{m.symbol}</span>
                  <span className="block truncate text-xs text-muted-foreground">{m.name}</span>
                </span>
                <span className="shrink-0 text-xs text-muted-foreground">
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

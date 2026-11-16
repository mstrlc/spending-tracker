import { useState, type ReactNode } from "react";
import { ArrowDownIcon, ArrowUpDownIcon, ArrowUpIcon, CheckIcon, ListFilterIcon, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { TableCell, TableHead, TableRow } from "@/components/ui/table";
import { cn } from "@/lib/utils";

export type SortDir = "asc" | "desc";

/** A table's sort: a column and a direction, or null for the table's own order. */
export type TableSort<K extends string> = { key: K; dir: SortDir } | null;

/** What a column sorts by: a number or text; null sorts last whichever way. */
export type SortValue = string | number | null | undefined;

/** A column's active filter: a substring query, a set of selected values, or both. */
export type ColumnFilter = {
  query?: string;
  selected?: string[];
};

export type TableFilters<K extends string> = Partial<Record<K, ColumnFilter>>;

export function isFilterActive(filter: ColumnFilter | undefined): boolean {
  return Boolean(filter && ((filter.query && filter.query.trim() !== "") || (filter.selected && filter.selected.length > 0)));
}

/**
 * [rows] in [sort]'s order by [values] (one function per column), or as they are without a sort. Text compares the
 * Czech way, numbers within it as numbers; empty values go last; ties keep the rows' own order.
 */
export function sortRows<T, K extends string>(rows: T[], sort: TableSort<K>, values: Record<K, (row: T) => SortValue>): T[] {
  if (!sort) return rows;
  const value = values[sort.key];
  const sign = sort.dir === "asc" ? 1 : -1;
  return rows
    .map((row, index) => ({ row, index, v: value(row) }))
    .sort((a, b) => {
      const aEmpty = a.v === null || a.v === undefined || a.v === "";
      const bEmpty = b.v === null || b.v === undefined || b.v === "";
      if (aEmpty || bEmpty) return aEmpty === bEmpty ? a.index - b.index : aEmpty ? 1 : -1;
      const c =
        typeof a.v === "number" && typeof b.v === "number"
          ? a.v - b.v
          : String(a.v).localeCompare(String(b.v), "cs", { numeric: true, sensitivity: "base" });
      return c !== 0 ? sign * c : a.index - b.index;
    })
    .map((x) => x.row);
}

/**
 * Filters [rows] by active [filters] using [values] (one display/filter string per column).
 * When [excludeKey] is given, that column's own filter is ignored (used to build faceted options).
 */
export function filterRows<T, K extends string>(
  rows: T[],
  filters: TableFilters<NoInfer<K>>,
  values: Record<K, (row: T) => SortValue>,
  excludeKey?: NoInfer<K>,
): T[] {
  const active = (Object.entries(filters) as [K, ColumnFilter | undefined][]).filter(
    ([key, f]) => key !== excludeKey && isFilterActive(f),
  );
  if (active.length === 0) return rows;
  return rows.filter((row) =>
    active.every(([key, f]) => {
      const text = String(values[key](row) ?? "");
      if (f!.selected && f!.selected.length > 0 && !f!.selected.includes(text)) return false;
      const q = f!.query?.trim().toLocaleLowerCase("cs");
      if (q && !text.toLocaleLowerCase("cs").includes(q)) return false;
      return true;
    }),
  );
}

/** Distinct sorted values for [key] across [rows] after applying all other active [filters]. */
export function columnOptions<T, K extends string>(
  rows: T[],
  filters: TableFilters<NoInfer<K>>,
  values: Record<K, (row: T) => SortValue>,
  key: NoInfer<K>,
): string[] {
  const scoped = filterRows(rows, filters, values, key);
  const unique = new Set(scoped.map((row) => String(values[key](row) ?? "")));
  return [...unique].sort((a, b) => a.localeCompare(b, "cs", { numeric: true, sensitivity: "base" }));
}

/** The next sort after clicking [key]: its first direction, then the other, then back to the table's own order. */
export function nextSort<K extends string>(current: TableSort<K>, key: K, first: SortDir): TableSort<K> {
  if (!current || current.key !== key) return { key, dir: first };
  if (current.dir === first) return { key, dir: first === "asc" ? "desc" : "asc" };
  return null;
}

/** A table's sort state, and what clicking a column header does to it. */
export function useTableSort<K extends string>(initial: TableSort<K> = null) {
  const [sort, setSort] = useState<TableSort<K>>(initial);
  return { sort, setSort, toggle: (key: K, first: SortDir) => setSort((s) => nextSort(s, key, first)) };
}

/** A table's per-column filter state. */
export function useTableFilters<K extends string>() {
  const [filters, setFilters] = useState<TableFilters<K>>({});
  const setFilter = (key: K, next: ColumnFilter | undefined) =>
    setFilters((prev) => {
      const copy = { ...prev };
      if (!isFilterActive(next)) delete copy[key];
      else copy[key] = next;
      return copy;
    });
  const clearFilters = () => setFilters({});
  const hasFilters = Object.values(filters).some((f) => isFilterActive(f as ColumnFilter | undefined));
  return { filters, setFilter, clearFilters, hasFilters };
}

export type ColumnFilterConfig = {
  value: ColumnFilter | undefined;
  onChange: (next: ColumnFilter | undefined) => void;
  options: string[];
  onSortDir?: (dir: SortDir | null) => void;
};

/** Combines sorting and per-column filtering for a table. */
export function useTableSortAndFilter<T, K extends string>(
  rows: T[],
  sortValues: Record<K, (row: T) => SortValue>,
  filterValues: Record<K, (row: T) => SortValue>,
  initialSort: TableSort<K> = null,
) {
  const { sort, setSort, toggle: onSort } = useTableSort<K>(initialSort);
  const { filters, setFilter, clearFilters, hasFilters } = useTableFilters<K>();
  const filtered = filterRows(rows, filters, filterValues);
  const shown = sortRows(filtered, sort, sortValues);
  const filterFor = (key: K): ColumnFilterConfig => ({
    value: filters[key],
    onChange: (next) => setFilter(key, next),
    options: columnOptions(rows, filters, filterValues, key),
    onSortDir: (dir) => setSort(dir ? { key, dir } : null),
  });
  return { sort, onSort, setSort, filters, setFilter, clearFilters, hasFilters, filtered, shown, filterFor };
}

/**
 * A column header that sorts the table by its column: [first] is the direction a first click gives (newest dates and
 * largest amounts first, text A to Z). Clicking again flips it; a third click goes back to the table's own order.
 * When [filter] is provided, a filter button beside it opens a menu to search, pick values, or sort.
 */
export function SortableHead<K extends string>({
  column,
  sort,
  onSort,
  first = "asc",
  filter,
  className,
  children,
}: {
  column: K;
  sort: TableSort<K>;
  onSort: (key: K, first: SortDir) => void;
  first?: SortDir;
  filter?: ColumnFilterConfig;
  className?: string;
  children: ReactNode;
}) {
  const active = sort?.key === column ? sort.dir : null;
  const Icon = active === "asc" ? ArrowUpIcon : active === "desc" ? ArrowDownIcon : ArrowUpDownIcon;
  const right = className?.includes("text-right");
  const filtered = isFilterActive(filter?.value);
  const query = filter?.value?.query ?? "";
  const selected = filter?.value?.selected ?? [];
  const matchingOptions = filter
    ? filter.options.filter((opt) => !query.trim() || opt.toLocaleLowerCase("cs").includes(query.trim().toLocaleLowerCase("cs")))
    : [];

  const toggleOption = (opt: string) => {
    if (!filter) return;
    const next = selected.includes(opt) ? selected.filter((v) => v !== opt) : [...selected, opt];
    filter.onChange({ query: filter.value?.query, selected: next });
  };

  return (
    <TableHead
      className={cn("group/head", className)}
      aria-sort={active === "asc" ? "ascending" : active === "desc" ? "descending" : "none"}
    >
      <div className={cn("inline-flex max-w-full items-center gap-0.5", right && "flex-row-reverse")}>
        <button
          type="button"
          className={cn(
            "group inline-flex min-w-0 items-center gap-1 rounded-sm outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring",
            right && "flex-row-reverse",
            active && "text-foreground",
          )}
          onClick={() => onSort(column, first)}
        >
          <span className="truncate">{children}</span>
          <Icon
            aria-hidden="true"
            className={cn(
              "size-3.5 shrink-0",
              active ? "opacity-100" : "opacity-0 group-hover:opacity-60 group-focus-visible:opacity-60",
            )}
          />
        </button>
        {filter && (
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button
                type="button"
                aria-label={`Filter ${typeof children === "string" ? children : column}`}
                className={cn(
                  "inline-flex size-5 shrink-0 items-center justify-center rounded-sm outline-none hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring",
                  filtered
                    ? "text-foreground opacity-100"
                    : "opacity-0 group-hover/head:opacity-60 group-focus-within/head:opacity-60 data-[state=open]:opacity-100",
                )}
              >
                <ListFilterIcon aria-hidden="true" className="size-3.5" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align={right ? "end" : "start"} className="w-56 p-1.5">
              {filter.onSortDir && (
                <>
                  <DropdownMenuItem onSelect={() => filter.onSortDir!(active === "asc" ? null : "asc")}>
                    <ArrowUpIcon className="size-3.5" />
                    Sort ascending
                    {active === "asc" && <CheckIcon className="ml-auto size-3.5" />}
                  </DropdownMenuItem>
                  <DropdownMenuItem onSelect={() => filter.onSortDir!(active === "desc" ? null : "desc")}>
                    <ArrowDownIcon className="size-3.5" />
                    Sort descending
                    {active === "desc" && <CheckIcon className="ml-auto size-3.5" />}
                  </DropdownMenuItem>
                  <DropdownMenuSeparator />
                </>
              )}
              <Input
                type="search"
                aria-label="Filter values"
                placeholder="Filter…"
                value={query}
                onChange={(e) => filter.onChange({ query: e.target.value, selected: filter.value?.selected })}
                onKeyDown={(e) => e.stopPropagation()}
                className="h-7 text-xs"
              />
              {matchingOptions.length > 0 && (
                <div className="mt-1 max-h-48 overflow-y-auto">
                  {matchingOptions.map((opt) => (
                    <DropdownMenuCheckboxItem
                      key={opt}
                      checked={selected.includes(opt)}
                      onSelect={(e) => e.preventDefault()}
                      onCheckedChange={() => toggleOption(opt)}
                    >
                      <span className={cn("truncate", opt === "" && "italic text-muted-foreground")}>{opt || "Empty"}</span>
                    </DropdownMenuCheckboxItem>
                  ))}
                </div>
              )}
              {matchingOptions.length === 0 && (
                <div className="px-2 py-2 text-center text-xs text-muted-foreground">No matching values</div>
              )}
              {filtered && (
                <>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem onSelect={() => filter.onChange(undefined)}>
                    <XIcon className="size-3.5" />
                    Clear filter
                  </DropdownMenuItem>
                </>
              )}
            </DropdownMenuContent>
          </DropdownMenu>
        )}
      </div>
    </TableHead>
  );
}

/** A single table row shown when column filters match nothing, with a button to clear them. */
export function EmptyFilterRow({ colSpan, onClear }: { colSpan: number; onClear: () => void }) {
  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={colSpan} className="py-8 text-center text-sm text-muted-foreground">
        No matching rows.{" "}
        <Button type="button" variant="link" size="sm" className="h-auto p-0" onClick={onClear}>
          Clear filters
        </Button>
      </TableCell>
    </TableRow>
  );
}

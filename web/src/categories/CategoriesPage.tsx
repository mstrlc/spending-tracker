import { useState, type FormEvent } from "react";
import { Chip, Dot, categoryTone } from "../chips";
import { ArchiveIcon, ArrowDownLeftIcon, TrendingUpIcon } from "lucide-react";
import { useCategories, useDeleteCategory, useSaveCategory } from "../api/hooks";
import type { Category, CategoryInput, CategoryKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { ErrorText, Field, FormError, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { usePendingDeleteIds, useUndoableDelete, useUndoableSave } from "../undo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { cn } from "@/lib/utils";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { categoryTree, incomeCategories, spendingCategories } from "./tree";

export function CategoriesPage() {
  const household = useHousehold();
  const categories = useCategories(household.id);
  const saveCategory = useSaveCategory(household.id);
  const deleteCategory = useDeleteCategory(household.id);
  const save = useUndoableSave("Category", saveCategory, deleteCategory);
  const remove = useUndoableDelete("Category", deleteCategory);
  const pendingDeletes = usePendingDeleteIds();
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  // Spending (and investing) categories and income categories are separate lists: an expense never takes an income
  // category, nor income a spending one.
  const [side, setSide] = useState<"spending" | "income">("spending");
  const [spendingKind, setSpendingKind] = useState<Exclude<CategoryKind, "income">>("consumption");
  const [renaming, setRenaming] = useState<string | null>(null);

  const shown = (categories.data ?? []).filter((c) => !pendingDeletes.has(c.id));
  const tree = categoryTree(side === "income" ? incomeCategories(shown) : spendingCategories(shown));
  const kind: CategoryKind = side === "income" ? "income" : spendingKind;

  function switchSide(next: "spending" | "income") {
    setSide(next);
    setParentId("");
  }

  function add(e: FormEvent) {
    e.preventDefault();
    save.mutate({ input: { name, parentId: parentId || null, kind } }, { onSuccess: () => setName("") });
  }

  const update = (category: Category, changes: Partial<CategoryInput>) => {
    const previous: CategoryInput = {
      name: category.name,
      parentId: category.parentId,
      kind: category.kind,
      archived: category.archived,
      sortOrder: category.sortOrder,
      version: category.version,
    };
    save.mutate({
      id: category.id,
      input: { ...previous, ...changes },
      previous,
    });
  };

  function deleteRow(category: Category) {
    remove.schedule({ id: category.id, version: category.version });
  }

  const row = (category: Category, isChild: boolean) => (
    <li
      key={category.id}
      className={cn(
        "flex min-h-12 items-center justify-between gap-3 py-1.5 pr-4 pl-4",
        isChild && "pl-10",
        category.archived && "text-muted-foreground",
      )}
    >
      <span className={cn("flex items-center gap-2", isChild ? "text-muted-foreground" : "font-medium")}>
        <Dot tone={categoryTone(categories.data ?? [], category.id)} className={cn(isChild && "size-1.5", category.archived && "opacity-40")} />
        <InlineRename
          name={category.name}
          editing={renaming === category.id}
          onSave={(newName) => update(category, { name: newName })}
          onDone={() => setRenaming(null)}
        />
        {!isChild && category.kind === "investment" && <Chip tone="transfer" icon={TrendingUpIcon}>Investing</Chip>}
        {category.archived && <Chip icon={ArchiveIcon}>Archived</Chip>}
      </span>
      <RowMenu
        label={category.name}
        actions={[
          { label: "Rename", onSelect: () => setRenaming(category.id) },
          { label: category.archived ? "Unarchive" : "Archive", onSelect: () => update(category, { archived: !category.archived }) },
          { label: "Delete", destructive: true, onSelect: () => deleteRow(category) },
        ]}
      />
    </li>
  );

  return (
    <>
      <PageHeader title="Categories" />
      <PageIntro>
        {side === "income"
          ? "Income categories say what kind of money came in (the source says who paid). Only income lines and income rules use them."
          : "Spending categories are for expenses. Investing ones hold money moved into investments: counted as invested, not spent."}{" "}
        Archive a category to hide it from new entries while keeping its history.
      </PageIntro>

      <Tabs value={side} onValueChange={(v) => switchSide(v as "spending" | "income")}>
        <TabsList aria-label="Which categories">
          <TabsTrigger value="spending">Spending</TabsTrigger>
          <TabsTrigger value="income">
            <ArrowDownLeftIcon /> Income
          </TabsTrigger>
        </TabsList>
      </Tabs>

      <Surface className="p-4">
        <form onSubmit={add} className="flex flex-wrap items-end gap-3" aria-label={side === "income" ? "Add income category" : "Add category"}>
          <Field label="Category name" name="name" error={save.error} className="w-56">
            <Input required value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Under" name="parentId" error={save.error} className="w-48">
            <NativeSelect className="w-full" value={parentId} onChange={(e) => setParentId(e.target.value)}>
              <NativeSelectOption value="">Top level</NativeSelectOption>
              {tree.map((c) => (
                <NativeSelectOption key={c.id} value={c.id}>
                  {c.name}
                </NativeSelectOption>
              ))}
            </NativeSelect>
          </Field>
          {!parentId && side === "spending" && (
            <Field label="Counts as" name="kind" error={save.error} className="w-40">
              <NativeSelect
                className="w-full"
                value={spendingKind}
                onChange={(e) => setSpendingKind(e.target.value as Exclude<CategoryKind, "income">)}
              >
                <NativeSelectOption value="consumption">Spending</NativeSelectOption>
                <NativeSelectOption value="investment">Investing</NativeSelectOption>
              </NativeSelect>
            </Field>
          )}
          <Button type="submit" disabled={save.isPending}>
            {side === "income" ? "Add income category" : "Add category"}
          </Button>
        </form>
        <FormError error={save.error} fields={["name", "parentId", "kind"]} />
      </Surface>

      <Surface>
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={categories.error} className="p-4" />
        {categories.isPending && <SkeletonRows />}
        <ul className="divide-y text-sm">
          {tree.map((parent) => [row(parent, false), ...parent.children.map((c) => row(c, true))])}
        </ul>
      </Surface>
    </>
  );
}

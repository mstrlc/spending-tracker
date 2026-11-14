import { useState, type FormEvent } from "react";
import { useCategories, useDeleteCategory, useSaveCategory } from "../api/hooks";
import type { Category, CategoryInput, CategoryKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { useConfirm } from "../dialogs";
import { ErrorText, Field, FormError, PageHeader, PageIntro, RowMenu, SkeletonRows, Surface } from "../ui";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { cn } from "@/lib/utils";
import { categoryTree } from "./tree";

export function CategoriesPage() {
  const household = useHousehold();
  const categories = useCategories(household.id);
  const save = useSaveCategory(household.id);
  const remove = useDeleteCategory(household.id);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  const [kind, setKind] = useState<CategoryKind>("consumption");
  const [renaming, setRenaming] = useState<string | null>(null);
  const confirm = useConfirm();

  const tree = categoryTree(categories.data ?? []);

  function add(e: FormEvent) {
    e.preventDefault();
    save.mutate({ input: { name, parentId: parentId || null, kind } }, { onSuccess: () => setName("") });
  }

  const update = (category: Category, changes: Partial<CategoryInput>) =>
    save.mutate({
      id: category.id,
      input: {
        name: category.name,
        parentId: category.parentId,
        kind: category.kind,
        archived: category.archived,
        sortOrder: category.sortOrder,
        version: category.version,
        ...changes,
      },
    });

  async function confirmDelete(category: Category) {
    if (await confirm({ title: `Delete ${category.name}?`, description: "Only a category nothing uses can be deleted; archive it to keep its history." })) {
      remove.mutate({ id: category.id, version: category.version });
    }
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
        <InlineRename
          name={category.name}
          editing={renaming === category.id}
          onSave={(newName) => update(category, { name: newName })}
          onDone={() => setRenaming(null)}
        />
        {!isChild && category.kind === "investment" && <Badge variant="outline">Investing</Badge>}
        {category.archived && <Badge variant="secondary">Archived</Badge>}
      </span>
      <RowMenu
        label={category.name}
        actions={[
          { label: "Rename", onSelect: () => setRenaming(category.id) },
          { label: category.archived ? "Unarchive" : "Archive", onSelect: () => update(category, { archived: !category.archived }) },
          { label: "Delete", destructive: true, onSelect: () => confirmDelete(category) },
        ]}
      />
    </li>
  );

  return (
    <>
      <PageHeader title="Categories" />
      <PageIntro>
        Investing categories hold money moved into investments: counted as invested, not spent. Archive a category to
        hide it from new expenses while keeping its history.
      </PageIntro>

      <Surface className="p-4">
        <form onSubmit={add} className="flex flex-wrap items-end gap-3" aria-label="Add category">
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
          {!parentId && (
            <Field label="Counts as" name="kind" error={save.error} className="w-40">
              <NativeSelect className="w-full" value={kind} onChange={(e) => setKind(e.target.value as CategoryKind)}>
                <NativeSelectOption value="consumption">Spending</NativeSelectOption>
                <NativeSelectOption value="investment">Investing</NativeSelectOption>
              </NativeSelect>
            </Field>
          )}
          <Button type="submit" disabled={save.isPending}>
            Add category
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

import { useState, type FormEvent } from "react";
import { useCategories, useDeleteCategory, useSaveCategory } from "../api/hooks";
import type { Category, CategoryInput, CategoryKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { ErrorText, Field, FormError, PageHeader, SkeletonRows } from "../ui";
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

  const row = (category: Category, isChild: boolean) => (
    <li key={category.id} className={["category-row", isChild && "is-child", category.archived && "dimmed"].filter(Boolean).join(" ")}>
      <span className="category-name">
        <InlineRename
          name={category.name}
          editing={renaming === category.id}
          onSave={(newName) => update(category, { name: newName })}
          onDone={() => setRenaming(null)}
        />
        {!isChild && category.kind === "investment" && <span className="badge">Investing</span>}
        {category.archived && <span className="badge badge-muted">Archived</span>}
      </span>
      <span className="row-actions">
        <button className="ghost" onClick={() => setRenaming(category.id)}>
          Rename
        </button>
        <button className="ghost" onClick={() => update(category, { archived: !category.archived })}>
          {category.archived ? "Unarchive" : "Archive"}
        </button>
        <button
          className="ghost danger"
          onClick={() => window.confirm(`Delete ${category.name}?`) && remove.mutate({ id: category.id, version: category.version })}
        >
          Delete
        </button>
      </span>
    </li>
  );

  return (
    <>
      <PageHeader title="Categories" />
      <p className="page-intro muted">
        Investing categories hold money moved into investments: counted as invested, not spent. Archive a category to
        hide it from new expenses while keeping its history.
      </p>

      <div className="add-block">
        <form onSubmit={add} className="toolbar-form" aria-label="Add category">
          <Field label="Category name" name="name" error={save.error}>
            <input required value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <Field label="Under" name="parentId" error={save.error}>
            <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
              <option value="">Top level</option>
              {tree.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </Field>
          {!parentId && (
            <Field label="Counts as" name="kind" error={save.error}>
              <select value={kind} onChange={(e) => setKind(e.target.value as CategoryKind)}>
                <option value="consumption">Spending</option>
                <option value="investment">Investing</option>
              </select>
            </Field>
          )}
          <button type="submit" disabled={save.isPending}>
            Add category
          </button>
        </form>
        <FormError error={save.error} fields={["name", "parentId", "kind"]} />
      </div>

      <section className="surface">
        <ErrorText error={remove.error} />
        <ErrorText error={categories.error} />
        {categories.isPending && <SkeletonRows />}
        <ul className="category-list">
          {tree.map((parent) => [row(parent, false), ...parent.children.map((c) => row(c, true))])}
        </ul>
      </section>
    </>
  );
}

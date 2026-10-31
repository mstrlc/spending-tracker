import { useState, type FormEvent } from "react";
import { useCategories, useDeleteCategory, useSaveCategory } from "../api/hooks";
import type { Category, CategoryInput, CategoryKind } from "../api/types";
import { useHousehold } from "../households/HouseholdPages";
import { InlineRename } from "../InlineRename";
import { ErrorText, Field, FormError, Loading } from "../ui";
import { categoryTree } from "./tree";

export function CategoriesPage() {
  const household = useHousehold();
  const categories = useCategories(household.id);
  const save = useSaveCategory(household.id);
  const remove = useDeleteCategory(household.id);
  const [name, setName] = useState("");
  const [parentId, setParentId] = useState("");
  const [kind, setKind] = useState<CategoryKind>("consumption");

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
    <li key={category.id} className={category.archived ? "category muted" : "category"}>
      <span>
        <InlineRename name={category.name} onSave={(newName) => update(category, { name: newName })} />
        {!isChild && category.kind === "investment" && <span className="badge">investment</span>}
        {category.archived && <span className="badge">archived</span>}
      </span>
      <span className="row-actions">
        <button className="link" onClick={() => update(category, { archived: !category.archived })}>
          {category.archived ? "Unarchive" : "Archive"}
        </button>
        <button
          className="link danger"
          onClick={() => window.confirm(`Delete ${category.name}?`) && remove.mutate({ id: category.id, version: category.version })}
        >
          Delete
        </button>
      </span>
    </li>
  );

  return (
    <>
      <section className="card">
        <h2>Add category</h2>
        <form onSubmit={add} className="row-form">
          <Field label="Name" name="name" error={save.error}>
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
            Add
          </button>
        </form>
        <FormError error={save.error} fields={["name", "parentId", "kind"]} />
      </section>

      <section className="card">
        <h2>Categories</h2>
        <p className="muted small">
          Investing categories are money moved into investments: counted as invested, not spent. Archive a category to
          hide it from new expenses while keeping its history.
        </p>
        <ErrorText error={remove.error} />
        {categories.isPending && <Loading />}
        <ErrorText error={categories.error} />
        <ul className="category-tree">
          {tree.map((parent) => (
            <li key={parent.id}>
              <ul>
                {row(parent, false)}
                {parent.children.length > 0 && <ul className="children">{parent.children.map((c) => row(c, true))}</ul>}
              </ul>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

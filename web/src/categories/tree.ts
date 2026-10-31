import type { Category } from "../api/types";

export type CategoryNode = Category & { children: Category[] };

/** Top-level categories with their subcategories, in display order. */
export function categoryTree(categories: Category[]): CategoryNode[] {
  return categories
    .filter((c) => c.parentId === null)
    .map((parent) => ({ ...parent, children: categories.filter((c) => c.parentId === parent.id) }));
}

/** "Food › Snacks" for subcategories, the plain name for top-level ones. */
export function categoryLabel(categories: Category[], id: string | null): string {
  if (id === null) return "";
  const category = categories.find((c) => c.id === id);
  if (!category) return "";
  const parent = category.parentId ? categories.find((c) => c.id === category.parentId) : undefined;
  return parent ? `${parent.name} › ${category.name}` : category.name;
}

import type { components } from "./schema";

type Schemas = components["schemas"];

export type ApiErrorBody = Schemas["ApiError"];
export type Me = Schemas["Me"];
export type Household = Schemas["Household"];
export type Member = Schemas["Member"];
export type HouseholdRole = Schemas["HouseholdRole"];
export type Invite = Schemas["Invite"];
export type InvitePreview = Schemas["InvitePreview"];
export type Account = Schemas["Account"];
export type AccountInput = Schemas["AccountInput"];
export type Category = Schemas["Category"];
export type CategoryInput = Schemas["CategoryInput"];
export type CategoryKind = Schemas["CategoryKind"];
export type Expense = Schemas["Expense"];
export type ExpenseInput = Schemas["ExpenseInput"];

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { ApiFailure, unwrap } from "./errors";
import type { AccountInput, CategoryInput, ExpenseInput, Household } from "./types";

export const keys = {
  me: ["me"] as const,
  households: ["households"] as const,
  household: (id: string) => ["households", id] as const,
  members: (id: string) => ["households", id, "members"] as const,
  accounts: (id: string) => ["households", id, "accounts"] as const,
  categories: (id: string) => ["households", id, "categories"] as const,
  expenses: (id: string, from?: string, to?: string) =>
    from === undefined ? (["households", id, "expenses"] as const) : (["households", id, "expenses", from, to] as const),
};

// ---- Auth ----

/** The logged-in user, or null when there is no session. */
export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: async () => {
      try {
        return unwrap(await api.GET("/api/auth/me"));
      } catch (e) {
        if (e instanceof ApiFailure && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: Infinity,
  });
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { email: string; password: string }) =>
      unwrap(await api.POST("/api/auth/login", { body })),
    onSuccess: (me) => {
      qc.clear();
      qc.setQueryData(keys.me, me);
    },
  });
}

export function useSignup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { email: string; password: string; displayName: string }) =>
      unwrap(await api.POST("/api/auth/signup", { body })),
    onSuccess: (me) => {
      qc.clear();
      qc.setQueryData(keys.me, me);
    },
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await api.POST("/api/auth/logout");
    },
    onSuccess: () => {
      qc.clear();
      qc.setQueryData(keys.me, null);
    },
  });
}

// ---- Households ----

export function useHouseholds() {
  return useQuery({
    queryKey: keys.households,
    queryFn: async () => unwrap(await api.GET("/api/households")),
  });
}

export function useCreateHousehold() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { name: string; currency: string }) =>
      unwrap(await api.POST("/api/households", { body })),
    onSuccess: (household) => {
      // Callers navigate to the new household right away; the cached list must already contain it,
      // because invalidation alone doesn't refetch a query nothing on screen is watching.
      qc.setQueryData<Household[]>(keys.households, (old) => [...(old ?? []), household]);
      return qc.invalidateQueries({ queryKey: keys.households });
    },
  });
}

export function useMembers(householdId: string) {
  return useQuery({
    queryKey: keys.members(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/members", { params: { path: { householdId } } })),
  });
}

// ---- Accounts ----

export function useAccounts(householdId: string) {
  return useQuery({
    queryKey: keys.accounts(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/accounts", { params: { path: { householdId } } })),
  });
}

export function useSaveAccount(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: AccountInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/accounts", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/accounts/{accountId}", {
              params: { path: { householdId, accountId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.accounts(householdId) }),
  });
}

export function useDeleteAccount(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/accounts/{accountId}", {
          params: { path: { householdId, accountId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.accounts(householdId) }),
  });
}

// ---- Categories ----

export function useCategories(householdId: string) {
  return useQuery({
    queryKey: keys.categories(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/categories", { params: { path: { householdId } } })),
  });
}

export function useSaveCategory(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: CategoryInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/categories", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/categories/{categoryId}", {
              params: { path: { householdId, categoryId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.categories(householdId) }),
  });
}

export function useDeleteCategory(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/categories/{categoryId}", {
          params: { path: { householdId, categoryId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.categories(householdId) }),
  });
}

// ---- Expenses ----

export function useExpenses(householdId: string, from: string, to: string) {
  return useQuery({
    queryKey: keys.expenses(householdId, from, to),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/expenses", {
          params: { path: { householdId }, query: { from, to } },
        }),
      ),
  });
}

export function useSaveExpense(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: ExpenseInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/expenses", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/expenses/{expenseId}", {
              params: { path: { householdId, expenseId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.expenses(householdId) }),
  });
}

export function useDeleteExpense(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/expenses/{expenseId}", {
          params: { path: { householdId, expenseId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.expenses(householdId) }),
  });
}

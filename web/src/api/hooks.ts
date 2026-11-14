import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { ApiFailure, unwrap } from "./errors";
import type {
  AccountInput,
  ActivityEntity,
  CategoryInput,
  ContributionInput,
  FeeInput,
  ExpenseInput,
  Household,
  HouseholdRole,
  IncomeInput,
  AssetInput,
  ValuationInput,
} from "./types";

export const keys = {
  me: ["me"] as const,
  households: ["households"] as const,
  household: (id: string) => ["households", id] as const,
  members: (id: string) => ["households", id, "members"] as const,
  invites: (id: string) => ["households", id, "invites"] as const,
  invitePreview: (token: string) => ["invite", token] as const,
  accounts: (id: string) => ["households", id, "accounts"] as const,
  categories: (id: string) => ["households", id, "categories"] as const,
  expenses: (id: string, from?: string, to?: string) =>
    from === undefined ? (["households", id, "expenses"] as const) : (["households", id, "expenses", from, to] as const),
  income: (id: string, from?: string, to?: string) =>
    from === undefined ? (["households", id, "income"] as const) : (["households", id, "income", from, to] as const),
  assets: (id: string) => ["households", id, "assets"] as const,
  contributions: (id: string, assetId: string) => ["households", id, "assets", assetId, "contributions"] as const,
  valuations: (id: string, assetId: string) => ["households", id, "assets", assetId, "valuations"] as const,
  fees: (id: string, assetId: string) => ["households", id, "assets", assetId, "fees"] as const,
  assetHistory: (id: string, assetId?: string) => ["households", id, "assets", "history", assetId ?? "all"] as const,
  securitySearch: (query: string) => ["securities", query] as const,
  activity: (id: string, entity?: ActivityEntity) => ["households", id, "activity", entity ?? "all"] as const,
  overview: (id: string, month: string) => ["households", id, "overview", month] as const,
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

/** Removes a member; with leaving, the current user leaves and the household drops out of their list. */
export function useRemoveMember(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ userId }: { userId: string; leaving: boolean }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/members/{userId}", {
          params: { path: { householdId, userId } },
        }),
      ),
    onSuccess: (_, { leaving }) => {
      if (!leaving) return qc.invalidateQueries({ queryKey: keys.members(householdId) });
      qc.setQueryData<Household[]>(keys.households, (old) => old?.filter((h) => h.id !== householdId));
      qc.removeQueries({ queryKey: keys.household(householdId) });
    },
  });
}

// ---- Invites ----

export function useInvites(householdId: string, enabled = true) {
  return useQuery({
    queryKey: keys.invites(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/invites", { params: { path: { householdId } } })),
    enabled,
  });
}

export function useCreateInvite(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: { email: string; role: HouseholdRole }) =>
      unwrap(await api.POST("/api/households/{householdId}/invites", { params: { path: { householdId } }, body })),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.invites(householdId) }),
  });
}

export function useRevokeInvite(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (inviteId: string) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/invites/{inviteId}", {
          params: { path: { householdId, inviteId } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.invites(householdId) }),
  });
}

/** What an invite link points at. Works without a session. */
export function useInvitePreview(token: string) {
  return useQuery({
    queryKey: keys.invitePreview(token),
    queryFn: async () => unwrap(await api.GET("/api/invites/{token}", { params: { path: { token } } })),
    retry: false,
  });
}

export function useAcceptInvite(token: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/invites/{token}/accept", { params: { path: { token } } })),
    onSuccess: (household) => {
      // As with creating a household: the caller navigates straight to it, so the list must contain it now.
      qc.setQueryData<Household[]>(keys.households, (old) => [...(old ?? []).filter((h) => h.id !== household.id), household]);
      return qc.invalidateQueries({ queryKey: keys.households });
    },
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

// ---- Income ----

export function useIncome(householdId: string, from: string, to: string) {
  return useQuery({
    queryKey: keys.income(householdId, from, to),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/income", {
          params: { path: { householdId }, query: { from, to } },
        }),
      ),
  });
}

export function useSaveIncome(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: IncomeInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/income", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/income/{incomeId}", {
              params: { path: { householdId, incomeId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.income(householdId) }),
  });
}

export function useDeleteIncome(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/income/{incomeId}", {
          params: { path: { householdId, incomeId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.income(householdId) }),
  });
}

// ---- Assets ----
// Contributions and valuations change a holding's figures, so saving one refetches the holdings too.

export function useAssets(householdId: string) {
  return useQuery({
    queryKey: keys.assets(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/assets", { params: { path: { householdId } } })),
  });
}

export function useSaveAsset(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: AssetInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/assets", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/assets/{assetId}", {
              params: { path: { householdId, assetId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

export function useDeleteAsset(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/assets/{assetId}", {
          params: { path: { householdId, assetId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

/** Put in and worth at each month end, then today: all holdings, or only [assetId]. */
export function useAssetHistory(householdId: string, assetId?: string) {
  return useQuery({
    queryKey: keys.assetHistory(householdId, assetId),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/assets/history", {
          params: { path: { householdId }, query: { assetId } },
        }),
      ),
  });
}

/** Securities matching [query]; idle until it has two characters. */
export function useSecuritySearch(query: string) {
  return useQuery({
    queryKey: keys.securitySearch(query),
    queryFn: async () => unwrap(await api.GET("/api/securities/search", { params: { query: { q: query } } })),
    enabled: query.length >= 2,
    staleTime: 5 * 60_000,
  });
}

export function useContributions(householdId: string, assetId: string) {
  return useQuery({
    queryKey: keys.contributions(householdId, assetId),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/assets/{assetId}/contributions", {
          params: { path: { householdId, assetId } },
        }),
      ),
  });
}

export function useSaveContribution(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: ContributionInput }) =>
      id === undefined
        ? unwrap(
            await api.POST("/api/households/{householdId}/assets/{assetId}/contributions", {
              params: { path: { householdId, assetId } },
              body: input,
            }),
          )
        : unwrap(
            await api.PUT("/api/households/{householdId}/assets/{assetId}/contributions/{contributionId}", {
              params: { path: { householdId, assetId, contributionId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

export function useDeleteContribution(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/assets/{assetId}/contributions/{contributionId}", {
          params: { path: { householdId, assetId, contributionId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

export function useValuations(householdId: string, assetId: string) {
  return useQuery({
    queryKey: keys.valuations(householdId, assetId),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/assets/{assetId}/valuations", {
          params: { path: { householdId, assetId } },
        }),
      ),
  });
}

export function useSaveValuation(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: ValuationInput }) =>
      id === undefined
        ? unwrap(
            await api.POST("/api/households/{householdId}/assets/{assetId}/valuations", {
              params: { path: { householdId, assetId } },
              body: input,
            }),
          )
        : unwrap(
            await api.PUT("/api/households/{householdId}/assets/{assetId}/valuations/{valuationId}", {
              params: { path: { householdId, assetId, valuationId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

export function useDeleteValuation(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/assets/{assetId}/valuations/{valuationId}", {
          params: { path: { householdId, assetId, valuationId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

export function useFees(householdId: string, assetId: string) {
  return useQuery({
    queryKey: keys.fees(householdId, assetId),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/assets/{assetId}/fees", {
          params: { path: { householdId, assetId } },
        }),
      ),
  });
}

export function useSaveFee(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: FeeInput }) =>
      id === undefined
        ? unwrap(
            await api.POST("/api/households/{householdId}/assets/{assetId}/fees", {
              params: { path: { householdId, assetId } },
              body: input,
            }),
          )
        : unwrap(
            await api.PUT("/api/households/{householdId}/assets/{assetId}/fees/{feeId}", {
              params: { path: { householdId, assetId, feeId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

export function useDeleteFee(householdId: string, assetId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/assets/{assetId}/fees/{feeId}", {
          params: { path: { householdId, assetId, feeId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.assets(householdId) }),
  });
}

// ---- Activity and overview ----
// Both are read-only views over everything else, so mutations don't invalidate them; they refetch on every visit.

/** The household's audit log, newest first, a page at a time. */
export function useActivity(householdId: string, entity?: ActivityEntity) {
  return useInfiniteQuery({
    queryKey: keys.activity(householdId, entity),
    queryFn: async ({ pageParam }) =>
      unwrap(
        await api.GET("/api/households/{householdId}/activity", {
          params: { path: { householdId }, query: { entity, before: pageParam } },
        }),
      ),
    initialPageParam: undefined as number | undefined,
    getNextPageParam: (page) => page.nextBefore ?? undefined,
  });
}

export function useOverview(householdId: string, month: string, today: string) {
  return useQuery({
    queryKey: keys.overview(householdId, month),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/overview", {
          params: { path: { householdId }, query: { month, today } },
        }),
      ),
  });
}

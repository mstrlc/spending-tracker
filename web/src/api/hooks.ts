import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "./client";
import { ApiFailure, unwrap } from "./errors";
import type {
  AccountInput,
  ActivityEntity,
  BankFeedInput,
  BankRowRef,
  CategoryInput,
  ContributionInput,
  FeeInput,
  FeedFromAccount,
  ExpenseInput,
  Household,
  HouseholdImport,
  HouseholdRole,
  HouseholdSettings,
  IncomeInput,
  InboxRuleInput,
  AssetInput,
  MerchantInput,
  SettlementInput,
  SplitRuleInput,
  TransferInput,
  TripInput,
  ValuationInput,
} from "./types";

export type BankRowStatus = "pending" | "decided";

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
  transfers: (id: string, from?: string, to?: string, accountId?: string) =>
    from === undefined
      ? (["households", id, "transfers"] as const)
      : (["households", id, "transfers", from, to, accountId ?? ""] as const),
  assets: (id: string) => ["households", id, "assets"] as const,
  trips: (id: string) => ["households", id, "trips"] as const,
  merchants: (id: string) => ["households", id, "merchants"] as const,
  contributions: (id: string, assetId: string) => ["households", id, "assets", assetId, "contributions"] as const,
  valuations: (id: string, assetId: string) => ["households", id, "assets", assetId, "valuations"] as const,
  fees: (id: string, assetId: string) => ["households", id, "assets", assetId, "fees"] as const,
  assetHistory: (id: string, assetId?: string) => ["households", id, "assets", "history", assetId ?? "all"] as const,
  assetSummary: (id: string) => ["households", id, "assets", "summary"] as const,
  securitySearch: (query: string) => ["securities", query] as const,
  activity: (id: string, entity?: ActivityEntity) => ["households", id, "activity", entity ?? "all"] as const,
  overview: (id: string, month: string) => ["households", id, "overview", month] as const,
  splitRules: (id: string) => ["households", id, "split-rules"] as const,
  inboxRules: (id: string) => ["households", id, "inbox-rules"] as const,
  settings: (id: string) => ["households", id, "settings"] as const,
  bankConnections: (id: string) => ["households", id, "bank-connections"] as const,
  bankInstitutions: (id: string, country: string) => ["households", id, "bank-institutions", country] as const,
  bankFeeds: (id: string) => ["households", id, "bank-feeds"] as const,
  bankRows: (id: string, status?: BankRowStatus) =>
    status === undefined ? (["households", id, "bank-rows"] as const) : (["households", id, "bank-rows", status] as const),
  balances: (id: string, from?: string, to?: string) =>
    from === undefined ? (["households", id, "balances"] as const) : (["households", id, "balances", from, to] as const),
  imports: (id: string) => ["households", id, "imports"] as const,
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

/** Adds a member without a login: someone things can belong to, who can't sign in. */
export function useAddMember(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (displayName: string) =>
      unwrap(
        await api.POST("/api/households/{householdId}/members", { params: { path: { householdId } }, body: { displayName } }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.members(householdId) }),
  });
}

export function useUpdateMember(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ userId, displayName, legalName }: { userId: string; displayName: string; legalName?: string | null }) =>
      unwrap(
        await api.PUT("/api/households/{householdId}/members/{userId}", {
          params: { path: { householdId, userId } },
          body: { displayName, legalName },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.members(householdId) }),
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
    mutationFn: async (body: { email: string; role: HouseholdRole; claimUserId?: string | null }) =>
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
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.accounts(householdId) });
      // Whose an account is decides who paid.
      qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      qc.invalidateQueries({ queryKey: keys.expenses(householdId) });
    },
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

// ---- Debts ----

export function useBalances(householdId: string, from: string, to: string) {
  return useQuery({
    queryKey: keys.balances(householdId, from, to),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/balances", {
          params: { path: { householdId }, query: { from, to } },
        }),
      ),
  });
}

export function useSplitRules(householdId: string) {
  return useQuery({
    queryKey: keys.splitRules(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/split-rules", { params: { path: { householdId } } })),
  });
}

/** A rule changes who owes what on every expense it covers. */
function invalidateSplits(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  qc.invalidateQueries({ queryKey: keys.splitRules(householdId) });
  qc.invalidateQueries({ queryKey: keys.balances(householdId) });
  qc.invalidateQueries({ queryKey: keys.expenses(householdId) });
}

export function useSaveSplitRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: SplitRuleInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/split-rules", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/split-rules/{ruleId}", {
              params: { path: { householdId, ruleId: id } },
              body: input,
            }),
          ),
    onSettled: () => invalidateSplits(qc, householdId),
  });
}

export function useDeleteSplitRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/split-rules/{ruleId}", {
          params: { path: { householdId, ruleId: id }, query: { version } },
        }),
      ),
    onSettled: () => invalidateSplits(qc, householdId),
  });
}

export function useSettlements(householdId: string, from: string, to: string) {
  return useQuery({
    queryKey: [...keys.balances(householdId), "settlements", from, to],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/settlements", {
          params: { path: { householdId }, query: { from, to } },
        }),
      ),
  });
}

export function useSaveSettlement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: SettlementInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/settlements", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/settlements/{settlementId}", {
              params: { path: { householdId, settlementId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.balances(householdId) }),
  });
}

export function useDeleteSettlement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/settlements/{settlementId}", {
          params: { path: { householdId, settlementId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.balances(householdId) }),
  });
}

// ---- Transfers ----

export function useTransfers(householdId: string, from: string, to: string, accountId?: string) {
  return useQuery({
    queryKey: keys.transfers(householdId, from, to, accountId),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/transfers", {
          params: { path: { householdId }, query: { from, to, ...(accountId ? { accountId } : {}) } },
        }),
      ),
  });
}

export function useSaveTransfer(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: TransferInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/transfers", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/transfers/{transferId}", {
              params: { path: { householdId, transferId: id } },
              body: input,
            }),
          ),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.transfers(householdId) });
      qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      qc.invalidateQueries({ queryKey: ["households", householdId, "activity"] });
    },
  });
}

export function useDeleteTransfer(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/transfers/{transferId}", {
          params: { path: { householdId, transferId: id }, query: { version } },
        }),
      ),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.transfers(householdId) });
      qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      qc.invalidateQueries({ queryKey: keys.bankRows(householdId) });
      qc.invalidateQueries({ queryKey: keys.bankFeeds(householdId) });
      qc.invalidateQueries({ queryKey: ["households", householdId, "activity"] });
    },
  });
}

// ---- Trips ----

export function useTrips(householdId: string) {
  return useQuery({
    queryKey: keys.trips(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/trips", { params: { path: { householdId } } })),
  });
}

export function useSaveTrip(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: TripInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/trips", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/trips/{tripId}", {
              params: { path: { householdId, tripId: id } },
              body: input,
            }),
          ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.trips(householdId) }),
  });
}

export function useDeleteTrip(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/trips/{tripId}", {
          params: { path: { householdId, tripId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.trips(householdId) }),
  });
}

// ---- Merchants ----

export function useMerchants(householdId: string) {
  return useQuery({
    queryKey: keys.merchants(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/merchants", { params: { path: { householdId } } })),
  });
}

/** Merchant names show on expenses, the overview and the activity log, so a change refreshes the whole household. */
function useInvalidateHousehold(householdId: string) {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: keys.household(householdId) });
}

export function useSaveMerchant(householdId: string) {
  const invalidate = useInvalidateHousehold(householdId);
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: MerchantInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/merchants", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/merchants/{merchantId}", {
              params: { path: { householdId, merchantId: id } },
              body: input,
            }),
          ),
    onSettled: invalidate,
  });
}

export function useMergeMerchant(householdId: string) {
  const invalidate = useInvalidateHousehold(householdId);
  return useMutation({
    mutationFn: async ({ id, version, intoId }: { id: string; version: number; intoId: string }) =>
      unwrap(
        await api.POST("/api/households/{householdId}/merchants/{merchantId}/merge", {
          params: { path: { householdId, merchantId: id } },
          body: { intoId, version },
        }),
      ),
    onSettled: invalidate,
  });
}

export function useDeleteMerchant(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/merchants/{merchantId}", {
          params: { path: { householdId, merchantId: id }, query: { version } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.merchants(householdId) }),
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

/** The expenses at one merchant, newest first (what a refund picks its original from); idle without a merchant. */
export function useMerchantExpenses(householdId: string, merchantId: string | undefined) {
  return useQuery({
    queryKey: [...keys.expenses(householdId), "merchant", merchantId] as const,
    enabled: merchantId !== undefined,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/expenses", {
          params: { path: { householdId }, query: { merchantId } },
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
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.expenses(householdId) });
      qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      // It may have added a merchant, and moves the totals of the others.
      qc.invalidateQueries({ queryKey: keys.merchants(householdId) });
      invalidateInbox(qc, householdId);
    },
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
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.expenses(householdId) });
      qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      // Its bank rows go back to the inbox.
      invalidateInbox(qc, householdId);
    },
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
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.income(householdId) });
      invalidateInbox(qc, householdId);
    },
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
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.income(householdId) });
      invalidateInbox(qc, householdId);
    },
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

export function useAssetSummary(householdId: string) {
  return useQuery({
    queryKey: keys.assetSummary(householdId),
    queryFn: async () =>
      unwrap(await api.GET("/api/households/{householdId}/assets/summary", { params: { path: { householdId } } })),
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

/** ČNB exchange rate for 1 unit of [from] in [to] on or before [date]. */
export function useFxRate(from: string | undefined, to: string, date: string) {
  return useQuery({
    queryKey: ["fx", from, to, date] as const,
    queryFn: async () =>
      unwrap(await api.GET("/api/securities/fx", { params: { query: { from: from ?? to, to, date } } })),
    enabled: Boolean(from && from !== to && date),
    staleTime: 60 * 60_000,
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

// ---- Import ----

/** Imports a file; with dryRun, only reports what it would create. A real import refreshes everything. */
export function useImport(householdId: string) {
  const qc = useQueryClient();
  const invalidate = useInvalidateHousehold(householdId);
  return useMutation({
    mutationFn: async ({ file, dryRun }: { file: HouseholdImport; dryRun: boolean }) =>
      unwrap(
        await api.POST("/api/households/{householdId}/import", {
          params: { path: { householdId }, query: { dryRun } },
          body: file,
        }),
      ),
    onSuccess: (result) => {
      if (!result.dryRun) {
        invalidate();
        qc.invalidateQueries({ queryKey: keys.households });
      }
    },
  });
}

export function useImports(householdId: string) {
  return useQuery({
    queryKey: keys.imports(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/imports", { params: { path: { householdId } } })),
  });
}

/** Undoes an import; with dryRun, only reports what it would remove, keep and unlink. */
export function useUndoImport(householdId: string) {
  const invalidate = useInvalidateHousehold(householdId);
  return useMutation({
    mutationFn: async ({ importId, dryRun }: { importId: string; dryRun: boolean }) =>
      unwrap(
        await api.POST("/api/households/{householdId}/imports/{id}/undo", {
          params: { path: { householdId, id: importId }, query: { dryRun } },
        }),
      ),
    onSuccess: (result) => {
      if (!result.dryRun) invalidate();
    },
  });
}

// ---- Inbox ----

function invalidateInbox(qc: ReturnType<typeof useQueryClient>, householdId: string) {
  qc.invalidateQueries({ queryKey: keys.bankRows(householdId) });
  qc.invalidateQueries({ queryKey: keys.bankFeeds(householdId) });
  qc.invalidateQueries({ queryKey: keys.bankConnections(householdId) });
  // Rules count the rows they filed.
  qc.invalidateQueries({ queryKey: keys.inboxRules(householdId) });
}

export function useBankConnections(householdId: string) {
  return useQuery({
    queryKey: keys.bankConnections(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/bank-connections", { params: { path: { householdId } } })),
  });
}

export function useBankInstitutions(householdId: string, country: string, enabled = true) {
  return useQuery({
    queryKey: keys.bankInstitutions(householdId, country),
    enabled,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/bank-institutions", {
          params: { path: { householdId }, query: { country } },
        }),
      ),
  });
}

export function useStartBankConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (institutionId: string) =>
      unwrap(
        await api.POST("/api/households/{householdId}/bank-connections", {
          params: { path: { householdId } },
          body: { institutionId },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.bankConnections(householdId) }),
  });
}

export function useFinishBankConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (connectionId: string) =>
      unwrap(
        await api.POST("/api/households/{householdId}/bank-connections/{connectionId}/finish", {
          params: { path: { householdId, connectionId } },
        }),
      ),
    onSettled: () => qc.invalidateQueries({ queryKey: keys.bankConnections(householdId) }),
  });
}

export function useCreateConnectionFeeds(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ connectionId, feeds }: { connectionId: string; feeds: FeedFromAccount[] }) =>
      unwrap(
        await api.POST("/api/households/{householdId}/bank-connections/{connectionId}/feeds", {
          params: { path: { householdId, connectionId } },
          body: { feeds },
        }),
      ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useSyncBankConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (connectionId: string) =>
      unwrap(
        await api.POST("/api/households/{householdId}/bank-connections/{connectionId}/sync", {
          params: { path: { householdId, connectionId } },
        }),
      ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useSyncBankFeed(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (feedId: string) =>
      unwrap(
        await api.POST("/api/households/{householdId}/bank-feeds/{feedId}/sync", {
          params: { path: { householdId, feedId } },
        }),
      ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useDeleteBankConnection(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/bank-connections/{connectionId}", {
          params: { path: { householdId, connectionId: id }, query: { version } },
        }),
      ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useBankFeeds(householdId: string) {
  return useQuery({
    queryKey: keys.bankFeeds(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/bank-feeds", { params: { path: { householdId } } })),
  });
}

export function useSaveBankFeed(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: BankFeedInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/bank-feeds", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/bank-feeds/{feedId}", {
              params: { path: { householdId, feedId: id } },
              body: input,
            }),
          ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useDeleteBankFeed(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/bank-feeds/{feedId}", {
          params: { path: { householdId, feedId: id }, query: { version } },
        }),
      ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useUploadStatement(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ feedId, content, fileName }: { feedId: string; content: string; fileName: string }) =>
      unwrap(
        await api.POST("/api/households/{householdId}/bank-feeds/{feedId}/statements", {
          params: { path: { householdId, feedId } },
          body: { content, fileName },
        }),
      ),
    onSettled: () => invalidateInbox(qc, householdId),
  });
}

export function useBankRows(householdId: string, status: BankRowStatus) {
  return useQuery({
    queryKey: keys.bankRows(householdId, status),
    queryFn: async () =>
      unwrap(
        await api.GET("/api/households/{householdId}/bank-rows", {
          params: { path: { householdId }, query: { status } },
        }),
      ),
  });
}

/** Files rows under an existing expense or income line, skips them, or puts them back. */
export function useDecideBankRows(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (
      decision:
        | { action: "link"; rows: BankRowRef[]; expenseId?: string; incomeId?: string }
        | { action: "skip"; rows: BankRowRef[]; reason: string }
        | { action: "transfer"; rows: BankRowRef[]; otherAccountId?: string | null; notes?: string }
        | { action: "settle"; rows: BankRowRef[]; fromUserId: string; toUserId: string; notes?: string }
        | { action: "undo"; rows: BankRowRef[] },
    ) => {
      const path = { householdId };
      switch (decision.action) {
        case "link":
          return unwrap(
            await api.POST("/api/households/{householdId}/bank-rows/link", {
              params: { path },
              body: { rows: decision.rows, expenseId: decision.expenseId, incomeId: decision.incomeId },
            }),
          );
        case "skip":
          return unwrap(
            await api.POST("/api/households/{householdId}/bank-rows/skip", {
              params: { path },
              body: { rows: decision.rows, reason: decision.reason },
            }),
          );
        case "transfer":
          return unwrap(
            await api.POST("/api/households/{householdId}/bank-rows/transfer", {
              params: { path },
              body: { rows: decision.rows, otherAccountId: decision.otherAccountId ?? null, notes: decision.notes ?? "" },
            }),
          );
        case "settle":
          return unwrap(
            await api.POST("/api/households/{householdId}/bank-rows/settle", {
              params: { path },
              body: { rows: decision.rows, fromUserId: decision.fromUserId, toUserId: decision.toUserId, notes: decision.notes ?? "" },
            }),
          );
        case "undo":
          return unwrap(await api.POST("/api/households/{householdId}/bank-rows/undo", { params: { path }, body: { rows: decision.rows } }));
      }
    },
    onSettled: (_data, _error, decision) => {
      invalidateInbox(qc, householdId);
      // Undoing a row made into a record deletes the record.
      if (decision.action === "undo") {
        qc.invalidateQueries({ queryKey: keys.expenses(householdId) });
        qc.invalidateQueries({ queryKey: keys.income(householdId) });
      }
      // Transfers change the joint contributions, settlements the balance, and undo may delete either.
      if (decision.action === "undo" || decision.action === "transfer" || decision.action === "settle") {
        qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      }
    },
  });
}

// ---- Inbox rules ----

export function useInboxRules(householdId: string) {
  return useQuery({
    queryKey: keys.inboxRules(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/inbox-rules", { params: { path: { householdId } } })),
  });
}

/** A rule changes what the inbox suggests, and the rows it filed keep pointing at it. */
export function useSaveInboxRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, input }: { id?: string; input: InboxRuleInput }) =>
      id === undefined
        ? unwrap(await api.POST("/api/households/{householdId}/inbox-rules", { params: { path: { householdId } }, body: input }))
        : unwrap(
            await api.PUT("/api/households/{householdId}/inbox-rules/{ruleId}", {
              params: { path: { householdId, ruleId: id } },
              body: input,
            }),
          ),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.inboxRules(householdId) });
      invalidateInbox(qc, householdId);
    },
  });
}

export function useDeleteInboxRule(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version }: { id: string; version: number }) =>
      unwrap(
        await api.DELETE("/api/households/{householdId}/inbox-rules/{ruleId}", {
          params: { path: { householdId, ruleId: id }, query: { version } },
        }),
      ),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.inboxRules(householdId) });
      invalidateInbox(qc, householdId);
    },
  });
}

/** Files what the rules and the matcher can file among the pending rows, which may make or link records. */
export function useApplyInboxRules(householdId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/households/{householdId}/bank-rows/apply-rules", { params: { path: { householdId } } })),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.inboxRules(householdId) });
      qc.invalidateQueries({ queryKey: keys.expenses(householdId) });
      qc.invalidateQueries({ queryKey: keys.income(householdId) });
      qc.invalidateQueries({ queryKey: keys.balances(householdId) });
      invalidateInbox(qc, householdId);
    },
  });
}

export function useHouseholdSettings(householdId: string) {
  return useQuery({
    queryKey: keys.settings(householdId),
    queryFn: async () => unwrap(await api.GET("/api/households/{householdId}/settings", { params: { path: { householdId } } })),
  });
}

export function useSaveHouseholdSettings(householdId: string) {
  const qc = useQueryClient();
  const invalidate = useInvalidateHousehold(householdId);
  return useMutation({
    mutationFn: async (settings: HouseholdSettings) =>
      unwrap(await api.PUT("/api/households/{householdId}/settings", { params: { path: { householdId } }, body: settings })),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.settings(householdId) });
      qc.invalidateQueries({ queryKey: keys.households });
      invalidate();
    },
  });
}

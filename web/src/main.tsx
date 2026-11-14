import { StrictMode, useEffect } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Navigate, Route, Routes, useLocation } from "react-router";
import { AccountsPage } from "./accounts/AccountsPage";
import { ActivityPage } from "./activity/ActivityPage";
import { LoginPage, SignupPage } from "./auth/AuthPages";
import { RequireAuth } from "./auth/RequireAuth";
import { CategoriesPage } from "./categories/CategoriesPage";
import { ExpensesPage } from "./expenses/ExpensesPage";
import { IncomePage } from "./income/IncomePage";
import { TransfersPage } from "./transfers/TransfersPage";
import { AssetsPage } from "./assets/AssetsPage";
import { TripsPage } from "./trips/TripsPage";
import { RecurringPage } from "./recurring/RecurringPage";
import { ReportsPage } from "./reports/ReportsPage";
import { BalancesPage } from "./debts/BalancesPage";
import { MerchantsPage } from "./merchants/MerchantsPage";
import { ImportPage } from "./import/ImportPage";
import { InboxPage } from "./inbox/InboxPage";
import { BankConnectedPage } from "./inbox/BankConnectedPage";
import { RulesPage } from "./inbox/RulesPage";
import { HomeRedirect, HouseholdLayout, OnboardingPage } from "./households/HouseholdPages";
import { InvitePage } from "./households/InvitePage";
import { OverviewPage } from "./overview/OverviewPage";
import { MembersPage } from "./households/MembersPage";
import { DialogsProvider } from "./dialogs";
import { TooltipProvider } from "@/components/ui/tooltip";
import "./styles.css";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

const APP_TITLE = "Finance Tracker";

const PAGE_TITLES: Record<string, string> = {
  overview: "Overview",
  inbox: "Inbox",
  "bank-connected": "Bank connected",
  rules: "Rules",
  expenses: "Expenses",
  income: "Income",
  transfers: "Transfers",
  assets: "Assets",
  merchants: "Merchants",
  balances: "Balances",
  trips: "Trips",
  recurring: "Recurring",
  accounts: "Accounts",
  categories: "Categories",
  members: "Members",
  activity: "Activity",
  import: "Import",
  login: "Log in",
  signup: "Sign up",
  onboarding: "New household",
  invite: "Invite",
};

/** Keeps the browser tab title in sync with the active route. */
function PageTitle() {
  const { pathname } = useLocation();
  useEffect(() => {
    const segments = pathname.split("/").filter(Boolean);
    const key = segments[0] === "h" ? segments[2] : segments[0];
    const page = key ? PAGE_TITLES[key] : undefined;
    document.title = page ? `${page} | ${APP_TITLE}` : APP_TITLE;
  }, [pathname]);
  return null;
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
      <DialogsProvider>
      <BrowserRouter>
        <PageTitle />
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/invite/:token" element={<InvitePage />} />
          <Route element={<RequireAuth />}>
            <Route index element={<HomeRedirect />} />
            <Route path="/onboarding" element={<OnboardingPage />} />
            <Route path="/h/:householdId" element={<HouseholdLayout />}>
              <Route index element={<Navigate to="overview" replace />} />
              <Route path="overview" element={<OverviewPage />} />
              <Route path="inbox" element={<InboxPage />} />
              <Route path="bank-connected" element={<BankConnectedPage />} />
              <Route path="rules" element={<RulesPage />} />
              <Route path="expenses" element={<ExpensesPage />} />
              <Route path="income" element={<IncomePage />} />
              <Route path="transfers" element={<TransfersPage />} />
              <Route path="assets" element={<AssetsPage />} />
              <Route path="merchants" element={<MerchantsPage />} />
              <Route path="balances" element={<BalancesPage />} />
              <Route path="trips" element={<TripsPage />} />
              <Route path="recurring" element={<RecurringPage />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="accounts" element={<AccountsPage />} />
              <Route path="categories" element={<CategoriesPage />} />
              <Route path="members" element={<MembersPage />} />
              <Route path="activity" element={<ActivityPage />} />
              <Route path="import" element={<ImportPage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
      </DialogsProvider>
      </TooltipProvider>
    </QueryClientProvider>
  </StrictMode>,
);

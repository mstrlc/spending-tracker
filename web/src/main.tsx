import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AccountsPage } from "./accounts/AccountsPage";
import { ActivityPage } from "./activity/ActivityPage";
import { LoginPage, SignupPage } from "./auth/AuthPages";
import { RequireAuth } from "./auth/RequireAuth";
import { CategoriesPage } from "./categories/CategoriesPage";
import { ExpensesPage } from "./expenses/ExpensesPage";
import { IncomePage } from "./income/IncomePage";
import { AssetsPage } from "./assets/AssetsPage";
import { TripsPage } from "./trips/TripsPage";
import { BalancesPage } from "./debts/BalancesPage";
import { MerchantsPage } from "./merchants/MerchantsPage";
import { ImportPage } from "./import/ImportPage";
import { InboxPage } from "./inbox/InboxPage";
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

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
      <DialogsProvider>
      <BrowserRouter>
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
              <Route path="expenses" element={<ExpensesPage />} />
              <Route path="income" element={<IncomePage />} />
              <Route path="assets" element={<AssetsPage />} />
              <Route path="merchants" element={<MerchantsPage />} />
              <Route path="balances" element={<BalancesPage />} />
              <Route path="trips" element={<TripsPage />} />
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

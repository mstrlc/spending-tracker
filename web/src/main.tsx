import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter, Navigate, Route, Routes } from "react-router";
import { AccountsPage } from "./accounts/AccountsPage";
import { LoginPage, SignupPage } from "./auth/AuthPages";
import { RequireAuth } from "./auth/RequireAuth";
import { CategoriesPage } from "./categories/CategoriesPage";
import { ExpensesPage } from "./expenses/ExpensesPage";
import { HomeRedirect, HouseholdLayout, OnboardingPage } from "./households/HouseholdPages";
import "./styles.css";

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route element={<RequireAuth />}>
            <Route index element={<HomeRedirect />} />
            <Route path="/onboarding" element={<OnboardingPage />} />
            <Route path="/h/:householdId" element={<HouseholdLayout />}>
              <Route index element={<Navigate to="expenses" replace />} />
              <Route path="expenses" element={<ExpensesPage />} />
              <Route path="accounts" element={<AccountsPage />} />
              <Route path="categories" element={<CategoriesPage />} />
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);

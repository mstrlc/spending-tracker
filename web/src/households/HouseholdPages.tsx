import { createContext, useContext, useState, type FormEvent, type ReactNode } from "react";
import { Link, Navigate, NavLink, Outlet, useLocation, useNavigate, useParams } from "react-router";
import {
  ArrowLeftRightIcon,
  ChevronsUpDownIcon,
  GaugeIcon,
  HandCoinsIcon,
  HistoryIcon,
  ImportIcon,
  InboxIcon,
  ListFilterIcon,
  KeyRoundIcon,
  LogOutIcon,
  LuggageIcon,
  StoreIcon,
  PlusIcon,
  ReceiptIcon,
  TagIcon,
  ScaleIcon,
  TrendingUpIcon,
  RepeatIcon,
  ChartColumnIcon,
  UsersIcon,
  WalletIcon,
} from "lucide-react";
import { useBankFeeds, useCreateHousehold, useHouseholds, useLogout, useMe } from "../api/hooks";
import type { Household } from "../api/types";
import { AuthShell } from "../auth/AuthShell";
import { ChangePasswordDialog } from "../auth/PasswordDialog";
import { ErrorText, Field, FormError, Loading, SheetSlot } from "../ui";
import { UndoFlusher } from "../undo";
import { Toaster } from "@/components/ui/sonner";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { SubmitButton } from "@/ui";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
  useSidebar,
} from "@/components/ui/sidebar";

const CURRENCIES = ["CZK", "EUR", "USD", "GBP", "PLN", "CHF", "SEK", "NOK", "DKK", "HUF"];

const HouseholdContext = createContext<Household | null>(null);

/** The household of the current /h/:householdId route. */
export function useHousehold(): Household {
  const household = useContext(HouseholdContext);
  if (!household) throw new Error("useHousehold outside HouseholdLayout");
  return household;
}

/** "/" sends the user to their first household, or to onboarding when they have none. */
export function HomeRedirect() {
  const households = useHouseholds();
  if (households.isPending) return <Loading />;
  if (households.isError) return <ErrorText error={households.error} />;
  const first = households.data[0];
  return <Navigate to={first ? `/h/${first.id}/overview` : "/onboarding"} replace />;
}

export function OnboardingPage() {
  const create = useCreateHousehold();
  const households = useHouseholds();
  const navigate = useNavigate();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("CZK");
  const hasHouseholds = (households.data?.length ?? 0) > 0;

  function submit(e: FormEvent) {
    e.preventDefault();
    create.mutate({ name, currency }, { onSuccess: (h) => navigate(`/h/${h.id}/overview`, { replace: true }) });
  }

  return (
    <AuthShell
      title={hasHouseholds ? "New household" : "Set up your household"}
      description="A household holds the accounts and expenses you track, alone or with others."
      footer={hasHouseholds && <Link to="/">Back to your household</Link>}
    >
      <form onSubmit={submit} className="flex flex-col gap-4">
        <Field label="Household name" name="name" error={create.error}>
          <Input required placeholder="Home" value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Currency" name="currency" error={create.error}>
          <NativeSelect className="w-full" value={currency} onChange={(e) => setCurrency(e.target.value)}>
            {CURRENCIES.map((c) => (
              <NativeSelectOption key={c}>{c}</NativeSelectOption>
            ))}
          </NativeSelect>
        </Field>
        <FormError error={create.error} fields={["name", "currency"]} />
        <SubmitButton size="lg" className="w-full" disabled={create.isPending}>
          Create household
        </SubmitButton>
      </form>
    </AuthShell>
  );
}

type Section = { to: string; label: string; icon: ReactNode; ownerOnly?: boolean };

/** The sidebar's sections in groups: the daily ones first, unlabelled, then money, organising and setup. */
const SECTION_GROUPS: { label?: string; sections: Section[] }[] = [
  {
    sections: [
      { to: "overview", label: "Overview", icon: <GaugeIcon /> },
      { to: "inbox", label: "Inbox", icon: <InboxIcon /> },
    ],
  },
  {
    label: "Money",
    sections: [
      { to: "expenses", label: "Expenses", icon: <ReceiptIcon /> },
      { to: "income", label: "Income", icon: <HandCoinsIcon /> },
      { to: "transfers", label: "Transfers", icon: <ArrowLeftRightIcon /> },
      { to: "balances", label: "Balances", icon: <ScaleIcon /> },
      { to: "assets", label: "Assets", icon: <TrendingUpIcon /> },
      { to: "recurring", label: "Recurring", icon: <RepeatIcon /> },
      { to: "reports", label: "Reports", icon: <ChartColumnIcon /> },
    ],
  },
  {
    label: "Organise",
    sections: [
      { to: "trips", label: "Trips", icon: <LuggageIcon /> },
      { to: "merchants", label: "Merchants", icon: <StoreIcon /> },
      { to: "categories", label: "Categories", icon: <TagIcon /> },
      { to: "rules", label: "Rules", icon: <ListFilterIcon /> },
    ],
  },
  {
    label: "Household",
    sections: [
      { to: "accounts", label: "Accounts", icon: <WalletIcon /> },
      { to: "members", label: "Members", icon: <UsersIcon /> },
      { to: "activity", label: "Activity", icon: <HistoryIcon /> },
      { to: "import", label: "Import", icon: <ImportIcon />, ownerOnly: true },
    ],
  },
];

export function HouseholdLayout() {
  const { householdId } = useParams();
  const households = useHouseholds();
  const [sheetSlot, setSheetSlot] = useState<HTMLElement | null>(null);

  if (households.isPending) return <Loading />;
  if (households.isError) return <ErrorText error={households.error} />;
  const household = households.data.find((h) => h.id === householdId);
  if (!household) return households.isFetching ? <Loading /> : <Navigate to="/" replace />;

  return (
    <HouseholdContext.Provider value={household}>
      <SidebarProvider>
        <AppSidebar household={household} households={households.data} />
        <SidebarInset className="min-w-0">
          <header className="sticky top-0 z-20 flex h-12 items-center gap-2 border-b bg-background px-3 md:hidden">
            <SidebarTrigger />
            <span className="truncate font-semibold">{household.name}</span>
          </header>
          <div className="flex min-w-0 flex-1">
            <SheetSlot.Provider value={sheetSlot}>
              <div className="flex min-w-0 flex-1 flex-col gap-6 px-4 py-6 md:px-8 md:py-8">
                <Outlet />
              </div>
            </SheetSlot.Provider>
            <div ref={setSheetSlot} className="flex flex-col empty:hidden md:sticky md:top-0 md:h-svh md:w-[440px] md:shrink-0 md:self-start md:overflow-y-auto md:overscroll-contain md:border-l" />
          </div>
        </SidebarInset>
      </SidebarProvider>
      <UndoFlusher />
      <Toaster />
    </HouseholdContext.Provider>
  );
}

/** Household switcher on top, the sections, then who is logged in. Becomes a drawer on phones. */
function AppSidebar({ household, households }: { household: Household; households: Household[] }) {
  const me = useMe();
  const logout = useLogout();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const { setOpenMobile } = useSidebar();
  const inboxCount = useBankFeeds(household.id).data?.reduce((sum, f) => sum + f.pendingCount, 0) ?? 0;
  const name = me.data?.displayName ?? "";
  const logOut = () => logout.mutate(undefined, { onSuccess: () => navigate("/login") });
  const [changingPassword, setChangingPassword] = useState(false);

  return (
    <Sidebar>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <SidebarMenuButton size="lg" aria-label={`Household: ${household.name}`}>
                  <span className="flex aspect-square size-8 items-center justify-center rounded-md bg-sidebar-primary text-sm font-semibold text-sidebar-primary-foreground">
                    {household.name.slice(0, 1).toUpperCase()}
                  </span>
                  <span className="grid flex-1 text-left leading-tight">
                    <span className="truncate font-semibold">{household.name}</span>
                    <span className="truncate text-xs text-muted-foreground">{household.currency}</span>
                  </span>
                  <ChevronsUpDownIcon className="ml-auto" />
                </SidebarMenuButton>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="w-(--radix-dropdown-menu-trigger-width) min-w-56">
                <DropdownMenuLabel className="text-xs text-muted-foreground">Households</DropdownMenuLabel>
                {households.map((h) => (
                  <DropdownMenuItem
                    key={h.id}
                    onSelect={() => {
                      setOpenMobile(false);
                      navigate(`/h/${h.id}/overview`);
                    }}
                  >
                    <span className="truncate">{h.name}</span>
                    {h.id === household.id && <span className="ml-auto text-xs text-muted-foreground">Current</span>}
                  </DropdownMenuItem>
                ))}
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => navigate("/onboarding")}>
                  <PlusIcon />
                  New household
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <nav aria-label="Sections" className="contents">
          {SECTION_GROUPS.map((group, i) => (
            <SidebarGroup key={group.label ?? i} className="py-1">
              {group.label && <SidebarGroupLabel>{group.label}</SidebarGroupLabel>}
              <SidebarMenu aria-label={group.label}>
                {group.sections
                  .filter((s) => !s.ownerOnly || household.role === "owner")
                  .map((s) => (
                    <SidebarMenuItem key={s.to}>
                      <SidebarMenuButton asChild isActive={pathname.startsWith(`/h/${household.id}/${s.to}`)}>
                        <NavLink to={s.to} onClick={() => setOpenMobile(false)}>
                          {s.icon}
                          <span>{s.label}</span>
                        </NavLink>
                      </SidebarMenuButton>
                      {s.to === "inbox" && inboxCount > 0 && (
                        <SidebarMenuBadge aria-label={`${inboxCount} to decide`}>{inboxCount}</SidebarMenuBadge>
                      )}
                    </SidebarMenuItem>
                  ))}
              </SidebarMenu>
            </SidebarGroup>
          ))}
        </nav>
      </SidebarContent>

      <SidebarFooter>
        <div className="flex items-center gap-2 p-1">
          <Avatar className="size-8">
            <AvatarFallback className="text-sm">{name.slice(0, 1).toUpperCase()}</AvatarFallback>
          </Avatar>
          <span className="grid min-w-0 flex-1 text-sm leading-tight">
            <span className="truncate font-medium">{name}</span>
            <span className="truncate text-xs text-muted-foreground">{me.data?.email}</span>
          </span>
          <Button
            variant="ghost"
            size="icon"
            aria-label="Change password"
            title="Change password"
            onClick={() => setChangingPassword(true)}
          >
            <KeyRoundIcon />
          </Button>
          <Button variant="ghost" size="icon" aria-label="Log out" title="Log out" onClick={logOut}>
            <LogOutIcon />
          </Button>
        </div>
        <ChangePasswordDialog open={changingPassword} onOpenChange={setChangingPassword} />
      </SidebarFooter>
    </Sidebar>
  );
}

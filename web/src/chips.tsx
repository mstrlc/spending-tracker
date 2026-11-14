import type { CSSProperties, ReactNode } from "react";
import {
  ArrowDownLeftIcon,
  ArrowLeftRightIcon,
  ArrowUpRightIcon,
  BanIcon,
  BanknoteIcon,
  BitcoinIcon,
  BoxIcon,
  CircleCheckIcon,
  CircleXIcon,
  ClockAlertIcon,
  ClockIcon,
  CarIcon,
  ChartCandlestickIcon,
  ChartPieIcon,
  CreditCardIcon,
  HandCoinsIcon,
  HandshakeIcon,
  HouseIcon,
  LandmarkIcon,
  PiggyBankIcon,
  ReceiptTextIcon,
  ScrollTextIcon,
  ShieldCheckIcon,
  TrendingUpIcon,
  Undo2Icon,
  UsersIcon,
  WalletIcon,
  type LucideIcon,
} from "lucide-react";
import type { AccountKind, AssetKind, BankConnectionStatus, Category, Member } from "./api/types";
import { cn } from "@/lib/utils";

/**
 * Colour that means something: a kind of record or a state (`--tone-*`), or one of eight hues that only tell
 * categories and people apart (`--hue-*`). "neutral" is plain grey.
 */
export type Tone =
  | "neutral"
  | "income"
  | "expense"
  | "deduction"
  | "transfer"
  | "settlement"
  | "warning"
  | "danger"
  | Hue;
export type Hue = `hue-${1 | 2 | 3 | 4 | 5 | 6 | 7 | 8}`;

const toneStyle = (tone: Tone) => (tone === "neutral" ? undefined : ({ "--t": `var(--${tone.startsWith("hue-") ? tone : `tone-${tone}`})` } as CSSProperties));

/** A small rounded label tinted with [tone], with an optional icon before the text. */
export function Chip({
  tone = "neutral",
  icon: Icon,
  children,
  className,
  title,
}: {
  tone?: Tone;
  icon?: LucideIcon;
  children: ReactNode;
  className?: string;
  title?: string;
}) {
  return (
    <span
      title={title}
      style={toneStyle(tone)}
      className={cn(
        "inline-flex h-5 w-fit max-w-full shrink-0 items-center gap-1 rounded-full px-2 text-xs font-medium whitespace-nowrap [&>svg]:size-3 [&>svg]:shrink-0",
        tone === "neutral"
          ? "bg-muted text-muted-foreground"
          : "bg-[color-mix(in_oklch,var(--t)_14%,transparent)] text-[color-mix(in_oklch,var(--t)_78%,var(--foreground))]",
        className,
      )}
    >
      {Icon && <Icon aria-hidden="true" />}
      <span className="truncate">{children}</span>
    </span>
  );
}

/** A coloured dot, for a category beside its name or a legend. */
export function Dot({ tone, className }: { tone: Tone; className?: string }) {
  return (
    <span
      aria-hidden="true"
      style={toneStyle(tone)}
      className={cn(
        "inline-block size-2 shrink-0 rounded-full",
        tone === "neutral" ? "border border-dashed border-muted-foreground/60" : "bg-(--t)",
        className,
      )}
    />
  );
}

// ---- What a record is --------------------------------------------------------------------------------------------

export type RecordKind = "expense" | "refund" | "income" | "deduction" | "transfer" | "settlement" | "contribution" | "skip";

export const RECORD_KINDS: Record<RecordKind, { label: string; icon: LucideIcon; tone: Tone }> = {
  expense: { label: "Expense", icon: ArrowUpRightIcon, tone: "expense" },
  refund: { label: "Refund", icon: Undo2Icon, tone: "income" },
  income: { label: "Income", icon: ArrowDownLeftIcon, tone: "income" },
  deduction: { label: "Deduction", icon: ReceiptTextIcon, tone: "deduction" },
  transfer: { label: "Transfer", icon: ArrowLeftRightIcon, tone: "transfer" },
  settlement: { label: "Settlement", icon: HandshakeIcon, tone: "settlement" },
  contribution: { label: "Investment", icon: TrendingUpIcon, tone: "transfer" },
  skip: { label: "Skip", icon: BanIcon, tone: "neutral" },
};

/** The chip saying what a record is (or a bank row became): expense, income, transfer… [children] replaces the label. */
export function KindChip({ kind, children, className }: { kind: RecordKind; children?: ReactNode; className?: string }) {
  const k = RECORD_KINDS[kind];
  return (
    <Chip tone={k.tone} icon={k.icon} className={className}>
      {children ?? k.label}
    </Chip>
  );
}

/** Just the kind's icon in its tone, for a dense row; the label goes to screen readers. */
export function KindIcon({ kind, className }: { kind: RecordKind; className?: string }) {
  const k = RECORD_KINDS[kind];
  const Icon = k.icon;
  return (
    <span
      role="img"
      aria-label={k.label}
      title={k.label}
      style={toneStyle(k.tone)}
      className={cn(
        "inline-flex size-6 shrink-0 items-center justify-center rounded-full [&>svg]:size-3.5",
        k.tone === "neutral"
          ? "bg-muted text-muted-foreground"
          : "bg-[color-mix(in_oklch,var(--t)_14%,transparent)] text-[color-mix(in_oklch,var(--t)_78%,var(--foreground))]",
        className,
      )}
    >
      <Icon aria-hidden="true" />
    </span>
  );
}

/** Colour for a signed amount: money in green, money out left plain. */
export const signedAmountClass = (minor: number) => (minor > 0 ? "text-(--tone-income)" : undefined);

// ---- Categories ---------------------------------------------------------------------------------------------------

/**
 * A category's hue, taken from its top-level category so a subcategory matches its parent. Top-level categories take
 * the eight hues in the household's order, income ones counted apart from the rest since they're never listed
 * together, so neighbours never share one; [categories] must be the whole list.
 */
export function categoryTone(
  categories: (Pick<Category, "id" | "parentId"> & { kind?: Category["kind"] })[],
  id: string | null | undefined,
): Tone {
  const category = categories.find((c) => c.id === id);
  if (!category) return "neutral";
  const rootId = category.parentId ?? category.id;
  const income = categories.find((c) => c.id === rootId)?.kind === "income";
  const index = categories.filter((c) => !c.parentId && (c.kind === "income") === income).findIndex((c) => c.id === rootId);
  return index < 0 ? "neutral" : (`hue-${(index % 8) + 1}` as Hue);
}

/** A category's name after a dot in its colour; "Parent › Child" for a subcategory, unless [short]. */
export function CategoryLabel({
  categories,
  id,
  short,
  className,
}: {
  categories: Category[];
  id: string | null | undefined;
  short?: boolean;
  className?: string;
}) {
  const category = categories.find((c) => c.id === id);
  const parent = category?.parentId ? categories.find((c) => c.id === category.parentId) : undefined;
  return (
    <span className={cn("inline-flex min-w-0 max-w-full items-center gap-1.5", className)}>
      <Dot tone={categoryTone(categories, id)} />
      {category ? (
        <span className="truncate">
          {parent && !short && <span className="text-muted-foreground">{parent.name} › </span>}
          {category.name}
        </span>
      ) : (
        <span className="text-muted-foreground">Uncategorized</span>
      )}
    </span>
  );
}

// ---- People -------------------------------------------------------------------------------------------------------

const initials = (name: string) =>
  name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]!.toUpperCase())
    .join("") || "?";

/**
 * A member's initials in a circle of their colour; [userId] null is joint (everyone). Members take hues in the
 * household's order, so a couple never shares one.
 */
export function Avatar({
  members,
  userId,
  className,
}: {
  members: Pick<Member, "userId" | "displayName">[];
  userId: string | null | undefined;
  className?: string;
}) {
  const index = members.findIndex((m) => m.userId === userId);
  const member = index >= 0 ? members[index] : undefined;
  const base = "inline-flex size-5 shrink-0 items-center justify-center rounded-full text-[0.6rem] font-semibold leading-none";
  if (!member) {
    return (
      <span aria-hidden="true" className={cn(base, "bg-muted text-muted-foreground [&>svg]:size-3", className)}>
        <UsersIcon />
      </span>
    );
  }
  return (
    <span
      aria-hidden="true"
      style={toneStyle(`hue-${(index % 8) + 1}` as Hue)}
      className={cn(base, "bg-[color-mix(in_oklch,var(--t)_22%,transparent)] text-[color-mix(in_oklch,var(--t)_70%,var(--foreground))]", className)}
    >
      {initials(member.displayName)}
    </span>
  );
}

/** A member's hue, as on their avatar; neutral for nobody in particular. */
export function memberTone(members: Pick<Member, "userId">[], userId: string | null | undefined): Tone {
  const index = members.findIndex((m) => m.userId === userId);
  return index < 0 ? "neutral" : (`hue-${(index % 8) + 1}` as Hue);
}

/** An avatar and the member's name; [jointLabel] for nobody in particular ("Joint", "Shared"). */
export function PersonLabel({
  members,
  userId,
  jointLabel = "Joint",
  className,
}: {
  members: Pick<Member, "userId" | "displayName">[];
  userId: string | null | undefined;
  jointLabel?: string;
  className?: string;
}) {
  const name = members.find((m) => m.userId === userId)?.displayName ?? jointLabel;
  return (
    <span className={cn("inline-flex min-w-0 max-w-full items-center gap-1.5", className)}>
      <Avatar members={members} userId={userId} />
      <span className="truncate">{name}</span>
    </span>
  );
}

/**
 * Whose an expense is: one person (or joint), or, when its split lines belong to different people, every whose on
 * it as overlapping avatars labelled "Mixed".
 */
export function ExpenseWhose({
  members,
  expense,
  className,
}: {
  members: Pick<Member, "userId" | "displayName">[];
  expense: { paidByUserId: string | null; lines: { paidByUserId: string | null }[] };
  className?: string;
}) {
  const owners = [...new Set(expense.lines.map((l) => l.paidByUserId))];
  if (owners.length < 2) return <PersonLabel members={members} userId={owners[0] ?? expense.paidByUserId} className={className} />;
  const names = owners.map((o) => members.find((m) => m.userId === o)?.displayName ?? "Joint").join(", ");
  return (
    <span className={cn("inline-flex min-w-0 max-w-full items-center gap-1.5", className)} title={names}>
      <span className="flex -space-x-1.5">
        {owners.map((o) => (
          <Avatar key={o ?? "joint"} members={members} userId={o} className="ring-2 ring-background" />
        ))}
      </span>
      <span className="truncate">Mixed</span>
      <span className="sr-only">: {names}</span>
    </span>
  );
}

// ---- Accounts and assets ------------------------------------------------------------------------------------------

const ACCOUNT_ICONS: Record<AccountKind, LucideIcon> = {
  bank: LandmarkIcon,
  savings: PiggyBankIcon,
  credit_card: CreditCardIcon,
  cash: BanknoteIcon,
  other: WalletIcon,
};

/** Each kind of account keeps one hue wherever it shows; savings match savings assets, cards the debt tone. */
const ACCOUNT_KIND_TONES: Record<AccountKind, Tone> = {
  bank: "hue-5",
  savings: "hue-4",
  credit_card: "deduction",
  cash: "hue-3",
  other: "neutral",
};

export const accountKindTone = (kind: AccountKind | undefined): Tone => ACCOUNT_KIND_TONES[kind ?? "other"];

const tintedText = "text-[color-mix(in_oklch,var(--t)_80%,var(--foreground))]";

/** The icon for a kind of account (current, savings, card, cash, other), in the kind's hue. */
export function AccountIcon({ kind, className }: { kind: AccountKind | undefined; className?: string }) {
  const Icon = ACCOUNT_ICONS[kind ?? "other"];
  const tone = accountKindTone(kind);
  return (
    <Icon
      aria-hidden="true"
      style={toneStyle(tone)}
      className={cn("size-3.5 shrink-0", tone === "neutral" ? "text-muted-foreground" : tintedText, className)}
    />
  );
}

/** An account kind's icon in a tinted square, like [AssetIcon], for tables that list accounts beside assets. */
export function AccountKindIcon({ kind, className }: { kind: AccountKind | undefined; className?: string }) {
  const tone = accountKindTone(kind);
  return (
    <span
      aria-hidden="true"
      style={toneStyle(tone)}
      className={cn(
        "inline-flex size-8 shrink-0 items-center justify-center rounded-md",
        tone === "neutral" ? "bg-muted" : "bg-[color-mix(in_oklch,var(--t)_16%,transparent)]",
        className,
      )}
    >
      <AccountIcon kind={kind} className="size-4" />
    </span>
  );
}

/** An account's name after its kind's icon. */
export function AccountLabel({
  account,
  className,
}: {
  account: { name: string; kind: AccountKind } | undefined;
  className?: string;
}) {
  if (!account) return null;
  return (
    <span className={cn("inline-flex min-w-0 max-w-full items-center gap-1.5", className)}>
      <AccountIcon kind={account.kind} />
      <span className="truncate">{account.name}</span>
    </span>
  );
}

const ASSET_ICONS: Record<AssetKind, LucideIcon> = {
  fund: ChartPieIcon,
  stock: ChartCandlestickIcon,
  bond: ScrollTextIcon,
  crypto: BitcoinIcon,
  savings: PiggyBankIcon,
  scheme: ShieldCheckIcon,
  receivable: HandCoinsIcon,
  vehicle: CarIcon,
  property: HouseIcon,
  other: BoxIcon,
  loan: LandmarkIcon,
  card_debt: CreditCardIcon,
  payable: ReceiptTextIcon,
};

/** Each kind of asset keeps one hue wherever it shows (icons, the share bar); debts are in the deduction tone. */
const ASSET_KIND_TONES: Record<AssetKind, Tone> = {
  fund: "hue-6",
  stock: "hue-7",
  bond: "hue-5",
  crypto: "hue-2",
  savings: "hue-4",
  scheme: "hue-5",
  receivable: "hue-8",
  vehicle: "hue-1",
  property: "hue-3",
  other: "neutral",
  loan: "deduction",
  card_debt: "deduction",
  payable: "deduction",
};

export const assetKindTone = (kind: AssetKind): Tone => ASSET_KIND_TONES[kind];

/** An asset kind's icon in a square tinted with the kind's hue. */
export function AssetIcon({ kind, className }: { kind: AssetKind; className?: string }) {
  const Icon = ASSET_ICONS[kind];
  const tone = assetKindTone(kind);
  return (
    <span
      aria-hidden="true"
      style={toneStyle(tone)}
      className={cn(
        "inline-flex size-7 shrink-0 items-center justify-center rounded-md [&>svg]:size-4",
        tone !== "neutral"
          ? "bg-[color-mix(in_oklch,var(--t)_16%,transparent)] text-[color-mix(in_oklch,var(--t)_80%,var(--foreground))]"
          : "bg-muted text-muted-foreground",
        className,
      )}
    >
      <Icon />
    </span>
  );
}

// ---- States -------------------------------------------------------------------------------------------------------

const CONNECTION_STATES: Record<BankConnectionStatus, { label: string; tone: Tone; icon: LucideIcon }> = {
  pending: { label: "Waiting for the bank", tone: "warning", icon: ClockIcon },
  active: { label: "Connected", tone: "income", icon: CircleCheckIcon },
  expired: { label: "Expired", tone: "warning", icon: ClockAlertIcon },
  revoked: { label: "Revoked", tone: "danger", icon: CircleXIcon },
  failed: { label: "Failed", tone: "danger", icon: CircleXIcon },
};

/** A bank connection's state: green while connected, amber while waiting or expired, red when it's gone. */
export function ConnectionStatus({ status }: { status: BankConnectionStatus }) {
  const s = CONNECTION_STATES[status];
  return (
    <Chip tone={s.tone} icon={s.icon}>
      {s.label}
    </Chip>
  );
}

/** Colour for a gain or return: up in green, down in red, nothing for zero. */
export const gainClass = (value: number) => (value > 0 ? "text-(--tone-income)" : value < 0 ? "text-destructive" : undefined);

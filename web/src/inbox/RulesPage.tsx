import { useState } from "react";
import { LightbulbIcon, PlusIcon, ZapIcon } from "lucide-react";
import type { ReactNode } from "react";
import { Chip, KindChip, type RecordKind } from "../chips";
import {
  useAccounts,
  useApplyInboxRules,
  useBankFeeds,
  useCategories,
  useDeleteInboxRule,
  useHouseholdSettings,
  useInboxRules,
  useMembers,
  useSaveHouseholdSettings,
} from "../api/hooks";
import type { ApplyRulesResult, InboxRule } from "../api/types";
import { categoryLabel } from "../categories/tree";
import { useConfirm } from "../dialogs";
import { useHousehold } from "../households/HouseholdPages";
import { EmptyState, ErrorText, PageHeader, PageIntro, RowMenu, Sheet, SkeletonRows, Surface } from "../ui";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { RuleForm } from "./RuleForm";
import { monthLabel, ruleConditions } from "./ruleDrafts";

/** What applying the rules did, in a sentence; "Nothing to file" when it did nothing. */
export function describeApplied(r: ApplyRulesResult): string {
  const rows = (n: number) => (n === 1 ? "1 row" : `${n} rows`);
  const parts = [
    r.filedByRules > 0 &&
      `${rows(r.filedByRules)} filed by rules${r.linkedByRules > 0 ? ` (${r.linkedByRules} under something you had already entered)` : ""}`,
    r.pairedByMatcher > 0 && `${rows(r.pairedByMatcher)} paired as transfers`,
    r.duplicatesByMatcher > 0 && `${rows(r.duplicatesByMatcher)} filed under an expense you had already entered`,
  ].filter(Boolean);
  return parts.length === 0 ? `Nothing to file among the ${rows(r.checked)} waiting.` : `${parts.join(", ")}.`;
}

export function RulesPage() {
  const household = useHousehold();
  const rules = useInboxRules(household.id);
  const feeds = useBankFeeds(household.id).data ?? [];
  const accounts = useAccounts(household.id).data ?? [];
  const members = useMembers(household.id).data ?? [];
  const categories = useCategories(household.id).data ?? [];
  const settings = useHouseholdSettings(household.id);
  const saveSettings = useSaveHouseholdSettings(household.id);
  const remove = useDeleteInboxRule(household.id);
  const apply = useApplyInboxRules(household.id);
  const confirm = useConfirm();
  const [editor, setEditor] = useState<{ open: boolean; rule?: InboxRule }>({ open: false });
  const isOwner = household.role === "owner";

  const accountName = (id: string | null) => accounts.find((a) => a.id === id)?.name ?? "";
  const memberName = (id: string | null) => members.find((m) => m.userId === id)?.displayName;

  /** What the rule makes of a row, in a phrase. */
  function becomes(rule: InboxRule): ReactNode {
    const kind: RecordKind = rule.outcome;
    const detail = becomesDetail(rule);
    return (
      <span className="inline-flex flex-wrap items-center gap-x-2 gap-y-1">
        <KindChip kind={kind} />
        {detail && <span>{detail}</span>}
      </span>
    );
  }

  function becomesDetail(rule: InboxRule): string {
    switch (rule.outcome) {
      case "expense":
        return [rule.merchant, categoryLabel(categories, rule.categoryId), memberName(rule.personUserId)].filter(Boolean).join(" · ");
      case "income":
        return [rule.incomeSource, memberName(rule.personUserId)].filter(Boolean).join(" · ");
      case "deduction":
        return [rule.incomeSource, memberName(rule.personUserId), monthLabel(rule.monthOffset)].filter(Boolean).join(" · ");
      case "transfer":
        return `With ${accountName(rule.otherAccountId) || "another account"}`;
      case "settlement":
        return [memberName(rule.settleFromUserId), memberName(rule.settleToUserId)].filter(Boolean).join(" → ");
      case "skip":
        return rule.skipReason;
    }
  }

  async function confirmDelete(rule: InboxRule) {
    if (
      await confirm({
        title: `Delete ${rule.name}?`,
        description:
          rule.filedCount > 0
            ? `The ${rule.filedCount === 1 ? "row" : `${rule.filedCount} rows`} it filed stay filed.`
            : "This can't be undone.",
      })
    ) {
      remove.mutate({ id: rule.id, version: rule.version });
    }
  }

  const list = rules.data ?? [];
  return (
    <>
      <PageHeader title="Rules">
        <Button variant="outline" onClick={() => apply.mutate()} disabled={apply.isPending || list.length === 0}>
          Apply to the inbox
        </Button>
        <Button onClick={() => setEditor({ open: true })}>
          <PlusIcon />
          New rule
        </Button>
      </PageHeader>
      <PageIntro>
        A rule says what a bank row is, by its merchant key, text or counterparty IBAN. Rules with “file without asking”
        add the row as an expense or income line, make a transfer or skip it as statements come in; the others fill in the
        suggestion in the Inbox. When two rules match, the higher priority and then the more specific one wins.
      </PageIntro>

      <Surface className="p-4">
        <div className="flex items-start gap-2">
          <Checkbox
            id="auto-match"
            className="mt-0.5"
            checked={settings.data?.autoMatch ?? false}
            disabled={!isOwner || !settings.data || saveSettings.isPending}
            onCheckedChange={(c) => saveSettings.mutate({ autoMatch: c === true })}
          />
          <Label htmlFor="auto-match" className="flex-col items-start gap-0.5 font-normal">
            File transfers and duplicates automatically
            <span className="text-xs text-muted-foreground">
              Pairs a payment with the exact same amount arriving on another feed within two days, and files a row under an
              expense you entered by hand with the same amount, account and day (give or take one), when there's only one
              candidate. {isOwner ? "" : "Only owners can change this. "}Off until you've seen it get a few right.
            </span>
          </Label>
        </div>
        <ErrorText error={saveSettings.error} className="mt-2" />
      </Surface>

      {apply.data && (
        <p role="status" className="rounded-lg border bg-muted/40 px-3 py-2 text-sm">
          {describeApplied(apply.data)}
        </p>
      )}
      <ErrorText error={apply.error} />

      <Surface>
        <ErrorText error={remove.error} className="p-4" />
        <ErrorText error={rules.error} className="p-4" />
        {rules.isPending && <SkeletonRows rows={3} />}
        {rules.data && list.length === 0 && (
          <EmptyState title="No rules yet" action={<Button onClick={() => setEditor({ open: true })}>New rule</Button>}>
            Make one from a row in the Inbox's Decided tab, or start from scratch here.
          </EmptyState>
        )}
        {list.length > 0 && (
          <Table>
            <TableHeader>
              <TableRow className="hover:bg-transparent">
                <TableHead>Rule</TableHead>
                <TableHead className="max-md:hidden">When a row</TableHead>
                <TableHead className="max-md:hidden">It becomes</TableHead>
                <TableHead className="text-right max-sm:hidden">Filed</TableHead>
                <TableHead className="w-12">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((rule) => {
                const when = ruleConditions(rule, {
                  feed: feeds.find((f) => f.id === rule.feedId)?.name,
                  account: rule.accountId ? accountName(rule.accountId) : null,
                }).join(" · ");
                return (
                  <TableRow key={rule.id}>
                    <TableCell className="whitespace-normal">
                      <div className="flex flex-wrap items-center gap-2">
                        <button type="button" className="font-medium hover:underline" onClick={() => setEditor({ open: true, rule })}>
                          {rule.name}
                        </button>
                        {rule.auto ? <Chip tone="transfer" icon={ZapIcon}>Automatic</Chip> : <Chip icon={LightbulbIcon}>Suggests</Chip>}
                        {rule.priority !== 0 && <span className="text-xs text-muted-foreground">Priority {rule.priority}</span>}
                      </div>
                      <div className="text-xs text-muted-foreground md:hidden">{when}</div>
                      <div className="mt-1 text-xs text-muted-foreground md:hidden">{becomes(rule)}</div>
                    </TableCell>
                    <TableCell className="whitespace-normal text-sm max-md:hidden">{when}</TableCell>
                    <TableCell className="whitespace-normal text-sm max-md:hidden">{becomes(rule)}</TableCell>
                    <TableCell className="amount text-right max-sm:hidden">{rule.filedCount}</TableCell>
                    <TableCell className="text-right">
                      <RowMenu
                        label={rule.name}
                        actions={[
                          { label: "Edit", onSelect: () => setEditor({ open: true, rule }) },
                          { label: "Delete", destructive: true, onSelect: () => confirmDelete(rule) },
                        ]}
                      />
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        )}
      </Surface>

      <Sheet open={editor.open} onClose={() => setEditor({ open: false })} title={editor.rule ? "Edit rule" : "New rule"}>
        <RuleForm key={editor.rule?.id ?? "new"} rule={editor.rule} onDone={() => setEditor({ open: false })} />
      </Sheet>
    </>
  );
}

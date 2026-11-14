import type { Member } from "../api/types";
import { Input } from "@/components/ui/input";
import { percents, sharesFrom, type WeightDraft } from "./split";

/** One weight per member, with the percent it comes to beside it. */
export function SplitEditor({
  members,
  draft,
  onChange,
  label,
}: {
  members: Member[];
  draft: WeightDraft;
  onChange: (draft: WeightDraft) => void;
  label: string;
}) {
  const parts = percents(sharesFrom(draft) ?? []);
  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="mb-2 text-sm font-medium">{label}</legend>
      {members.map((m) => (
        <div key={m.userId} className="grid grid-cols-[1fr_6rem_3.5rem] items-center gap-3">
          <label htmlFor={`share-${m.userId}`} className="truncate text-sm">
            {m.displayName}
          </label>
          <Input
            id={`share-${m.userId}`}
            inputMode="numeric"
            className="amount text-right"
            value={draft[m.userId] ?? ""}
            onChange={(e) => onChange({ ...draft, [m.userId]: e.target.value })}
          />
          <span className="amount text-right text-sm text-muted-foreground">{parts.get(m.userId) ?? 0} %</span>
        </div>
      ))}
    </fieldset>
  );
}

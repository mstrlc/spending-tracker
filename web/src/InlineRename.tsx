import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/** A name that becomes a text field while [editing]; the Rename trigger lives with the row's other actions. */
export function InlineRename({
  name,
  editing,
  onSave,
  onDone,
}: {
  name: string;
  editing: boolean;
  onSave: (name: string) => void;
  onDone: () => void;
}) {
  const [value, setValue] = useState(name);

  function submit(e: FormEvent) {
    e.preventDefault();
    onDone();
    if (value.trim() && value !== name) onSave(value);
  }

  if (!editing) return <>{name}</>;
  return (
    <form onSubmit={submit} className="flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
      <Input
        autoFocus
        aria-label="Name"
        className="h-8 max-w-60"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => e.key === "Escape" && onDone()}
      />
      <Button type="submit" size="sm">
        Save
      </Button>
      <Button type="button" variant="ghost" size="sm" onClick={onDone}>
        Cancel
      </Button>
    </form>
  );
}

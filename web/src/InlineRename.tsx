import { useState, type FormEvent } from "react";

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
    <form onSubmit={submit} className="inline-form">
      <input autoFocus aria-label="Name" value={value} onChange={(e) => setValue(e.target.value)} />
      <button type="submit">Save</button>
      <button type="button" className="secondary" onClick={onDone}>
        Cancel
      </button>
    </form>
  );
}

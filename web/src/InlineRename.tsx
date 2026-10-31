import { useState, type FormEvent } from "react";

/** A name that turns into a text field on "Rename". */
export function InlineRename({ name, onSave }: { name: string; onSave: (name: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(name);

  function submit(e: FormEvent) {
    e.preventDefault();
    setEditing(false);
    if (value.trim() && value !== name) onSave(value);
  }

  if (!editing) {
    return (
      <>
        {name}{" "}
        <button
          className="link small"
          onClick={() => {
            setValue(name);
            setEditing(true);
          }}
        >
          Rename
        </button>
      </>
    );
  }
  return (
    <form onSubmit={submit} className="inline-form">
      <input autoFocus aria-label="Name" value={value} onChange={(e) => setValue(e.target.value)} />
      <button type="submit">Save</button>
      <button type="button" className="secondary" onClick={() => setEditing(false)}>
        Cancel
      </button>
    </form>
  );
}

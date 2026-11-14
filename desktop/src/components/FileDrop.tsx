import { FileUp, FileText } from "lucide-react";
import { useRef, useState } from "react";

interface FileDropProps {
  busy: boolean;
  fileName?: string;
  onSelect: (file: File) => void;
}

export default function FileDrop({ busy, fileName, onSelect }: FileDropProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  function acceptFile(file?: File) {
    if (!file) return;
    if (file.type !== "application/pdf" && !file.name.toLowerCase().endsWith(".pdf")) {
      return;
    }
    onSelect(file);
  }

  return (
    <div
      className={`file-drop ${dragging ? "file-drop-active" : ""} ${busy ? "file-drop-busy" : ""}`}
      onDragOver={(event) => {
        event.preventDefault();
        if (!busy) setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        if (!busy) acceptFile(event.dataTransfer.files[0]);
      }}
    >
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf,.pdf"
        className="sr-only"
        disabled={busy}
        onChange={(event) => {
          acceptFile(event.currentTarget.files?.[0]);
          event.currentTarget.value = "";
        }}
      />
      {fileName ? (
        <FileText size={22} aria-hidden="true" />
      ) : (
        <FileUp size={22} aria-hidden="true" />
      )}
      <div className="file-drop-copy">
        <strong>{fileName || "Drop your LinkedIn PDF here"}</strong>
        <span>PDF export · up to 15 MB</span>
      </div>
      <button
        className="button button-outline button-small"
        type="button"
        disabled={busy}
        onClick={() => inputRef.current?.click()}
      >
        {busy ? "Parsing…" : fileName ? "Replace" : "Choose PDF"}
      </button>
    </div>
  );
}

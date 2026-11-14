import { Download, ExternalLink, FileText } from "lucide-react";
import { useEffect, useState } from "react";

interface PDFPreviewProps {
  blob: Blob;
  title: string;
}

export default function PDFPreview({ blob, title }: PDFPreviewProps) {
  const [url, setUrl] = useState("");

  useEffect(() => {
    const objectUrl = URL.createObjectURL(blob);
    setUrl(objectUrl);
    return () => URL.revokeObjectURL(objectUrl);
  }, [blob]);

  if (!url) return null;

  return (
    <section className="panel preview-panel">
      <div className="panel-heading">
        <div className="heading-icon heading-icon-purple">
          <FileText size={18} />
        </div>
        <div className="heading-copy">
          <h2>Resume preview</h2>
          <p>Your generated, ATS-friendly PDF.</p>
        </div>
        <div className="preview-actions">
          <a className="button button-outline button-small" href={url} target="_blank" rel="noreferrer">
            <ExternalLink size={15} />
            Open
          </a>
          <a className="button button-primary button-small" href={url} download={title}>
            <Download size={15} />
            Save PDF
          </a>
        </div>
      </div>
      <iframe className="pdf-frame" src={url} title="Generated resume PDF preview" />
    </section>
  );
}

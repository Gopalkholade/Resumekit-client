import { Check, CircleAlert, Plus, Sparkles } from "lucide-react";
import type { ReactNode } from "react";

import type { GapReport } from "../types";

interface GapReportViewProps {
  report: GapReport;
  onAddSkill?: (skill: string) => void;
}

function SkillList({
  title,
  values,
  tone,
  icon,
  empty,
  onAddSkill,
}: {
  title: string;
  values: string[];
  tone: "green" | "amber" | "blue";
  icon: ReactNode;
  empty: string;
  onAddSkill?: (skill: string) => void;
}) {
  return (
    <section className="skill-result">
      <div className="skill-result-heading">
        <span className={`result-icon result-icon-${tone}`}>{icon}</span>
        <div>
          <h3>{title}</h3>
          <span className="muted">{values.length} skills</span>
        </div>
      </div>
      {values.length ? (
        <div className="chip-list">
          {values.map((skill) => (
            <span className={`chip chip-${tone} ${onAddSkill ? "chip-actionable" : ""}`} key={skill}>
              <span>{skill}</span>
              {onAddSkill && (
                <button
                  type="button"
                  className="chip-action-btn"
                  title={`I have this skill — save "${skill}" permanently to my skills`}
                  onClick={() => onAddSkill(skill)}
                >
                  <Plus size={11} />
                  <span>Add to skills</span>
                </button>
              )}
            </span>
          ))}
        </div>
      ) : (
        <p className="empty-note">{empty}</p>
      )}
    </section>
  );
}

export default function GapReportView({ report, onAddSkill }: GapReportViewProps) {
  return (
    <div className="gap-report">
      <SkillList
        title="Skills found"
        values={report.matched_skills}
        tone="green"
        icon={<Check size={16} />}
        empty="No direct matches yet. Add more experience or project evidence."
      />
      <SkillList
        title="Discovered in projects"
        values={report.implicit_skills_from_readme}
        tone="blue"
        icon={<Sparkles size={16} />}
        empty="No additional implicit skills found."
      />
      <SkillList
        title="Potential gaps"
        values={report.missing_critical_gaps}
        tone="amber"
        icon={<CircleAlert size={16} />}
        empty="Great — no listed skills are missing from your evidence."
        onAddSkill={onAddSkill}
      />
    </div>
  );
}

export interface ProjectItem {
  name: string;
  description: string;
  repo_url: string | null;
  live_url: string | null;
  primary_language: string | null;
  tags: string[];
  readme_summary: string | null;
  implicit_skills_found: string[];
  relevance_score: number;
}

export interface TargetedProject {
  name: string;
  bullets: string[];
}

export interface WorkExperience {
  company: string;
  title: string;
  location: string | null;
  start_date: string;
  end_date: string;
  bullets: string[];
  skills_used: string[];
}

export interface EducationEntry {
  university: string;
  degree: string;
  field_of_study: string;
  start_date: string;
  end_date: string;
}

export interface LinkedInProfileImport {
  full_name?: string;
  contact?: Record<string, string>;
  professional_summary?: string;
  skills_inventory?: Record<string, string[]>;
  work_history: WorkExperience[];
  education?: EducationEntry[];
  certifications?: string[];
}

export interface MasterCV {
  full_name: string;
  contact: Record<string, string>;
  professional_summary: string;
  work_history: WorkExperience[];
  all_projects: ProjectItem[];
  skills_inventory: Record<string, string[]>;
  education: EducationEntry[];
  certifications: string[];
}

export interface TargetedResume {
  full_name: string;
  contact: Record<string, string>;
  header_title: string;
  tailored_summary: string;
  skills_matrix: Record<string, string[]>;
  selected_experience: WorkExperience[];
  selected_projects: TargetedProject[];
  education: EducationEntry[];
  certifications: string[];
}

export interface GapReport {
  matched_skills: string[];
  implicit_skills_from_readme: string[];
  missing_critical_gaps: string[];
}

export interface AuditResult {
  gap_report: GapReport;
  targeted_resume: TargetedResume;
}

export interface SavedProfile {
  full_name: string;
  email: string;
  professional_summary: string;
  skills: string;
  contact?: Record<string, string>;
  education?: EducationEntry[] | string;
  certifications?: string;
  work_history: WorkExperience[];
  all_projects: ProjectItem[];
}

export interface SavedProfileFile {
  version: 2;
  profile: SavedProfile;
}

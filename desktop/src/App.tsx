import {
  ArrowRight,
  BriefcaseBusiness,
  Check,
  CircleHelp,
  CloudDownload,
  CloudUpload,
  Compass,
  FileText,
  Github,
  Link2,
  LoaderCircle,
  RefreshCw,
  Sparkles,
  Trash2,
  UserRound,
  WandSparkles,
} from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { FormEvent } from "react";

import {
  auditResume,
  checkApiHealth,
  checkApiStatus,
  compileResumePDF,
  configureApiSettings,
  deleteSavedProfile,
  fetchRemoteProfile,
  ingestGitHub,
  ingestJobDescriptionUrl,
  ingestLinkedIn,
  loadApiSettings,
  loadSavedProfile,
  saveApiSettings,
  saveProfile,
  syncRemoteProfile,
} from "./api";
import FileDrop from "./components/FileDrop";
import GapReportView from "./components/GapReportView";
import PDFPreview from "./components/PDFPreview";
import type {
  AuditResult,
  EducationEntry,
  MasterCV,
  ProjectItem,
  SavedProfile,
  WorkExperience,
} from "./types";

type APIState = "checking" | "online" | "offline";
type BusyAction = "linkedin" | "github" | "job-url" | "audit" | "pdf" | "profile-sync" | null;
type ProfileSaveState = "loading" | "empty" | "saving" | "saved" | "error";

function emptyProfile(): SavedProfile {
  return {
    full_name: "",
    email: "",
    professional_summary: "",
    skills: "",
    contact: {},
    education: [],
    certifications: "",
    work_history: [],
    all_projects: [],
  };
}

function serializeProfile(profile: SavedProfile): string {
  return JSON.stringify({ version: 2, profile });
}

function emptyEducationEntry(): EducationEntry {
  return {
    university: "",
    degree: "",
    field_of_study: "",
    start_date: "",
    end_date: "",
  };
}

function splitList(value: string): string[] {
  return [...new Set(value.split(",").map((item) => item.trim()).filter(Boolean))];
}

function mergeSkills(...groups: string[][]): string[] {
  const seen = new Set<string>();
  return groups.flat().filter((skill) => {
    const key = skill.trim().toLocaleLowerCase();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function messageFrom(error: unknown): string {
  return error instanceof Error ? error.message : "Something went wrong. Please try again.";
}

export default function App() {
  const [apiState, setApiState] = useState<APIState>("checking");
  const [apiConfigLoaded, setApiConfigLoaded] = useState(false);
  const [apiUrl, setApiUrl] = useState(
    import.meta.env.VITE_RESUMEKIT_API_URL || "https://folderdf-resumekit-api.hf.space/",
  );
  const [apiToken, setApiToken] = useState("");
  const [apiConfigError, setApiConfigError] = useState("");
  const [apiConfigSaving, setApiConfigSaving] = useState(false);
  const [busy, setBusy] = useState<BusyAction>(null);
  const [error, setError] = useState("");
  const [profileSaveState, setProfileSaveState] = useState<ProfileSaveState>("loading");
  const [profileStorageError, setProfileStorageError] = useState("");
  const [profileStorageAvailable, setProfileStorageAvailable] = useState(false);
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [profileContact, setProfileContact] = useState<Record<string, string>>({});
  const [summary, setSummary] = useState("");
  const [skills, setSkills] = useState("");
  const [education, setEducation] = useState<EducationEntry[]>([]);
  const [educationReviewPending, setEducationReviewPending] = useState(false);
  const [educationReviewNotice, setEducationReviewNotice] = useState("");
  const [certifications, setCertifications] = useState("");
  const [githubUsername, setGithubUsername] = useState("");
  const [githubToken, setGithubToken] = useState("");
  const [jobDescription, setJobDescription] = useState("");
  const [externalProcessingConsent, setExternalProcessingConsent] = useState(false);
  const [jobUrl, setJobUrl] = useState("");
  const [jobUrlNotice, setJobUrlNotice] = useState("");
  const [linkedinFile, setLinkedinFile] = useState("");
  const [workHistory, setWorkHistory] = useState<WorkExperience[]>([]);
  const [projects, setProjects] = useState<ProjectItem[]>([]);
  const [result, setResult] = useState<AuditResult | null>(null);
  const [pdf, setPdf] = useState<Blob | null>(null);
  const lastSavedProfile = useRef<string | null>(null);
  const saveVersion = useRef(0);
  const saveQueue = useRef<Promise<void>>(Promise.resolve());

  const refreshHealth = useCallback(async () => {
    setApiState("checking");
    try {
      await checkApiHealth();
      setApiState("online");
    } catch {
      setApiState("offline");
    }
  }, []);

  useEffect(() => {
    let active = true;
    void loadApiSettings()
      .then((settings) => {
        if (!active || !settings) return;
        configureApiSettings(settings.api_url, settings.api_key);
        setApiUrl(settings.api_url);
        setApiToken(settings.api_key);
      })
      .catch((caught: unknown) => {
        if (active) setApiConfigError(messageFrom(caught));
      })
      .finally(() => {
        if (active) setApiConfigLoaded(true);
      });
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (apiConfigLoaded) void refreshHealth();
  }, [apiConfigLoaded, refreshHealth]);

  useEffect(() => {
    let active = true;
    void loadSavedProfile()
      .then((profile) => {
        if (!active) return;
        const loadedProfile = profile ?? emptyProfile();
        setFullName(loadedProfile.full_name);
        setEmail(loadedProfile.email);
        const otherContact = { ...(loadedProfile.contact ?? {}) };
        delete otherContact.email;
        setProfileContact(otherContact);
        setSummary(loadedProfile.professional_summary);
        setSkills(loadedProfile.skills);
        setEducation(Array.isArray(loadedProfile.education) ? loadedProfile.education : []);
        setCertifications(loadedProfile.certifications ?? "");
        setWorkHistory(loadedProfile.work_history);
        setProjects(loadedProfile.all_projects);
        lastSavedProfile.current = serializeProfile(loadedProfile);
        setProfileStorageAvailable(true);
        setProfileSaveState(profile ? "saved" : "empty");
      })
      .catch((caught: unknown) => {
        if (!active) return;
        setProfileStorageAvailable(false);
        setProfileStorageError(messageFrom(caught));
        setProfileSaveState("error");
      });
    return () => {
      active = false;
    };
  }, []);

  const populatedCount = useMemo(
    () => [fullName, summary, workHistory.length, projects.length, jobDescription].filter(Boolean).length,
    [fullName, summary, workHistory.length, projects.length, jobDescription],
  );

  async function handleSaveApiSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const normalizedUrl = apiUrl.trim().replace(/\/+$/, "");
    let validSpaceUrl = false;
    try {
      const parsed = new URL(normalizedUrl);
      validSpaceUrl = parsed.protocol === "https:"
        && parsed.hostname.toLowerCase().endsWith(".hf.space")
        && !parsed.username
        && !parsed.password
        && !parsed.search
        && !parsed.hash;
    } catch {
      validSpaceUrl = false;
    }
    if (!validSpaceUrl) {
      setApiConfigError("Use a Hugging Face Space HTTPS URL.");
      return;
    }
    if (apiToken.trim().length < 16) {
      setApiConfigError("Enter the API token configured in your Hugging Face Space secrets.");
      return;
    }

    setApiConfigSaving(true);
    setApiConfigError("");
    try {
      await saveApiSettings({ api_url: normalizedUrl, api_key: apiToken.trim() });
      setApiUrl(normalizedUrl);
      await checkApiHealth();
      await checkApiStatus();
      setApiState("online");
    } catch (caught) {
      setApiConfigError(messageFrom(caught));
      setApiState("offline");
    } finally {
      setApiConfigSaving(false);
    }
  }

  const masterCV: MasterCV = useMemo(
    () => {
      const contact = { ...profileContact };
      delete contact.email;
      if (email.trim()) contact.email = email.trim();
      const skillList = splitList(skills);
      const experienceSkills = mergeSkills(...workHistory.map((role) => role.skills_used));
      const projectSkills = mergeSkills(
        ...projects.map((project) => [
          ...(project.primary_language ? [project.primary_language] : []),
          ...project.tags,
          ...project.implicit_skills_found,
        ]),
      );
      const allSkills = mergeSkills(skillList, experienceSkills, projectSkills);
      const skillsInventory: Record<string, string[]> = {};
      if (allSkills.length) skillsInventory["Skills"] = allSkills;
      return {
        full_name: fullName.trim(),
        contact,
        professional_summary: summary.trim(),
        work_history: workHistory,
        all_projects: projects,
        skills_inventory: skillsInventory,
        education,
        certifications: certifications
          .split(/\r?\n/)
          .map((item) => item.trim())
          .filter(Boolean),
      };
    },
    [certifications, education, email, fullName, profileContact, projects, skills, summary, workHistory],
  );

  const savedProfile: SavedProfile = useMemo(
    () => ({
      full_name: fullName,
      email,
      professional_summary: summary,
      skills,
      contact: profileContact,
      education,
      certifications,
      work_history: workHistory,
      all_projects: projects,
    }),
    [certifications, education, email, fullName, profileContact, projects, skills, summary, workHistory],
  );

  useEffect(() => {
    if (!profileStorageAvailable) return;
    const serialized = serializeProfile(savedProfile);
    if (serialized === lastSavedProfile.current) return;

    const version = ++saveVersion.current;
    setProfileSaveState("saving");
    const timer = window.setTimeout(() => {
      const save = saveQueue.current.then(() => saveProfile(savedProfile));
      saveQueue.current = save.catch(() => undefined);
      void save
        .then(() => {
          if (version !== saveVersion.current) return;
          lastSavedProfile.current = serialized;
          setProfileStorageError("");
          setProfileSaveState("saved");
        })
        .catch((caught: unknown) => {
          if (version !== saveVersion.current) return;
          setProfileStorageError(messageFrom(caught));
          setProfileSaveState("error");
        });
    }, 700);

    return () => window.clearTimeout(timer);
  }, [profileStorageAvailable, savedProfile]);

  async function handleDeleteProfile() {
    const hasStoredProfile = profileSaveState === "error"
      || (lastSavedProfile.current !== null
        && lastSavedProfile.current !== serializeProfile(emptyProfile()));
    if (
      hasStoredProfile &&
      !window.confirm("Permanently delete the encrypted profile saved on this Windows account?")
    ) {
      return;
    }

    setError("");
    try {
      await deleteSavedProfile();
      const empty = emptyProfile();
      lastSavedProfile.current = serializeProfile(empty);
      setFullName("");
      setEmail("");
      setProfileContact({});
      setSummary("");
      setSkills("");
      setEducation([]);
      setEducationReviewPending(false);
      setEducationReviewNotice("");
      setCertifications("");
      setWorkHistory([]);
      setProjects([]);
      setGithubToken("");
      setJobDescription("");
      setJobUrl("");
      setJobUrlNotice("");
      setLinkedinFile("");
      setResult(null);
      setPdf(null);
      setProfileStorageError("");
      setProfileStorageAvailable(true);
      setProfileSaveState("empty");
    } catch (caught) {
      setError(messageFrom(caught));
    }
  }

  async function handleLinkedIn(file: File) {
    setBusy("linkedin");
    setError("");
    setLinkedinFile(file.name);
    try {
      const imported = await ingestLinkedIn(file);
      if (imported.full_name?.trim()) setFullName(imported.full_name);
      const importedContact = imported.contact ?? {};
      const { email: importedEmail, ...otherContact } = importedContact;
      if (importedEmail) setEmail(importedEmail);
      if (imported.contact !== undefined) setProfileContact(otherContact);
      if (imported.professional_summary?.trim()) {
        setSummary(imported.professional_summary);
      }
      const importedSkills = mergeSkills(...Object.values(imported.skills_inventory ?? {}));
      if (importedSkills.length) setSkills(importedSkills.join(", "));
      setWorkHistory(imported.work_history);
      if (imported.education?.length) {
        setEducation(imported.education);
        setEducationReviewPending(true);
        setEducationReviewNotice("");
      } else {
        setEducationReviewPending(false);
        setEducationReviewNotice("No education details were found in this LinkedIn PDF.");
      }
      if (imported.certifications?.length) {
        setCertifications(imported.certifications.join("\n"));
      }
      setResult(null);
      setPdf(null);
    } catch (caught) {
      setError(messageFrom(caught));
      setLinkedinFile("");
    } finally {
      setBusy(null);
    }
  }

  async function handleGitHub(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!githubUsername.trim()) return;
    setBusy("github");
    setError("");
    try {
      const fetchedProjects = await ingestGitHub(githubUsername.trim(), githubToken.trim());
      setProjects(fetchedProjects);
      setResult(null);
      setPdf(null);
    } catch (caught) {
      setError(messageFrom(caught));
    } finally {
      setGithubToken("");
      setBusy(null);
    }
  }

  async function handleAudit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!fullName.trim() || !jobDescription.trim()) {
      setError("Add your name and paste a job description before running the match.");
      return;
    }
    if (workHistory.length === 0 && projects.length === 0 && !skills.trim()) {
      setError("Add a LinkedIn PDF, GitHub profile, or skills so there is evidence to compare.");
      return;
    }
    if (!externalProcessingConsent) {
      setError("Confirm that your profile and job description may be sent to Google Gemini.");
      return;
    }
    setExternalProcessingConsent(false);
    setBusy("audit");
    setError("");
    setPdf(null);
    try {
      setResult(await auditResume(masterCV, jobDescription.trim(), externalProcessingConsent));
    } catch (caught) {
      setError(messageFrom(caught));
    } finally {
      setBusy(null);
    }
  }

  async function handleJobDescriptionUrl() {
    if (!jobUrl.trim()) return;
    setBusy("job-url");
    setError("");
    setJobUrlNotice("");
    try {
      const extracted = await ingestJobDescriptionUrl(jobUrl.trim());
      setJobDescription(extracted);
      setJobUrlNotice(
        `We found ${extracted.length.toLocaleString()} characters. Give the text a quick read before matching.`,
      );
      setResult(null);
      setPdf(null);
    } catch (caught) {
      setError(messageFrom(caught));
    } finally {
      setBusy(null);
    }
  }

  async function handlePDF() {
    if (!result) return;
    setBusy("pdf");
    setError("");
    try {
      setPdf(await compileResumePDF(result.targeted_resume));
    } catch (caught) {
      setError(messageFrom(caught));
    } finally {
      setBusy(null);
    }
  }

  function handleAddSkillFromGap(skillToAdd: string) {
    const trimmed = skillToAdd.trim();
    if (!trimmed) return;

    const currentList = skills
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);

    if (!currentList.some((s) => s.toLowerCase() === trimmed.toLowerCase())) {
      const updatedList = [...currentList, trimmed];
      const updatedSkillsStr = updatedList.join(", ");
      setSkills(updatedSkillsStr);
      try {
        saveProfile({
          ...savedProfile,
          skills: updatedSkillsStr,
        });
        setProfileSaveState("saved");
      } catch (err) {
        setProfileStorageError(messageFrom(err));
      }

      // Sync to cloud so Android app receives it immediately
      const currentSkillsMap = masterCV.skills_inventory;
      const allMerged = [...(currentSkillsMap["Skills"] || []), trimmed];
      void syncRemoteProfile({
        ...masterCV,
        skills_inventory: {
          ...currentSkillsMap,
          Skills: Array.from(new Set(allMerged)),
        },
      }).catch(() => {});
    }

    if (result) {
      setResult({
        ...result,
        gap_report: {
          ...result.gap_report,
          missing_critical_gaps: result.gap_report.missing_critical_gaps.filter(
            (s) => s.toLowerCase() !== trimmed.toLowerCase(),
          ),
          matched_skills: [...result.gap_report.matched_skills, trimmed],
        },
      });
    }
  }

  async function handlePullRemoteProfile() {
    setBusy("profile-sync");
    setError("");
    try {
      const remote = await fetchRemoteProfile();
      if (!remote || !remote.full_name) {
        setError("No remote profile found in your Space yet. You can push your current profile.");
        return;
      }
      setFullName(remote.full_name);
      if (remote.contact) {
        if (remote.contact.email) setEmail(remote.contact.email);
        const { email: _, ...rest } = remote.contact;
        setProfileContact(rest);
      }
      if (remote.professional_summary) setSummary(remote.professional_summary);
      const remoteSkills = mergeSkills(...Object.values(remote.skills_inventory ?? {}));
      if (remoteSkills.length) setSkills(remoteSkills.join(", "));
      if (remote.work_history?.length) setWorkHistory(remote.work_history);
      if (remote.all_projects?.length) setProjects(remote.all_projects);
      if (remote.education?.length) {
        setEducation(
          remote.education.map((e) => ({
            degree: e.degree || "",
            university: e.university || "",
            field_of_study: e.field_of_study || "",
            start_date: e.start_date || "",
            end_date: e.end_date || "",
          })),
        );
      }
      if (remote.certifications?.length) {
        setCertifications(remote.certifications.map((c) => String(c)).join("\n"));
      }
      saveProfile({
        full_name: remote.full_name,
        email: remote.contact?.email || "",
        professional_summary: remote.professional_summary || "",
        skills: remoteSkills.join(", "),
        contact: remote.contact || {},
        education: remote.education || [],
        certifications: (remote.certifications || []).map((c) => String(c)).join("\n"),
        work_history: remote.work_history || [],
        all_projects: remote.all_projects || [],
      });
      setProfileSaveState("saved");
    } catch (caught) {
      setError(messageFrom(caught));
    } finally {
      setBusy(null);
    }
  }

  async function handlePushRemoteProfile() {
    setBusy("profile-sync");
    setError("");
    try {
      await syncRemoteProfile(masterCV);
      setProfileSaveState("saved");
    } catch (caught) {
      setError(messageFrom(caught));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="#" aria-label="ResumeKit home">
          <span className="brand-mark"><FileText size={20} /></span>
          <span>resume<span className="brand-accent">kit</span></span>
        </a>

        <div className="workspace-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Main navigation">
          <a className="nav-item nav-item-active" href="#builder">
            <WandSparkles size={17} /> My next chapter
          </a>
          <a className="nav-item" href="#profile">
            <BriefcaseBusiness size={17} /> My story
          </a>
        </nav>

        <div className="sidebar-bottom">
          <div className="api-card">
            <div className="api-card-top">
              <span className={`status-dot status-${apiState}`} />
              <strong>
                {apiState === "online"
                  ? "Engine connected"
                  : apiState === "checking"
                    ? "Connecting…"
                    : "Engine offline"}
              </strong>
              <button
                className="icon-button"
                type="button"
                aria-label="Retry API connection"
                onClick={() => void refreshHealth()}
              >
                <RefreshCw size={14} />
              </button>
            </div>
            <p>
              {apiState === "online"
                ? "ResumeKit API is ready."
                : "Connect to your Hugging Face Space."}
            </p>
            <details className="api-settings">
              <summary>Connection settings</summary>
              <form onSubmit={(event) => void handleSaveApiSettings(event)}>
                <label className="api-setting-field">
                  <span>API URL</span>
                  <input
                    type="url"
                    value={apiUrl}
                    onChange={(event) => setApiUrl(event.target.value)}
                    placeholder="https://your-space.hf.space"
                    required
                  />
                </label>
                <label className="api-setting-field">
                  <span>Space API token</span>
                  <input
                    type="password"
                    autoComplete="new-password"
                    value={apiToken}
                    onChange={(event) => setApiToken(event.target.value)}
                    placeholder="Paste your personal API token"
                    required
                  />
                </label>
                {apiConfigError && <p className="api-config-error" role="alert">{apiConfigError}</p>}
                <button className="button button-outline api-settings-submit" type="submit" disabled={apiConfigSaving || !apiConfigLoaded}>
                  {apiConfigSaving ? "Saving…" : "Save & connect"}
                </button>
                <p className="api-settings-note">Token encrypted for this Windows account. Requests wait up to 90 seconds for Space startup.</p>
              </form>
            </details>
          </div>
          <a
            className="help-link"
            href={`${apiUrl.trim().replace(/\/+$/, "")}/docs`}
            target="_blank"
            rel="noreferrer"
          >
            <CircleHelp size={16} /> Help & API docs
          </a>
          <div className="user-badge">
            <span className="avatar">{fullName.trim().charAt(0).toUpperCase() || "R"}</span>
            <span className="user-name">{fullName.trim() || "Your workspace"}</span>
            <span className="local-label">LOCAL</span>
          </div>
        </div>
      </aside>

      <main className="main-content" id="builder">
        <header className="topbar">
          <div className="breadcrumbs">Your workspace <span>/</span> Career story</div>
          <div className="topbar-right">
            <span className="privacy-pill"><span /> Gemini AI · opt-in processing</span>
          </div>
        </header>

        <div className="page-content">
          <section className="welcome-row">
            <div>
              <div className="eyebrow"><Sparkles size={14} /> A LITTLE HELP WITH YOUR NEXT MOVE</div>
              <h1>Your experience has a story.<br /><span>Let’s tell it well.</span></h1>
              <p className="welcome-copy">
                Start with what you have. We’ll bring your work, projects, and next-step ambitions
                together into a resume that still feels like you.
              </p>
            </div>
            <div className="progress-card" aria-label={`Your story is ${populatedCount} out of 5 parts complete`}>
              <div className="progress-meta"><span>YOUR STORY SO FAR</span><strong>{populatedCount} of 5</strong></div>
              <div
                className="progress-track"
                role="progressbar"
                aria-label="Profile progress"
                aria-valuemin={0}
                aria-valuemax={5}
                aria-valuenow={populatedCount}
              >
                <span style={{ width: `${populatedCount * 20}%` }} />
              </div>
              <span className="progress-caption">
                {populatedCount === 0
                  ? "Every good story starts somewhere."
                  : populatedCount === 5
                    ? "Looking good. Let’s find your next step."
                    : "One detail at a time — you’re getting there."}
              </span>
            </div>
          </section>

          <div className="builder-grid">
            <div className="builder-column">
              <section className="panel panel-profile" id="profile">
                <div className="panel-heading profile-heading">
                  <div className="step-badge"><UserRound size={16} /></div>
                  <div className="heading-copy">
                    <h2>First, the basics</h2>
                    <p>A name and a few details are plenty to get started.</p>
                  </div>
                  <div className="profile-actions">
                    <span className={`save-status save-status-${profileSaveState}`}>
                      <span className="status-dot" />
                      {profileSaveState === "loading" && "Unlocking profile…"}
                      {profileSaveState === "empty" && "Ready when you are"}
                      {profileSaveState === "saving" && "Encrypting & saving…"}
                      {profileSaveState === "saved" && "Safely saved on this device"}
                      {profileSaveState === "error" && "Vault unavailable"}
                    </span>
                    <button
                      className="button button-outline button-small"
                      type="button"
                      title="Push your profile to your Space so Android and other devices stay in sync"
                      disabled={busy === "profile-sync"}
                      onClick={() => void handlePushRemoteProfile()}
                    >
                      <CloudUpload size={13} />
                      Sync to cloud
                    </button>
                    <button
                      className="button button-outline button-small"
                      type="button"
                      title="Pull latest profile from your Space (e.g. updated from Android or another browser)"
                      disabled={busy === "profile-sync"}
                      onClick={() => void handlePullRemoteProfile()}
                    >
                      <CloudDownload size={13} />
                      Pull from cloud
                    </button>
                    {(profileSaveState === "saved" || profileSaveState === "error") && (
                      <button
                        className="button button-danger button-small"
                        type="button"
                        onClick={() => void handleDeleteProfile()}
                      >
                        <Trash2 size={13} />
                        {profileSaveState === "error" ? "Reset vault" : "Delete saved"}
                      </button>
                    )}
                  </div>
                </div>
                {profileStorageError && (
                  <div className="vault-error" role="alert">
                    <CircleHelp size={15} />
                    <span>
                      {profileStorageError} Profile autosave is paused so existing encrypted
                      data will not be overwritten. Use “Reset vault” only if you want to delete it.
                    </span>
                  </div>
                )}
                <div className="form-grid">
                  <label className="field">
                    <span>Full name <b>*</b></span>
                    <input
                      autoComplete="name"
                      value={fullName}
                      onChange={(event) => setFullName(event.target.value)}
                      placeholder="e.g. Alex Morgan"
                    />
                  </label>
                  <label className="field">
                    <span>Email <small>OPTIONAL</small></span>
                    <input
                      autoComplete="email"
                      type="email"
                      value={email}
                      onChange={(event) => setEmail(event.target.value)}
                      placeholder="alex@example.com"
                    />
                  </label>
                  <label className="field field-wide">
                    <span>Professional summary <small>OPTIONAL</small></span>
                    <textarea
                      rows={3}
                      value={summary}
                      onChange={(event) => setSummary(event.target.value)}
                      placeholder="A brief snapshot of your experience and strengths…"
                    />
                  </label>
                  <div className="field field-wide education-editor">
                    <span>Education <small>OPTIONAL · IMPORTED FROM LINKEDIN OR ENTERED BELOW</small></span>
                    {educationReviewPending && (
                      <div className="field-hint" role="status">
                        Education details were extracted from LinkedIn. Do they need to be modified?
                        <div className="education-review-actions">
                          <button
                            className="button button-outline button-small"
                            type="button"
                            onClick={() => {
                              setEducationReviewPending(false);
                              setEducationReviewNotice("Edit any extracted education fields below.");
                            }}
                          >
                            Yes, I need to edit them
                          </button>
                          <button
                            className="button button-outline button-small"
                            type="button"
                            onClick={() => {
                              setEducationReviewPending(false);
                              setEducationReviewNotice("Education details reviewed.");
                            }}
                          >
                            No, they look correct
                          </button>
                        </div>
                      </div>
                    )}
                    {educationReviewNotice && (
                      <p className="field-hint" role="status">{educationReviewNotice}</p>
                    )}
                    {education.map((entry, index) => (
                      <div className="form-grid education-entry" key={`education-${index}`}>
                        <label className="field">
                          <span>University / institution</span>
                          <input
                            value={entry.university}
                            onChange={(event) => setEducation((entries) => entries.map(
                              (item, itemIndex) => itemIndex === index
                                ? { ...item, university: event.target.value }
                                : item,
                            ))}
                          />
                        </label>
                        <label className="field">
                          <span>Degree</span>
                          <input
                            value={entry.degree}
                            onChange={(event) => setEducation((entries) => entries.map(
                              (item, itemIndex) => itemIndex === index
                                ? { ...item, degree: event.target.value }
                                : item,
                            ))}
                          />
                        </label>
                        <label className="field field-wide">
                          <span>Field of study</span>
                          <input
                            value={entry.field_of_study}
                            onChange={(event) => setEducation((entries) => entries.map(
                              (item, itemIndex) => itemIndex === index
                                ? { ...item, field_of_study: event.target.value }
                                : item,
                            ))}
                          />
                        </label>
                        <label className="field">
                          <span>Start date</span>
                          <input
                            value={entry.start_date}
                            onChange={(event) => setEducation((entries) => entries.map(
                              (item, itemIndex) => itemIndex === index
                                ? { ...item, start_date: event.target.value }
                                : item,
                            ))}
                            placeholder="e.g. June 2018"
                          />
                        </label>
                        <label className="field">
                          <span>End date / graduation</span>
                          <input
                            value={entry.end_date}
                            onChange={(event) => setEducation((entries) => entries.map(
                              (item, itemIndex) => itemIndex === index
                                ? { ...item, end_date: event.target.value }
                                : item,
                            ))}
                            placeholder="e.g. June 2022"
                          />
                        </label>
                        <button
                          className="button button-danger button-small"
                          type="button"
                          onClick={() => setEducation((entries) =>
                            entries.filter((_, itemIndex) => itemIndex !== index))}
                        >
                          Remove education entry
                        </button>
                      </div>
                    ))}
                    <button
                      className="button button-outline button-small"
                      type="button"
                      onClick={() => setEducation((entries) => [...entries, emptyEducationEntry()])}
                    >
                      Add education entry
                    </button>
                  </div>
                  <label className="field field-wide">
                    <span>Certifications <small>OPTIONAL · ONE ENTRY PER LINE (NAME — PROVIDER)</small></span>
                    <textarea
                      rows={3}
                      value={certifications}
                      onChange={(event) => setCertifications(event.target.value)}
                      placeholder="e.g. SQL — HackerRank&#10;Project Management Foundations — LinkedIn Learning&#10;AWS Certified Solutions Architect — Amazon Web Services"
                    />
                  </label>
                  <label className="field field-wide">
                    <span>Skills <small>COMMA-SEPARATED · COMBINED WITH IMPORTED PROJECT SKILLS</small></span>
                    <input
                      value={skills}
                      onChange={(event) => setSkills(event.target.value)}
                      placeholder="Python, data analysis, project management"
                    />
                  </label>
                  {projects.length > 0 && (
                    <p className="field-hint field-wide">
                      {mergeSkills(...projects.map((project) => [
                        ...(project.primary_language ? [project.primary_language] : []),
                        ...project.tags,
                        ...project.implicit_skills_found,
                      ])).length}{" "}
                      skills from GitHub languages, topics, and README analysis will be included
                      alongside your skills.
                    </p>
                  )}
                </div>
              </section>

              <section className="panel panel-sources">
                <div className="panel-heading">
                  <div className="step-badge"><FileText size={16} /></div>
                  <div className="heading-copy">
                    <h2>Gather the good stuff</h2>
                    <p>Your work and projects tell the story behind your skills.</p>
                  </div>
                </div>
                <div className="source-block">
                  <div className="source-title"><FileText size={16} /> Bring in your LinkedIn history</div>
                  <FileDrop
                    busy={busy === "linkedin"}
                    fileName={linkedinFile}
                    onSelect={(file) => void handleLinkedIn(file)}
                  />
                  {workHistory.length > 0 && (
                    <div className="inline-success"><Check size={14} /> {workHistory.length} roles added to your profile</div>
                  )}
                </div>
                <div className="source-divider"><span>OR</span></div>
                <form className="source-block" onSubmit={(event) => void handleGitHub(event)}>
                  <div className="source-title"><Github size={16} /> Find your public GitHub projects</div>
                  <div className="github-row">
                    <label className="field grow">
                      <span>GitHub username</span>
                      <input
                        autoComplete="username"
                        value={githubUsername}
                        onChange={(event) => setGithubUsername(event.target.value)}
                        placeholder="octocat"
                      />
                    </label>
                    <button className="button button-outline source-submit" type="submit" disabled={busy !== null || !githubUsername.trim()}>
                      {busy === "github" ? <LoaderCircle className="spin" size={16} /> : <Github size={16} />}
                      {busy === "github" ? "Looking…" : "Find projects"}
                    </button>
                  </div>
                  <label className="field token-field">
                    <span>GitHub token <small>OPTIONAL · HIGHER RATE LIMIT</small></span>
                    <input
                      type="password"
                      autoComplete="off"
                      value={githubToken}
                      onChange={(event) => setGithubToken(event.target.value)}
                      placeholder="Only sent to your Space for this request"
                    />
                  </label>
                  {projects.length > 0 && (
                    <div className="inline-success"><Check size={14} /> {projects.length} public projects added</div>
                  )}
                </form>
              </section>
            </div>

            <div className="builder-column">
              <section className="panel panel-role jd-panel">
                <div className="panel-heading">
                  <div className="step-badge"><Compass size={16} /></div>
                  <div className="heading-copy">
                    <h2>Where would you like to go?</h2>
                    <p>Share a role that excites you. We’ll find where your experience connects.</p>
                  </div>
                </div>
                <form onSubmit={(event) => void handleAudit(event)}>
                  <div className="job-url-row">
                    <label className="field grow">
                      <span>Have a link instead? <small>OPTIONAL</small></span>
                      <input
                        type="url"
                        autoComplete="url"
                        value={jobUrl}
                        onChange={(event) => setJobUrl(event.target.value)}
                        placeholder="https://company.com/careers/job..."
                      />
                    </label>
                    <button
                      className="button button-outline job-url-submit"
                      type="button"
                      onClick={() => void handleJobDescriptionUrl()}
                      disabled={busy !== null || apiState !== "online" || !jobUrl.trim()}
                    >
                      {busy === "job-url" ? <LoaderCircle className="spin" size={16} /> : <Link2 size={16} />}
                      {busy === "job-url" ? "Reading…" : "Get text"}
                    </button>
                  </div>
                  <p className="job-url-help">
                    We’ll bring the posting text here so you can review it. Some sites may block
                    reading; if so, you can still paste the description below.
                  </p>
                  {jobUrlNotice && <p className="job-url-notice" role="status">{jobUrlNotice}</p>}
                  <label className="field">
                    <span>Job description <b>*</b></span>
                    <textarea
                      className="jd-textarea"
                      value={jobDescription}
                      onChange={(event) => setJobDescription(event.target.value)}
                      placeholder={"Paste the job description here…\n\nWe’ll look for the experience and skills that connect with what they need."}
                      required
                    />
                  </label>
                  <label className="consent-row">
                    <input
                      type="checkbox"
                      checked={externalProcessingConsent}
                      onChange={(event) => setExternalProcessingConsent(event.target.checked)}
                      required
                    />
                    <span>
                      I agree to send my profile and this job description to my Hugging Face
                      Space and Google Gemini for AI analysis. This includes personal details
                      and imported work history.
                    </span>
                  </label>
                  <div className="form-footnote">
                    <span>Sent to Gemini only after you consent</span>
                    <span>{jobDescription.length.toLocaleString()} characters</span>
                  </div>
                  <button
                    className="button button-primary match-button"
                    type="submit"
                    disabled={busy !== null || apiState !== "online" || !externalProcessingConsent}
                  >
                    {busy === "audit" ? <LoaderCircle className="spin" size={17} /> : <WandSparkles size={17} />}
                    {busy === "audit" ? "Finding the connections…" : "Find my fit"}
                    {busy !== "audit" && <ArrowRight size={17} />}
                  </button>
                  {apiState !== "online" && (
                    <p className="help-text">Connect to your Space to analyze your profile.</p>
                  )}
                </form>
              </section>

              <div className="privacy-note">
                <span className="privacy-lock">⌑</span>
                <div>
                  <strong>Your saved profile is encrypted on this device.</strong>
                  <p>
                    Windows protects the saved profile for your account using DPAPI. It is not
                    stored in browser local storage. When you opt in to a match, your profile and
                    job description are sent to your Hugging Face Space and forwarded to Google
                    Gemini. Review their data-handling policies before using this feature.
                    Job descriptions, GitHub tokens, and the original LinkedIn PDF are never
                    saved in the local profile.
                  </p>
                </div>
              </div>
            </div>
          </div>

          {error && (
            <div className="error-banner" role="alert">
              <CircleHelp size={17} />
              <span>{error}</span>
              <button type="button" className="icon-button" aria-label="Dismiss error" onClick={() => setError("")}>×</button>
            </div>
          )}

          {result && (
            <section className="results-section">
              <div className="results-title-row">
                <div>
                  <div className="eyebrow"><Sparkles size={14} /> YOUR ROLE MATCH</div>
                  <h2>{result.targeted_resume.header_title}</h2>
                  <p>Here’s how your experience lines up with this opportunity.</p>
                </div>
                <button className="button button-primary" type="button" disabled={busy !== null} onClick={() => void handlePDF()}>
                  {busy === "pdf" ? <LoaderCircle className="spin" size={17} /> : <FileText size={17} />}
                  {busy === "pdf" ? "Creating PDF…" : "Generate PDF"}
                </button>
              </div>
              <GapReportView report={result.gap_report} onAddSkill={handleAddSkillFromGap} />
              <div className="tailored-card">
                <div className="tailored-card-heading"><WandSparkles size={17} /><strong>Gemini-tailored summary · review for accuracy</strong></div>
                <p>{result.targeted_resume.tailored_summary}</p>
                <div className="tailored-meta">
                  <span>{result.targeted_resume.selected_projects.length} projects selected</span>
                  <span>{result.targeted_resume.selected_experience.length} roles highlighted</span>
                  <span>{Object.values(result.targeted_resume.skills_matrix).flat().length} skills prioritized</span>
                </div>
              </div>
              {pdf && <PDFPreview blob={pdf} title="targeted-resume.pdf" />}
            </section>
          )}

          <footer className="page-footer">
            <span>ResumeKit <span className="footer-dot">·</span> AI-assisted career toolkit</span>
            <span>Built for your next chapter <span className="footer-heart">♥</span></span>
          </footer>
        </div>
      </main>
    </div>
  );
}

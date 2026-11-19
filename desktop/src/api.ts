import { invoke } from "@tauri-apps/api/core";
import { fetch as tauriFetch } from "@tauri-apps/plugin-http";

import type {
  AuditResult,
  EducationEntry,
  LinkedInProfileImport,
  MasterCV,
  ProjectItem,
  SavedProfile,
  SavedProfileFile,
  WorkExperience,
} from "./types";

const API_BASE_URL = (
  import.meta.env.VITE_RESUMEKIT_API_URL || "https://folderdf-resumekit-api.hf.space/"
).replace(/\/+$/, "");
let activeApiBaseUrl = API_BASE_URL;
let apiKeyPromise: Promise<string> | undefined;
const REQUEST_TIMEOUT_MS = 90_000;

export interface ApiSettings {
  api_url: string;
  api_key: string;
}

export async function loadApiSettings(): Promise<ApiSettings | null> {
  const contents = await invoke<string | null>("load_api_settings");
  if (contents === null) return null;
  const settings: unknown = JSON.parse(contents);
  if (
    !isRecord(settings) ||
    typeof settings.api_url !== "string" ||
    typeof settings.api_key !== "string"
  ) {
    throw new Error("Saved API settings have an unsupported format.");
  }
  return { api_url: settings.api_url, api_key: settings.api_key };
}

export async function saveApiSettings(settings: ApiSettings): Promise<void> {
  await invoke("save_api_settings", {
    settingsJson: JSON.stringify({ version: 1, ...settings }),
  });
  configureApiSettings(settings.api_url, settings.api_key);
}

export function configureApiSettings(apiUrl: string, apiKey: string): void {
  activeApiBaseUrl = apiUrl.replace(/\/+$/, "");
  apiKeyPromise = Promise.resolve(apiKey);
}

async function getApiKey(): Promise<string> {
  apiKeyPromise ??= invoke<string>("get_api_key").catch((error: unknown) => {
    apiKeyPromise = undefined;
    throw new Error(
      typeof error === "string"
        ? error
        : "Could not load the API key. Configure the Hugging Face Space connection first.",
    );
  });
  return apiKeyPromise;
}

async function sendRequest(path: string, init: RequestInit = {}): Promise<Response> {
  const key = await getApiKey();
  const headers = new Headers(init.headers);
  headers.set("X-API-Key", key);
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await tauriFetch(`${activeApiBaseUrl}${path}`, {
      ...init,
      headers,
      signal: controller.signal,
      connectTimeout: REQUEST_TIMEOUT_MS,
    });
    await ensureSuccess(response);
    return response;
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error(
        "The API did not respond within 90 seconds. A sleeping Hugging Face Space may still be starting; check the Space status and retry.",
      );
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

async function ensureSuccess(response: Response): Promise<void> {
  if (response.ok) return;
  const body = await response.text();
  let detail = body;
  try {
    const parsed = JSON.parse(body) as { detail?: unknown };
    if (typeof parsed.detail === "string") detail = parsed.detail;
  } catch {
    // Keep the backend response text when it is not JSON.
  }
  throw new Error(detail || `Request failed (${response.status}).`);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await sendRequest(path, init);
  return (await response.json()) as T;
}

export async function checkApiHealth(): Promise<void> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    const response = await tauriFetch(`${activeApiBaseUrl}/health`, {
      connectTimeout: REQUEST_TIMEOUT_MS,
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(`ResumeKit API returned HTTP ${response.status}.`);
    }
  } catch (error) {
    if (controller.signal.aborted) {
      throw new Error("The API health check timed out after 90 seconds.");
    }
    throw error;
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function checkApiStatus(): Promise<void> {
  const status = await request<{ gemini_configured: boolean }>("/api/v1/status");
  if (!status.gemini_configured) {
    throw new Error("The Space is reachable, but its GEMINI_API_KEY secret is not configured.");
  }
}

export async function ingestLinkedIn(file: File): Promise<LinkedInProfileImport> {
  const form = new FormData();
  form.append("file", file, file.name);
  return request<LinkedInProfileImport>(
    "/api/v1/ingest/linkedin",
    { method: "POST", body: form },
  );
}

export async function ingestGitHub(
  username: string,
  token?: string,
): Promise<ProjectItem[]> {
  const result = await request<{ projects: ProjectItem[] }>(
    "/api/v1/ingest/github",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, token: token || null }),
    },
  );
  return result.projects;
}

export async function ingestJobDescriptionUrl(url: string): Promise<string> {
  const result = await request<{ job_description: string }>(
    "/api/v1/ingest/job-description",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ url }),
    },
  );
  return result.job_description;
}

export async function auditResume(
  masterCV: MasterCV,
  jobDescription: string,
  allowExternalProcessing: boolean,
): Promise<AuditResult> {
  return request<AuditResult>("/api/v1/audit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      master_cv: masterCV,
      job_description: jobDescription,
      allow_external_processing: allowExternalProcessing,
    }),
  });
}

export async function fetchRemoteProfile(): Promise<MasterCV | null> {
  const data = await request<{ profile: MasterCV | null }>("/api/v1/profile");
  return data.profile ?? null;
}

export async function syncRemoteProfile(profile: MasterCV): Promise<void> {
  await request<{ status: string }>("/api/v1/profile", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(profile),
  });
}

export async function compileResumePDF(
  document: AuditResult["targeted_resume"],
): Promise<Blob> {
  const response = await sendRequest("/api/v1/compile/pdf", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ document, document_type: "resume" }),
  });
  const pdfData = await response.arrayBuffer();
  const signature = new TextDecoder().decode(pdfData.slice(0, 5));
  if (signature !== "%PDF-") {
    throw new Error("The API did not return a valid PDF. Try generating the resume again.");
  }
  return new Blob([pdfData], { type: "application/pdf" });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return isRecord(value) && Object.values(value).every((item) => typeof item === "string");
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === "string");
}

function isEducationEntry(value: unknown): value is EducationEntry {
  if (!isRecord(value)) return false;
  return (
    typeof value.university === "string" &&
    typeof value.degree === "string" &&
    typeof value.field_of_study === "string" &&
    typeof value.start_date === "string" &&
    typeof value.end_date === "string"
  );
}

function isEducationArray(value: unknown): value is EducationEntry[] {
  return Array.isArray(value) && value.every(isEducationEntry);
}

function normalizeSavedEducation(value: EducationEntry[] | string | undefined): EducationEntry[] {
  if (Array.isArray(value)) return value;
  if (typeof value !== "string") return [];
  return value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const parts = line.split(",").map((part) => part.trim());
      return {
        degree: parts[0] ?? line,
        university: parts[1] ?? "",
        field_of_study: parts[2] ?? "",
        start_date: "",
        end_date: parts[3] ?? "",
      };
    });
}

function isWorkExperience(value: unknown): value is WorkExperience {
  if (!isRecord(value)) return false;
  return (
    typeof value.company === "string" &&
    typeof value.title === "string" &&
    (typeof value.location === "string" || value.location === null) &&
    typeof value.start_date === "string" &&
    typeof value.end_date === "string" &&
    isStringArray(value.bullets) &&
    isStringArray(value.skills_used)
  );
}

function isProjectItem(value: unknown): value is ProjectItem {
  if (!isRecord(value)) return false;
  return (
    typeof value.name === "string" &&
    typeof value.description === "string" &&
    (typeof value.repo_url === "string" || value.repo_url === null) &&
    (typeof value.live_url === "string" || value.live_url === null) &&
    (typeof value.primary_language === "string" || value.primary_language === null) &&
    isStringArray(value.tags) &&
    (typeof value.readme_summary === "string" || value.readme_summary === null) &&
    isStringArray(value.implicit_skills_found) &&
    typeof value.relevance_score === "number"
  );
}

function isSavedProfile(value: unknown): value is SavedProfile {
  if (!isRecord(value)) return false;
  return (
    typeof value.full_name === "string" &&
    typeof value.email === "string" &&
    typeof value.professional_summary === "string" &&
    typeof value.skills === "string" &&
    (value.contact === undefined || isStringRecord(value.contact)) &&
    (value.education === undefined ||
      typeof value.education === "string" ||
      isEducationArray(value.education)) &&
    (value.certifications === undefined || typeof value.certifications === "string") &&
    Array.isArray(value.work_history) &&
    value.work_history.every(isWorkExperience) &&
    Array.isArray(value.all_projects) &&
    value.all_projects.every(isProjectItem)
  );
}

export async function loadSavedProfile(): Promise<SavedProfile | null> {
  const contents = await invoke<string | null>("load_saved_profile");
  if (contents === null) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(contents) as unknown;
  } catch {
    throw new Error("The encrypted profile does not contain valid JSON; it was not changed.");
  }
  if (
    !isRecord(parsed) ||
    (parsed.version !== 1 && parsed.version !== 2) ||
    !isSavedProfile(parsed.profile)
  ) {
    throw new Error("The saved profile has an unsupported format; it was not changed.");
  }
  return {
    ...parsed.profile,
    education: normalizeSavedEducation(parsed.profile.education),
  };
}

export async function saveProfile(profile: SavedProfile): Promise<void> {
  const file: SavedProfileFile = { version: 2, profile };
  await invoke("save_profile", { profileJson: JSON.stringify(file) });
}

export async function deleteSavedProfile(): Promise<void> {
  await invoke("delete_saved_profile");
}

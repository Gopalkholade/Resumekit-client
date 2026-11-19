package com.resumekit.android.data

import kotlinx.serialization.Serializable

@Serializable
data class ProjectItem(
    val name: String,
    val description: String,
    val repo_url: String? = null,
    val live_url: String? = null,
    val primary_language: String? = null,
    val tags: List<String> = emptyList(),
    val readme_summary: String? = null,
    val implicit_skills_found: List<String> = emptyList(),
    val relevance_score: Double = 0.0,
)

@Serializable
data class TargetedProject(
    val name: String = "",
    val bullets: List<String> = emptyList(),
)

@Serializable
data class WorkExperience(
    val company: String,
    val title: String,
    val location: String? = null,
    val start_date: String,
    val end_date: String,
    val bullets: List<String> = emptyList(),
    val skills_used: List<String> = emptyList(),
)

@Serializable
data class MasterCV(
    val full_name: String,
    val contact: Map<String, String> = emptyMap(),
    val professional_summary: String = "",
    val work_history: List<WorkExperience> = emptyList(),
    val all_projects: List<ProjectItem> = emptyList(),
    val skills_inventory: Map<String, List<String>> = emptyMap(),
    val education: List<Map<String, String>> = emptyList(),
    val certifications: List<String> = emptyList(),
)

@Serializable
data class LinkedInProfileImport(
    val full_name: String = "",
    val contact: Map<String, String> = emptyMap(),
    val professional_summary: String = "",
    val skills_inventory: Map<String, List<String>> = emptyMap(),
    val work_history: List<WorkExperience> = emptyList(),
    val education: List<Map<String, String>> = emptyList(),
    val certifications: List<String> = emptyList(),
)

@Serializable
data class TargetedResume(
    val header_title: String = "",
    val tailored_summary: String = "",
    val skills_matrix: Map<String, List<String>> = emptyMap(),
    val selected_experience: List<WorkExperience> = emptyList(),
    val selected_projects: List<TargetedProject> = emptyList(),
    val education: List<Map<String, String>> = emptyList(),
    val certifications: List<String> = emptyList(),
    val full_name: String = "",
    val contact: Map<String, String> = emptyMap(),
)

@Serializable
data class GapReport(
    val matched_skills: List<String> = emptyList(),
    val implicit_skills_from_readme: List<String> = emptyList(),
    val missing_critical_gaps: List<String> = emptyList(),
)

@Serializable
data class AuditResult(
    val gap_report: GapReport,
    val targeted_resume: TargetedResume,
)

@Serializable
data class WorkHistoryResponse(val work_history: List<WorkExperience>)

@Serializable
data class ProjectsResponse(val projects: List<ProjectItem>)

@Serializable
data class JobDescriptionResponse(val job_description: String)

@Serializable
data class ProfileResponse(val profile: MasterCV? = null)

@Serializable
data class ApiStatusResponse(val status: String, val gemini_configured: Boolean)

@Serializable
data class JobDescriptionUrlRequest(val url: String)

@Serializable
data class GitHubIngestRequest(val username: String, val token: String? = null)

@Serializable
data class AuditRequest(
    val master_cv: MasterCV,
    val job_description: String,
    val allow_external_processing: Boolean,
)

@Serializable
data class CompileRequest(
    val document: TargetedResume,
    val document_type: String,
)

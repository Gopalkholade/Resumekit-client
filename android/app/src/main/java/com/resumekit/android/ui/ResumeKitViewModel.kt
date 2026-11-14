package com.resumekit.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resumekit.android.data.AuditResult
import com.resumekit.android.data.MasterCV
import com.resumekit.android.data.ResumeKitApi
import com.resumekit.android.data.SecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

sealed interface RequestStatus {
    data object Idle : RequestStatus
    data object WakingServer : RequestStatus
    data object Auditing : RequestStatus
    data class Working(val message: String) : RequestStatus
    data class Ready(val message: String) : RequestStatus
    data class Error(val message: String) : RequestStatus
}

enum class AppTab(val label: String) {
    Connection("Connect"),
    Profile("Profile"),
    Match("Match"),
    Resume("Resume"),
}

private fun normalizeEducation(entry: Map<String, String>): Map<String, String> = mapOf(
    "university" to (entry["university"] ?: entry["institution"].orEmpty()),
    "degree" to (entry["degree"] ?: entry["description"].orEmpty()),
    "field_of_study" to entry["field_of_study"].orEmpty(),
    "start_date" to entry["start_date"].orEmpty(),
    "end_date" to (entry["end_date"] ?: entry["year"].orEmpty()),
)

data class ResumeKitUiState(
    val apiUrl: String = "",
    val apiKey: String = "",
    val profile: MasterCV = MasterCV(full_name = ""),
    val skillsText: String = "",
    val certificationsText: String = "",
    val educationUniversity: String = "",
    val educationDegree: String = "",
    val educationFieldOfStudy: String = "",
    val educationStartDate: String = "",
    val educationEndDate: String = "",
    val educationReviewPending: Boolean = false,
    val jobDescription: String = "",
    val jobUrl: String = "",
    val githubUsername: String = "",
    val consentGiven: Boolean = false,
    val connected: Boolean = false,
    val geminiConfigured: Boolean = false,
    val busy: Boolean = false,
    val status: RequestStatus = RequestStatus.Idle,
    val result: AuditResult? = null,
    val pdfPath: String? = null,
    val activeTab: AppTab = AppTab.Connection,
)

class ResumeKitViewModel(application: Application) : AndroidViewModel(application) {
    private val secureStore = SecureStore(application)
    private val api = ResumeKitApi()
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(ResumeKitUiState())
    val state: StateFlow<ResumeKitUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val url = secureStore.read(API_URL_KEY).orEmpty()
                val key = secureStore.read(API_KEY_KEY).orEmpty()
                val profileText = secureStore.read(PROFILE_KEY)
                val loadedProfile = profileText?.let { json.decodeFromString<MasterCV>(it) }
                    ?: MasterCV(full_name = "")
                val profile = loadedProfile.copy(
                    education = loadedProfile.education.map(::normalizeEducation),
                )
                _state.update {
                    it.copy(
                        apiUrl = url,
                        apiKey = key,
                        profile = profile,
                        skillsText = profile.skills_inventory.values.flatten().distinct().joinToString(", "),
                        certificationsText = profile.certifications.joinToString(", "),
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(status = RequestStatus.Error(error.message ?: "Could not load encrypted settings.")) }
            }
        }
    }

    fun selectTab(tab: AppTab) = _state.update { it.copy(activeTab = tab) }

    fun updateApiUrl(value: String) = _state.update { it.copy(apiUrl = value, connected = false) }
    fun updateApiKey(value: String) = _state.update { it.copy(apiKey = value, connected = false) }
    fun updateJobDescription(value: String) = _state.update {
        it.copy(jobDescription = value, consentGiven = false, result = null, pdfPath = null)
    }
    fun updateJobUrl(value: String) = _state.update { it.copy(jobUrl = value) }
    fun updateGitHubUsername(value: String) = _state.update { it.copy(githubUsername = value) }
    fun updateConsent(value: Boolean) = _state.update { it.copy(consentGiven = value) }

    fun updateName(value: String) = updateProfile { it.copy(full_name = value) }
    fun updateSummary(value: String) = updateProfile { it.copy(professional_summary = value) }

    fun updateContact(key: String, value: String) =
        updateProfile { it.copy(contact = it.contact.toMutableMap().apply { put(key, value) }) }

    fun updateEducationUniversity(value: String) = _state.update { it.copy(educationUniversity = value) }
    fun updateEducationDegree(value: String) = _state.update { it.copy(educationDegree = value) }
    fun updateEducationFieldOfStudy(value: String) = _state.update { it.copy(educationFieldOfStudy = value) }
    fun updateEducationStartDate(value: String) = _state.update { it.copy(educationStartDate = value) }
    fun updateEducationEndDate(value: String) = _state.update { it.copy(educationEndDate = value) }

    fun updateEducationEntry(index: Int, key: String, value: String) {
        updateProfile { profile ->
            profile.copy(
                education = profile.education.mapIndexed { entryIndex, entry ->
                    if (entryIndex == index) entry + (key to value) else entry
                },
            )
        }
    }

    fun addEducation() {
        val snapshot = _state.value
        val values = listOf(
            snapshot.educationUniversity,
            snapshot.educationDegree,
            snapshot.educationFieldOfStudy,
            snapshot.educationStartDate,
            snapshot.educationEndDate,
        )
        if (values.all(String::isBlank)) {
            _state.update { it.copy(status = RequestStatus.Error("Enter at least one education detail.")) }
            return
        }
        val entry = mapOf(
            "university" to snapshot.educationUniversity.trim(),
            "degree" to snapshot.educationDegree.trim(),
            "field_of_study" to snapshot.educationFieldOfStudy.trim(),
            "start_date" to snapshot.educationStartDate.trim(),
            "end_date" to snapshot.educationEndDate.trim(),
        )
        updateProfile { it.copy(education = it.education + entry) }
        _state.update {
            it.copy(
                educationUniversity = "",
                educationDegree = "",
                educationFieldOfStudy = "",
                educationStartDate = "",
                educationEndDate = "",
            )
        }
    }

    fun removeEducation(index: Int) {
        updateProfile { profile ->
            profile.copy(education = profile.education.filterIndexed { entryIndex, _ -> entryIndex != index })
        }
    }

    fun dismissEducationReview() =
        _state.update { it.copy(educationReviewPending = false) }

    fun reviewEducationForEditing() = _state.update {
        it.copy(educationReviewPending = false, activeTab = AppTab.Profile)
    }

    fun updateCertifications(value: String) {
        _state.update { it.copy(certificationsText = value) }
        val certifications = value.split(",").map(String::trim).filter(String::isNotEmpty).distinct()
        updateProfile { it.copy(certifications = certifications) }
    }

    fun updateSkills(value: String) {
        _state.update { it.copy(skillsText = value) }
        updateProfile {
            val skills = value.split(",").map(String::trim).filter(String::isNotEmpty).distinct()
            it.copy(skills_inventory = if (skills.isEmpty()) emptyMap() else mapOf("Skills" to skills))
        }
    }

    private fun updateProfile(transform: (MasterCV) -> MasterCV) {
        _state.update {
            it.copy(
                profile = transform(it.profile),
                consentGiven = false,
                result = null,
                pdfPath = null,
            )
        }
        persistProfile()
    }

    private fun persistProfile() {
        viewModelScope.launch {
            try {
                secureStore.write(PROFILE_KEY, json.encodeToString(_state.value.profile))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update { it.copy(status = RequestStatus.Error(error.message ?: "Could not save the encrypted profile.")) }
            }
        }
    }

    fun saveConnection() {
        val snapshot = _state.value
        val parsed = runCatching { URI(snapshot.apiUrl.trim()) }.getOrNull()
        if (
            parsed == null ||
            parsed.scheme != "https" ||
            parsed.host.isNullOrBlank() ||
            parsed.userInfo != null ||
            parsed.rawPath !in setOf("", "/") ||
            parsed.query != null ||
            parsed.fragment != null
        ) {
            _state.update { it.copy(status = RequestStatus.Error("Enter a valid HTTPS API URL, such as https://your-space.hf.space.")) }
            return
        }
        if (snapshot.apiKey.isBlank()) {
            _state.update { it.copy(status = RequestStatus.Error("Enter your ResumeKit API key.")) }
            return
        }
        launchRequest(RequestStatus.WakingServer) {
            secureStore.write(API_URL_KEY, snapshot.apiUrl.trim().trimEnd('/'))
            secureStore.write(API_KEY_KEY, snapshot.apiKey)
            api.checkHealth(snapshot.apiUrl.trim().trimEnd('/'))
            val remoteStatus = api.checkStatus(snapshot.apiUrl.trim().trimEnd('/'), snapshot.apiKey)
            if (!remoteStatus.gemini_configured) {
                throw IllegalStateException("The Space is reachable, but its GEMINI_API_KEY secret is not configured.")
            }
            _state.update { it.copy(consentGiven = false) }
            _state.update {
                it.copy(
                    connected = true,
                    geminiConfigured = remoteStatus.gemini_configured,
                    apiUrl = snapshot.apiUrl.trim().trimEnd('/'),
                    status = RequestStatus.Ready("Connected. The Space and Gemini are ready."),
                )
            }
        }
    }

    fun ingestLinkedIn(fileName: String, bytes: ByteArray) {
        if (bytes.size > MAX_LINKEDIN_BYTES) {
            _state.update { it.copy(status = RequestStatus.Error("LinkedIn PDF exceeds the 15 MiB upload limit.")) }
            return
        }
        val snapshot = _state.value
        launchRequest(RequestStatus.Working("Importing LinkedIn work history...")) {
            requireCredentials(snapshot.apiUrl, snapshot.apiKey)
            val imported = api.ingestLinkedIn(snapshot.apiUrl, snapshot.apiKey, fileName, bytes)
            updateProfile {
                val merged = (it.work_history + imported.work_history).distinctBy {
                    listOf(it.company, it.title, it.start_date, it.end_date).joinToString("|")
                }
                it.copy(
                    work_history = merged,
                    education = if (imported.education.isNotEmpty()) {
                        imported.education.map(::normalizeEducation)
                    } else {
                        it.education
                    },
                )
            }
            _state.update {
                it.copy(
                    educationReviewPending = imported.education.isNotEmpty(),
                    status = RequestStatus.Ready(
                        if (imported.education.isNotEmpty()) {
                            "Imported ${imported.work_history.size} work entries and " +
                                "${imported.education.size} education entries. Review education fields."
                        } else {
                            "Imported ${imported.work_history.size} work entries; no education details were found."
                        },
                    ),
                )
            }
        }
    }

    fun ingestGitHub(token: String) {
        val snapshot = _state.value
        if (snapshot.githubUsername.isBlank()) {
            _state.update { it.copy(status = RequestStatus.Error("Enter your GitHub username first.")) }
            return
        }
        launchRequest(RequestStatus.Working("Importing public GitHub projects...")) {
            requireCredentials(snapshot.apiUrl, snapshot.apiKey)
            val projects = api.ingestGitHub(snapshot.apiUrl, snapshot.apiKey, snapshot.githubUsername.trim(), token)
            updateProfile {
                val merged = (it.all_projects + projects).distinctBy { project ->
                    project.repo_url?.takeIf(String::isNotBlank) ?: project.name
                }
                it.copy(all_projects = merged)
            }
            _state.update { it.copy(status = RequestStatus.Ready("Imported ${projects.size} GitHub projects.")) }
        }
    }

    fun fetchJobDescription() {
        val snapshot = _state.value
        if (snapshot.jobUrl.isBlank()) {
            _state.update { it.copy(status = RequestStatus.Error("Enter a public job-description URL.")) }
            return
        }
        launchRequest(RequestStatus.Working("Fetching job description...")) {
            requireCredentials(snapshot.apiUrl, snapshot.apiKey)
            val description = api.ingestJobDescription(snapshot.apiUrl, snapshot.apiKey, snapshot.jobUrl.trim())
            _state.update {
                it.copy(
                    jobDescription = description,
                    consentGiven = false,
                    result = null,
                    pdfPath = null,
                    status = RequestStatus.Ready("Job description imported. Review it before matching."),
                )
            }
        }
    }

    fun runAudit() {
        val snapshot = _state.value
        if (!snapshot.consentGiven) {
            _state.update { it.copy(status = RequestStatus.Error("Consent is required before sending profile details to Gemini.")) }
            return
        }
        if (snapshot.profile.full_name.isBlank()) {
            _state.update { it.copy(status = RequestStatus.Error("Add your name to the profile first.")) }
            return
        }
        if (snapshot.jobDescription.isBlank()) {
            _state.update { it.copy(status = RequestStatus.Error("Paste or import the job description first.")) }
            return
        }
        _state.update { it.copy(consentGiven = false, result = null, pdfPath = null) }
        launchRequest(RequestStatus.WakingServer) {
            requireCredentials(snapshot.apiUrl, snapshot.apiKey)
            api.checkHealth(snapshot.apiUrl)
            val remoteStatus = api.checkStatus(snapshot.apiUrl, snapshot.apiKey)
            if (!remoteStatus.gemini_configured) {
                throw IllegalStateException("The Space is reachable, but its GEMINI_API_KEY secret is not configured.")
            }
            _state.update { it.copy(status = RequestStatus.Auditing) }
            val result = api.audit(snapshot.apiUrl, snapshot.apiKey, snapshot.profile, snapshot.jobDescription)
            _state.update {
                it.copy(
                    result = result,
                    consentGiven = false,
                    geminiConfigured = remoteStatus.gemini_configured,
                    activeTab = AppTab.Resume,
                    status = RequestStatus.Ready("Resume tailored. Review the gap report and generate a PDF."),
                )
            }
        }
    }

    fun compileResume() {
        val snapshot = _state.value
        val resume = snapshot.result?.targeted_resume
        if (resume == null) {
            _state.update { it.copy(status = RequestStatus.Error("Create a tailored resume before compiling a PDF.")) }
            return
        }
        launchRequest(RequestStatus.Working("Compiling resume PDF...")) {
            requireCredentials(snapshot.apiUrl, snapshot.apiKey)
            val bytes = api.compileResume(snapshot.apiUrl, snapshot.apiKey, resume)
            val directory = File(getApplication<Application>().cacheDir, "resumes")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IllegalStateException("Could not create the temporary resume directory.")
            }
            val pdf = File(directory, "targeted-resume.pdf")
            withContext(Dispatchers.IO) { pdf.writeBytes(bytes) }
            _state.update {
                it.copy(pdfPath = pdf.absolutePath, status = RequestStatus.Ready("PDF ready to preview or share."))
            }
        }
    }

    fun showError(message: String) {
        _state.update { it.copy(status = RequestStatus.Error(message)) }
    }

    private fun launchRequest(initialStatus: RequestStatus, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = initialStatus) }
            try {
                block()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.update {
                    it.copy(
                        connected = if (initialStatus == RequestStatus.WakingServer) false else it.connected,
                        status = RequestStatus.Error(error.message ?: "The request failed."),
                    )
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun requireCredentials(url: String, key: String) {
        if (url.isBlank() || key.isBlank()) {
            throw IllegalStateException("Configure the HTTPS API URL and API key on the Connect tab first.")
        }
    }

    override fun onCleared() {
        api.close()
        super.onCleared()
    }

    companion object {
        private const val API_URL_KEY = "api_url"
        private const val API_KEY_KEY = "api_key"
        private const val PROFILE_KEY = "master_cv"
        private const val MAX_LINKEDIN_BYTES = 15 * 1024 * 1024
    }
}

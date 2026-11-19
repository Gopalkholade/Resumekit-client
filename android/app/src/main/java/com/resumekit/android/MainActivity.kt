package com.resumekit.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.resumekit.android.ui.AppTab
import com.resumekit.android.ui.RequestStatus
import com.resumekit.android.ui.ResumeKitUiState
import com.resumekit.android.ui.ResumeKitViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor

class MainActivity : ComponentActivity() {
    private val viewModel: ResumeKitViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val linkedinPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) {
                    lifecycleScope.launch {
                        try {
                            val (fileName, bytes) = withContext(Dispatchers.IO) { readLinkedInPdf(uri) }
                            viewModel.ingestLinkedIn(fileName, bytes)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            viewModel.showError(error.message ?: "Could not read the selected PDF.")
                        }
                    }
                }
            }

            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("ResumeKit") },
                            actions = {
                                Text(
                                    text = if (state.connected) "Connected" else "Not connected",
                                    modifier = Modifier.padding(end = 16.dp),
                                    color = if (state.connected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    },
                    bottomBar = {
                        NavigationBar {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = state.activeTab == tab,
                                    onClick = { viewModel.selectTab(tab) },
                                    icon = { Text(tab.label.take(1)) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp),
                    ) {
                        StatusMessage(state, onDismiss = viewModel::dismissStatus)
                        when (state.activeTab) {
                            AppTab.Connection -> ConnectionScreen(state, viewModel)
                            AppTab.Profile -> ProfileScreen(
                                state = state,
                                viewModel = viewModel,
                                onChooseLinkedIn = { linkedinPicker.launch(arrayOf("application/pdf")) },
                            )
                            AppTab.Match -> MatchScreen(state, viewModel)
                            AppTab.Resume -> ResumeScreen(
                                state = state,
                                viewModel = viewModel,
                                onShare = { path -> sharePdf(path) },
                                onOpen = { path -> openPdf(path) },
                            )
                        }
                    }
                }
                if (state.educationReviewPending) {
                    AlertDialog(
                        onDismissRequest = viewModel::dismissEducationReview,
                        title = { Text("Review extracted education") },
                        text = {
                            Text("Education details were extracted from LinkedIn. Do they need to be modified?")
                        },
                        confirmButton = {
                            TextButton(onClick = viewModel::reviewEducationForEditing) {
                                Text("Yes, edit details")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissEducationReview) {
                                Text("No, looks correct")
                            }
                        },
                    )
                }
            }
        }
    }

    private fun openPdf(path: String) {
        try {
            val file = File(path)
            if (!file.isFile) throw IllegalStateException("The generated PDF is no longer available. Generate it again.")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "Open resume PDF"))
        } catch (error: Exception) {
            viewModel.showError(error.message ?: "Could not open the resume PDF.")
        }
    }

    private fun sharePdf(path: String) {
        try {
            val file = File(path)
            if (!file.isFile) throw IllegalStateException("The generated PDF is no longer available. Generate it again.")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share resume PDF"))
        } catch (error: Exception) {
            viewModel.showError(error.message ?: "Could not share the resume PDF.")
        }
    }

    private fun readLinkedInPdf(uri: Uri): Pair<String, ByteArray> {
        val fileName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "linkedin-profile.pdf"
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var totalBytes = 0
            while (totalBytes <= MAX_LINKEDIN_BYTES) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size, MAX_LINKEDIN_BYTES + 1 - totalBytes),
                )
                if (count < 0) break
                if (count == 0) {
                    val nextByte = input.read()
                    if (nextByte < 0) break
                    output.write(nextByte)
                    totalBytes++
                } else {
                    output.write(buffer, 0, count)
                    totalBytes += count
                }
            }
            if (totalBytes > MAX_LINKEDIN_BYTES) {
                throw IllegalStateException("LinkedIn PDF exceeds the 15 MiB upload limit.")
            }
            output.toByteArray()
        } ?: throw IllegalStateException("Could not read the selected PDF.")
        return fileName to bytes
    }

    companion object {
        private const val MAX_LINKEDIN_BYTES = 15 * 1024 * 1024
    }
}

@Composable
private fun StatusMessage(state: ResumeKitUiState, onDismiss: () -> Unit) {
    val message = when (val status = state.status) {
        RequestStatus.Idle -> null
        RequestStatus.WakingServer -> "Waking/checking the ResumeKit Space... This can take up to 90 seconds."
        RequestStatus.Auditing -> "Gemini is evaluating the job description and selecting profile evidence..."
        is RequestStatus.Working -> status.message
        is RequestStatus.Ready -> status.message
        is RequestStatus.Error -> status.message
    } ?: return
    Card(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.busy) CircularProgressIndicator(modifier = Modifier.height(22.dp))
            Text(
                text = message,
                color = if (state.status is RequestStatus.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (!state.busy) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen(state: ResumeKitUiState, viewModel: ResumeKitViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect to your API", style = MaterialTheme.typography.headlineSmall)
        Text("Use the HTTPS URL of your Hugging Face Space. The API key is encrypted on this device and is never sent to Google Gemini.")
        OutlinedTextField(
            value = state.apiUrl,
            onValueChange = viewModel::updateApiUrl,
            label = { Text("API base URL") },
            placeholder = { Text("https://your-space.hf.space") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::updateApiKey,
            label = { Text("ResumeKit API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = viewModel::saveConnection,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.busy) "Connecting..." else "Save securely and connect")
        }
        Text(
            "The first health check wakes a sleeping Space. Connection and API requests allow up to 90 seconds.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.connected && state.geminiConfigured) {
            Text("Gemini is configured and ready.", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProfileScreen(
    state: ResumeKitUiState,
    viewModel: ResumeKitViewModel,
    onChooseLinkedIn: () -> Unit,
) {
    var githubToken by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Master profile", style = MaterialTheme.typography.headlineSmall)
        Text("Your profile is encrypted on this device. Imported details stay here until you explicitly consent to a match.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cloud sync", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Keep your skills, experience, and profile synchronized with Desktop via your private Space.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = viewModel::syncProfileFromCloud,
                        enabled = !state.busy && state.connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Pull from cloud")
                    }
                    OutlinedButton(
                        onClick = viewModel::pushProfileToCloud,
                        enabled = !state.busy && state.connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Push to cloud")
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.profile.full_name,
            onValueChange = viewModel::updateName,
            label = { Text("Full name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.contact["email"].orEmpty(),
            onValueChange = { viewModel.updateContact("email", it) },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.contact["phone"].orEmpty(),
            onValueChange = { viewModel.updateContact("phone", it) },
            label = { Text("Phone") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.contact["location"].orEmpty(),
            onValueChange = { viewModel.updateContact("location", it) },
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.contact["linkedin_url"].orEmpty(),
            onValueChange = { viewModel.updateContact("linkedin_url", it) },
            label = { Text("LinkedIn URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.contact["portfolio_url"].orEmpty(),
            onValueChange = { viewModel.updateContact("portfolio_url", it) },
            label = { Text("Portfolio URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.professional_summary,
            onValueChange = viewModel::updateSummary,
            label = { Text("Professional summary") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.skillsText,
            onValueChange = viewModel::updateSkills,
            label = { Text("Skills (comma separated)") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Education (optional)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = state.educationUniversity,
            onValueChange = viewModel::updateEducationUniversity,
            label = { Text("University / institution") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.educationDegree,
            onValueChange = viewModel::updateEducationDegree,
            label = { Text("Degree") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.educationFieldOfStudy,
            onValueChange = viewModel::updateEducationFieldOfStudy,
            label = { Text("Field of study") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.educationStartDate,
            onValueChange = viewModel::updateEducationStartDate,
            label = { Text("Start date") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.educationEndDate,
            onValueChange = viewModel::updateEducationEndDate,
            label = { Text("End date / graduation") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = viewModel::addEducation, enabled = !state.busy) {
            Text("Add education entry")
        }
        state.profile.education.forEachIndexed { index, education ->
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = education["university"].orEmpty(),
                        onValueChange = { viewModel.updateEducationEntry(index, "university", it) },
                        label = { Text("University / institution") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = education["degree"].orEmpty(),
                        onValueChange = { viewModel.updateEducationEntry(index, "degree", it) },
                        label = { Text("Degree") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = education["field_of_study"].orEmpty(),
                        onValueChange = { viewModel.updateEducationEntry(index, "field_of_study", it) },
                        label = { Text("Field of study") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = education["start_date"].orEmpty(),
                        onValueChange = { viewModel.updateEducationEntry(index, "start_date", it) },
                        label = { Text("Start date") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = education["end_date"].orEmpty(),
                        onValueChange = { viewModel.updateEducationEntry(index, "end_date", it) },
                        label = { Text("End date / graduation") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { viewModel.removeEducation(index) }, enabled = !state.busy) {
                        Text("Remove education entry")
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.certificationsText,
            onValueChange = viewModel::updateCertifications,
            label = { Text("Certifications (format: Certificate — Provider)") },
            placeholder = { Text("e.g. SQL (Basic) — HackerRank, AWS Cloud Practitioner — AWS") },
            supportingText = { Text("Format as 'Certification — Provider'. ResumeKit auto-infers recognized issuers on import.") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LinkedIn work history", style = MaterialTheme.typography.titleMedium)
                Text("${state.profile.work_history.size} work entries saved")
                OutlinedButton(onClick = onChooseLinkedIn, enabled = !state.busy) {
                    Text("Choose LinkedIn PDF")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GitHub projects", style = MaterialTheme.typography.titleMedium)
                Text("${state.profile.all_projects.size} projects saved")
                OutlinedTextField(
                    value = state.githubUsername,
                    onValueChange = viewModel::updateGitHubUsername,
                    label = { Text("GitHub username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = githubToken,
                    onValueChange = { githubToken = it },
                    label = { Text("Optional GitHub token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("The optional token is used only for this import and is not saved.")
                OutlinedButton(
                    onClick = {
                        viewModel.ingestGitHub(githubToken)
                        githubToken = ""
                    },
                    enabled = !state.busy,
                ) {
                    Text("Import public projects")
                }
            }
        }
        if (state.profile.work_history.isNotEmpty()) {
            Text("Experience entries", style = MaterialTheme.typography.titleMedium)
            state.profile.work_history.forEach { experience ->
                Text("- ${experience.title} - ${experience.company} (${experience.start_date}-${experience.end_date})")
            }
        }
    }
}

@Composable
private fun MatchScreen(state: ResumeKitUiState, viewModel: ResumeKitViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Match a job", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = state.jobUrl,
            onValueChange = viewModel::updateJobUrl,
            label = { Text("Job description URL (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = viewModel::fetchJobDescription,
            enabled = !state.busy && state.jobUrl.isNotBlank(),
        ) {
            Text("Fetch job description")
        }
        OutlinedTextField(
            value = state.jobDescription,
            onValueChange = viewModel::updateJobDescription,
            label = { Text("Job description") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Switch(
                    checked = state.consentGiven,
                    onCheckedChange = viewModel::updateConsent,
                )
                Text("I consent to send my profile and this job description to the ResumeKit API for processing with Google Gemini.")
            }
        }
        Button(
            onClick = viewModel::runAudit,
            enabled = !state.busy && state.consentGiven && state.jobDescription.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Analyze and tailor resume")
        }
    }
}

@Composable
private fun ResumeScreen(
    state: ResumeKitUiState,
    viewModel: ResumeKitViewModel,
    onShare: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val result = state.result
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tailored resume", style = MaterialTheme.typography.headlineSmall)
        if (result == null) {
            Text("Complete a job match to see your gap report and tailored resume here.")
            return@Column
        }
        Text("Matched skills", style = MaterialTheme.typography.titleMedium)
        Text(result.gap_report.matched_skills.ifEmpty { listOf("No matched skills reported.") }.joinToString(", "))
        Text("Skills identified from project evidence", style = MaterialTheme.typography.titleMedium)
        Text(result.gap_report.implicit_skills_from_readme.ifEmpty { listOf("None reported.") }.joinToString(", "))
        Text("Critical gaps", style = MaterialTheme.typography.titleMedium)
        if (result.gap_report.missing_critical_gaps.isEmpty()) {
            Text("No critical gaps reported. Great match!")
        } else {
            Text(
                "If you possess any of these skills, tap '+ Add' to save it permanently to your skills database:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.gap_report.missing_critical_gaps.forEach { gap ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("• $gap", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { viewModel.addSkillFromGap(gap) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text("+ Add to my skills", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(result.targeted_resume.header_title, style = MaterialTheme.typography.titleLarge)
        Text(result.targeted_resume.tailored_summary)
        if (result.targeted_resume.selected_projects.isNotEmpty()) {
            Text("Selected projects (${result.targeted_resume.selected_projects.size})", style = MaterialTheme.typography.titleMedium)
            result.targeted_resume.selected_projects.forEach { project ->
                Text("• ${project.name}", style = MaterialTheme.typography.titleSmall)
                project.bullets.forEach { bullet ->
                    Text("  - $bullet", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (result.targeted_resume.selected_experience.isNotEmpty()) {
            Text("Selected experience (${result.targeted_resume.selected_experience.size})", style = MaterialTheme.typography.titleMedium)
            result.targeted_resume.selected_experience.forEach { exp ->
                Text("• ${exp.title} - ${exp.company}", style = MaterialTheme.typography.titleSmall)
                exp.bullets.forEach { bullet ->
                    Text("  - $bullet", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            onClick = viewModel::compileResume,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Compile PDF")
        }
        state.pdfPath?.let { path ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onOpen(path) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open PDF")
                }
                OutlinedButton(
                    onClick = { onShare(path) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Share PDF")
                }
            }
            Text("PDF preview", style = MaterialTheme.typography.titleMedium)
            PdfPreview(path)
        }
    }
}

private data class PdfPagesState(val pages: List<Bitmap> = emptyList(), val error: String? = null)

@Composable
private fun PdfPreview(path: String) {
    val rendered by produceState(PdfPagesState(), path) {
        value = withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.isFile) throw IllegalStateException("The cached PDF is no longer available.")
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val pages = ArrayList<Bitmap>(renderer.pageCount)
                        for (index in 0 until renderer.pageCount) {
                            renderer.openPage(index).use { page ->
                                val width = 720
                                val height = (page.height.toFloat() / page.width * width).toInt().coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages.add(bitmap)
                            }
                        }
                        PdfPagesState(pages = pages)
                    }
                }
            } catch (error: Throwable) {
                PdfPagesState(error = "Preview rendering unavailable (${error.localizedMessage ?: "renderer error"}). Use 'Open PDF' or 'Share PDF' above.")
            }
        }
    }

    val renderError = rendered.error
    when {
        renderError != null -> {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = renderError,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        rendered.pages.isEmpty() -> CircularProgressIndicator()
        else -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rendered.pages.forEachIndexed { index, bitmap ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                "Page ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Resume Page ${index + 1}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(bitmap.width.toFloat() / bitmap.height),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.resumekit.android.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ResumeKitApi {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(this@ResumeKitApi.json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun checkHealth(baseUrl: String) {
        withTimeoutMessage {
            val response = client.get("${baseUrl.trimEnd('/')}/health")
            requireSuccess(response)
        }
    }

    suspend fun checkStatus(baseUrl: String, apiKey: String): ApiStatusResponse =
        requestJson(baseUrl, apiKey, "/api/v1/status", HttpMethod.Get)

    suspend fun ingestLinkedIn(
        baseUrl: String,
        apiKey: String,
        fileName: String,
        bytes: ByteArray,
    ): LinkedInProfileImport {
        return withTimeoutMessage {
            val safeFileName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "profile.pdf" }
            val response = client.post("${baseUrl.trimEnd('/')}/api/v1/ingest/linkedin") {
                header(API_KEY_HEADER, apiKey)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        ContentDisposition("form-data").withParameter("name", "file")
                                            .withParameter("filename", safeFileName).toString(),
                                    )
                                    append(HttpHeaders.ContentType, ContentType.parse("application/pdf").toString())
                                },
                            )
                        },
                    ),
                )
            }
            decode<LinkedInProfileImport>(response)
        }
    }

    suspend fun ingestGitHub(
        baseUrl: String,
        apiKey: String,
        username: String,
        token: String?,
    ): List<ProjectItem> =
        requestJson<ProjectsResponse>(baseUrl, apiKey, "/api/v1/ingest/github", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(GitHubIngestRequest(username, token?.takeIf(String::isNotBlank)))
        }.projects

    suspend fun ingestJobDescription(baseUrl: String, apiKey: String, url: String): String =
        requestJson<JobDescriptionResponse>(baseUrl, apiKey, "/api/v1/ingest/job-description", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(JobDescriptionUrlRequest(url))
        }.job_description

    suspend fun audit(
        baseUrl: String,
        apiKey: String,
        masterCV: MasterCV,
        jobDescription: String,
    ): AuditResult =
        requestJson(baseUrl, apiKey, "/api/v1/audit", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(AuditRequest(masterCV, jobDescription, allow_external_processing = true))
        }

    suspend fun fetchRemoteProfile(
        baseUrl: String,
        apiKey: String,
    ): MasterCV? =
        requestJson<ProfileResponse>(baseUrl, apiKey, "/api/v1/profile", HttpMethod.Get).profile

    suspend fun syncRemoteProfile(
        baseUrl: String,
        apiKey: String,
        profile: MasterCV,
    ) {
        withTimeoutMessage {
            val response = client.request(baseUrl.trimEnd('/') + "/api/v1/profile") {
                this.method = HttpMethod.Put
                header(API_KEY_HEADER, apiKey)
                contentType(ContentType.Application.Json)
                setBody(profile)
            }
            requireSuccess(response)
        }
    }

    suspend fun compileResume(
        baseUrl: String,
        apiKey: String,
        resume: TargetedResume,
    ): ByteArray {
        return withTimeoutMessage {
            val response = client.post("${baseUrl.trimEnd('/')}/api/v1/compile/pdf") {
                header(API_KEY_HEADER, apiKey)
                contentType(ContentType.Application.Json)
                setBody(CompileRequest(resume, "resume"))
            }
            requireSuccess(response)
            val bytes = response.bodyAsBytes()
            if (
                bytes.size < PDF_SIGNATURE.size ||
                !PDF_SIGNATURE.indices.all { bytes[it] == PDF_SIGNATURE[it] }
            ) {
                throw IllegalStateException("The API response was not a valid PDF.")
            }
            bytes
        }
    }

    private suspend inline fun <reified T> requestJson(
        baseUrl: String,
        apiKey: String,
        path: String,
        method: HttpMethod,
        crossinline configure: HttpRequestBuilder.() -> Unit = {},
    ): T {
        return withTimeoutMessage {
            val response = client.request(baseUrl.trimEnd('/') + path) {
                this.method = method
                header(API_KEY_HEADER, apiKey)
                configure()
            }
            decode(response)
        }
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        requireSuccess(response)
        return try {
            json.decodeFromString(response.bodyAsText())
        } catch (error: SerializationException) {
            throw IllegalStateException("The API returned an invalid response.", error)
        }
    }

    private suspend fun requireSuccess(response: HttpResponse) {
        if (response.status.value in 200..299) return
        val body = response.bodyAsText()
        val detail = runCatching {
            val element = json.parseToJsonElement(body)
            if (element is kotlinx.serialization.json.JsonObject) {
                val detailElement = element["detail"]
                when (detailElement) {
                    is kotlinx.serialization.json.JsonPrimitive -> detailElement.content
                    is kotlinx.serialization.json.JsonArray -> {
                        detailElement.mapNotNull { err ->
                            (err as? kotlinx.serialization.json.JsonObject)?.let { obj ->
                                val loc = (obj["loc"] as? kotlinx.serialization.json.JsonArray)
                                    ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                                    ?.filter { it != "body" }
                                    ?.joinToString(" -> ")
                                val msg = (obj["msg"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                if (loc.isNullOrBlank()) msg else "$loc: $msg"
                            }
                        }.joinToString("; ")
                    }
                    else -> detailElement?.toString()?.trim('"')
                }
            } else null
        }.getOrNull()
        throw IllegalStateException(detail?.takeIf(String::isNotBlank) ?: "Request failed (HTTP ${response.status.value}).")
    }

    private suspend fun <T> withTimeoutMessage(block: suspend () -> T): T {
        try {
            return block()
        } catch (error: HttpRequestTimeoutException) {
            throw IllegalStateException(
                "The API did not respond within 90 seconds. A sleeping Hugging Face Space may still be starting; retry when it is awake.",
                error,
            )
        }
    }

    fun close() = client.close()

    companion object {
        private const val API_KEY_HEADER = "X-API-Key"
        private const val REQUEST_TIMEOUT_MS = 90_000L
        private val PDF_SIGNATURE = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte())
    }
}

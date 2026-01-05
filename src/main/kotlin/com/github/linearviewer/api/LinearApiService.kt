package com.github.linearviewer.api

import com.github.linearviewer.model.*
import com.github.linearviewer.settings.LinearSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Service
class LinearApiService {
    private val logger = Logger.getInstance(LinearApiService::class.java)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient.newBuilder().build()

    private val apiUrl = "https://api.linear.app/graphql"

    private fun getApiKey(): String? = service<LinearSettings>().apiKey

    fun fetchMyIssuesSync(): Result<List<Issue>> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("API key not configured"))
        }

        return try {
            val query = """
                query MyIssues {
                    issues(
                        filter: {
                            assignee: { isMe: { eq: true } }
                            state: { type: { nin: ["completed", "canceled"] } }
                        }
                        orderBy: updatedAt
                        first: 50
                    ) {
                        nodes {
                            id
                            identifier
                            title
                            description
                            priority
                            url
                            createdAt
                            updatedAt
                            state {
                                id
                                name
                                color
                                type
                            }
                            assignee {
                                id
                                name
                                email
                                avatarUrl
                            }
                            project {
                                id
                                name
                                icon
                                color
                            }
                            labels {
                                nodes {
                                    id
                                    name
                                    color
                                }
                            }
                            comments {
                                nodes {
                                    id
                                    body
                                    createdAt
                                    user {
                                        id
                                        name
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()

            val requestBody = json.encodeToString(GraphQLRequest(query))
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val result: GraphQLResponse<IssuesData> = json.decodeFromString(response.body())

            if (result.errors != null && result.errors.isNotEmpty()) {
                val errorMessage = result.errors.joinToString(", ") { it.message }
                logger.warn("GraphQL errors: $errorMessage")
                return Result.failure(Exception(errorMessage))
            }

            val issues = result.data?.issues?.nodes ?: emptyList()
            Result.success(issues)
        } catch (e: Exception) {
            logger.error("Failed to fetch issues", e)
            Result.failure(e)
        }
    }

    fun fetchAllIssuesSync(): Result<List<Issue>> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("API key not configured"))
        }

        return try {
            val query = """
                query AllIssues {
                    issues(
                        filter: {
                            state: { type: { nin: ["completed", "canceled"] } }
                        }
                        orderBy: updatedAt
                        first: 50
                    ) {
                        nodes {
                            id
                            identifier
                            title
                            description
                            priority
                            url
                            createdAt
                            updatedAt
                            state {
                                id
                                name
                                color
                                type
                            }
                            assignee {
                                id
                                name
                                email
                                avatarUrl
                            }
                            project {
                                id
                                name
                                icon
                                color
                            }
                            labels {
                                nodes {
                                    id
                                    name
                                    color
                                }
                            }
                            comments {
                                nodes {
                                    id
                                    body
                                    createdAt
                                    user {
                                        id
                                        name
                                    }
                                }
                            }
                        }
                    }
                }
            """.trimIndent()

            val requestBody = json.encodeToString(GraphQLRequest(query))
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val result: GraphQLResponse<IssuesData> = json.decodeFromString(response.body())

            if (result.errors != null && result.errors.isNotEmpty()) {
                val errorMessage = result.errors.joinToString(", ") { it.message }
                logger.warn("GraphQL errors: $errorMessage")
                return Result.failure(Exception(errorMessage))
            }

            val issues = result.data?.issues?.nodes ?: emptyList()
            Result.success(issues)
        } catch (e: Exception) {
            logger.error("Failed to fetch issues", e)
            Result.failure(e)
        }
    }

    fun testConnectionSync(): Result<User> {
        val apiKey = getApiKey()
        if (apiKey.isNullOrBlank()) {
            return Result.failure(IllegalStateException("API key not configured"))
        }

        return try {
            val query = """
                query Viewer {
                    viewer {
                        id
                        name
                        email
                    }
                }
            """.trimIndent()

            val requestBody = json.encodeToString(GraphQLRequest(query))
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            val result: GraphQLResponse<ViewerData> = json.decodeFromString(response.body())

            if (result.errors != null && result.errors.isNotEmpty()) {
                val errorMessage = result.errors.joinToString(", ") { it.message }
                return Result.failure(Exception(errorMessage))
            }

            val viewer = result.data?.viewer
                ?: return Result.failure(Exception("No user data returned"))

            Result.success(viewer)
        } catch (e: Exception) {
            logger.error("Failed to test connection", e)
            Result.failure(e)
        }
    }

    companion object {
        fun getInstance(): LinearApiService = service()
    }
}

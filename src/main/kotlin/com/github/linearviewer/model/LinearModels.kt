package com.github.linearviewer.model

import kotlinx.serialization.Serializable

@Serializable
data class GraphQLRequest(
    val query: String,
    val variables: Map<String, String> = emptyMap()
)

@Serializable
data class GraphQLResponse<T>(
    val data: T? = null,
    val errors: List<GraphQLError>? = null
)

@Serializable
data class GraphQLError(
    val message: String
)

@Serializable
data class IssuesData(
    val issues: IssueConnection
)

@Serializable
data class IssueConnection(
    val nodes: List<Issue>
)

@Serializable
data class Issue(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String? = null,
    val priority: Int = 0,
    val url: String,
    val state: WorkflowState? = null,
    val assignee: User? = null,
    val project: Project? = null,
    val labels: LabelConnection? = null,
    val comments: CommentConnection? = null,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class CommentConnection(
    val nodes: List<Comment>
)

@Serializable
data class Comment(
    val id: String,
    val body: String,
    val createdAt: String,
    val user: User? = null
)

@Serializable
data class WorkflowState(
    val id: String,
    val name: String,
    val color: String,
    val type: String
)

@Serializable
data class User(
    val id: String,
    val name: String,
    val email: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val icon: String? = null,
    val color: String? = null
)

@Serializable
data class LabelConnection(
    val nodes: List<Label>
)

@Serializable
data class Label(
    val id: String,
    val name: String,
    val color: String
)

@Serializable
data class ViewerData(
    val viewer: User
)

// Priority levels in Linear
object Priority {
    const val NO_PRIORITY = 0
    const val URGENT = 1
    const val HIGH = 2
    const val MEDIUM = 3
    const val LOW = 4

    fun toDisplayString(priority: Int): String = when (priority) {
        URGENT -> "Urgent"
        HIGH -> "High"
        MEDIUM -> "Medium"
        LOW -> "Low"
        else -> "No Priority"
    }
}

package com.github.linearviewer.settings

import com.github.linearviewer.api.LinearApiService
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import java.util.concurrent.CompletableFuture
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.SwingUtilities

class LinearSettingsConfigurable : Configurable {
    private var panel: DialogPanel? = null
    private var apiKeyField: JBPasswordField? = null
    private var showOnlyMyIssuesCheckbox: JBCheckBox? = null
    private var branchNameFormatField: JBTextField? = null
    private var testResultLabel: JBLabel? = null

    override fun getDisplayName(): String = "Linear"

    override fun createComponent(): JComponent {
        val settings = LinearSettings.getInstance()

        panel = panel {
            group("API Configuration") {
                row("API Key:") {
                    apiKeyField = passwordField()
                        .columns(COLUMNS_LARGE)
                        .comment("Get your API key from Linear Settings → API → Personal API keys")
                        .component
                    apiKeyField?.text = settings.apiKey ?: ""
                }
                row {
                    val testButton = JButton("Test Connection").apply {
                        addActionListener { testConnection() }
                    }
                    cell(testButton)
                    testResultLabel = JBLabel("")
                    cell(testResultLabel!!)
                }
            }
            group("Display Options") {
                row {
                    showOnlyMyIssuesCheckbox = checkBox("Show only my issues")
                        .comment("When enabled, only shows issues assigned to you")
                        .component
                    showOnlyMyIssuesCheckbox?.isSelected = settings.showOnlyMyIssues
                }
            }
            group("Branch Name Format") {
                row("Format:") {
                    branchNameFormatField = textField()
                        .columns(COLUMNS_LARGE)
                        .component
                    branchNameFormatField?.text = settings.branchNameFormat
                }
                row {
                    comment("Placeholders: {id} = pr-123, {ID} = PR-123, {title} = issue-title")
                }
                row {
                    comment("Example: kumachan/{id} → kumachan/pr-123")
                }
            }
            group("Help") {
                row {
                    browserLink("Get API Key from Linear", "https://linear.app/settings/api")
                }
                row {
                    comment("Create a Personal API key in Linear and paste it above.")
                }
            }
        }

        return panel!!
    }

    private fun testConnection() {
        val apiKey = String(apiKeyField?.password ?: charArrayOf())
        if (apiKey.isBlank()) {
            testResultLabel?.text = "Please enter an API key"
            return
        }

        // Temporarily save the API key for testing
        val settings = LinearSettings.getInstance()
        val originalKey = settings.apiKey
        settings.apiKey = apiKey

        testResultLabel?.text = "Testing..."

        CompletableFuture.supplyAsync {
            LinearApiService.getInstance().testConnectionSync()
        }.thenAccept { result ->
            SwingUtilities.invokeLater {
                result.fold(
                    onSuccess = { user ->
                        testResultLabel?.text = "Connected as ${user.name}"
                    },
                    onFailure = { error ->
                        testResultLabel?.text = "Error: ${error.message}"
                        settings.apiKey = originalKey
                    }
                )
            }
        }.exceptionally { throwable ->
            SwingUtilities.invokeLater {
                testResultLabel?.text = "Error: ${throwable.message}"
                settings.apiKey = originalKey
            }
            null
        }
    }

    override fun isModified(): Boolean {
        val settings = LinearSettings.getInstance()
        val currentApiKey = String(apiKeyField?.password ?: charArrayOf())
        val currentShowOnlyMyIssues = showOnlyMyIssuesCheckbox?.isSelected ?: true
        val currentBranchNameFormat = branchNameFormatField?.text ?: "{id}-{title}"

        return currentApiKey != (settings.apiKey ?: "") ||
                currentShowOnlyMyIssues != settings.showOnlyMyIssues ||
                currentBranchNameFormat != settings.branchNameFormat
    }

    override fun apply() {
        val settings = LinearSettings.getInstance()
        settings.apiKey = String(apiKeyField?.password ?: charArrayOf())
        settings.showOnlyMyIssues = showOnlyMyIssuesCheckbox?.isSelected ?: true
        settings.branchNameFormat = branchNameFormatField?.text ?: "{id}-{title}"
    }

    override fun reset() {
        val settings = LinearSettings.getInstance()
        apiKeyField?.text = settings.apiKey ?: ""
        showOnlyMyIssuesCheckbox?.isSelected = settings.showOnlyMyIssues
        branchNameFormatField?.text = settings.branchNameFormat
        testResultLabel?.text = ""
    }
}

package com.github.linearviewer.toolwindow

import com.github.linearviewer.api.LinearApiService
import com.github.linearviewer.model.Comment
import com.github.linearviewer.model.Issue
import com.github.linearviewer.model.Priority
import com.github.linearviewer.settings.LinearSettings
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CompletableFuture
import javax.swing.*

enum class SortOption(val displayName: String) {
    UPDATED_AT("Updated"),
    PRIORITY("Priority"),
    STATUS("Status"),
    CREATED_AT("Created")
}

enum class StatusFilter(val displayName: String, val stateType: String?) {
    ALL("All", null),
    STARTED("Started", "started"),
    UNSTARTED("Unstarted", "unstarted"),
    BACKLOG("Backlog", "backlog"),
    COMPLETED("Completed", "completed"),
    CANCELED("Canceled", "canceled")
}

class LinearToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val issueListModel = DefaultListModel<Issue>()
    private val issueList = JBList(issueListModel)
    private val statusLabel = JBLabel("Loading...")

    private var allIssues: List<Issue> = emptyList()
    private var currentSort: SortOption = SortOption.UPDATED_AT
    private var currentStatusFilter: StatusFilter = StatusFilter.ALL
    private var currentProjectFilter: String? = null

    init {
        setupUI()
        setupToolbar()
        refreshIssues()
    }

    private fun setupUI() {
        issueList.cellRenderer = IssueListCellRenderer()
        issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        // Double-click to show detail dialog
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !e.isPopupTrigger) {
                    val selectedIssue = issueList.selectedValue
                    selectedIssue?.let { showIssueDetailDialog(it) }
                }
            }
        })

        // Right-click context menu with macOS support
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handlePopupTrigger(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                handlePopupTrigger(e)
            }

            private fun handlePopupTrigger(e: MouseEvent) {
                // macOS: Ctrl+Click should also trigger popup
                if (e.isPopupTrigger ||
                    (SystemInfo.isMac && e.isControlDown && e.button == MouseEvent.BUTTON1)) {
                    showContextMenu(e)
                }
            }
        })

        val mainPanel = JPanel(BorderLayout())
        mainPanel.add(JBScrollPane(issueList), BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        setContent(mainPanel)
    }

    private fun showIssueDetailDialog(issue: Issue) {
        IssueDetailDialog(project, issue).show()
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = issueList.locationToIndex(e.point)
        if (index < 0) return

        issueList.selectedIndex = index
        val issue = issueList.selectedValue ?: return

        val actionGroup = createContextMenuActions(issue)
        val popupMenu = ActionManager.getInstance()
            .createActionPopupMenu("LinearIssueContextMenu", actionGroup)
        popupMenu.component.show(e.component, e.x, e.y)
    }

    private fun createContextMenuActions(issue: Issue): ActionGroup {
        return DefaultActionGroup().apply {
            add(object : AnAction("Copy Branch Name") {
                override fun actionPerformed(e: AnActionEvent) {
                    copyToClipboard(generateBranchName(issue))
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

            add(object : AnAction("Copy Issue ID (${issue.identifier})") {
                override fun actionPerformed(e: AnActionEvent) {
                    copyToClipboard(issue.identifier)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

            add(object : AnAction("Copy Issue URL") {
                override fun actionPerformed(e: AnActionEvent) {
                    copyToClipboard(issue.url)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

            addSeparator()

            add(object : AnAction("Open in Browser", null, com.intellij.icons.AllIcons.General.Web) {
                override fun actionPerformed(e: AnActionEvent) {
                    openInBrowser(issue.url)
                }
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
    }

    private fun generateBranchName(issue: Issue): String {
        val settings = LinearSettings.getInstance()
        val format = settings.branchNameFormat

        val id = issue.identifier.lowercase()
        val title = issue.title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .take(50)
            .trimEnd('-')

        return format
            .replace("{id}", id)
            .replace("{ID}", issue.identifier)
            .replace("{title}", title)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    private fun openInBrowser(url: String) {
        try {
            if (SystemInfo.isMac) {
                Runtime.getRuntime().exec(arrayOf("/usr/bin/open", url))
            } else if (SystemInfo.isWindows) {
                Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", url))
            } else {
                Runtime.getRuntime().exec(arrayOf("xdg-open", url))
            }
        } catch (e: Exception) {
            // Fallback to BrowserUtil
            BrowserUtil.browse(url)
        }
    }

    private fun setupToolbar() {
        val actionGroup = DefaultActionGroup().apply {
            // Refresh action
            add(object : AnAction("Refresh", "Refresh issues", com.intellij.icons.AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshIssues()
                }
            })

            addSeparator()

            // Sort options as dropdown
            val sortGroup = DefaultActionGroup("Sort By", true).apply {
                templatePresentation.icon = com.intellij.icons.AllIcons.General.ArrowDown
                SortOption.entries.forEach { option ->
                    add(object : AnAction(option.displayName) {
                        override fun actionPerformed(e: AnActionEvent) {
                            currentSort = option
                            applyFiltersAndSort()
                        }

                        override fun update(e: AnActionEvent) {
                            e.presentation.icon = if (currentSort == option)
                                com.intellij.icons.AllIcons.Actions.Checked else null
                        }

                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    })
                }
            }
            add(sortGroup)

            // Status filter dropdown
            val statusGroup = DefaultActionGroup("Status", true).apply {
                templatePresentation.icon = com.intellij.icons.AllIcons.General.Filter
                StatusFilter.entries.forEach { filter ->
                    add(object : AnAction(filter.displayName) {
                        override fun actionPerformed(e: AnActionEvent) {
                            currentStatusFilter = filter
                            applyFiltersAndSort()
                        }

                        override fun update(e: AnActionEvent) {
                            e.presentation.icon = if (currentStatusFilter == filter)
                                com.intellij.icons.AllIcons.Actions.Checked else null
                        }

                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    })
                }
            }
            add(statusGroup)

            // Project filter dropdown (dynamically populated)
            add(object : ActionGroup("Project", true) {
                init {
                    templatePresentation.icon = com.intellij.icons.AllIcons.Nodes.Folder
                }

                override fun getChildren(e: AnActionEvent?): Array<AnAction> {
                    val projects = allIssues.mapNotNull { it.project?.name }.distinct().sorted()
                    val actions = mutableListOf<AnAction>()

                    // All projects option
                    actions.add(object : AnAction("All Projects") {
                        override fun actionPerformed(e: AnActionEvent) {
                            currentProjectFilter = null
                            applyFiltersAndSort()
                        }

                        override fun update(e: AnActionEvent) {
                            e.presentation.icon = if (currentProjectFilter == null)
                                com.intellij.icons.AllIcons.Actions.Checked else null
                        }

                        override fun getActionUpdateThread() = ActionUpdateThread.BGT
                    })

                    if (projects.isNotEmpty()) {
                        actions.add(Separator.create())
                        projects.forEach { projectName ->
                            actions.add(object : AnAction(projectName) {
                                override fun actionPerformed(e: AnActionEvent) {
                                    currentProjectFilter = projectName
                                    applyFiltersAndSort()
                                }

                                override fun update(e: AnActionEvent) {
                                    e.presentation.icon = if (currentProjectFilter == projectName)
                                        com.intellij.icons.AllIcons.Actions.Checked else null
                                }

                                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                            })
                        }
                    }

                    return actions.toTypedArray()
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_TITLE, actionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
    }

    private fun applyFiltersAndSort() {
        var filtered = allIssues

        // Apply status filter (type-based, environment-independent)
        if (currentStatusFilter != StatusFilter.ALL) {
            filtered = filtered.filter { issue ->
                issue.state?.type == currentStatusFilter.stateType
            }
        }

        // Apply project filter
        currentProjectFilter?.let { projectName ->
            filtered = filtered.filter { it.project?.name == projectName }
        }

        // Apply sort
        filtered = when (currentSort) {
            SortOption.UPDATED_AT -> filtered.sortedByDescending { it.updatedAt }
            SortOption.CREATED_AT -> filtered.sortedByDescending { it.createdAt }
            SortOption.PRIORITY -> filtered.sortedBy {
                if (it.priority == 0) Int.MAX_VALUE else it.priority
            }
            SortOption.STATUS -> filtered.sortedBy { it.state?.name ?: "zzz" }
        }

        // Update UI
        issueListModel.clear()
        filtered.forEach { issueListModel.addElement(it) }
        statusLabel.text = "${filtered.size} / ${allIssues.size} issues"
    }

    fun refreshIssues() {
        statusLabel.text = "Loading..."
        issueListModel.clear()

        val settings = LinearSettings.getInstance()
        val apiService = LinearApiService.getInstance()

        CompletableFuture.supplyAsync {
            if (settings.showOnlyMyIssues) {
                apiService.fetchMyIssuesSync()
            } else {
                apiService.fetchAllIssuesSync()
            }
        }.thenAccept { result ->
            ApplicationManager.getApplication().invokeLater {
                result.fold(
                    onSuccess = { issues ->
                        allIssues = issues
                        applyFiltersAndSort()
                    },
                    onFailure = { error ->
                        statusLabel.text = "Error: ${error.message}"
                        if (error.message?.contains("API key") == true) {
                            statusLabel.text = "Please configure API key in Settings → Tools → Linear"
                        }
                    }
                )
            }
        }.exceptionally { throwable ->
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = "Error: ${throwable.message}"
            }
            null
        }
    }
}

class IssueListCellRenderer : ListCellRenderer<Issue> {
    override fun getListCellRendererComponent(
        list: JList<out Issue>,
        value: Issue,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 12)
        }

        // Top row: identifier and title
        val identifierLabel = JBLabel("[${value.identifier}]").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = if (isSelected) list.selectionForeground else JBColor.namedColor("Label.infoForeground", JBColor(0x5A5A5A, 0xABABAB))
        }

        val titleLabel = JBLabel(value.title).apply {
            foreground = if (isSelected) list.selectionForeground else list.foreground
        }

        // Bottom row: status, priority, project
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        // Status badge
        value.state?.let { state ->
            val stateLabel = createBadge(state.name, state.color)
            bottomPanel.add(stateLabel)
        }

        // Priority badge
        if (value.priority > 0) {
            val priorityText = Priority.toDisplayString(value.priority)
            val priorityColor = getPriorityColor(value.priority)
            val priorityLabel = createBadge(priorityText, priorityColor)
            bottomPanel.add(priorityLabel)
        }

        // Project badge
        value.project?.let { proj ->
            val projectLabel = createBadge(proj.name, proj.color ?: "#666666")
            bottomPanel.add(projectLabel)
        }

        // Main layout
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(createRowPanel(identifierLabel, titleLabel))
            add(Box.createVerticalStrut(4))
            add(bottomPanel)
        }

        panel.add(contentPanel, BorderLayout.CENTER)

        if (isSelected) {
            panel.background = list.selectionBackground
            panel.isOpaque = true
        } else {
            panel.background = list.background
            panel.isOpaque = true
        }

        return panel
    }

    private fun createRowPanel(left: JComponent, right: JComponent): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(left)
            add(Box.createHorizontalStrut(8))
            add(right)
            add(Box.createHorizontalGlue())
        }
    }

    private fun createBadge(text: String, hexColor: String): JComponent {
        val color = try {
            ColorUtil.fromHex(hexColor.removePrefix("#"))
        } catch (e: Exception) {
            JBColor.GRAY
        }

        return object : JLabel(text) {
            init {
                font = font.deriveFont(font.size - 2f)
                foreground = if (ColorUtil.isDark(color)) Color.WHITE else Color.BLACK
                border = JBUI.Borders.empty(3, 8)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun getPriorityColor(priority: Int): String = when (priority) {
        Priority.URGENT -> "#dc2626"
        Priority.HIGH -> "#f97316"
        Priority.MEDIUM -> "#eab308"
        Priority.LOW -> "#22c55e"
        else -> "#666666"
    }
}

class IssueDetailDialog(
    private val project: Project,
    private val issue: Issue
) : DialogWrapper(project, true) {

    init {
        title = "${issue.identifier}: ${issue.title}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(700, 500)
            border = JBUI.Borders.empty(8)
        }

        // Create tabbed pane for Description and Comments
        val tabbedPane = JTabbedPane().apply {
            // Description tab
            addTab("Description", createDescriptionPanel())

            // Comments tab
            val comments = issue.comments?.nodes ?: emptyList()
            addTab("Comments (${comments.size})", createCommentsPanel(comments))
        }

        // Header with metadata
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyBottom(12)

            // Identifier
            add(JBLabel(issue.identifier).apply {
                foreground = JBColor.namedColor("Label.infoForeground", JBColor(0x5A5A5A, 0xABABAB))
                font = font.deriveFont(12f)
                alignmentX = Component.LEFT_ALIGNMENT
            })

            add(Box.createVerticalStrut(4))

            // Title
            add(JBLabel("<html><body style='width: 650px'>${issue.title}</body></html>").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                alignmentX = Component.LEFT_ALIGNMENT
            })

            add(Box.createVerticalStrut(12))

            // Metadata row
            val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
            }

            // Status badge
            issue.state?.let { state ->
                metaPanel.add(createBadge(state.name, state.color))
            }

            // Priority badge
            if (issue.priority > 0) {
                val priorityText = Priority.toDisplayString(issue.priority)
                val priorityColor = getPriorityColor(issue.priority)
                metaPanel.add(createBadge(priorityText, priorityColor))
            }

            // Assignee
            issue.assignee?.let { assignee ->
                metaPanel.add(JBLabel("Assignee: ${assignee.name}").apply {
                    foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                })
            }

            // Project
            issue.project?.let { proj ->
                metaPanel.add(createBadge(proj.name, proj.color ?: "#666666"))
            }

            add(metaPanel)

            // Labels
            val labels = issue.labels?.nodes ?: emptyList()
            if (labels.isNotEmpty()) {
                add(Box.createVerticalStrut(8))
                val labelsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                labels.forEach { label ->
                    labelsPanel.add(createBadge(label.name, label.color))
                }
                add(labelsPanel)
            }
        }
    }

    private fun createDescriptionPanel(): JComponent {
        val description = issue.description ?: "No description"
        val html = markdownToHtml(description)

        val editorPane = JEditorPane().apply {
            contentType = "text/html"
            text = wrapInHtmlStyle(html)
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(8)
            // Allow links to be clickable
            addHyperlinkListener { e ->
                if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                    openInBrowser(e.url.toString())
                }
            }
        }

        return JBScrollPane(editorPane).apply {
            border = JBUI.Borders.empty()
        }
    }

    private fun createCommentsPanel(comments: List<Comment>): JComponent {
        if (comments.isEmpty()) {
            return JPanel(BorderLayout()).apply {
                add(JBLabel("No comments").apply {
                    horizontalAlignment = SwingConstants.CENTER
                    foreground = JBColor.namedColor("Label.disabledForeground", JBColor.GRAY)
                }, BorderLayout.CENTER)
            }
        }

        val commentsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        comments.sortedBy { it.createdAt }.forEach { comment ->
            commentsPanel.add(createCommentCard(comment))
            commentsPanel.add(Box.createVerticalStrut(12))
        }

        return JBScrollPane(commentsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }
    }

    private fun createCommentCard(comment: Comment): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.namedColor("Borders.color", JBColor(0xD0D0D0, 0x4A4A4A)), 1),
                JBUI.Borders.empty(12)
            )
            isOpaque = true
            background = JBColor.namedColor("Panel.background", JBColor.WHITE)

            // Header: Author and date
            val headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                val authorLabel = JBLabel(comment.user?.name ?: "Unknown").apply {
                    font = font.deriveFont(Font.BOLD)
                }
                val dateLabel = JBLabel(formatDate(comment.createdAt)).apply {
                    foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                    font = font.deriveFont(font.size - 1f)
                }
                add(authorLabel, BorderLayout.WEST)
                add(dateLabel, BorderLayout.EAST)
            }

            // Body: Markdown rendered
            val bodyHtml = markdownToHtml(comment.body)
            val bodyPane = JEditorPane().apply {
                contentType = "text/html"
                text = wrapInHtmlStyle(bodyHtml)
                isEditable = false
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                addHyperlinkListener { e ->
                    if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                        openInBrowser(e.url.toString())
                    }
                }
            }

            add(headerPanel, BorderLayout.NORTH)
            add(bodyPane, BorderLayout.CENTER)
        }
    }

    private fun markdownToHtml(markdown: String): String {
        return try {
            val flavour = GFMFlavourDescriptor()
            val parsedTree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, parsedTree, flavour).generateHtml()
        } catch (e: Exception) {
            // Fallback: escape HTML and convert newlines
            markdown
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")
        }
    }

    private fun wrapInHtmlStyle(html: String): String {
        val isDark = ColorUtil.isDark(JBColor.background())
        val textColor = if (isDark) "#BBBBBB" else "#333333"
        val linkColor = if (isDark) "#589DF6" else "#2470B3"
        val codeBackground = if (isDark) "#2B2D30" else "#F5F5F5"

        // Note: Java Swing's HTMLEditorKit has limited CSS support
        // Using only basic properties that are supported
        return """
            <html>
            <head>
            <style>
                body { font-family: sans-serif; font-size: 13pt; color: $textColor; margin: 0; padding: 0; }
                a { color: $linkColor; }
                code { background-color: $codeBackground; font-family: monospace; font-size: 12pt; }
                pre { background-color: $codeBackground; padding: 8px; }
                blockquote { margin-left: 12px; padding-left: 12px; color: #888888; }
                ul, ol { margin-left: 20px; }
            </style>
            </head>
            <body>$html</body>
            </html>
        """.trimIndent()
    }

    private fun formatDate(isoDate: String): String {
        return try {
            // Simple date formatting: 2024-01-15T10:30:00.000Z -> 2024-01-15 10:30
            val date = isoDate.take(10)
            val time = isoDate.drop(11).take(5)
            "$date $time"
        } catch (e: Exception) {
            isoDate.take(10)
        }
    }

    private fun createBadge(text: String, hexColor: String): JComponent {
        val color = try {
            ColorUtil.fromHex(hexColor.removePrefix("#"))
        } catch (e: Exception) {
            JBColor.GRAY
        }

        return object : JLabel(text) {
            init {
                font = font.deriveFont(font.size - 1f)
                foreground = if (ColorUtil.isDark(color)) Color.WHITE else Color.BLACK
                border = JBUI.Borders.empty(3, 8)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun getPriorityColor(priority: Int): String = when (priority) {
        Priority.URGENT -> "#dc2626"
        Priority.HIGH -> "#f97316"
        Priority.MEDIUM -> "#eab308"
        Priority.LOW -> "#22c55e"
        else -> "#666666"
    }

    private fun openInBrowser(url: String) {
        try {
            if (SystemInfo.isMac) {
                Runtime.getRuntime().exec(arrayOf("/usr/bin/open", url))
            } else if (SystemInfo.isWindows) {
                Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", url))
            } else {
                Runtime.getRuntime().exec(arrayOf("xdg-open", url))
            }
        } catch (e: Exception) {
            BrowserUtil.browse(url)
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}

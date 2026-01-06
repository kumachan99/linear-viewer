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
import java.awt.event.MouseMotionAdapter
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

enum class SortOrder(val displayName: String, val icon: javax.swing.Icon) {
    DESCENDING("Descending", com.intellij.icons.AllIcons.General.ArrowDown),
    ASCENDING("Ascending", com.intellij.icons.AllIcons.General.ArrowUp)
}

class LinearToolWindowPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val issueListModel = DefaultListModel<Issue>()
    private val issueList = JBList(issueListModel)
    private val statusLabel = JBLabel("Loading...")

    private var allIssues: List<Issue> = emptyList()
    private var currentSort: SortOption = SortOption.UPDATED_AT
    private var currentSortOrder: SortOrder = SortOrder.DESCENDING
    private var currentStatusFilter: StatusFilter = StatusFilter.ALL
    private var currentProjectFilter: String? = null
    private var hoveredIndex: Int = -1
    private var isLoading: Boolean = false
    private lateinit var filterPanel: JPanel

    init {
        setupUI()
        setupToolbar()
        refreshIssues()
    }

    private fun setupUI() {
        issueList.cellRenderer = IssueListCellRenderer { hoveredIndex }
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

        // Hover effect - track mouse position
        issueList.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val index = issueList.locationToIndex(e.point)
                if (hoveredIndex != index) {
                    hoveredIndex = index
                    issueList.repaint()
                }
            }
        })

        // Clear hover when mouse exits
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                hoveredIndex = -1
                issueList.repaint()
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

        // Filter display panel (shows active filters)
        filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = JBUI.Borders.empty(4, 8)
            isVisible = false
        }
        mainPanel.add(filterPanel, BorderLayout.NORTH)

        mainPanel.add(JBScrollPane(issueList), BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(statusLabel, BorderLayout.WEST)
        }
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        setContent(mainPanel)
    }

    private fun updateFilterPanel() {
        filterPanel.removeAll()

        val hasFilters = currentStatusFilter != StatusFilter.ALL || currentProjectFilter != null

        if (hasFilters) {
            filterPanel.add(JBLabel("Filters:").apply {
                foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            })

            // Status filter chip
            if (currentStatusFilter != StatusFilter.ALL) {
                filterPanel.add(createFilterChip("Status: ${currentStatusFilter.displayName}") {
                    currentStatusFilter = StatusFilter.ALL
                    applyFiltersAndSort()
                })
            }

            // Project filter chip
            currentProjectFilter?.let { project ->
                filterPanel.add(createFilterChip("Project: $project") {
                    currentProjectFilter = null
                    applyFiltersAndSort()
                })
            }
        }

        filterPanel.isVisible = hasFilters
        filterPanel.revalidate()
        filterPanel.repaint()
    }

    private fun createFilterChip(text: String, onRemove: () -> Unit): JComponent {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = true
            background = JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xE8E8E8, 0x3C3F41))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.namedColor("Borders.color", JBColor(0xD0D0D0, 0x4A4A4A)), 1),
                JBUI.Borders.empty(2, 6)
            )
        }

        chip.add(JBLabel(text).apply {
            font = font.deriveFont(11f)
        })

        val removeButton = JBLabel("×").apply {
            font = font.deriveFont(Font.BOLD, 12f)
            foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onRemove()
                }
                override fun mouseEntered(e: MouseEvent) {
                    foreground = JBColor.namedColor("Label.errorForeground", JBColor.RED)
                }
                override fun mouseExited(e: MouseEvent) {
                    foreground = JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
                }
            })
        }
        chip.add(removeButton)

        return chip
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
            // Refresh action with loading state
            add(object : AnAction("Refresh", "Refresh issues", com.intellij.icons.AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshIssues()
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = !isLoading
                    e.presentation.icon = if (isLoading)
                        com.intellij.icons.AllIcons.Actions.ForceRefresh
                    else
                        com.intellij.icons.AllIcons.Actions.Refresh
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })

            addSeparator()

            // Sort options as dropdown (click same option to toggle order)
            val sortGroup = DefaultActionGroup("Sort By", true).apply {
                templatePresentation.icon = com.intellij.icons.AllIcons.General.ArrowDown
                SortOption.entries.forEach { option ->
                    add(object : AnAction(option.displayName) {
                        override fun actionPerformed(e: AnActionEvent) {
                            if (currentSort == option) {
                                // Toggle sort order when clicking same option
                                currentSortOrder = if (currentSortOrder == SortOrder.DESCENDING)
                                    SortOrder.ASCENDING else SortOrder.DESCENDING
                            } else {
                                currentSort = option
                                currentSortOrder = SortOrder.DESCENDING  // Reset to default
                            }
                            applyFiltersAndSort()
                        }

                        override fun update(e: AnActionEvent) {
                            e.presentation.icon = when {
                                currentSort == option && currentSortOrder == SortOrder.DESCENDING ->
                                    com.intellij.icons.AllIcons.General.ArrowDown
                                currentSort == option && currentSortOrder == SortOrder.ASCENDING ->
                                    com.intellij.icons.AllIcons.General.ArrowUp
                                else -> null
                            }
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

        // Apply sort with order
        filtered = when (currentSort) {
            SortOption.UPDATED_AT -> {
                if (currentSortOrder == SortOrder.DESCENDING)
                    filtered.sortedByDescending { it.updatedAt }
                else
                    filtered.sortedBy { it.updatedAt }
            }
            SortOption.CREATED_AT -> {
                if (currentSortOrder == SortOrder.DESCENDING)
                    filtered.sortedByDescending { it.createdAt }
                else
                    filtered.sortedBy { it.createdAt }
            }
            SortOption.PRIORITY -> {
                if (currentSortOrder == SortOrder.DESCENDING) {
                    // Descending: Urgent first (1 -> 4), No priority last
                    filtered.sortedBy { if (it.priority == 0) Int.MAX_VALUE else it.priority }
                } else {
                    // Ascending: Low first (4 -> 1), No priority last
                    filtered.sortedByDescending { if (it.priority == 0) Int.MIN_VALUE else it.priority }
                }
            }
            SortOption.STATUS -> {
                if (currentSortOrder == SortOrder.DESCENDING)
                    filtered.sortedByDescending { it.state?.name ?: "" }
                else
                    filtered.sortedBy { it.state?.name ?: "zzz" }
            }
        }

        // Update UI
        issueListModel.clear()
        filtered.forEach { issueListModel.addElement(it) }
        statusLabel.text = "${filtered.size} / ${allIssues.size} issues"
        updateFilterPanel()
    }

    fun refreshIssues() {
        isLoading = true
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
                isLoading = false
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
                isLoading = false
                statusLabel.text = "Error: ${throwable.message}"
            }
            null
        }
    }
}

class IssueListCellRenderer(private val getHoveredIndex: () -> Int) : ListCellRenderer<Issue> {
    override fun getListCellRendererComponent(
        list: JList<out Issue>,
        value: Issue,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        val isHovered = index == getHoveredIndex() && !isSelected
        val isCompletedOrCanceled = value.state?.type == "completed" || value.state?.type == "canceled"

        val panel = JPanel(BorderLayout()).apply {
            // Increased padding and bottom border for cell separation
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.namedColor("Borders.color", JBColor(0xE5E5E5, 0x3C3C3C))),
                JBUI.Borders.empty(12, 12)
            )
        }

        // Left edge color bar for selected rows
        if (isSelected) {
            val colorBarWrapper = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyRight(8)  // Space between bar and content
                val colorBar = JPanel().apply {
                    preferredSize = Dimension(4, 0)
                    background = getStateColor(value.state?.type)
                    isOpaque = true
                }
                add(colorBar, BorderLayout.CENTER)
            }
            panel.add(colorBarWrapper, BorderLayout.WEST)
        }

        // Top row: identifier and title
        val identifierLabel = JBLabel("[${value.identifier}]").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = if (isSelected) list.selectionForeground else JBColor.namedColor("Label.infoForeground", JBColor(0x5A5A5A, 0xABABAB))
        }

        // Title with strikethrough for completed/canceled issues
        val titleLabel = JBLabel(
            if (isCompletedOrCanceled) "<html><s>${value.title}</s></html>" else value.title
        ).apply {
            foreground = when {
                isSelected -> list.selectionForeground
                isCompletedOrCanceled -> JBColor(0x9CA3AF, 0x6B7280)  // Muted gray
                else -> list.foreground
            }
        }

        // Bottom row: status, priority, project
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        // Status badge with icon
        value.state?.let { state ->
            val statusIcon = getStatusIcon(state.type)
            val stateLabel = createBadge("$statusIcon ${state.name}", state.color)
            bottomPanel.add(stateLabel)
        }

        // Priority badge with dot icon
        if (value.priority > 0) {
            val priorityText = "● ${Priority.toDisplayString(value.priority)}"
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

        // Background color based on state
        when {
            isSelected -> {
                panel.background = list.selectionBackground
            }
            isHovered -> {
                // More visible hover effect
                val hoverColor = if (ColorUtil.isDark(list.background)) {
                    JBColor(0x3C3F41, 0x3C3F41)  // Lighter for dark theme
                } else {
                    JBColor(0xE8E8E8, 0xE8E8E8)  // Darker for light theme
                }
                panel.background = hoverColor
            }
            else -> {
                panel.background = list.background
            }
        }
        panel.isOpaque = true

        return panel
    }

    private fun getStatusIcon(stateType: String?): String = when (stateType) {
        "started" -> "◉"      // In Progress
        "unstarted" -> "○"    // Not Started
        "backlog" -> "◌"      // Backlog
        "completed" -> "✓"    // Completed
        "canceled" -> "✕"     // Canceled
        else -> "○"
    }

    private fun getStateColor(stateType: String?): Color = when (stateType) {
        "started" -> JBColor(0x3B82F6, 0x60A5FA)     // Blue
        "completed" -> JBColor(0x22C55E, 0x4ADE80)   // Green
        "canceled" -> JBColor(0xEF4444, 0xF87171)    // Red
        else -> JBColor(0x9CA3AF, 0x6B7280)          // Gray
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
            border = JBUI.Borders.empty(16, 0, 12, 0)

            // Identifier
            add(JBLabel(issue.identifier).apply {
                foreground = JBColor.namedColor("Label.infoForeground", JBColor(0x5A5A5A, 0xABABAB))
                font = font.deriveFont(11f)
                alignmentX = Component.LEFT_ALIGNMENT
            })

            add(Box.createVerticalStrut(4))

            // Title
            add(JBLabel("<html><body style='width: 620px'>${issue.title}</body></html>").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                alignmentX = Component.LEFT_ALIGNMENT
            })

            add(Box.createVerticalStrut(16))

            // Separator line
            add(JSeparator(SwingConstants.HORIZONTAL).apply {
                maximumSize = Dimension(Int.MAX_VALUE, 1)
                alignmentX = Component.LEFT_ALIGNMENT
            })

            add(Box.createVerticalStrut(12))

            // Metadata row - unified spacing (6px between badges)
            val metaPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                isOpaque = false
            }

            // Status badge with icon
            issue.state?.let { state ->
                val statusIcon = getStatusIcon(state.type)
                metaPanel.add(createBadge("$statusIcon ${state.name}", state.color))
            }

            // Priority badge with dot icon
            if (issue.priority > 0) {
                val priorityText = "● ${Priority.toDisplayString(issue.priority)}"
                val priorityColor = getPriorityColor(issue.priority)
                metaPanel.add(createBadge(priorityText, priorityColor))
            }

            // Project badge
            issue.project?.let { proj ->
                metaPanel.add(createBadge(proj.name, proj.color ?: "#666666"))
            }

            // Assignee (subtle text style)
            issue.assignee?.let { assignee ->
                metaPanel.add(Box.createHorizontalStrut(8))
                metaPanel.add(JBLabel("@${assignee.name}").apply {
                    foreground = JBColor.namedColor("Label.infoForeground", JBColor(0x6E6E6E, 0x9E9E9E))
                    font = font.deriveFont(12f)
                })
            }

            add(metaPanel)

            // Labels - same spacing as metadata (6px)
            val labels = issue.labels?.nodes ?: emptyList()
            if (labels.isNotEmpty()) {
                add(Box.createVerticalStrut(8))
                val labelsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    isOpaque = false
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

        comments.sortedByDescending { it.createdAt }.forEach { comment ->
            commentsPanel.add(createCommentCard(comment))
            commentsPanel.add(Box.createVerticalStrut(12))
        }

        return JBScrollPane(commentsPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }
    }

    private fun createCommentCard(comment: Comment): JPanel {
        val isDark = ColorUtil.isDark(JBColor.background())

        return JPanel(BorderLayout()).apply {
            // Enhanced border - thicker and more visible
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(
                    JBColor.namedColor("Borders.color", JBColor(0xC0C0C0, 0x505050)),
                    1
                ),
                JBUI.Borders.empty(14)
            )
            isOpaque = true
            // Subtle background differentiation
            background = if (isDark) {
                JBColor(0x2D2D2D, 0x2D2D2D)
            } else {
                JBColor(0xFAFAFA, 0xFAFAFA)
            }

            // Header: Author and date
            val headerPanel = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(8)

                val authorLabel = JBLabel(comment.user?.name ?: "Unknown").apply {
                    font = font.deriveFont(Font.BOLD, 14f)
                    foreground = if (isDark) JBColor(0xDDDDDD, 0xDDDDDD) else JBColor(0x333333, 0x333333)
                }
                val dateLabel = JBLabel(formatDate(comment.createdAt)).apply {
                    foreground = JBColor.namedColor("Label.infoForeground", JBColor(0x888888, 0x888888))
                    font = font.deriveFont(11f)
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
                border = JBUI.Borders.empty()
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
        val baseColor = try {
            ColorUtil.fromHex(hexColor.removePrefix("#"))
        } catch (e: Exception) {
            JBColor.GRAY
        }

        return object : JLabel(text) {
            private var isHovered = false

            init {
                font = font.deriveFont(font.size - 1f)
                foreground = if (ColorUtil.isDark(baseColor)) Color.WHITE else Color.BLACK
                border = JBUI.Borders.empty(3, 8)
                cursor = Cursor.getDefaultCursor()

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        isHovered = true
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        isHovered = false
                        repaint()
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                val displayColor = if (isHovered) ColorUtil.brighter(baseColor, 2) else baseColor

                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = displayColor
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun getStatusIcon(stateType: String?): String = when (stateType) {
        "started" -> "◉"      // In Progress
        "unstarted" -> "○"    // Not Started
        "backlog" -> "◌"      // Backlog
        "completed" -> "✓"    // Completed
        "canceled" -> "✕"     // Canceled
        else -> "○"
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

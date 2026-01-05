# Linear Viewer

View and manage [Linear](https://linear.app) issues directly from your JetBrains IDE.

## Features

- **Issue List** - View your assigned issues in a dedicated tool window
- **Filtering** - Filter by status (Started, Unstarted, Backlog, etc.) and project
- **Sorting** - Sort by updated date, priority, status, or created date
- **Issue Details** - Double-click to view issue details with Markdown rendering
- **Comments** - View issue comments in the detail dialog
- **Copy Branch Name** - Customizable branch name format with placeholders
- **Open in Browser** - Quick access to Linear web interface

## Installation

### From JetBrains Marketplace

Search for "Linear Viewer" in the JetBrains plugin marketplace.

### Manual Installation

1. Download the latest release from the [Releases](https://github.com/kumachan99/linear-viewer/releases) page
2. In your JetBrains IDE, go to **Settings** → **Plugins** → **⚙️** → **Install Plugin from Disk...**
3. Select the downloaded `.zip` file

## Getting Started

1. Get your Linear API key from [Linear Settings](https://linear.app/settings/api)
2. Go to **Settings** → **Tools** → **Linear** and enter your API key
3. Open the Linear tool window from the right sidebar

## Configuration

### Branch Name Format

You can customize the branch name format in **Settings** → **Tools** → **Linear**. Available placeholders:

- `{identifier}` - Issue identifier (e.g., `ENG-123`)
- `{title}` - Issue title (lowercase, spaces replaced with hyphens)
- `{priority}` - Issue priority number

Default format: `{identifier}-{title}`

## Building from Source

```bash
./gradlew buildPlugin
```

The plugin will be built to `build/distributions/`.

## Requirements

- IntelliJ IDEA 2024.2 or later (or other JetBrains IDE based on the same platform version)
- Linear API key

## License

MIT License

---

# Linear Viewer (日本語)

[Linear](https://linear.app)のIssueをJetBrains IDEから直接閲覧・管理できるプラグインです。

## 機能

- **Issue一覧** - 担当Issueを専用ツールウィンドウで表示
- **フィルタリング** - ステータス（Started, Unstarted, Backlogなど）やプロジェクトで絞り込み
- **ソート** - 更新日、優先度、ステータス、作成日で並び替え
- **Issue詳細** - ダブルクリックでMarkdownレンダリング付きの詳細を表示
- **コメント** - Issue詳細ダイアログでコメントを閲覧
- **ブランチ名コピー** - プレースホルダーでカスタマイズ可能なブランチ名形式
- **ブラウザで開く** - Linear Webインターフェースへのクイックアクセス

## 使い方

1. [Linear Settings](https://linear.app/settings/api)からAPIキーを取得
2. **Settings** → **Tools** → **Linear** でAPIキーを設定
3. 右サイドバーからLinearツールウィンドウを開く

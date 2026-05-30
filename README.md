# TaskNotes Companion

Local-only Android companion app for TaskNotes-style Obsidian task notes.

## Build

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## What Works

- Select or enter an Obsidian vault path.
- Scan nested Markdown files.
- Detect TaskNotes by tag, frontmatter property, or task folder.
- Cache scanned tasks in Room while keeping Markdown files authoritative.
- List today, overdue, upcoming, and all tasks.
- Search, create, edit, save, and complete TaskNotes.
- Preserve unknown YAML frontmatter fields while updating app-managed fields.
- Schedule local reminder notifications from reminder metadata.
- Complete tasks from notifications.
- Create the next file for simple recurring tasks.

## Notes

This is built as a personal APK rather than a Play Store-ready app. It requests broad file access because Obsidian vaults are plain folders on device storage.

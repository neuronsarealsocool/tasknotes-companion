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
- Choose priorities in Quick Add and Edit Task, with colors on task cards.
- Read configured priorities from the vault TaskNotes settings and add custom names/colors. Custom definitions are saved on this device for the selected vault; task priority values are written to the standard Markdown frontmatter.
- Preserve unknown YAML frontmatter fields while updating app-managed fields.
- Schedule local reminder notifications from reminder metadata.
- Mark an event as Alarm — phone only to keep it in the phone Alarms tab and hide it from the updated desktop TaskNotes calendar. The note and its recurrence/media stay intact; turning Alarm off restores desktop calendar visibility. Alarms use the device alarm sound when no custom audio or video is selected.
- Complete tasks from notifications.
- Create the next file for simple recurring tasks.

## Notes

This is built as a personal APK rather than a Play Store-ready app. It requests broad file access because Obsidian vaults are plain folders on device storage.

Web addresses in reminder overlay titles and notification notes are clickable. Opening a link dismisses that overlay and stops its media without completing the task. Supports HTTP(S), www addresses, bare domains, and escaped dots.

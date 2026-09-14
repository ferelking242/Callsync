---
name: Shared audio scanning
description: Durable Android storage behavior relevant to finding call recordings in shared folders.
---

For shared call-recording folders, use the URI returned by `OpenDocumentTree` as the source of truth. Persist the tree permission, enumerate descendants with `DocumentsContract`, and store each child document URI for hashing and upload through `ContentResolver`.

**Why:** Android scoped storage can show files in the system picker while hiding the corresponding physical `/storage/...` paths from the app. MediaStore is not a reliable substitute for the user-selected folder.

**How to apply:** Never convert a selected tree URI back into a physical path for scanning. Keep `FileObserver` optional for legacy paths, but use periodic/manual SAF scans for selected folders.
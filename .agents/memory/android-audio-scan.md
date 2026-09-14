---
name: Shared audio scanning
description: Durable Android storage behavior relevant to finding call recordings in shared folders.
---

For shared call-recording folders, treat a raw filesystem walk as incomplete and merge it with MediaStore results. Android can expose audio files to the user and MediaStore while returning an empty or partial `File.walkTopDown()` result to the app. When the physical path is not readable, retain the MediaStore content URI and stream from `ContentResolver` rather than dropping the item.

**Why:** The visible folder may contain `.mp3` or `.m4a` files while a direct scan reports zero on newer Android storage implementations.

**How to apply:** Prefer a readable filesystem path for compatibility, otherwise persist the content URI and use it for hashing/upload/deletion. Resolve known folder candidates case-insensitively because recorder apps vary between `Call` and `call`.
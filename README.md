# PhotoCleaner-Android

Android MVP-1 of the Photo Cleaner AI project.

## What MVP-1 does

- Selects a local folder using Android Storage Access Framework.
- Recursively scans JPEG, PNG, WebP and HEIF/HEIC images.
- Calculates SHA-256 file hashes for exact duplicate detection.
- Measures a first-pass sharpness/blur score using Laplacian variance.
- Measures average exposure.
- Calculates a preliminary relative quality score.
- Groups exact duplicates and ranks the best copy as `BEST`.
- Marks other exact duplicates as `CANDIDATE`.
- Shows a thumbnail, decision, group, rank and technical metrics before deletion.
- Deletes only exact-duplicate candidates after explicit user action.

## Planned next stages

1. Perceptual similarity / near-duplicate grouping.
2. Face detection and eye-open/eye-closed analysis.
3. TOP-3 selection inside photo bursts.
4. Screenshots, WhatsApp/Telegram and meme classification.
5. Document detection and document archive organization.
6. Video analysis.
7. SQLite history, undo/trash and detailed event log.
8. OpenCV and MediaPipe native processing.
9. Optional cloud AI modules, disabled by default.

The architecture deliberately keeps the device-side analysis local. Photos are not uploaded by MVP-1.

## Build

GitHub Actions builds a debug APK automatically on pushes to `main` and can also be started manually from the Actions tab.

The project currently uses Android Gradle Plugin 9.3.0 and Gradle 9.5.0.

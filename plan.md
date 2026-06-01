# Fix QR Pairing Crash And PIN Back Bypass

## Summary
- Fix the QR pairing crash by removing `viewLifecycleOwner` usage from asynchronous Firestore success callbacks in `HomeFragment`.
- Lock down the PIN entry screen so Android Back / remote Back cannot bypass a locked profile.
- Validate with `./gradlew.bat :app:assemblePrereleaseDebug`.

## Key Changes
- In `HomeFragment`, replace `viewLifecycleOwner.lifecycleScope.launch` inside pairing callbacks with fragment-safe lifecycle handling that does not call `getView()` after `onDestroyView()`.
- Add lifecycle guards before showing pairing completion/failure toasts.
- In `PinEntryDialog`, make the locked-profile PIN dialog non-cancelable and intercept Android Back / remote Back.
- Disable the visible PIN cancel action so only a correct PIN can invoke `onPinVerified`.

## Test Plan
- Scan QR and navigate away immediately after scan; the app must not crash or enter safe mode.
- Complete QR pairing successfully; phone waits for TV completion, then shows success.
- Complete manual pairing successfully.
- Press Android Back, gesture Back, and TV remote Back on the locked-profile PIN screen; the PIN screen must remain open.
- Enter a wrong PIN; the dialog stays open and clears the digits.
- Enter the correct PIN; the profile unlocks and selects.
- Run `./gradlew.bat :app:assemblePrereleaseDebug`.

## Assumptions
- Back blocking applies only to the locked-profile PIN authentication screen.
- No Firestore schema, navigation graph, or public API changes are needed.

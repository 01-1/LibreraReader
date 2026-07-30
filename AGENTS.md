# Fork task instructions

Complete this fork in the following order:

1. Add a checkbox in Preferences that makes the configured-dictionary action available during normal text selection without automatically opening the dictionary. Keep the existing **Open selection in dictionary** checkbox as the automatic mode.
2. Add a checkbox under **Preferences → Status bar** that changes the seek bar from whole-book navigation to current chapter/section navigation. Add a second checkbox that changes that scope to the current module/part. The module/part checkbox must be a nested subcheckbox under the chapter/section checkbox, matching the way **Module/part tick marks** is scoped under **Enable progress bar** in the current settings.
3. Add an option to show arrows beside the seek bar that navigate to the previous and next chapter.
4. Display estimated reading time remaining for both the current chapter and the whole book, for example **18 mins left in chapter** and **9 hrs 15 mins left in book**. Provide four independently changeable visibility checkboxes: chapter time in expanded reader controls, book time in expanded reader controls, chapter time in the status bar, and book time in the status bar. Every combination must be possible; do not model this as two content switches plus two shared location switches. Enable both expanded-reader estimates by default; status-bar estimates remain opt-in. Base the estimates on actual remaining word counts, not page counts, provide an option to change the reading speed in words per minute (WPM), and do not make a chapter result wait for a full-book text scan. For EPUB, count the spine/XHTML text directly instead of scanning reflowed layout pages.

For items 2 and 3, change only the interactive seek bar; do not change the display-only progress bar.
When the seek bar is scoped to a chapter/section or module/part, change the page numbers on its left and right to the current page within that scope and the scope's total page count. Restore whole-book page numbers when scoped navigation is disabled.

After implementing and verifying the changes:

- Compile the Android application on a Modal machine. Modal is installed.
- Publish the source publicly to `git.meowc.at/miki/librera-fork`.
- Use the Forgejo credentials under `~/projectmaxxing/credentials/forgejo` without committing credentials or other secrets.
- Verify the published branch hash against the local commit hash.

# Fork task instructions

Complete this fork in the following order:

1. Add a checkbox in Preferences that makes the configured-dictionary action available during normal text selection without automatically opening the dictionary. Keep the existing **Open selection in dictionary** checkbox as the automatic mode.
2. Add a checkbox under **Preferences → Status bar** that changes the progress scrollbar from whole-book navigation to current chapter/section navigation. Add a second checkbox that changes that scope to the current module/part. The module/part checkbox must be a nested subcheckbox under the chapter/section checkbox, matching the way **Module/part tick marks** is scoped under **Enable progress bar** in the current settings.
3. Add an option to show arrows beside the progress scrollbar that navigate to the previous and next chapter.
4. Display estimated reading time remaining for both the current chapter and the whole book, for example **18 mins left in chapter** and **9 hrs 15 mins left in book**. Show these estimates in the expanded reader controls by default, and add a checkbox under **Preferences → Status bar** that also displays them in the status bar. Base the estimates on actual remaining word counts, not page counts, and provide an option to change the reading speed in words per minute (WPM).

For items 2 and 3, Librera has two progress bars. Change only the interactive progress bar; do not change the display-only progress bar.

After implementing and verifying the changes:

- Compile the Android application on a Modal machine. Modal is installed.
- Publish the source publicly to `git.meowc.at/miki/librera-fork`.
- Use the Forgejo credentials under `~/projectmaxxing/credentials/forgejo` without committing credentials or other secrets.
- Verify the published branch hash against the local commit hash.

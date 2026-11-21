---
name: commit-and-changelog
description: Write the commit message, and a CHANGELOG entry when the change is user-visible, for Unrot. Use whenever the user asks to commit.
---

# Commit and changelog

Only commit when the user asks. Never push unless asked.

## Workflow

1. Look at the whole change: `git status --short`, `git diff --stat` and `git diff`. Make sure `local.properties`, build output and Inspector dumps are not staged.
2. **Subject line:** `<type>(<scope>): <summary>`, in the imperative, around 60 characters.
   - **Types:** `feat`, `fix`, `rules`, `refactor`, `test`, `docs`, `build`, `chore`.
   - **Scopes:** `service`, `rules`, `policy`, `overlay`, `inspector`, `ui`, `onboarding`, `work`, `build`, `docs`.
   - Use the `rules` type for changes to `rules.json` only, and put the new rules version in the summary, for example `rules(rules): v4, IG 3xx clips viewer id`.
3. **Body** (only when it adds something):
   - why the change was made
   - risks or trade-offs
   - what was verified, and what still needs testing on a phone
   - a `review-change` verdict, if one was run

   End with the attribution lines the session requires.
4. **Changelog:** `CHANGELOG.md` at the repo root covers what testers notice, grouped under the app `versionName`. Create it the first time a build goes to testers.
   - Add one bullet per user-visible outcome.
   - Note the rules version when it changes.
   - Skip refactors, tests and docs.
5. Run `git diff --cached --check`, then reread the subject, body and changelog against the staged diff before committing.

## Checks

- [ ] The subject has the right type and scope and describes the outcome.
- [ ] No secrets, `local.properties`, build output or dumps were staged.
- [ ] The changelog was touched only for user-visible changes.
- [ ] The message says what's still unverified on a device.

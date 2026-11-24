---
name: sharpen-feature
description: Settle an Unrot feature idea before any code is written, by working through the unclear parts one question at a time. Use when the user is ideating or proposes a feature whose behaviour, scope or acceptance isn't settled yet; not for bug fixes or already-agreed edits.
---

# Sharpen a feature

The user decides when coding starts. This skill ends with a shared, approved understanding and **no code**.

## Workflow

1. **Read what's already settled.** Read `README.md`, every file in `docs/decisions/`, the related FEAT in `docs/feats/` and `docs/later.md`, and the parts of the code the idea touches. That usually means `app/src/main/assets/rules.json`, `rules/Rules.kt`, `policy/Policy.kt` and `service/BlockerService.kt`. Answer facts from the repo yourself. Don't ask the user things the code or a past decision already answers.
2. **Map the feature** against this list. Mark each item as *settled* (by a decision or the code), *the user's call*, *assumption* or *open risk*:
   - **Outcome:** what the user should experience, in one sentence.
   - **Where:** which app (Instagram or YouTube), which screens, and which entry points (tab, feed, Explore, DMs, links, notifications).
   - **Detection:** what signal identifies the screen (view id, description, text, size or position). Can the Inspector capture it? Does it differ between app versions?
   - **Action:** Back, a tap, a cover patch or a message. Could it loop (Back landing on another blocked screen)?
   - **Interaction with existing rules:** the DM one-reel exception, the 30-second friction on weakening changes, the blocked-today counter and the per-feature toggles.
   - **Failure modes:**
     - A *false positive* blocks a screen the user needed, like DMs, posting or a profile. This is worse than a miss.
     - A *miss* lets a reel play.
     - The rules may break after an Instagram or YouTube update.
   - **Play Store and privacy:** does it change what the accessibility service reads or does? Does it keep "nothing leaves the phone" true? Does the prominent disclosure or the Play declaration need updating?
   - **Device cost:** battery, event volume and tree size. Does it behave on the target phones (Xiaomi, Samsung, Oppo)?
   - **Acceptance:** what "done" looks like on a real phone, plus which unit tests prove it.
3. **Ask one question at a time:** the highest-impact open item that's the user's call. Put your recommendation first and say what choosing it implies. Then wait.
4. **Repeat** until nothing material is open. Then summarise the shared understanding, covering scope, what's out of scope, assumptions and open risks, and ask the user to approve it.
5. **Hand off.** Record any load-bearing choice with `record-decision`, and write the approved feature up as a FEAT with `write-feat` (status Planned). Then stop. Don't write code, rules or a task list until the user explicitly says to start.

## Checks

- [ ] The repo and past decisions were read before asking anything.
- [ ] Only the user's calls were asked, one per turn, each with a recommendation first.
- [ ] False positives, update breakage and the Play/privacy impact were considered.
- [ ] The summary names scope, out-of-scope, assumptions and risks, and the user approved it.
- [ ] No code or rules were written.

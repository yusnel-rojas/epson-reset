# Formatting

```bash
./gradlew ktlintFormat
```

[ktlint](https://pinterest.github.io/ktlint/) decides the layout of every `.kt` and `.kts` file in
the repository. Its default style is taken whole, unmodified — the point of having a formatter is
that nobody has to argue about, or even think about, where the line breaks go.

The rules live in [`.editorconfig`](../.editorconfig), which is the file ktlint reads and also the
file IntelliJ reads. There is no second copy to keep in sync: format in the IDE and format on the
command line produce the same bytes.

## The hooks

Two hooks are versioned in [`.githooks/`](../.githooks), rather than written into `.git/hooks`, so
that a change to them arrives with a pull instead of having to be re-installed by everyone:

| Hook | Runs | When |
|---|---|---|
| `pre-commit` | `ktlintCheck` | Only when the commit touches `.kt`/`.kts`; a docs-only commit skips it |
| `pre-push` | `test` | Every push — the same 448 offline tests CI runs |

Install them once:

```bash
./gradlew installGitHooks
```

All that does is set `core.hooksPath` to `.githooks`, so it is idempotent, and
`git config --unset core.hooksPath` undoes it. `./gradlew build` runs it for you, which is how a
fresh clone gets the hooks without anyone having read this page. It deliberately does **not** run
on CI: `sync-printer-data` commits and pushes as the bot, and hooks installed on a runner would
run ktlint against that commit and the whole suite against that push.

Either hook can be skipped for one command with `--no-verify`:

```bash
git commit --no-verify
```

That is not a way around the check, only a way to defer it — the `Format` job in
[`ci.yml`](../.github/workflows/ci.yml) runs `ktlintCheck` on push regardless, so a commit made
with `--no-verify` on a machine that never ran `installGitHooks` still gets caught.

## Two names the formatter is not allowed to change

Both are cases where something outside this repository dictates the name, so ktlint's naming rules
would produce code that compiles and then misbehaves.

- **Composables.** A `@Composable` returning `Unit` is PascalCase — that is Compose's own
  convention, and camel-casing the 74 of them here would be the wrong code. `.editorconfig` sets
  `ktlint_function_naming_ignore_when_annotated_with = Composable`, which is the knob ktlint
  provides for exactly this.
- **The libusb structs.** JNA maps struct fields *by name*, so `MaxPower` and `extra_length` in
  [`usb/LibUsb.kt`](../src/main/kotlin/nl/redlabs/epsonreset/usb/LibUsb.kt) are the names libusb's
  headers use. Renaming them compiles and then reads the wrong offsets at run time. The naming rule
  is suppressed for that file, at the file, with the reason written next to it.

## A wrinkle worth knowing

If the only change since the last run is a *deleted* `.kt` file, `runKtlintCheckOverMainSourceSet`
stays `UP-TO-DATE` and the report still names the file that is gone. Editing anything clears it, as
does `./gradlew --rerun-tasks ktlintCheck`. It cannot block a real commit — the pre-commit hook
only looks at added, copied, modified and renamed paths, so a commit that only deletes Kotlin skips
the check entirely — and CI always starts from a clean checkout.

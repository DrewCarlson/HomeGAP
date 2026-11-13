# Code style

This page gives the rules for the Kotlin code of the orchestrator in `src/`. The aim is code that a person can read from top to bottom and understand. `MeshReconciler.kt` is the reference example.

The dashboard code in `web/` and `presentation/` follows the same rules.

## Behavior

A cleanup must not change what the code does.

- Keep each log message, error message, script, template, wire format and serialized name exactly as it is.
- Change the structure of a function only if a test or the end-to-end harness covers it. If nothing covers it, make only the changes that cannot change the behavior: a new name, a line break, a constant.
- Do not change what a test asserts to make a cleanup pass.
- The tests and the build scripts follow these rules too. A cleanup of a test keeps its name, its calls and its expected values.

## Warnings

A file has no warnings from the compiler and no warnings from the inspections of the IDE.

- Correct the cause of a warning. Do not hide it.
- Two warnings can have `@Suppress`, with the reason in a comment or in the KDoc:
    - `HttpUrlsUsage`, where the code uses plain HTTP on purpose. An example is a request on the mesh, which WireGuard encrypts.
    - `unused`, on a class or a function that only the dependency graph or a serializer uses.

## Lines and layout

- A line has 120 characters or fewer. The text in a raw string (a script, a page, a template) is the exception, because a line break changes the output.
- When a call or a declaration does not fit on one line, put each argument or parameter on its own line, with a comma after the last one.
- When a chain of calls does not fit on one line, put each call on its own line.
- A branch of `if`, `else` or `when` that has more than one line has braces.
- One statement on each line. Do not use semicolons.
- Build a long string from parts with names, or from a list that you join. Do not write one long concatenation.

## Functions

- A function does one thing. A long function is a short outline that calls functions with clear names, in the order of the work.
- A function has four parameters or fewer. If it needs more, put the values that belong together in a small class. Then the class can have the function as a member.
- Use named arguments when a call has more than three arguments, when two arguments have the same type, or when an argument is a literal such as `null` or `true`.
- Do not write a local function that is longer than a few lines. Make it a private function.
- Return early for the case that ends the work. Do not put the main path in a deep `if`.

## Names

- A name says what the value is: `node`, `result`, `address`. Do not use one letter (`n`, `r`, `q`). The exceptions are `it` in a short lambda, `e` for a caught exception, and an index.
- A number or a string with a meaning is a constant with a name: a timeout, a limit, a marker in the output of a script.
- Constants are `const val` in a `private companion object`, or `private const val` at the top level.

## Shared code

- Do not copy a helper. If two files need the same helper, put it in a shared file and use it in both.
- To catch the errors of a suspend block, use `runCatchingCancellable`. It is in `homegap.platform` for the orchestrator, and in `homegap.presentation.core` for the dashboard. Do not write `try`/`catch` with a check for `CancellationException` by hand.
- Use `orEmpty()`, not `?: ""` or `?: emptyList()`.
- Use `check` and `require` for a condition that throws. Do not write `if (...) throw IllegalStateException(...)`.

## Comments and KDoc

- Write few comments. A comment gives the reason for code that does not explain itself. It does not say again what the code does.
- A public class and a function that is not clear from its name have KDoc. Short KDoc is better than long KDoc.
- KDoc and comments follow the [Writing style guide](style-guide.md): the present simple, the active voice, short sentences, no "-ing" forms for actions, no semicolons and no em dashes.
- Keep a KDoc that is correct and that follows the guide. Change a KDoc only to correct it or to make it follow the guide.
- Do not keep code in a comment, and do not keep a comment about code that is gone.

## Checks before a commit

1. Run the inspections of the IDE on each file that you changed. The result must be empty.
2. Run the tests:
    ```bash
    ./gradlew macosArm64Test :presentation:jsNodeTest
    ```
3. For a change in a reconciler, a template or a script, run the end-to-end harness. See [End-to-end tests](e2e.md). After the deploy, the first pass must show no changes, and the stack of each node must keep its hash.

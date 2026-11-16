package homegap.signin

/** The pages of the sign-in. They load nothing from another place, and each text from outside is escaped. */
object SignInPage {
    /** The link that brings the user back to the app. */
    const val APP_LINK = "pangolin://auth"

    /** What the form offers. With [provider], it has a button for that sign-in provider. */
    class Options(val passwords: Boolean = true, val provider: String? = null)

    /** The request of a device: its [code], what waits for it and the [address] of the browser. */
    class Request(val code: String, val pending: SignIn.Pending, val address: String)

    fun form(request: Request, options: Options, problem: String? = null): String = page(
        "Sign in",
        device(request) + problem(problem) + provider(request, options) + passwords(request, options),
    )

    private fun device(request: Request): String {
        val (code, pending, address) = Triple(request.code, request.pending, request.address)
        return """
        <p class="lead">A device asks for access to your HomeGAP.</p>
        <dl>
          <dt>Device</dt><dd>${escape(pending.device.ifBlank { "No name" })}</dd>
          <dt>App</dt><dd>${escape(pending.application.ifBlank { "No name" })}</dd>
          <dt>Code</dt><dd class="code">${escape(code)}</dd>
          <dt>Your address</dt><dd>${escape(address)}</dd>
        </dl>
        <p>Sign in only if the code is the one that your device shows.</p>
        """
    }

    private fun problem(text: String?): String =
        text?.let { "<p class=\"problem\" role=\"alert\">${escape(it)}</p>" }.orEmpty()

    private fun provider(request: Request, options: Options): String {
        val name = options.provider ?: return ""
        // A link and not a form: the browser must be free to go on to the provider.
        val query = "code=${escape(request.code)}&amp;form=${escape(request.pending.form)}"
        return "<a class=\"button\" href=\"/auth/oidc/start?$query\">Sign in with ${escape(name)}</a>"
    }

    private fun passwords(request: Request, options: Options): String {
        if (!options.passwords) return ""
        val divider = if (options.provider != null) "<p class=\"or\">or with your HomeGAP password</p>" else ""
        return """
        $divider
        <form method="post" action="/auth/login/device">
          <input type="hidden" name="code" value="${escape(request.code)}">
          <input type="hidden" name="form" value="${escape(request.pending.form)}">
          <label>Email <input name="email" type="email" autocomplete="username" required></label>
          <label>Password <input name="password" type="password" autocomplete="current-password" required></label>
          <button type="submit">Sign in and approve the device</button>
        </form>
        """
    }

    fun askCode(problem: String? = null): String = page(
        "Sign in",
        """
        <p class="lead">Type the code that your device shows.</p>
        ${problem(problem)}
        <form method="get" action="/auth/login/device">
          <label>Code <input name="code" autocomplete="off" autocapitalize="characters" required autofocus></label>
          <button type="submit">Continue</button>
        </form>
        """,
    )

    fun approved(email: String, nonce: String): String = page(
        "Device approved",
        """
        <p class="lead">You are signed in as ${escape(email)}.</p>
        <p>Go back to the app. <a href="$APP_LINK">Open the app</a></p>
        <script nonce="${escape(nonce)}">location.href = "$APP_LINK"</script>
        """,
    )

    fun message(title: String, text: String): String = page(title, "<p class=\"lead\">${escape(text)}</p>")

    fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private fun page(title: String, body: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <meta name="robots" content="noindex">
        <title>${escape(title)} · HomeGAP</title>
        <style>$STYLE</style>
        </head>
        <body><main><h1>${escape(title)}</h1>$body</main></body>
        </html>
    """.trimIndent()

    private const val STYLE = """
        :root { color-scheme: light dark; --bg: #f5f6f8; --card: #fff; --text: #15181d; --dim: #5b6470;
          --line: #d9dde3; --accent: #2f6fed; --bad: #b3261e; }
        @media (prefers-color-scheme: dark) { :root { --bg: #0f1216; --card: #171b21; --text: #e8ebef;
          --dim: #9aa4b1; --line: #2a3038; --accent: #6b9bff; --bad: #ff8a80; } }
        body { margin: 0; background: var(--bg); color: var(--text); font: 16px/1.5 system-ui, sans-serif; }
        main { max-width: 26rem; margin: 8vh auto; padding: 1.5rem; background: var(--card);
          border: 1px solid var(--line); border-radius: 12px; }
        h1 { font-size: 1.4rem; margin: 0 0 1rem; }
        .lead { margin-top: 0; }
        dl { display: grid; grid-template-columns: auto 1fr; gap: .25rem 1rem; margin: 1rem 0; }
        dt { color: var(--dim); } dd { margin: 0; overflow-wrap: anywhere; }
        .code { font-family: ui-monospace, monospace; letter-spacing: .12em; font-weight: 600; }
        label { display: block; margin: .75rem 0; color: var(--dim); }
        input { display: block; width: 100%; box-sizing: border-box; margin-top: .25rem; padding: .6rem;
          font: inherit; color: var(--text); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; }
        button, .button { display: block; width: 100%; box-sizing: border-box; margin-top: .5rem; padding: .7rem;
          font: inherit; font-weight: 600; color: #fff; text-align: center; text-decoration: none;
          background: var(--accent); border: 0; border-radius: 8px; cursor: pointer; }
        .problem { color: var(--bad); }
        .or { margin: 1.25rem 0 0; color: var(--dim); text-align: center; }
        a { color: var(--accent); }
    """
}

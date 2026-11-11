package homegap.presentation.data

/**
 * Where the dashboard tokens are kept. [token] is the admin's (localStorage in the browser);
 * [viewToken] is a share link's, kept per tab (sessionStorage), so opening a link in one tab
 * leaves an admin signed in on the others.
 */
interface TokenStore {
    var token: String?

    var viewToken: String?

    /** The token requests send: a share link's while one is open in this tab. */
    val active: String? get() = viewToken ?: token
}

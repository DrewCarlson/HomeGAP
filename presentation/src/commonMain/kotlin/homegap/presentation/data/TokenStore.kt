package homegap.presentation.data

/** Where the optional dashboard bearer token is kept (localStorage in the browser). */
interface TokenStore {
    var token: String?
}

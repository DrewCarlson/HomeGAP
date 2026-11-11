package homegap.presentation.data

/** Where the dashboard is served from (`https://dashboard.example.net`), to build links to it. */
interface PageAddress {
    val origin: String
}

/** The fragment that opens a read-only view: `#view=<token>`. */
const val VIEW_FRAGMENT = "view="

/** A share link to the dashboard at [origin]; the token is in the fragment, so no server logs it. */
fun shareUrl(origin: String, token: String): String = "$origin/#$VIEW_FRAGMENT$token"

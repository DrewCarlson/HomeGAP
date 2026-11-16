package homegap

import homegap.config.OidcConfig
import homegap.identity.IdentityStore
import homegap.identity.Keys
import homegap.identity.ProviderUser
import homegap.identity.ProviderUsers
import homegap.platform.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderUsersTest {
    private fun dir() = "/tmp/homegap-provider-test-${Keys.encode(homegap.platform.Crypto.randomBytes(6))}"

    private fun store(root: String) = IdentityStore("$root/authority", "$root/identity") { 1_000 }

    private val groups = mapOf("vpn-admins" to listOf("Admin"), "family" to listOf("Member", "Media"))
    private val mapped = OidcConfig("https://auth.example.net", "homegap", name = "Authelia", groupRoles = groups)
    private val creating = mapped.copy(createUsers = true)

    @Test
    fun theGroupsOfThePersonGiveTheRoles() {
        assertEquals(listOf("Admin", "Member", "Media"), mapped.rolesFor(listOf("vpn-admins", "family", "other")))
        assertEquals(emptyList(), mapped.rolesFor(listOf("other")), "a person in no known group gets no role")
        assertEquals(listOf("Guest"), mapped.copy(defaultRoles = listOf("Guest")).rolesFor(listOf("other")))
        assertTrue(mapped.managesRoles && mapped.reportsUsers)
        val plain = OidcConfig("https://auth.example.net", "homegap")
        assertTrue(!plain.managesRoles && !plain.reportsUsers && "groups" !in plain.requestedScopes)
    }

    @Test
    fun aNewPersonGetsAUserOnlyWithCreateUsers() {
        val store = store(dir())
        val ada = ProviderUser("Ada@Example.net", "Ada L", listOf("family"), at = 10)
        assertNull(store.applyProvider(ada, mapped), "no user without create_users")
        assertNotNull(store.applyProvider(ada, creating))
        val user = store.users().single()
        assertEquals("ada@example.net", user.email)
        assertEquals("Ada L", user.name)
        assertEquals(listOf("Member", "Media"), user.roles)
        assertEquals("Authelia", user.provider)
        assertNull(user.passwordHash)
        assertNull(store.applyProvider(ProviderUser("eve@example.net", "Eve", listOf("other"), at = 11), creating))
        assertEquals(1, store.users().size, "a person with no role gets no user")
    }

    @Test
    fun aUserWithTheSameAddressTakesTheNameAndTheRolesOfTheProvider() {
        val store = store(dir())
        store.create("root@example.net", "Root", listOf("Admin"))
        val (ada, _) = store.create("ada@example.net", "", listOf("Member"))
        assertNotNull(store.applyProvider(ProviderUser("ada@example.net", "Ada L", listOf("vpn-admins"), at = 10), mapped))
        val linked = store.users().single { it.id == ada.id }
        assertEquals("Ada L", linked.name)
        assertEquals(listOf("Admin"), linked.roles)
        assertEquals("Authelia", linked.provider)
        assertNotNull(linked.passwordHash, "the password stays")
        // HomeGAP cannot change the roles of a user of the provider.
        val refusal = assertFailsWith<IllegalStateException> { store.setRoles(ada.id, listOf("Member")) }
        assertTrue("Authelia" in refusal.message.orEmpty())
        // An older answer changes nothing, and a newer one does.
        assertNull(store.applyProvider(ProviderUser("ada@example.net", "Old", listOf("family"), at = 9), mapped))
        assertNotNull(store.applyProvider(ProviderUser("ada@example.net", "Ada L", listOf("family"), at = 20), mapped))
        assertEquals(listOf("Member", "Media"), store.users().single { it.id == ada.id }.roles)
    }

    @Test
    fun withoutGroupRolesHomegapKeepsTheRoles() {
        val store = store(dir())
        val (ada, _) = store.create("ada@example.net", "", listOf("Media"))
        val plain = OidcConfig("https://auth.example.net", "homegap", createUsers = true, defaultRoles = listOf("Member"))
        store.applyProvider(ProviderUser("ada@example.net", "Ada L", listOf("family"), at = 10), plain)
        val user = store.users().single()
        assertEquals(listOf("Media"), user.roles)
        assertNull(user.provider)
        assertEquals(10, user.providerAt)
        assertEquals(listOf("Admin"), store.setRoles(ada.id, listOf("Admin")).roles)
    }

    @Test
    fun theOnlyAdminKeepsTheAdminRole() {
        val store = store(dir())
        store.create("root@example.net", "Root", listOf("Admin"))
        store.applyProvider(ProviderUser("root@example.net", "Root", listOf("family"), at = 10), mapped)
        assertEquals(listOf("Member", "Media", "Admin"), store.users().single().roles)
    }

    @Test
    fun theSignInWritesTheNewestAnswerForEachAddress() {
        val root = dir()
        Files.mkdirs(root)
        ProviderUsers.record(root, ProviderUser("Ada@example.net", "Ada", listOf("a"), at = 1))
        ProviderUsers.record(root, ProviderUser("ada@example.net", "Ada L", listOf("b"), at = 2))
        ProviderUsers.record(root, ProviderUser("bob@example.net", "Bob", at = 3))
        val people = ProviderUsers.read(root)
        assertEquals(setOf("ada@example.net", "bob@example.net"), people.keys)
        assertEquals(listOf("b"), people.getValue("ada@example.net").groups)
    }
}

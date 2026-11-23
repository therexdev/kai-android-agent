package com.kai.agent.connections

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountIdentityTest {
    @Test fun onlyDocumentedDisplayNameCanSupplyAccountIdentity() {
        val raw = JSONObject("""{"name":"alias@example.com","email":"decoy@example.com","state":{"val":{"displayName":"owner@example.com","access_token":"secret","email":"secret@example.com"}}}""")
        val identity = AccountIdentity.fromAccount(raw, "gmail")!!
        assertEquals("owner@example.com", identity.emailAddress)
        assertEquals(AccountIdentity.SOURCE_ACCOUNT_DISPLAY_NAME, identity.source)
        assertFalse(identity.toString().contains("secret"))
        raw.getJSONObject("state").getJSONObject("val").remove("displayName")
        assertNull(AccountIdentity.fromAccount(raw, "gmail"))
    }

    @Test fun generatedAliasCannotMasqueradeAsProviderDisplayName() {
        val raw=account("discord_random-code").put("alias","discord_random-code")
        assertNull(AccountIdentity.fromAccount(raw,"discord"))
        raw.remove("alias");raw.put("word_id","discord_random-code")
        assertNull(AccountIdentity.fromAccount(raw,"discord"))
    }

    @Test fun providerNamesRemainDisplayNamesRatherThanInferredEmailAddresses() {
        val identity = AccountIdentity.fromAccount(account("My Google account"), "gmail")!!
        assertEquals("My Google account", identity.providerDisplayName)
        assertEquals("", identity.emailAddress)
        assertEquals("", AccountIdentity.fromAccount(account("owner@example.com"), "github")!!.emailAddress)
    }

    @Test fun unsafeMaskedAndMalformedLabelsDoNotBecomeVerifiedEmails() {
        for (text in listOf("", " ", "a***@gmail.com", "[redacted]", "a\nb@example.com", "a\u202Eb@example.com", "a".repeat(257))) {
            assertNull(text, AccountIdentity.fromAccount(account(text), "gmail"))
        }
        for (text in listOf("Jane <jane@example.com>", "a@example.com,b@example.com", "a..b@example.com", "a@-example.com", "a@localhost")) {
            assertEquals(text, "", AccountIdentity.fromAccount(account(text), "gmail")!!.emailAddress)
        }
        assertEquals("a+b@work.example", AccountIdentity.fromAccount(account(" a+b@work.example "), "gmail")!!.emailAddress)
    }

    @Test fun profileRequiresTrueSuccessAndExactEmailField() {
        val envelope = profile(JSONObject().put("emailAddress", "owner@example.com"))
        val identity = AccountIdentity.fromGmailProfile(envelope)!!
        assertEquals("owner@example.com", identity.emailAddress)
        assertEquals(AccountIdentity.SOURCE_GMAIL_PROFILE, identity.source)
        for (success in listOf<Any>(false, "true", 1, JSONObject.NULL)) {
            assertNull(AccountIdentity.fromGmailProfile(JSONObject(envelope.toString()).put("successful", success)))
        }
        assertNull(AccountIdentity.fromGmailProfile(profile(JSONObject().put("email", "owner@example.com"))))
        assertNull(AccountIdentity.fromGmailProfile(JSONObject().put("successful", true).put("emailAddress", "owner@example.com")))
        assertNull(AccountIdentity.fromGmailProfile(JSONObject(envelope.toString()).put("error", "Unauthorized")))
        assertNotNull(AccountIdentity.fromGmailProfile(JSONObject(envelope.toString()).put("error", JSONObject.NULL)))
    }

    @Test fun boundedProfileWrappersAndSerializedDataAreSupported() {
        val mailbox = JSONObject().put("emailAddress", "owner@example.com").put("messagesTotal", 10)
        assertEquals("owner@example.com", AccountIdentity.fromGmailProfile(profile(mailbox.toString()))!!.emailAddress)
        assertEquals("owner@example.com", AccountIdentity.fromGmailProfile(profile(JSONObject().put("response", JSONObject().put("data", mailbox))))!!.emailAddress)
        assertEquals("owner@example.com", AccountIdentity.fromGmailProfile(profile(JSONObject().put("response_data", mailbox)))!!.emailAddress)
        assertNull(AccountIdentity.fromGmailProfile(profile(JSONObject().put("credentials", mailbox))))
    }

    @Test fun conflictingOverdeepLargeAndFailedNestedProfilesAreRejected() {
        val mailbox = JSONObject().put("emailAddress", "owner@example.com")
        assertNull(AccountIdentity.fromGmailProfile(profile(JSONObject(mailbox.toString()).put("data", JSONObject().put("emailAddress", "other@example.com")))))
        assertNull(AccountIdentity.fromGmailProfile(profile(JSONObject(mailbox.toString()).put("response", JSONObject().put("successful", false)))))
        assertNull(AccountIdentity.fromGmailProfile(profile(JSONObject(mailbox.toString()).put("response", JSONObject().put("error", "Denied")))))
        assertNull(AccountIdentity.fromGmailProfile(profile(JSONObject(mailbox.toString()).put("large", "x".repeat(33_000)))))
        var nested = mailbox
        repeat(6) { nested = JSONObject().put("data", nested) }
        assertNull(AccountIdentity.fromGmailProfile(profile(nested)))
        assertNull(AccountIdentity.fromGmailProfile(profile("not JSON")))
    }

    @Test fun identityProjectionPreservesAliasAndAccountOwnership() {
        val original = ConnectionAccount("acct-1", "gmail", "owner-id", "gmail_random-alias", "ACTIVE", false, "")
        assertEquals("Connected account", original.displayLabel)
        val named = AccountIdentity("Work Gmail", "", "provider").applyTo(original)
        assertEquals("Work Gmail", named.displayLabel)
        val verified = AccountIdentity("Work Gmail", "owner@example.com", "profile").applyTo(original)
        assertEquals("owner@example.com", verified.displayLabel)
        assertEquals(original.name, verified.name)
        assertEquals(original.id, verified.id)
        assertEquals(original.userId, verified.userId)
        assertTrue(verified.active)
    }

    private fun account(label: String) = JSONObject().put("state", JSONObject().put("val", JSONObject().put("displayName", label)))
    private fun profile(data: Any) = JSONObject().put("successful", true).put("data", data)
}

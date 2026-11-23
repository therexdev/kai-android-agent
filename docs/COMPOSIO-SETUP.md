# Connect KAI directly to Composio

KAI connects from the phone to `https://backend.composio.dev/api/v3.1` using your own Composio project API key in the `x-api-key` header. No KAI website login, desktop agent, or shared publisher key is involved. The app stores the entered key in encrypted phone storage and excludes it from exports, model prompts, and logs.

## Choose the right key

In the Composio dashboard, select **Platform → your project → Settings → API Keys**. Use a project API key or a scoped project API key for that project.

| Credential | Composio header | Supported by this KAI setup |
| --- | --- | --- |
| Project API key | `x-api-key` | Yes |
| Scoped project API key | `x-api-key` | Yes, with the required permissions |
| Organization API key | `x-org-api-key` | No |
| User/CLI/consumer key beginning `uak_` | `x-user-api-key` | No |

A Koinos AI inference key, OpenAI key, KAI login token, OAuth connection link, or individual app token is not a Composio project API key. KAI identifies known `uak_` user keys locally; it cannot identify every other wrong credential by its appearance.

## Configure the phone

1. Open **Connections** and enter the project API key and a stable Composio **user ID**.
2. For existing connections, use the **same project and exact user ID** used to create them. A value such as `kai:acc_…` is valid when it is the existing Composio user ID; KAI does not add or remove that prefix.
3. Save the settings. KAI verifies access and loads connected accounts and the app catalog.
4. Select an app to connect. Complete consent on Composio's hosted connection page, then return to KAI and refresh. A browser return alone does not establish success; the account must become active and enabled.
5. If setup fails, tap **Check access**. It performs four small read-only checks: Toolkits, Connected accounts, Tools, and Auth configs. It creates no connections and executes no tools.

The key input removes surrounding whitespace, balanced quotes, and a copied `Bearer ` or `x-api-key:` wrapper. It preserves the key itself and rejects embedded spaces or line breaks. Paste only the complete key; do not paste a whole command or configuration file.

Reusing an old user ID with a different project key does not move connections between projects. Accounts from a previous KAI-managed project require access to that same Composio project or must be connected in your own project.

## Scoped-key permissions

| KAI feature | Composio permission |
| --- | --- |
| Browse apps | Toolkits: Read |
| List and verify connected accounts | Connected accounts: Read |
| Connect or disconnect an account | Connected accounts: Write |
| Reuse an app's auth configuration | Auth configs: Read |
| Create a missing managed/API-key auth configuration | Auth configs: Write |
| Discover action definitions | Tools: Read |
| Run an approved action | Tool execution: Write; currently listed as **Tool execution (Legacy)** in the permissions reference |

KAI does not need organization-wide access, raw proxy execution, or session-management permissions for this direct integration. Composio says scoped-key permissions are selected at creation; to change them, create a suitably scoped replacement key and save it in KAI.

Custom OAuth apps may need configuration in the Composio project first. If that project has its own callback verifier configured, its verification policy still applies; KAI does not bypass or change it.

## Interpret access errors

- **401:** The request was denied. Possible causes include an incorrect or revoked credential, the wrong key type, or missing scoped permissions. Composio documents cases where valid scoped keys receive a generic “Invalid API key” error. A 401 alone does not identify which cause applies.
- **403:** Permission was denied for the operation. Check the named permission area and the selected project's/account's access; this does not prove the key is invalid.
- **404:** The requested resource may be missing or inaccessible in that key's project.
- **Some checks pass:** The key works for those APIs. Inspect the denied permission areas instead of assuming the entire key is invalid.
- **All read checks pass:** Read access works. This does not test write permissions, external-app consent, or whether an action would succeed.

The user ID filters accounts; it is not an API credential. Changing it is not a remedy for an authentication error. KAI keeps the fixed documented API endpoint and does not retry against a different host, API version, or project. An interrupted action with an unknown outcome is not automatically replayed.

## Account labels and profile reads

Connections display a verified email address when Composio's provider display label supplies one. Generated connection aliases are not email addresses. For an active Gmail account with no email label, KAI can use the read-only Gmail profile action bound to that exact account. Optional profile lookup is bounded and a failure leaves the existing account visible. Successful profile results are cached for 15 minutes, failures for one minute, within the current credential session. Version 0.4.1 extends current-user profile lookups to Discord, YouTube, Google Drive/Docs/Sheets/Calendar and other common services. These optional lookups run independently of chat. Generated aliases are no longer shown as identities. See `RESPONSIVE-VOICE-AND-ACCOUNT-NAMES.md` for the exact service coverage and fallbacks.

The new provider profile reads use **Proxy execute (Legacy): Write**, a separate Composio scoped-key permission from Tool execution (Legacy). The app only invokes a compiled allowlist of current-user profile endpoints. A denied profile lookup leaves the connection available; it does not prove that OAuth needs reconnecting.

Tool discovery checks up to five pages, with 20 actions per page. If a search returns no actions, it can fall back to the toolkit catalog and rank actions locally. Definitions can be inspected separately when their full schemas do not fit in the first response. Catalog results are cached for up to five minutes and cleared when credentials change. A partial catalog remains marked incomplete.

An empty search or a denied Tools API request does not prove Gmail needs reconnecting. Tools: Read permits discovery; Tool execution: Write is also needed to execute read-only Gmail actions. Each actual inbox read verifies the selected account again. The phone reports which account produced a result and preserves known failures and unknown outcomes separately. Its agent loop allows up to ten decisions with a cumulative context budget; it will stop explicitly when that budget is exhausted.

## Official references

- [API base URL and overview](https://docs.composio.dev/reference)
- [Key kinds and authentication headers](https://docs.composio.dev/reference/authenticating-to-composio)
- [Scoped-key permissions and routes](https://docs.composio.dev/reference/authenticating-to-composio/project-api-key-permissions)
- [Permission failures that can surface as generic 401 errors](https://docs.composio.dev/kb/guide/platform-project-api-key-permissions)
- [Hosted connection links](https://docs.composio.dev/reference/api-reference/connected-accounts/postConnectedAccountsLink)
- [Exact user identity and connection verification](https://docs.composio.dev/docs/authentication/white-labeling-authentication)

Documentation checked on November 16, 2026. Mock tests cover request construction and failure handling; they do not verify a particular user's live project key or app connection.

# Using connected paid credits (0.4.29)

1. Sign in to the same Koinos AI account used on the website.
2. Open **Account** in kai and tap **Refresh balance & models** after linking a credit account or buying credits.
3. Under **Credit source**, choose **Automatic · connected accounts** or a specific connected account. Existing Core wallet credit links are included.
4. Enable **Use purchased credits**. Off keeps requests free-only. This setting applies to chats, saved tasks, and automatic memory processing.
5. Choose a model and send a message. Free credits are used first; purchased credits cover the rest.

Automatic mode follows the website primary credit account, then considers the other linked accounts in their existing order. A request uses one account that can cover its budget. Balances from different accounts are not pooled. A retry stays with its original funding account.

The displayed balance is for the primary or first connected account in Automatic mode. Pick another account to inspect its balance. The app displays the wallet/account label supplied by the server, falling back to its address.

**Manage connected credit accounts** opens your website account. Link your Core wallet to that same login from Core or connect a payment wallet on the website, then return to the app and refresh. The phone does not import the wallet's signing key. Linking credit access does not transfer wallet ownership or merge private workspaces.

Changing the selected credit account returns the phone to free-only mode. Selecting the same account again preserves the current choice. An account without a connected wallet cannot enable purchased-credit usage. Unlinked or revoked access remains enforced by the server; the phone never moves a failed paid request to a different balance on its own.

The app reads the shared server ledger. It does not purchase credits, move money, or spend credits merely by refreshing the Account screen. Enabling purchased credits permits subsequent model work to consume them; review the selected account and model prices first.

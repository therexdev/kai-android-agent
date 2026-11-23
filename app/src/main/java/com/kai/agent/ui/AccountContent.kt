package com.kai.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kai.agent.account.creditAmount

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountContent(state: KaiUiState, actions: KaiUiActions, onClose: () -> Unit) {
    val a=state.account
    var signOut by remember { mutableStateOf(false) }
    var modelsExpanded by remember(a.signedIn) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Your account",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
            IconButton(onClick=onClose) { KaiIcon(KaiGlyph.CLOSE,label="Close account") }
        }
        Text(a.message,style=MaterialTheme.typography.bodyMedium)
        if(a.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(!a.signedIn) {
            Text("Use the same AI credit account as the website. Sign in or create an account with email or Google in your browser, then approve this phone.")
            if(a.code.isNotBlank()) {
                SoftCard { Text("Approve this code",style=MaterialTheme.typography.titleMedium);Text(a.code,style=MaterialTheme.typography.headlineLarge);Text("Return to KAI after approval. This code expires automatically.") }
                OutlinedButton(onClick=actions::accountRefresh,modifier=Modifier.fillMaxWidth()) { Text("Check sign-in") }
                TextButton(onClick=actions::accountCancelSignIn) { Text("Cancel sign-in") }
            } else {
                Button(onClick=actions::accountSignIn,enabled=!a.busy,modifier=Modifier.fillMaxWidth()) { Text("Sign in / Create account") }
            }
        } else {
            Text(a.email.ifBlank { "Signed in" },style=MaterialTheme.typography.titleLarge)
            a.overview?.let { o ->
                SoftCard {
                    Text("Available credits",style=MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Free allowance: "+if(o.freeState=="unavailable") "Temporarily unavailable" else creditAmount(o.freeCredits),style=MaterialTheme.typography.bodyLarge)
                    Text("Purchased: ${creditAmount(o.paidCredits)}",style=MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp));Text("Credits pay for model tokens. Token costs vary by model. Purchased credits belong to the selected wallet and are shared with the website.",style=MaterialTheme.typography.bodySmall)
                }
                Text("Credit balance",style=MaterialTheme.typography.titleMedium)
                FilterChip(selected=a.wallet.isBlank(),onClick={actions.accountSelectWallet("")},label={Text("Free account allowance")})
                o.wallets.forEach { wallet ->
                    FilterChip(selected=a.wallet==wallet,onClick={actions.accountSelectWallet(wallet)},label={Text(wallet)})
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Use free credits only");Text("Turn off to allow purchased credits for chats, tasks and automatic memory processing.",style=MaterialTheme.typography.bodySmall) }
                    Switch(checked=a.freeOnly,onCheckedChange=actions::accountFreeOnly,enabled=a.wallet.isNotBlank())
                }
                Text("Choose a model",style=MaterialTheme.typography.titleMedium)
                val selectedModel=o.models.firstOrNull { it.id==a.model }
                val canChooseModel=!a.busy && o.models.any { it.available }
                ExposedDropdownMenuBox(expanded=modelsExpanded && canChooseModel,onExpandedChange={modelsExpanded=it && canChooseModel}) {
                    OutlinedTextField(value=selectedModel?.label ?: a.model.ifBlank { "Choose a model" },onValueChange={},
                        readOnly=true,singleLine=true,enabled=canChooseModel,label={Text("AI model")},
                        trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=modelsExpanded && canChooseModel)},
                        modifier=Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable,enabled=canChooseModel).testTag("account-model-selector"))
                    ExposedDropdownMenu(expanded=modelsExpanded && canChooseModel,onDismissRequest={modelsExpanded=false}) {
                        o.models.forEach { m ->
                            DropdownMenuItem(enabled=m.available,text={
                                Column {
                                    Text(m.label+(if(a.model==m.id) " · Selected" else ""))
                                    Text(if(!m.available) "Currently unavailable" else if(m.freeEligible) "Eligible for free allowance" else "Purchased credits required",style=MaterialTheme.typography.bodySmall)
                                }
                            },onClick={modelsExpanded=false;actions.accountSelectModel(m.id)})
                        }
                    }
                }
                selectedModel?.let { m ->
                    Text(if(!m.available) "Currently unavailable. Choose another model or refresh." else if(m.freeEligible) "Eligible for free allowance" else "Purchased credits required",style=MaterialTheme.typography.bodySmall)
                    Text("Per million tokens: ${m.inputPrice} credits input · ${m.outputPrice} output",style=MaterialTheme.typography.bodySmall)
                }
                if(o.models.none { it.available }) Text("No models are available right now. Refresh to check again.")
            }
            OutlinedButton(onClick=actions::accountRefresh,enabled=!a.busy,modifier=Modifier.fillMaxWidth()) { Text("Refresh balance & models") }
            Button(onClick={actions.accountWebsite("wallets")},enabled=a.overview?.vaultAvailable==true,modifier=Modifier.fillMaxWidth()) { Text("Connect KOIN Vault") }
            if(a.overview?.vaultAvailable!=true) Text("KOIN Vault support is waiting for the account-service update. Existing website credits are still available.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={actions.accountWebsite("billing")},modifier=Modifier.fillMaxWidth()) { Text("Buy credits") }
            Text("Wallet connection and purchases open your website account in the browser. Use the same email shown above. Review each payment in KOIN Vault. Return here to refresh your balance.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={actions.accountWebsite("security")}) { Text("Manage account") }
            TextButton(onClick={signOut=true}) { Text("Sign out") }
        }
        Text("Chats, memories and connected apps stay on this phone. Signing in changes model access and billing; it does not upload or sync your local workspace. The selected model receives the context needed for your requests.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
    if(signOut) AlertDialog(onDismissRequest={signOut=false},title={Text("Sign out on this phone?")},text={Text("Model requests stop. Local chats and memories remain on this device.")},confirmButton={TextButton(onClick={signOut=false;actions.accountSignOut()}){Text("Sign out")}},dismissButton={TextButton(onClick={signOut=false}){Text("Cancel")}})
}

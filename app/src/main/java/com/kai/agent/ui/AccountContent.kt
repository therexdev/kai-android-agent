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
    var creditSourcesExpanded by remember(a.signedIn) { mutableStateOf(false) }
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
                    if(o.selectedWallet.isNotBlank()) Text("Balance for: ${o.walletLabel(o.selectedWallet)}",style=MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp));Text("Purchased credits are shared with your connected website and Core credit accounts. Each request uses one account; separate balances are not combined.",style=MaterialTheme.typography.bodySmall)
                }
                Text("Credit account",style=MaterialTheme.typography.titleMedium)
                ExposedDropdownMenuBox(expanded=creditSourcesExpanded && !a.busy,onExpandedChange={creditSourcesExpanded=it && !a.busy}) {
                    OutlinedTextField(value=if(a.wallet.isBlank()) "Automatic · connected accounts" else o.walletLabel(a.wallet),onValueChange={},
                        readOnly=true,singleLine=true,enabled=!a.busy,label={Text("Credit source")},
                        trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=creditSourcesExpanded && !a.busy)},
                        modifier=Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable,enabled=!a.busy).testTag("account-credit-source"))
                    ExposedDropdownMenu(expanded=creditSourcesExpanded && !a.busy,onDismissRequest={creditSourcesExpanded=false}) {
                        DropdownMenuItem(text={Text("Automatic · connected accounts")},onClick={creditSourcesExpanded=false;actions.accountSelectWallet("")})
                        o.wallets.forEach { wallet ->
                            DropdownMenuItem(text={Column {
                                Text(o.walletLabel(wallet)+(if(wallet==o.primaryCreditWallet) " · Primary" else ""))
                                if(o.walletLabel(wallet)!=wallet) Text(wallet,style=MaterialTheme.typography.bodySmall)
                            }},onClick={creditSourcesExpanded=false;actions.accountSelectWallet(wallet)})
                        }
                    }
                }
                if(a.wallet.isBlank() && o.wallets.isNotEmpty()) Text("Automatic uses your primary connected account first, then another linked account that can cover the request. The balance above shows your primary or first connected account.",style=MaterialTheme.typography.bodySmall)
                if(o.wallets.isEmpty()) Text("Connect a credit account on the website or link your Core wallet to this login, then return and refresh. Your free allowance is still available.",style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Use purchased credits");Text("Uses free credits first, then purchased credits for chats, tasks and automatic memory processing. Off keeps usage free-only.",style=MaterialTheme.typography.bodySmall) }
                    Switch(checked=!a.freeOnly,onCheckedChange={actions.accountFreeOnly(!it)},
                        enabled=!a.busy && o.wallets.isNotEmpty() && (a.wallet.isBlank() || a.wallet in o.wallets),modifier=Modifier.testTag("account-paid-credits"))
                }
                OutlinedButton(onClick={actions.accountWebsite("wallets")},modifier=Modifier.fillMaxWidth()) { Text("Manage connected credit accounts") }
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
                    if(m.available && !m.freeEligible && a.freeOnly) Text("Enable Use purchased credits to use this model.",style=MaterialTheme.typography.bodySmall)
                    Text("Per million tokens: ${m.inputPrice} credits input · ${m.outputPrice} output",style=MaterialTheme.typography.bodySmall)
                }
                Text("Response allowance",style=MaterialTheme.typography.titleMedium)
                Text("Includes reasoning on models that count it as output. Higher limits can cost more; the serving model's limit still applies.",style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(2048,4096,8192).forEach { limit -> FilterChip(selected=state.preferredOutput==limit,onClick={actions.setAgentOutput(limit)},label={Text(limit.toString())}) }
                }
                Text("Estimated credit allowance",style=MaterialTheme.typography.titleMedium)
                Text("For new chat and saved-task runs and explicit continuations, including retries and summaries. Background memory processing is separate. Estimates use catalog prices; they are not settled charges or a guaranteed billing cap. Free/purchased credit settings still apply.",style=MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf(0,1,5,10,25).forEach { limit -> FilterChip(selected=state.runCreditLimit==limit,
                        onClick={actions.setRunCreditLimit(limit)},label={Text(if(limit==0) "No extra limit" else "$limit credits")}) }
                }
                Text("Model work also has request, token and time limits. Reaching a limit saves progress for review; waiting for your approval does not use model time.",style=MaterialTheme.typography.bodySmall)
                Text("Optional fallback models",style=MaterialTheme.typography.titleMedium)
                Text("Selected models are tried in the order shown, within the same credit account and free/paid policy. Fallback never repeats an external action. Models with a different tool protocol are skipped.",style=MaterialTheme.typography.bodySmall)
                state.fallbackModels.forEachIndexed { index,id ->
                    TextButton(onClick={actions.setFallbackModels(state.fallbackModels-id)}) { Text("${index+1}. ${o.models.firstOrNull { it.id==id }?.label ?: id} · Remove") }
                }
                var fallbackOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick={fallbackOpen=true},enabled=state.fallbackModels.size<8) { Text("Add fallback") }
                    DropdownMenu(expanded=fallbackOpen,onDismissRequest={fallbackOpen=false}) {
                        o.models.filter { it.id!=a.model && it.id !in state.fallbackModels }.forEach { m ->
                            DropdownMenuItem(text={Text(m.label)},onClick={fallbackOpen=false;actions.setFallbackModels(state.fallbackModels+m.id)},enabled=m.available && (!a.freeOnly || m.freeEligible))
                        }
                    }
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

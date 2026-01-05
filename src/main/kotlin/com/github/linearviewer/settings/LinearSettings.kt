package com.github.linearviewer.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*

@Service
@State(
    name = "LinearSettings",
    storages = [Storage("linear-viewer.xml")]
)
class LinearSettings : PersistentStateComponent<LinearSettings.State> {
    private var myState = State()

    class State {
        var showOnlyMyIssues: Boolean = true
        var branchNameFormat: String = "{id}-{title}"
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    var showOnlyMyIssues: Boolean
        get() = myState.showOnlyMyIssues
        set(value) {
            myState.showOnlyMyIssues = value
        }

    var branchNameFormat: String
        get() = myState.branchNameFormat
        set(value) {
            myState.branchNameFormat = value
        }

    var apiKey: String?
        get() {
            val credentialAttributes = createCredentialAttributes()
            return PasswordSafe.instance.getPassword(credentialAttributes)
        }
        set(value) {
            val credentialAttributes = createCredentialAttributes()
            PasswordSafe.instance.set(credentialAttributes, value?.let { Credentials("", it) })
        }

    private fun createCredentialAttributes(): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("LinearViewer", "apiKey")
        )
    }

    companion object {
        fun getInstance(): LinearSettings = service()
    }
}

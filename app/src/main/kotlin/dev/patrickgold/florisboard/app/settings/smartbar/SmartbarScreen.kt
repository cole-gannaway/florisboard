/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.smartbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.enumDisplayEntriesOf
import dev.patrickgold.florisboard.app.settings.theme.DialogProperty
import dev.patrickgold.florisboard.ime.smartbar.CandidatesDisplayMode
import dev.patrickgold.florisboard.ime.smartbar.ExtendedActionsPlacement
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.model.collectAsState
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.material.ui.JetPrefAlertDialog
import dev.patrickgold.jetpref.material.ui.JetPrefTextField
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

@Composable
fun SmartbarScreen() = FlorisScreen {
    title = stringRes(R.string.settings__smartbar__title)
    previewFieldVisible = true

    content {
        SwitchPreference(
            prefs.smartbar.enabled,
            title = stringRes(R.string.pref__smartbar__enabled__label),
            summary = stringRes(R.string.pref__smartbar__enabled__summary),
        )
        ListPreference(
            listPref = prefs.smartbar.layout,
            title = stringRes(R.string.pref__smartbar__layout__label),
            entries = enumDisplayEntriesOf(SmartbarLayout::class),
            enabledIf = { prefs.smartbar.enabled isEqualTo true },
        )

        PreferenceGroup(title = stringRes(R.string.pref__smartbar__group_layout_specific__label)) {
            ListPreference(
                prefs.suggestion.displayMode,
                title = stringRes(R.string.pref__suggestion__display_mode__label),
                entries = enumDisplayEntriesOf(CandidatesDisplayMode::class),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = { prefs.smartbar.layout isNotEqualTo SmartbarLayout.ACTIONS_ONLY },
            )
            SwitchPreference(
                prefs.smartbar.flipToggles,
                title = stringRes(R.string.pref__smartbar__flip_toggles__label),
                summary = stringRes(R.string.pref__smartbar__flip_toggles__summary),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = {
                    prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED ||
                        prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED
                },
            )
            // TODO: schedule to remove this preference in the future, but keep it for now so users
            //  know why the setting is not available anymore. Also force enable it for UI display.
            SideEffect {
                // prefs.smartbar.sharedActionsAutoExpandCollapse.set(true)
            }
            SwitchPreference(
                prefs.smartbar.sharedActionsAutoExpandCollapse,
                title = stringRes(R.string.pref__smartbar__shared_actions_auto_expand_collapse__label),
                summary = "[Since v0.4.1] Always enabled due to UX issues",
                enabledIf = { false },
                visibleIf = { prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_SHARED },
            )
            ListPreference(
                listPref = prefs.smartbar.extendedActionsPlacement,
                title = stringRes(R.string.pref__smartbar__extended_actions_placement__label),
                entries = enumDisplayEntriesOf(ExtendedActionsPlacement::class),
                enabledIf = { prefs.smartbar.enabled isEqualTo true },
                visibleIf = { prefs.smartbar.layout isEqualTo SmartbarLayout.SUGGESTIONS_ACTIONS_EXTENDED },
            )
        }

        PreferenceGroup(title = stringRes(R.string.pref__voice_input__group__label)) {
            val scope = rememberCoroutineScope()
            val endpointUrl by prefs.voiceInput.endpointUrl.collectAsState()
            var showEndpointUrlDialog by rememberSaveable { mutableStateOf(false) }
            Preference(
                title = stringRes(R.string.pref__voice_input__endpoint_url__label),
                summary = endpointUrl.ifBlank {
                    stringRes(R.string.pref__voice_input__endpoint_url__summary_empty)
                },
                onClick = { showEndpointUrlDialog = true },
            )
            if (showEndpointUrlDialog) {
                var urlInput by rememberSaveable { mutableStateOf(endpointUrl) }
                JetPrefAlertDialog(
                    title = stringRes(R.string.voice_input__dialog__title),
                    confirmLabel = stringRes(R.string.action__apply),
                    onConfirm = {
                        scope.launch { prefs.voiceInput.endpointUrl.set(urlInput.trim()) }
                        showEndpointUrlDialog = false
                    },
                    dismissLabel = stringRes(R.string.action__cancel),
                    onDismiss = { showEndpointUrlDialog = false },
                ) {
                    DialogProperty(text = stringRes(R.string.voice_input__dialog__url_label)) {
                        JetPrefTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                        )
                    }
                }
            }
        }
    }
}

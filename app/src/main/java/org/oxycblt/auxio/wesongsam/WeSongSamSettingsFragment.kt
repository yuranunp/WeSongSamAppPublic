/*
 * Copyright (c) 2026 WeSongSam Project
 * WeSongSamSettingsFragment.kt is part of WeSongSam.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.wesongsam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentWesongsamSettingsBinding
import org.oxycblt.auxio.databinding.ItemWssFolderBinding
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * The WeSongSam settings screen: configure the four move-to-folder destinations (SAF), button
 * labels, mood keywords, listening situations, the action log location, and import the
 * WeSongSam_phone.json written by the Windows program.
 */
@AndroidEntryPoint
class WeSongSamSettingsFragment : Fragment() {
    @Inject lateinit var wesongsamSettings: WeSongSamSettingsImpl

    @Inject lateinit var sync: WeSongSamSync

    private var folderLauncher: ActivityResultLauncher<Uri?>? = null
    private var logLauncher: ActivityResultLauncher<Uri?>? = null
    private var jsonLauncher: ActivityResultLauncher<Intent>? = null
    private var applyLogLauncher: ActivityResultLauncher<Array<String>>? = null
    private var exportProfileLauncher: ActivityResultLauncher<String>? = null
    private var pendingFolderIndex = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentWesongsamSettingsBinding.inflate(inflater)
        folderLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null && pendingFolderIndex >= 0) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    val folder = wesongsamSettings.folders[pendingFolderIndex]
                    wesongsamSettings.setFolder(pendingFolderIndex, uri.toString(), folder.label)
                    pendingFolderIndex = -1
                    rebuildFolderRows(binding)
                }
            }
        logLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    wesongsamSettings.setLogTreeUri(uri.toString())
                    updateLogSummary(binding)
                }
            }
        jsonLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val uri = it.data?.data
                if (uri != null) {
                    importJson(binding, uri)
                }
            }
        applyLogLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    applyLog(binding, uri)
                }
            }
        exportProfileLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
                if (uri != null) {
                    exportProfile(binding, uri)
                }
            }

        rebuildFolderRows(binding)
        rebuildButtonRows(binding)
        val multiPress = wesongsamSettings.multiPressMs
        binding.wssMultiPress.value = multiPress.toFloat()
        binding.wssMultiPressLabel.text = getString(R.string.wss_set_multi_press, multiPress)
        binding.wssMultiPress.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                wesongsamSettings.setMultiPressMs(value.toInt())
                binding.wssMultiPressLabel.text = getString(R.string.wss_set_multi_press, value.toInt())
            }
        }

        binding.wssMoodsInput.setText(
            wesongsamSettings.moods.joinToString(", ") { it.name }
        )
        binding.wssSituationsInput.setText(
            wesongsamSettings.situations.joinToString(", ") { it.name }
        )
        binding.wssSaveLists.setOnClickListener {
            wesongsamSettings.setMoods(splitList(binding.wssMoodsInput.text?.toString()))
            wesongsamSettings.setSituations(splitList(binding.wssSituationsInput.text?.toString()))
            rebuildButtonRows(binding)
            requireContext().showToast(R.string.wss_saved_toast)
        }
        binding.wssPickLogDir.setOnClickListener { logLauncher?.launch(null as Uri?) }
        binding.wssApplyLog.setOnClickListener {
            applyLogLauncher?.launch(arrayOf("*/*"))
        }
        binding.wssExportProfile.setOnClickListener {
            exportProfileLauncher?.launch("WeSongSam_profile.txt")
        }
        binding.wssImportJson.setOnClickListener {
            val intent =
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
            jsonLauncher?.launch(intent)
        }
        updateLogSummary(binding)
        return binding.root
    }

    private fun rebuildFolderRows(binding: FragmentWesongsamSettingsBinding) {
        val rows = binding.wssFolderRows
        rows.removeAllViews()
        val inflater = layoutInflater
        wesongsamSettings.folders.forEach { folder ->
            val row = ItemWssFolderBinding.inflate(inflater, rows, false)
            row.wssFolderNumber.text = folder.index.plus(1).toString()
            row.wssFolderLabel.setText(folder.label)
            row.wssFolderLabel.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    wesongsamSettings.setFolder(
                        folder.index,
                        folder.treeUri,
                        row.wssFolderLabel.text?.toString()?.ifEmpty { folder.label } ?: folder.label,
                    )
                }
            }
            row.wssFolderPick.setOnClickListener {
                pendingFolderIndex = folder.index
                folderLauncher?.launch(null as Uri?)
            }
            row.wssFolderSummary.text =
                if (folder.treeUri != null) {
                    folder.treeUri
                } else {
                    getString(R.string.wss_set_folder_none)
                }
            rows.addView(row.root)
        }
    }

    private fun rebuildButtonRows(binding: FragmentWesongsamSettingsBinding) {
        val rows = binding.wssButtonRows
        rows.removeAllViews()
        val context = requireContext()
        val actions = WssButtonAction.entries
        val labels = actions.map(::actionLabel)
        val keyNames = intArrayOf(R.string.wss_key_prev, R.string.wss_key_next, R.string.wss_key_play)
        for (key in 0 until WeSongSamSettings.KEY_COUNT) {
            for (presses in 1..WeSongSamSettings.MAX_PRESSES) {
                val row = LinearLayout(context)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                val name = TextView(context)
                name.text = getString(R.string.wss_key_presses, getString(keyNames[key]), presses)
                row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val spinner = Spinner(context)
                spinner.adapter =
                    ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
                spinner.setSelection(actions.indexOf(wesongsamSettings.buttonAction(key, presses)))
                spinner.onItemSelectedListener =
                    object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            position: Int,
                            id: Long,
                        ) {
                            wesongsamSettings.setButtonAction(key, presses, actions[position])
                        }

                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                row.addView(
                    spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f))
                rows.addView(row)
            }
        }
    }

    private fun actionLabel(action: WssButtonAction): String {
        val folders = wesongsamSettings.folders
        val moods = wesongsamSettings.moodButtonNames
        return when (action) {
            WssButtonAction.NORMAL -> getString(R.string.wss_act_normal)
            WssButtonAction.NOTHING -> getString(R.string.wss_act_nothing)
            WssButtonAction.MOVE_1 -> getString(R.string.wss_act_move, 1, folders[0].label)
            WssButtonAction.MOVE_2 -> getString(R.string.wss_act_move, 2, folders[1].label)
            WssButtonAction.MOVE_3 -> getString(R.string.wss_act_move, 3, folders[2].label)
            WssButtonAction.MOVE_4 -> getString(R.string.wss_act_move, 4, folders[3].label)
            WssButtonAction.MOOD_1 -> getString(R.string.wss_act_mood, moods[0])
            WssButtonAction.MOOD_2 -> getString(R.string.wss_act_mood, moods[1])
            WssButtonAction.MOOD_3 -> getString(R.string.wss_act_mood, moods[2])
        }
    }

    private fun updateLogSummary(binding: FragmentWesongsamSettingsBinding) {
        binding.wssLogLocation.text =
            wesongsamSettings.logTreeUri ?: getString(R.string.wss_set_log_default)
    }

    private fun importJson(binding: FragmentWesongsamSettingsBinding, uri: Uri) {
        try {
            val text =
                requireContext()
                    .contentResolver
                    .openInputStream(uri)
                    ?.bufferedReader()
                    ?.use { it.readText() }
            val ok =
                when {
                    text == null -> false
                    text.trimStart('﻿', ' ', '\n', '\r').startsWith("{") ->
                        wesongsamSettings.importPhoneJson(text)
                    else -> sync.importProfile(text)
                }
            if (ok) {
                requireContext().showToast(R.string.wss_import_ok_toast)
                rebuildFolderRows(binding)
                rebuildButtonRows(binding)
                binding.wssMoodsInput.setText(wesongsamSettings.moods.joinToString(", ") { it.name })
                binding.wssSituationsInput.setText(
                    wesongsamSettings.situations.joinToString(", ") { it.name }
                )
                val multiPress = wesongsamSettings.multiPressMs
                binding.wssMultiPress.value = multiPress.toFloat()
                binding.wssMultiPressLabel.text =
                    getString(R.string.wss_set_multi_press, multiPress)
                updateLogSummary(binding)
            } else {
                requireContext().showToast(R.string.wss_import_failed_toast)
            }
        } catch (e: Exception) {
            L.e("WeSongSam profile import failed", e)
            requireContext().showToast(R.string.wss_import_failed_toast)
        }
    }

    private fun applyLog(binding: FragmentWesongsamSettingsBinding, uri: Uri) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text =
                    appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    }
                if (text == null) {
                    withContext(Dispatchers.Main) {
                        appContext.showToast(R.string.wss_import_failed_toast)
                    }
                    return@launch
                }
                val counts = sync.applyLog(text)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                            appContext,
                            // The Fragment may be detached by now, so resolve the string on the
                            // application context instead of this Fragment's resources.
                            appContext.getString(
                                R.string.wss_apply_log_result,
                                counts.moved,
                                counts.already,
                                counts.notFound,
                                counts.ambiguous,
                                counts.noFolder,
                            ),
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            } catch (e: Exception) {
                L.e("WeSongSam move log apply failed", e)
                withContext(Dispatchers.Main) {
                    appContext.showToast(R.string.wss_import_failed_toast)
                }
            }
        }
    }

    private fun exportProfile(binding: FragmentWesongsamSettingsBinding, uri: Uri) {
        try {
            requireContext()
                .contentResolver
                .openOutputStream(uri)
                ?.use { out -> out.write(sync.exportProfile().toByteArray(Charsets.UTF_8)) }
            requireContext().showToast(R.string.wss_export_ok_toast)
        } catch (e: Exception) {
            L.e("WeSongSam profile export failed", e)
            requireContext().showToast(R.string.wss_import_failed_toast)
        }
    }

    private fun splitList(raw: String?) =
        raw?.split(',', ';', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf()
}

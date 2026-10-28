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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentWesongsamSettingsBinding
import org.oxycblt.auxio.databinding.ItemWssFolderBinding
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * The WeSongSam settings screen: configure the six move-to-folder destinations (SAF), button
 * labels, mood keywords, listening situations, the action log location, and import the
 * WeSongSam_phone.json written by the Windows program.
 */
@AndroidEntryPoint
class WeSongSamSettingsFragment : Fragment() {
    @Inject lateinit var wesongsamSettings: WeSongSamSettingsImpl

    private var folderLauncher: ActivityResultLauncher<Uri?>? = null
    private var logLauncher: ActivityResultLauncher<Uri?>? = null
    private var jsonLauncher: ActivityResultLauncher<Intent>? = null
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

        rebuildFolderRows(binding)

        binding.wssMoodsInput.setText(
            wesongsamSettings.moods.joinToString(", ") { it.name }
        )
        binding.wssSituationsInput.setText(
            wesongsamSettings.situations.joinToString(", ") { it.name }
        )
        binding.wssSaveLists.setOnClickListener {
            wesongsamSettings.setMoods(splitList(binding.wssMoodsInput.text?.toString()))
            wesongsamSettings.setSituations(splitList(binding.wssSituationsInput.text?.toString()))
            requireContext().showToast(R.string.wss_saved_toast)
        }
        binding.wssPickLogDir.setOnClickListener { logLauncher?.launch(null as Uri?) }
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
            if (text != null && wesongsamSettings.importPhoneJson(text)) {
                requireContext().showToast(R.string.wss_import_ok_toast)
                rebuildFolderRows(binding)
                binding.wssMoodsInput.setText(wesongsamSettings.moods.joinToString(", ") { it.name })
                binding.wssSituationsInput.setText(
                    wesongsamSettings.situations.joinToString(", ") { it.name }
                )
                updateLogSummary(binding)
            } else {
                requireContext().showToast(R.string.wss_import_failed_toast)
            }
        } catch (e: Exception) {
            L.e("WeSongSam_phone.json import failed", e)
            requireContext().showToast(R.string.wss_import_failed_toast)
        }
    }

    private fun splitList(raw: String?) =
        raw?.split(',', ';', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf()
}

/*
 * Copyright (c) 2026 NOTLI contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.vocal

import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Build
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.DialogVocalStudioBinding
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.ui.ViewBindingBottomSheetDialogFragment

@AndroidEntryPoint
class VocalStudioDialogFragment : ViewBindingBottomSheetDialogFragment<DialogVocalStudioBinding>() {
    @Inject lateinit var musicRepository: MusicRepository
    private val args: VocalStudioDialogFragmentArgs by navArgs()
    private lateinit var modelStore: VocalModelStore
    private var modelCheckJob: Job? = null
    private var processingJob: Job? = null
    private var downloadJob: Job? = null
    private var instrumentalFile: File? = null
    private var player: MediaPlayer? = null

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            val source = instrumentalFile
            if (uri != null && source?.isFile == true) {
                runCatching {
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("Could not open the selected destination.")
                }.onSuccess {
                    Toast.makeText(requireContext(), R.string.vocal_saved, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(
                        requireContext(),
                        it.message ?: getString(R.string.vocal_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

    override fun onCreateBinding(inflater: LayoutInflater) = DialogVocalStudioBinding.inflate(inflater)

    override fun onBindingCreated(binding: DialogVocalStudioBinding, savedInstanceState: android.os.Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        modelStore = VocalModelStore(requireContext())
        val song = musicRepository.library?.findSong(args.songUid)
        if (song == null) {
            binding.vocalSongTitle.text = getString(R.string.vocal_song_unavailable)
            binding.vocalSongArtist.text = ""
            binding.vocalProcessButton.isEnabled = false
        } else {
            binding.vocalSongTitle.text = song.name.resolve(requireContext())
            binding.vocalSongArtist.text = song.artists.resolveNames(requireContext())
        }

        refreshModelState(binding)
        binding.vocalDownloadButton.setOnClickListener { confirmModelDownload() }
        binding.vocalRemoveModelButton.setOnClickListener { confirmModelRemoval() }
        binding.vocalProcessButton.setOnClickListener { processSelectedSong() }
        binding.vocalCancelButton.setOnClickListener { cancelProcessing() }
        binding.vocalPreviewButton.setOnClickListener { togglePreview() }
        binding.vocalSaveButton.setOnClickListener {
            saveLauncher.launch("NOTLI_Instrumental.wav")
        }
        binding.vocalDiscardButton.setOnClickListener { discardResult() }
    }

    private fun refreshModelState(binding: DialogVocalStudioBinding) {
        if (!supportsEngine()) {
            binding.vocalModelStatus.setText(R.string.vocal_unsupported_device)
            binding.vocalDownloadButton.isEnabled = false
            binding.vocalProcessButton.isEnabled = false
            return
        }

        binding.vocalModelStatus.setText(
            if (modelStore.isReady) R.string.vocal_model_ready else R.string.vocal_model_checking,
        )
        binding.vocalDownloadButton.isEnabled = modelStore.isReady.not() && modelCheckJob?.isActive != true &&
            downloadJob?.isActive != true
        binding.vocalProcessButton.isEnabled = modelStore.isReady && processingJob?.isActive != true
        binding.vocalRemoveModelButton.visibility =
            if (modelStore.isReady) android.view.View.VISIBLE else android.view.View.GONE
        binding.vocalRemoveModelButton.isEnabled = processingJob?.isActive != true &&
            downloadJob?.isActive != true

        if (!modelStore.isReady && modelCheckJob?.isActive != true) {
            binding.vocalDownloadButton.isEnabled = false
            modelCheckJob = viewLifecycleOwner.lifecycleScope.launch {
                val ready = runCatching { modelStore.verifyInstalled() }.getOrDefault(false)
                if (view === binding.root) {
                    binding.vocalModelStatus.setText(
                        if (ready) R.string.vocal_model_ready else R.string.vocal_model_missing,
                    )
                    binding.vocalDownloadButton.isEnabled = !ready && downloadJob?.isActive != true
                    binding.vocalProcessButton.isEnabled = ready && processingJob?.isActive != true
                    binding.vocalRemoveModelButton.visibility =
                        if (ready) android.view.View.VISIBLE else android.view.View.GONE
                    binding.vocalRemoveModelButton.isEnabled = processingJob?.isActive != true &&
                        downloadJob?.isActive != true
                }
            }
        }
    }

    private fun supportsEngine() =
        Build.VERSION.SDK_INT >= 29 && Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun confirmModelDownload() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vocal_model_license_title)
            .setMessage(R.string.vocal_model_license_notice)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.vocal_download_model) { _, _ -> downloadModel() }
            .show()
    }

    private fun confirmModelRemoval() {
        if (processingJob?.isActive == true || downloadJob?.isActive == true) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vocal_remove_model_title)
            .setMessage(R.string.vocal_remove_model_message)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.vocal_remove_model) { _, _ -> removeModel() }
            .show()
    }

    private fun removeModel() {
        val binding = binding ?: return
        if (processingJob?.isActive == true || downloadJob?.isActive == true) return
        viewLifecycleOwner.lifecycleScope.launch {
            binding.vocalRemoveModelButton.isEnabled = false
            try {
                modelStore.deleteInstalledModel()
                binding.vocalModelStatus.setText(R.string.vocal_model_missing)
                binding.vocalProcessButton.isEnabled = false
                binding.vocalRemoveModelButton.visibility = android.view.View.GONE
                binding.vocalDownloadButton.isEnabled = true
                binding.vocalProgressLabel.setText(R.string.vocal_model_removed)
            } catch (error: Exception) {
                binding.vocalProgressLabel.text =
                    error.message ?: getString(R.string.vocal_remove_model_failed)
                refreshModelState(binding)
            }
        }
    }

    private fun downloadModel() {
        val binding = binding ?: return
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.vocalProgress.progress = 0
            binding.vocalProgress.visibility = android.view.View.VISIBLE
            binding.vocalProgressLabel.setText(R.string.vocal_downloading)
            binding.vocalDownloadButton.isEnabled = false
            try {
                modelStore.download { progress ->
                    binding.root.post {
                        binding.vocalProgress.progress = progress
                        binding.vocalProgressLabel.text =
                            getString(R.string.vocal_download_percent, progress)
                    }
                }
                if (view === binding.root) {
                    binding.vocalProgressLabel.setText(R.string.vocal_download_complete)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && view === binding.root) {
                    binding.vocalProgressLabel.text =
                        error.message ?: getString(R.string.vocal_download_failed)
                }
            } finally {
                if (view === binding.root) refreshModelState(binding)
            }
        }
    }

    private fun processSelectedSong() {
        val binding = binding ?: return
        val song = musicRepository.library?.findSong(args.songUid) ?: return
        val model = modelStore.modelFile
        if (!modelStore.isReady) {
            Toast.makeText(requireContext(), R.string.vocal_model_missing, Toast.LENGTH_SHORT).show()
            refreshModelState(binding)
            return
        }

        val appContext = requireContext().applicationContext
        processingJob = viewLifecycleOwner.lifecycleScope.launch {
            val work = File(appContext.cacheDir, "vocal-studio/" + System.currentTimeMillis()).apply {
                mkdirs()
            }
            val raw = File(work, "input.f32")
            val prepared = File(work, "prepared.f32")
            val stems = File(work, "stems").apply { mkdirs() }
            val output = File(work, "instrumental.wav")
            var completed = false

            binding.vocalProgress.progress = 0
            binding.vocalProgress.visibility = android.view.View.VISIBLE
            binding.vocalProgressLabel.setText(R.string.vocal_decoding)
            binding.vocalProcessButton.isEnabled = false
            binding.vocalCancelButton.isEnabled = true

            try {
                withContext(Dispatchers.IO) {
                    if (!modelStore.verifyInstalled()) {
                        modelStore.markInvalid()
                        error("The local AI model failed its integrity check. Download it again.")
                    }

                    val pcm = AudioPcmDecoder.decode(appContext, song.uri, raw) {
                        processingJob?.isActive == true
                    }
                    NativeEngine.loadModel(model.absolutePath)
                    NativeEngine.prepareInput(
                        raw.absolutePath,
                        pcm.sampleRate,
                        pcm.channels,
                        prepared.absolutePath,
                        listener = NativeEngine.ProgressListener { _, _ ->
                            processingJob?.isActive == true
                        },
                    )
                    NativeEngine.separate(
                        prepared.absolutePath,
                        stems.absolutePath,
                        NativeEngine.sources.toSet(),
                        threads = 1,
                        gpu = true,
                        listener = NativeEngine.ProgressListener { fraction, stage ->
                            binding.root.post {
                                binding.vocalProgress.progress =
                                    (fraction * 100).toInt().coerceIn(0, 100)
                                binding.vocalProgressLabel.text = stage
                            }
                            processingJob?.isActive == true
                        },
                    )
                    InstrumentalMixer.mix(stems, output) {
                        processingJob?.isActive == true
                    }
                }

                completed = true
                instrumentalFile = output
                if (view === binding.root) {
                    binding.vocalProgress.progress = 100
                    binding.vocalProgressLabel.setText(R.string.vocal_ready)
                    binding.vocalPreviewButton.isEnabled = true
                    binding.vocalSaveButton.isEnabled = true
                    binding.vocalDiscardButton.isEnabled = true
                }
            } catch (error: Exception) {
                if (error !is CancellationException && view === binding.root) {
                    binding.vocalProgressLabel.text =
                        error.message ?: getString(R.string.vocal_process_failed)
                }
            } finally {
                raw.delete()
                prepared.delete()
                if (!completed) work.deleteRecursively()
                if (view === binding.root) {
                    binding.vocalCancelButton.isEnabled = false
                    refreshModelState(binding)
                }
            }
        }
    }

    private fun cancelProcessing() {
        if (processingJob?.isActive == true) {
            runCatching { NativeEngine.cancel() }
            processingJob?.cancel()
        }
        binding?.vocalProgressLabel?.setText(R.string.vocal_cancelled)
    }

    private fun togglePreview() {
        val file = instrumentalFile ?: return
        if (player?.isPlaying == true) {
            player?.pause()
            binding?.vocalPreviewButton?.setText(R.string.vocal_preview)
            return
        }

        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    binding?.vocalPreviewButton?.setText(R.string.vocal_preview)
                }
                setOnErrorListener { _, _, _ ->
                    binding?.vocalPreviewButton?.setText(R.string.vocal_preview)
                    Toast.makeText(
                        requireContext(),
                        R.string.vocal_preview_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                    true
                }
                setOnPreparedListener {
                    it.start()
                    binding?.vocalPreviewButton?.setText(R.string.vocal_pause_preview)
                }
                prepareAsync()
            }
        }.onFailure {
            Toast.makeText(
                requireContext(),
                it.message ?: getString(R.string.vocal_preview_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun discardResult() {
        player?.release()
        player = null
        instrumentalFile?.parentFile?.deleteRecursively()
        instrumentalFile = null
        binding?.vocalPreviewButton?.isEnabled = false
        binding?.vocalSaveButton?.isEnabled = false
        binding?.vocalDiscardButton?.isEnabled = false
        binding?.vocalProgressLabel?.setText(R.string.vocal_result_discarded)
    }

    override fun onDestroyBinding(binding: DialogVocalStudioBinding) {
        cancelProcessing()
        downloadJob?.cancel()
        modelCheckJob?.cancel()
        player?.release()
        player = null
        instrumentalFile?.parentFile?.deleteRecursively()
        instrumentalFile = null
        super.onDestroyBinding(binding)
    }
}

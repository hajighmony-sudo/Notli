/*
 * Copyright (c) 2026 NOTLI contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package org.oxycblt.auxio.vocal

import android.app.AlertDialog
import android.content.Intent
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
    private var processingJob: Job? = null
    private var downloadJob: Job? = null
    private var instrumentalFile: File? = null
    private var player: MediaPlayer? = null

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val source = instrumentalFile
        if (uri != null && source?.isFile == true) {
            runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not open the selected destination.")
            }.onSuccess {
                Toast.makeText(requireContext(), R.string.vocal_saved, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(requireContext(), it.message ?: getString(R.string.vocal_save_failed), Toast.LENGTH_LONG).show()
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
        binding.vocalProcessButton.setOnClickListener { processSelectedSong() }
        binding.vocalCancelButton.setOnClickListener { cancelProcessing() }
        binding.vocalPreviewButton.setOnClickListener { togglePreview() }
        binding.vocalSaveButton.setOnClickListener { saveLauncher.launch("NOTLI_Instrumental.wav") }
        binding.vocalDiscardButton.setOnClickListener { discardResult() }
    }

    private fun refreshModelState(binding: DialogVocalStudioBinding) {
        binding.vocalModelStatus.text = when {
            !supportsEngine() -> getString(R.string.vocal_unsupported_device)
            modelStore.isReady -> getString(R.string.vocal_model_ready)
            else -> getString(R.string.vocal_model_missing)
        }
        binding.vocalDownloadButton.isEnabled = supportsEngine() && !modelStore.isReady && downloadJob?.isActive != true
        binding.vocalProcessButton.isEnabled = supportsEngine() && modelStore.isReady && processingJob?.isActive != true
    }

    private fun supportsEngine() = Build.VERSION.SDK_INT >= 29 &&
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun confirmModelDownload() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vocal_model_license_title)
            .setMessage(R.string.vocal_model_license_notice)
            .setNegativeButton(R.string.lbl_cancel, null)
            .setPositiveButton(R.string.vocal_download_model) { _, _ -> downloadModel() }
            .show()
    }

    private fun downloadModel() {
        val binding = binding ?: return
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.vocalProgress.progress = 0
            binding.vocalProgress.visibility = android.view.View.VISIBLE
            binding.vocalProgressLabel.text = getString(R.string.vocal_downloading)
            binding.vocalDownloadButton.isEnabled = false
            try {
                modelStore.download { progress ->
                    binding.vocalProgress.post {
                        binding.vocalProgress.progress = progress
                        binding.vocalProgressLabel.text = getString(R.string.vocal_download_percent, progress)
                    }
                }
                binding.vocalProgressLabel.text = getString(R.string.vocal_download_complete)
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    binding.vocalProgressLabel.text = error.message ?: getString(R.string.vocal_download_failed)
                }
            } finally {
                refreshModelState(binding)
            }
        }
    }

    private fun processSelectedSong() {
        val binding = binding ?: return
        val song = musicRepository.library?.findSong(args.songUid) ?: return
        val model = modelStore.modelFile
        if (!modelStore.isReady) {
            Toast.makeText(requireContext(), R.string.vocal_model_missing, Toast.LENGTH_SHORT).show()
            return
        }
        processingJob = viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val work = File(appContext.cacheDir, "vocal-studio/" + System.currentTimeMillis()).apply { mkdirs() }
            val raw = File(work, "input.f32")
            val prepared = File(work, "prepared.f32")
            val stems = File(work, "stems").apply { mkdirs() }
            val output = File(work, "instrumental.wav")
            binding.vocalProgress.progress = 0
            binding.vocalProgress.visibility = android.view.View.VISIBLE
            binding.vocalProgressLabel.text = getString(R.string.vocal_decoding)
            binding.vocalProcessButton.isEnabled = false
            binding.vocalCancelButton.isEnabled = true
            try {
                withContext(Dispatchers.IO) {
                    if (!modelStore.verifyInstalled()) {
                        model.delete()
                        error("The local AI model failed its integrity check. Download it again.")
                    }
                    val pcm = AudioPcmDecoder.decode(appContext, song.uri, raw)
                    NativeEngine.loadModel(model.absolutePath)
                    NativeEngine.prepareInput(raw.absolutePath, pcm.sampleRate, pcm.channels, prepared.absolutePath)
                    NativeEngine.separate(
                        prepared.absolutePath,
                        stems.absolutePath,
                        NativeEngine.sources.toSet(),
                        threads = 1,
                        gpu = true,
                        listener = NativeEngine.ProgressListener { fraction, stage ->
                            binding.vocalProgress.post {
                                binding.vocalProgress.progress = (fraction * 100).toInt().coerceIn(0, 100)
                                binding.vocalProgressLabel.text = stage
                            }
                            !Thread.currentThread().isInterrupted
                        },
                    )
                    InstrumentalMixer.mix(stems, output)
                }
                instrumentalFile = output
                binding.vocalProgress.progress = 100
                binding.vocalProgressLabel.text = getString(R.string.vocal_ready)
                binding.vocalPreviewButton.isEnabled = true
                binding.vocalSaveButton.isEnabled = true
                binding.vocalDiscardButton.isEnabled = true
            } catch (error: Exception) {
                if (error !is CancellationException) {
                    binding.vocalProgressLabel.text = error.message ?: getString(R.string.vocal_process_failed)
                }
            } finally {
                raw.delete()
                prepared.delete()
                binding.vocalCancelButton.isEnabled = false
                binding.vocalProcessButton.isEnabled = modelStore.isReady
            }
        }
    }

    private fun cancelProcessing() {
        if (processingJob?.isActive == true) runCatching { NativeEngine.cancel() }
        processingJob?.cancel()
        binding?.vocalProgressLabel?.text = getString(R.string.vocal_cancelled)
    }

    private fun togglePreview() {
        val file = instrumentalFile ?: return
        if (player?.isPlaying == true) {
            player?.pause()
            binding?.vocalPreviewButton?.text = getString(R.string.vocal_preview)
            return
        }
        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { binding?.vocalPreviewButton?.text = getString(R.string.vocal_preview) }
                setOnPreparedListener {
                    it.start()
                    binding?.vocalPreviewButton?.text = getString(R.string.vocal_pause_preview)
                }
                prepareAsync()
            }
            binding?.vocalPreviewButton?.text = getString(R.string.vocal_pause_preview)
        }.onFailure {
            Toast.makeText(requireContext(), it.message ?: getString(R.string.vocal_preview_failed), Toast.LENGTH_LONG).show()
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
        binding?.vocalProgressLabel?.text = getString(R.string.vocal_result_discarded)
    }

    override fun onDestroyBinding(binding: DialogVocalStudioBinding) {
        cancelProcessing()
        downloadJob?.cancel()
        player?.release()
        player = null
        super.onDestroyBinding(binding)
    }
}

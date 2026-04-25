package com.ffai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ffai.config.FFAIConfig
import com.ffai.databinding.FragmentControlPanelBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Control Panel Fragment - Main control interface
 */
class ControlPanelFragment : Fragment() {
    
    private var _binding: FragmentControlPanelBinding? = null
    private val binding get() = _binding!!
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentControlPanelBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupPersonalitySpinner()
        setupAssistLevelSpinner()
        setupSwitches()
        setupSliders()
        observeState()
    }
    
    private fun setupPersonalitySpinner() {
        val personalities = FFAIConfig.PersonalityMode.values().map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, personalities)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPersonality.adapter = adapter
        
        binding.spinnerPersonality.setSelection(
            personalities.indexOf(FFAIConfig.personalityMode.value.name)
        )
        
        binding.spinnerPersonality.onItemSelectedListener = 
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val mode = FFAIConfig.PersonalityMode.values()[position]
                    FFAIConfig.setPersonalityMode(mode)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }
    
    private fun setupAssistLevelSpinner() {
        val levels = FFAIConfig.AssistLevel.values().map { it.name }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, levels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAssistLevel.adapter = adapter
        
        binding.spinnerAssistLevel.setSelection(
            levels.indexOf(FFAIConfig.assistLevel.value.name)
        )
        
        binding.spinnerAssistLevel.onItemSelectedListener = 
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val level = FFAIConfig.AssistLevel.values()[position]
                    FFAIConfig.setAssistLevel(level)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }
    
    private fun setupSwitches() {
        binding.switchInference.isChecked = FFAIConfig.inferenceEnabled.value
        binding.switchInference.setOnCheckedChangeListener { _, isChecked ->
            FFAIConfig.setInferenceEnabled(isChecked)
        }
        
        binding.switchScreenCapture.isChecked = true
        binding.switchScreenCapture.setOnCheckedChangeListener { _, isChecked ->
            // Handle screen capture toggle
        }
    }
    
    private fun setupSliders() {
        binding.sliderAggression.value = FFAIConfig.aggressionLevel.value * 100
        binding.sliderAggression.addOnChangeListener { _, value, _ ->
            FFAIConfig.setAggressionLevel(value / 100f)
            binding.tvAggressionValue.text = "${value.toInt()}%"
        }
    }
    
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            FFAIConfig.inferenceEnabled.collectLatest { enabled ->
                binding.switchInference.isChecked = enabled
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            FFAIConfig.aggressionLevel.collectLatest { level ->
                binding.sliderAggression.value = level * 100
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

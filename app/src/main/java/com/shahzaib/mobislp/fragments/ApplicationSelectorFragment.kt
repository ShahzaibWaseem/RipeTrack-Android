package com.shahzaib.mobislp.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.Navigation
import com.shahzaib.mobislp.MainActivity
import com.shahzaib.mobislp.MainActivity.Companion.generateAlertBox
import com.shahzaib.mobislp.R
import com.shahzaib.mobislp.Utils
import com.shahzaib.mobislp.databinding.FragmentApplicationselectorBinding
import com.shahzaib.mobislp.makeDirectory
import kotlinx.coroutines.launch

class ApplicationSelectorFragment: Fragment() {
    private lateinit var fragmentApplicationselectorBinding: FragmentApplicationselectorBinding
    private lateinit var applicationArray: Array<String>

    /** Host's navigation controller */
    private val navController: NavController by lazy {
        Navigation.findNavController(requireActivity(), R.id.fragment_container)
    }

    private fun NavController.safeNavigate(direction: NavDirections) {
        currentDestination?.getAction(direction.actionId)?.run {
            navigate(direction)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        fragmentApplicationselectorBinding = FragmentApplicationselectorBinding.inflate(inflater, container, false)
        val applicationPicker = fragmentApplicationselectorBinding.applicationPicker
        applicationArray = arrayOf(getString(R.string.avocado_string),
            getString(R.string.pear_string) , getString(R.string.apple_string))
        applicationPicker.minValue = 0
        applicationPicker.maxValue = applicationArray.size-1
        applicationPicker.displayedValues = applicationArray

        makeDirectory(Utils.rawImageDirectory)
        makeDirectory(Utils.croppedImageDirectory)
        makeDirectory(Utils.processedImageDirectory)
        makeDirectory(Utils.hypercubeDirectory)

        return fragmentApplicationselectorBinding.root
    }

    private fun disableButton(cameraIdNIR: String) {
        if (cameraIdNIR == "No NIR Camera") {
            fragmentApplicationselectorBinding.runApplicationButton.isEnabled = false
            fragmentApplicationselectorBinding.runApplicationButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sfu_dark_gray))
            fragmentApplicationselectorBinding.runApplicationButton.text = resources.getString(R.string.no_nir_warning)
            fragmentApplicationselectorBinding.runApplicationButton.transformationMethod = null
        }
    }

    private fun enableButton() {
        fragmentApplicationselectorBinding.runApplicationButton.isEnabled = true
        fragmentApplicationselectorBinding.runApplicationButton.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.sfu_primary))
        fragmentApplicationselectorBinding.runApplicationButton.text = resources.getString(R.string.launch_application_button).uppercase()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStart() {
        super.onStart()
        MainActivity.cameraIDList = Utils.getCameraIDs(requireContext(), MainActivity.MOBISPECTRAL_APPLICATION)
        val cameraIdNIR = MainActivity.cameraIDList.second

        fragmentApplicationselectorBinding.information.setOnClickListener {
            generateAlertBox(requireContext(), "Information", getString(R.string.application_selector_information_string)) { }
        }

        fragmentApplicationselectorBinding.radioGroup.setOnCheckedChangeListener { _, _ ->
            val selectedRadio = fragmentApplicationselectorBinding.radioGroup.checkedRadioButtonId
            val selectedOption = requireView().findViewById<RadioButton>(selectedRadio).text.toString()
            if (selectedOption == getString(R.string.offline_mode_string))
                enableButton()
            else
                disableButton(cameraIdNIR)
        }

        if (cameraIdNIR == "No NIR Camera"){
            fragmentApplicationselectorBinding.onlineMode.isEnabled = false
        }

        fragmentApplicationselectorBinding.runApplicationButton.setOnTouchListener { _, _ ->
            val selectedApplication = applicationArray[fragmentApplicationselectorBinding.applicationPicker.value]
            val selectedRadio = fragmentApplicationselectorBinding.radioGroup.checkedRadioButtonId
            val selectedOption = requireView().findViewById<RadioButton>(selectedRadio).text.toString()
            val offlineMode = selectedOption == getString(R.string.offline_mode_string)
            val sharedPreferences = requireActivity().getSharedPreferences("mobislp_preferences", Context.MODE_PRIVATE)
            val editor = sharedPreferences?.edit()
            editor!!.putString("application", selectedApplication)
            editor.putString("option", getString(R.string.advanced_option_string))
            editor.putBoolean("offline_mode", offlineMode)
            Log.i("Radio Button", "$selectedApplication, $selectedOption")
            editor.apply()
            lifecycleScope.launch {
                withStarted {
                    navController.safeNavigate(ApplicationSelectorFragmentDirections.actionAppselectorToCameraFragment(
                        MainActivity.cameraIDList.first, ImageFormat.JPEG)
                    )
                }
            }

            true
        }
    }
}
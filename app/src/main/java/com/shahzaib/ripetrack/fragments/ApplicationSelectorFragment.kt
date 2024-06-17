package com.shahzaib.ripetrack.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.navigation.NavController
import androidx.navigation.NavDirections
import androidx.navigation.Navigation
import com.shahzaib.ripetrack.MainActivity
import com.shahzaib.ripetrack.MainActivity.Companion.generateAlertBox
import com.shahzaib.ripetrack.R
import com.shahzaib.ripetrack.Utils
import com.shahzaib.ripetrack.Utils.imageFormat
import com.shahzaib.ripetrack.databinding.FragmentApplicationselectorBinding
import com.shahzaib.ripetrack.makeDirectory
import kotlinx.coroutines.launch

class ApplicationSelectorFragment: Fragment() {
	private lateinit var fragmentApplicationSelectorBinding: FragmentApplicationselectorBinding
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

	@RequiresApi(Build.VERSION_CODES.Q)
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		fragmentApplicationSelectorBinding =
			FragmentApplicationselectorBinding.inflate(inflater, container, false)
		val applicationPicker = fragmentApplicationSelectorBinding.applicationPicker
		applicationArray = arrayOf(getString(R.string.pear_string), getString(R.string.avocado_string), getString(R.string.banana_string), getString(R.string.mango_string), getString(R.string.nectarine_string))
		applicationPicker.textSize = 90F
		applicationPicker.minValue = 0
		applicationPicker.maxValue = applicationArray.size - 1
		applicationPicker.displayedValues = applicationArray

		makeDirectory(Utils.rawImageDirectory)
		makeDirectory(Utils.croppedImageDirectory)
		makeDirectory(Utils.processedImageDirectory)
		makeDirectory(Utils.hypercubeDirectory)

		return fragmentApplicationSelectorBinding.root
	}

	private fun disableButton(cameraIdNIR: String) {
		if (cameraIdNIR == "No NIR Camera") {
			fragmentApplicationSelectorBinding.runApplicationButton.isEnabled = false
			fragmentApplicationSelectorBinding.runApplicationButton.setBackgroundColor(
				ContextCompat.getColor(requireContext(), R.color.sfu_dark_gray)
			)
			fragmentApplicationSelectorBinding.runApplicationButton.text = resources.getString(R.string.no_nir_warning)
			fragmentApplicationSelectorBinding.runApplicationButton.transformationMethod = null
		}
	}

	private fun enableButton() {
		fragmentApplicationSelectorBinding.runApplicationButton.isEnabled = true
		fragmentApplicationSelectorBinding.runApplicationButton.setBackgroundColor(
			ContextCompat.getColor(requireContext(), R.color.background)
		)
		fragmentApplicationSelectorBinding.runApplicationButton.text = resources.getString(R.string.launch_application_button).uppercase()
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onStart() {
		super.onStart()
		MainActivity.cameraIDList =
			Utils.getCameraIDs(requireContext(), MainActivity.RIPETRACK_APPLICATION)
		val cameraIdNIR = MainActivity.cameraIDList.second
		val sharedPreferences =
			requireActivity().getSharedPreferences("ripetrack_preferences", Context.MODE_PRIVATE)
		val editor = sharedPreferences?.edit()

		fragmentApplicationSelectorBinding.applicationPicker.value =
			applicationArray.indexOf(sharedPreferences.getString("fruit", "Pear"))
		fragmentApplicationSelectorBinding.information.setOnClickListener {
			generateAlertBox(requireContext(), "", getString(R.string.application_selector_information_string)) { }
		}

		fragmentApplicationSelectorBinding.radioGroup.setOnCheckedChangeListener { _, _ ->
			val selectedRadio = fragmentApplicationSelectorBinding.radioGroup.checkedRadioButtonId
			val selectedOption =
				requireView().findViewById<RadioButton>(selectedRadio).text.toString()
			if (selectedOption == getString(R.string.offline_mode_string))
				enableButton()
			else
				disableButton(cameraIdNIR)


			if (cameraIdNIR == "No NIR Camera") {
				fragmentApplicationSelectorBinding.onlineMode.isEnabled = false
			}
		}

		fragmentApplicationSelectorBinding.runApplicationButton.setOnTouchListener { _, _ ->
			val selectedApplication = applicationArray[fragmentApplicationSelectorBinding.applicationPicker.value]
			val selectedRadio = fragmentApplicationSelectorBinding.radioGroup.checkedRadioButtonId
			val selectedOption = requireView().findViewById<RadioButton>(selectedRadio).text.toString()
			val offlineMode = selectedOption == getString(R.string.offline_mode_string) // signifies online/offline mode

			editor!!.putString("application", selectedApplication)
			editor.putString("fruit", selectedApplication)
			editor.putString("option", getString(R.string.advanced_option_string))
			editor.putBoolean("offline_mode", offlineMode)
			Log.i("Radio Button", "$selectedApplication, $selectedOption")
			editor.apply()
			MainActivity.actualLabel = selectedApplication
			if (selectedOption == getString(R.string.data_capturing_mode_string))
				lifecycleScope.launch {
					withStarted {
						navController.safeNavigate(
							ApplicationSelectorFragmentDirections.actionAppselectorToDataCapturingFragment(
								MainActivity.cameraIDList.first, imageFormat
							)
						)
					}
				}
			else
				lifecycleScope.launch {
					withStarted {
						navController.safeNavigate(
							ApplicationSelectorFragmentDirections.actionAppselectorToCameraFragment(
								MainActivity.cameraIDList.first, imageFormat
							)
						)
					}
				}
			true
		}
	}
}
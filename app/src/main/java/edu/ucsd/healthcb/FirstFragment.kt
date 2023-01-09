package edu.ucsd.healthcb

/*
Copyright Notice
This software is Copyright © 2XXX The Regents of the University of California. All Rights Reserved. Permission to copy, modify, and distribute this software and its documentation for educational, research and non-profit purposes, without fee, and without a written agreement is hereby granted, provided that the above copyright notice, this paragraph and the following three paragraphs appear in all copies. Permission to make commercial use of this software may be obtained by contacting:

Office of Innovation and Commercialization
9500 Gilman Drive, Mail Code 0910
University of California
La Jolla, CA 92093-0910
innovation@ucsd.edu

This software program and documentation are copyrighted by The Regents of the University of California. The software program and documentation are supplied “as is”, without any accompanying services from The Regents. The Regents does not warrant that the operation of the program will be uninterrupted or error-free. The end-user understands that the program was developed for research purposes and is advised not to rely exclusively on the program for any reason.

IN NO EVENT SHALL THE UNIVERSITY OF CALIFORNIA BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT, SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS, ARISING OUT OF THE USE OF THIS SOFTWARE AND ITS DOCUMENTATION, EVEN IF THE UNIVERSITY OF CALIFORNIA HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. THE UNIVERSITY OF CALIFORNIA SPECIFICALLY DISCLAIMS ANY WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE. THE SOFTWARE PROVIDED HEREUNDER IS ON AN “AS IS” BASIS, AND THE UNIVERSITY OF CALIFORNIA HAS NO OBLIGATIONS TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR MODIFICATIONS.
*/

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import edu.ucsd.healthcb.databinding.FragmentFirstBinding
import edu.ucsd.healthcb.farred.FarRedActivity


/**
 * This first fragment is the default destination homepage. It contains navigation buttons to each
 * measurement app and a user id input.
 */
class FirstFragment : Fragment() {

    val PERMISSIONS_REQUEST_CODE = 10
    @RequiresApi(Build.VERSION_CODES.S)
    private val PERMISSIONS_REQUIRED = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        Manifest.permission.MANAGE_DOCUMENTS,
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
        Manifest.permission.RECORD_AUDIO)

    private var _binding: FragmentFirstBinding? = null
    private var userid: String = "0"
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val textWatcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            userid = s.toString()
            Log.d("userid", "userid = ".plus(userid))
        }
        override fun afterTextChanged(s: Editable) {

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

      _binding = FragmentFirstBinding.inflate(inflater, container, false)
      return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cameraManager =
            requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager

        //options of all cameras
        val cameraList = enumerateVideoCameras(cameraManager)

        //Set User ID to user text input
        binding.userIDNumber.addTextChangedListener(textWatcher)

        //Listeners on each measurement/app button to take the user to the specified measurement app
        binding.buttonFarred.setOnClickListener {
            val i = 0
//            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().cameraId="0"
//            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().fps=30
//            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().width=480
//            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().height=640
            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().cameraId=cameraList[i].cameraId
            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().fps=cameraList[i].fps
            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().width=cameraList[i].size.width
            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().height=cameraList[i].size.height
            FirstFragmentDirections.actionFirstFragmentToFarRedFragment().useHardware=false
            findNavController().navigate(FirstFragmentDirections.actionFirstFragmentToFarRedFragment())
//            val farredActivityIntent = Intent(context, FarRedActivity::class.java)
//            farredActivityIntent.putExtra("UserId", userid)
//            startActivity(farredActivityIntent)
        }
        binding.buttonPupAlz.setOnClickListener {
            if (Build.MODEL.toString() != "Pixel 4"){
                Toast.makeText(requireContext(),"Compatible with Pixel 4 only", Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonDyna.setOnClickListener {
            val dynaActivityIntent = Intent(context, DynaActivity::class.java)
            dynaActivityIntent.putExtra("UserId", userid)
            startActivity(dynaActivityIntent)
        }
        binding.buttonVibroBP.setOnClickListener {
//            findNavController().navigate(FirstFragmentDirections.actionFirstFragmentToVibroBPActivity())
            val vibroBPActivityIntent = Intent(context, VibroBPActivity::class.java)
            vibroBPActivityIntent.putExtra("UserId", userid)
            startActivity(vibroBPActivityIntent)
        }
        binding.buttonHemaApp.setOnClickListener {

        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Takes the user to the success fragment when permission is granted
//                Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_content_main).navigate(
//                    R.id.action_permissions_to_firstfragment)
            } else {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    companion object {
        @RequiresApi(Build.VERSION_CODES.S)
        private val PERMISSIONS_REQUIRED = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.MANAGE_DOCUMENTS,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.RECORD_AUDIO)

        /** Convenience method used to check if all permissions required by this app are granted */
        @RequiresApi(Build.VERSION_CODES.S)
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        private data class CameraInfo(
            val name: String,
            val cameraId: String,
            val size: Size,
            val fps: Int)

        /** Converts a lens orientation enum into a human-readable string */
        private fun lensOrientationString(value: Int) = when (value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        /** Lists all video-capable cameras and supported resolution and FPS combinations */
        @SuppressLint("InlinedApi")
        private fun enumerateVideoCameras(cameraManager: CameraManager): List<CameraInfo> {
            val availableCameras: MutableList<CameraInfo> = mutableListOf()

            // Iterate over the list of cameras and add those with high speed video recording
            //  capability to our output. This function only returns those cameras that declare
            //  constrained high speed video recording, but some cameras may be capable of doing
            //  unconstrained video recording with high enough FPS for some use cases and they will
            //  not necessarily declare constrained high speed video capability.
            cameraManager.cameraIdList.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = lensOrientationString(
                    characteristics.get(CameraCharacteristics.LENS_FACING)!!)

                // Query the available capabilities and output formats
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val cameraConfig = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

                // Return cameras that declare to be backward compatible
                if (capabilities.contains(
                        CameraCharacteristics
                        .REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)) {
                    // Recording should always be done in the most efficient format, which is
                    //  the format native to the camera framework
                    val targetClass = MediaRecorder::class.java

                    // For each size, list the expected FPS
                    cameraConfig.getOutputSizes(targetClass).forEach { size ->
                        // Get the number of seconds that each frame will take to process
                        val secondsPerFrame =
                            cameraConfig.getOutputMinFrameDuration(targetClass, size) /
                                    1_000_000_000.0
                        // Compute the frames per second to let user select a configuration
                        val fps = if (secondsPerFrame > 0) (1.0 / secondsPerFrame).toInt() else 0
                        val fpsLabel = if (fps > 0) "$fps" else "N/A"
                        availableCameras.add(CameraInfo(
                            "$orientation ($id) $size $fpsLabel FPS", id, size, fps))
                    }
                }
            }

            return availableCameras
        }
    }
}
package edu.ucsd.healthcb.farred


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

import android.content.Intent
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.navigation.findNavController
import edu.ucsd.healthcb.FirstFragmentDirections
import edu.ucsd.healthcb.MainActivity
import edu.ucsd.healthcb.R
import edu.ucsd.healthcb.databinding.FragmentFarredBinding
import edu.ucsd.healthcb.databinding.ActivityFarredBinding
import java.util.*


/**
 * FarRedActivity is meant to be supported by a physical red filter and/or red LED. The activity
 * utilizes the smartphone camera and torch to perform pupillometry measurements.
 */

class FarRedActivity: AppCompatActivity() {
    private lateinit var binding: ActivityFarredBinding
    private lateinit var userid: String


    // This property is only valid between onCreateView and
    // onDestroyView.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFarredBinding.inflate(layoutInflater)
        setContentView(binding.root)

//        if (intent.extras?.get("UserId") != null) {
//            userid = intent.extras?.get("UserId") as String
//            Log.d("userid", "farred userid = ".plus(userid))
//            binding.userIDNumber.setText(userid)
//        }



//        binding.buttonHome.setOnClickListener {
////            findNavController(R.id.action_FarRedFragment_to_FirstFragment)
//            val mainActivityIntent = Intent(this, MainActivity::class.java)
//            mainActivityIntent.putExtra("UserId", userid)
//            startActivity(mainActivityIntent)
//        }


    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action: Int
        val keycode: Int
        val plrButton = findViewById<Button>(R.id.button_test)
        val dsButton = findViewById<Button>(R.id.button_digitspan)
        action = event.action
        keycode = event.keyCode
        when (keycode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (KeyEvent.ACTION_UP === action) {
                    plrButton.performClick()
                    return true
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (KeyEvent.ACTION_DOWN == action){
                    dsButton.performClick()
                    return true
                }
            }
        }
        if (KeyEvent.ACTION_UP === action || KeyEvent.ACTION_DOWN == action) {
            return true
        }else{
            return super.dispatchKeyEvent(event)
        }
    }
}

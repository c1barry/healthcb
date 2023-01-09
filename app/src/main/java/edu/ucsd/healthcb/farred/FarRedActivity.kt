package edu.ucsd.healthcb.farred

import android.content.Intent
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import edu.ucsd.healthcb.MainActivity
import edu.ucsd.healthcb.R
import edu.ucsd.healthcb.databinding.FragmentFarredBinding
import java.util.*


/**
 * FarRedActivity is meant to be supported by a physical red filter and/or red LED. The activity
 * utilizes the smartphone camera and torch to perform pupillometry measurements.
 */

class FarRedActivity: AppCompatActivity() {
    private lateinit var binding: FragmentFarredBinding
    private lateinit var userid: String


    // This property is only valid between onCreateView and
    // onDestroyView.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = FragmentFarredBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent.extras?.get("UserId") != null) {
            userid = intent.extras?.get("UserId") as String
            Log.d("userid", "farred userid = ".plus(userid))
            binding.userIDNumber.setText(userid)
        }



        binding.buttonHome.setOnClickListener {
//            findNavController(R.id.action_FarRedFragment_to_FirstFragment)
            val mainActivityIntent = Intent(this, MainActivity::class.java)
            mainActivityIntent.putExtra("UserId", userid)
            startActivity(mainActivityIntent)
        }


    }
}

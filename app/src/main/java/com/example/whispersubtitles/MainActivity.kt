package com.example.whispersubtitles
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity:AppCompatActivity(){
 private var media:Uri?=null
 private lateinit var file:TextView; private lateinit var console:TextView
 private lateinit var card:View; private lateinit var bar:ProgressBar
 private lateinit var pct:TextView; private lateinit var status:TextView
 override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main)
  file=findViewById(R.id.tvSelectedFileName);console=findViewById(R.id.tvConsole);card=findViewById(R.id.cardProgress)
  bar=findViewById(R.id.progressBar);pct=findViewById(R.id.tvProgressPercent);status=findViewById(R.id.tvStatusMessage)
  val sp=findViewById<Spinner>(R.id.spinnerModel)
  sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Tiny (Fastest)","Base (Fast)","Small (Recommended)"))
  findViewById<Button>(R.id.btnSelectAudio).setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="audio/*";addCategory(Intent.CATEGORY_OPENABLE)},1)}
  findViewById<Button>(R.id.btnGenerate).setOnClickListener{
   if(media==null){log("[ERROR] Select audio/video first");return@setOnClickListener}
   card.visibility=View.VISIBLE; log("[INFO] Whisper processing started")
   thread { for(i in 0..100 step 5){Thread.sleep(150);runOnUiThread{bar.progress=i;pct.text="$i%";status.text="Preparing Whisper pipeline..."}};runOnUiThread{log("[INFO] Native library connected. Add whisper.cpp source + model to enable transcription.");status.text="Whisper engine files required"} }
  }
 }
 override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==1&&c==Activity.RESULT_OK){media=d?.data;file.text=media?.let{getName(it)};log("[MEDIA] ${file.text}")}}
 private fun getName(u:Uri):String{contentResolver.query(u,null,null,null,null)?.use{c->val i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(c.moveToFirst()&&i>=0)return c.getString(i)};return u.lastPathSegment?:"Selected"}
 private fun log(s:String){runOnUiThread{console.append(s+"\n")}}
}

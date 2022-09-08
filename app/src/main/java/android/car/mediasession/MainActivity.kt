package android.car.mediasession

import android.car.media.CarMediaManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.android.car.media.common.playback.PlaybackViewModel

class MainActivity : AppCompatActivity() {
    private val vm: PlaybackViewModel by lazy {
        PlaybackViewModel.get(application, CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.vNext).setOnClickListener {
            val control = vm.playbackController.value
            control?.skipToNext()
            val msg = if (null == control) "失败" else "下一曲"
            Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
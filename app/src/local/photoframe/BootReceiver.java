package local.photoframe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the server and opens the frame after the tablet boots and after the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, FrameService.class));
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}

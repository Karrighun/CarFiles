package dev.carfiles;

import com.google.android.apps.auto.sdk.CarActivity;
import com.google.android.apps.auto.sdk.CarActivityService;

/** Legacy host adapter. Presence in Android Auto must be verified per host version. */
@androidx.media3.common.util.UnstableApi
public class CarProjectionService extends CarActivityService {
    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("car_diagnostics", MODE_PRIVATE).edit().putLong("projection_service_created", System.currentTimeMillis()).apply();
        CarLog.add(this, "Сервис проекции создан host-ом Android Auto");
    }
    @Override public android.os.IBinder onBind(android.content.Intent intent) {
        getSharedPreferences("car_diagnostics", MODE_PRIVATE).edit().putLong("projection_service_bound", System.currentTimeMillis()).apply();
        CarLog.add(this, "Host Android Auto подключился к сервису");
        return super.onBind(intent);
    }
    @Override public Class<? extends CarActivity> getCarActivity() {
        return CarProjectionActivity.class;
    }
}

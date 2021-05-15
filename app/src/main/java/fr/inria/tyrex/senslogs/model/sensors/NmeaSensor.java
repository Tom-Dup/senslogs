package fr.inria.tyrex.senslogs.model.sensors;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;

import fr.inria.tyrex.senslogs.R;
import fr.inria.tyrex.senslogs.model.FieldsWritableObject;
import fr.inria.tyrex.senslogs.model.log.Log;

/**
 * NMEA Sensor provides NMEA sentences from the GPS
 * http://developer.android.com/reference/android/location/GpsStatus.NmeaListener.html
 */
public class NmeaSensor extends Sensor implements FieldsWritableObject {

    transient private static NmeaSensor instance;
    transient private double mStartTime;

    public static NmeaSensor getInstance() {
        if (instance == null) {
            instance = new NmeaSensor();
        }
        return instance;
    }

    private NmeaSensor() {
        super(TYPE_NMEA, Category.RADIO);
    }

    @Override
    public String getName() {
        return "NMEA data";
    }

    @Override
    public String getStringType() {
        return null;
    }

    @Override
    public String getStorageFileName(Context context) {
        return context.getString(R.string.file_name_nmea);
    }

    @Override
    public String getFieldsDescription(Resources res) {
        return res.getString(R.string.description_nmea);
    }

    @Override
    public String[] getFields(Resources resources) {
        return resources.getStringArray(R.array.fields_nmea);
    }

    @Override
    public String getWebPage(Resources res) {
        return res.getString(R.string.webpage_nmea);
    }

    @Override
    public boolean exists(Context context) {

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;

    }

    @Override
    public boolean checkPermission(Context context) {
        return !(Build.VERSION.SDK_INT >= 23 &&
                context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED);
    }


    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void start(Context context, Settings settings, Log.RecordTimes recordTimes) {
        LocationManager locationManager = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);

        if (!checkPermission(context)) {
            return;
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, mLocationListener);

        mStartTime = recordTimes.startTime;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void stop(Context context) {
        LocationManager locationManager = (LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);

        if (!checkPermission(context)) {
            return;
        }

        locationManager.removeUpdates(mLocationListener);
    }

    @Override
    public boolean hasSettings() {
        return false;
    }

    transient private LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
        }
    };

    @Override
    public boolean mustRunOnUiThread() {
        return true;
    }
}

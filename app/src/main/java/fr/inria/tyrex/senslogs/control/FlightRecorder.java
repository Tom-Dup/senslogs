package fr.inria.tyrex.senslogs.control;

import android.content.Context;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import fr.inria.tyrex.senslogs.Application;
import fr.inria.tyrex.senslogs.model.FieldsWritableObject;
import fr.inria.tyrex.senslogs.model.log.Log;
import fr.inria.tyrex.senslogs.model.sensors.Sensor;

public class FlightRecorder {

    private final Context mContext;
    private final Recorder mRecorder;
    private RecorderWriter mRecorderWriter;
    private Log mLog;
    private final Recorder.RecorderListener mRecorderListener;
    private Timer timer;
    private TimerTask timerTask;
    private final int interval = 5000; // 10 Second
    private Integer iteration = 1;
    private Map<Integer, File> mWorkingFolders;
    private Set<Sensor> mSensors;

    public FlightRecorder(Context context, Recorder realRecorder) {
        mContext = context;
        mRecorder = realRecorder;
        mRecorderWriter = mRecorder.getRecorderWriter();
        mWorkingFolders = new HashMap<>();
        mRecorderListener = new Recorder.RecorderListener() {
            @Override
            public void onPlay() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onPlay");
                timer = new Timer();
                timerTask = createTask();
                timer.scheduleAtFixedRate(timerTask, 0, interval);
            }

            @Override
            public void onPause() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onPause");
                timer.cancel();
                timerTask.cancel();
            }

            @Override
            public void onCancel() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onCancel");
                timer.cancel();
                timerTask.cancel();
                for (Map.Entry<Integer, File> folder : mWorkingFolders.entrySet()) {
                    File tmp = folder.getValue();
                    if (tmp.delete())
                        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder " + tmp.toString() + " deleted");
                }
            }

            @Override
            public void onSave() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onSave");
                timer.cancel();
                timerTask.cancel();
                save();
                for (Map.Entry<Integer, File> folder : mWorkingFolders.entrySet()) {
                    File tmp = folder.getValue();
                    if (tmp.isDirectory()) {
                        String[] children = tmp.list();
                        for (String child : children) {
                            if (new File(tmp, child).delete())
                                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: file " + child + " deleted in working folder " + tmp.toString());
                        }
                    }
                    if (tmp.delete())
                        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder " + tmp.toString() + " deleted");
                }
            }
        };
        mRecorder.setListener(mRecorderListener);
    }

    private TimerTask createTask() {
        FlightRecorder flightRecorder = this;
        return new TimerTask() {
            @Override
            public void run() {
                try {
                    flightRecorder.run();
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }
            @Override
            public boolean cancel() {
                flightRecorder.cancel();
                return super.cancel();
            }
        };
    }

    private void run() throws FileNotFoundException {
        mRecorderWriter = mRecorder.getRecorderWriter();
        mSensors = mRecorder.getSensors();
        mLog = mRecorder.getLog();
        if (iteration > 1) {
            save();
        }
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: timer run " + iteration.toString());
        // Create new folder for records
        File mTemporaryFolder = new File(mContext.getFilesDir(), String.valueOf(UUID.randomUUID()));
        if (!mTemporaryFolder.mkdir()) {
            throw new FileNotFoundException();
        }
        mWorkingFolders.put(iteration, mTemporaryFolder);
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder = " + mTemporaryFolder.toString());
        mRecorderWriter.setFrOutputDirectory(mTemporaryFolder);
        for (Sensor sensor : mLog.getSensors()) {
            if (!(sensor instanceof FieldsWritableObject))
                continue;
            mRecorderWriter.createFile((FieldsWritableObject) sensor, true);
        }

        iteration++;
    }

    private void save() {
        try {
            mRecorderWriter.frFinish();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mRecorderWriter.clearFrSensorsFiles();
        mRecorderWriter.clearFrSensorsFos();
        mRecorderWriter.clearFrFileNames();
    }

    private void cancel() {
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: timer cancel");
    }
}


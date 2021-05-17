package fr.inria.tyrex.senslogs.control;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.util.Pair;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import android.provider.Settings.Secure;
import androidx.core.content.ContextCompat;
import fr.inria.tyrex.senslogs.Application;
import fr.inria.tyrex.senslogs.R;
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
    private final int interval = 30000; // create an iteration every 30 Seconds
    private Integer iteration = 0;
    private String mainWorkingFolder;
    private Map<Integer, File> mWorkingFolders; //sub folders for iterations
    private SendQueue sendQueue;
    private String geoLocationUrl = "";
    private String android_id = "";

    public FlightRecorder(Context context, Recorder realRecorder) {
        mContext = context;
        mRecorder = realRecorder;
        sendQueue  = new SendQueue(context);
        android_id= Secure.getString(context.getContentResolver(), Secure.ANDROID_ID);
        geoLocationUrl = context.getResources().getString(R.string.geolocation_url);
        mRecorderWriter = mRecorder.getRecorderWriter();
        mWorkingFolders = new HashMap<>();
        mRecorderListener = new Recorder.RecorderListener() {
            // CAUTION: this function is called on start but also on resume!
            @Override
            public void onPlay() throws FileNotFoundException {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onPlay");
                mRecorderWriter = mRecorder.getRecorderWriter();
                mLog = mRecorder.getLog();
                if (iteration == 0) {
                    mainWorkingFolder = String.valueOf(UUID.randomUUID());
                    File mTemporaryFolder = new File(mContext.getFilesDir(), mainWorkingFolder);
                    if (!mTemporaryFolder.mkdir()) {
                        throw new FileNotFoundException();
                    }
                    mRecorderWriter.setCurrentFrWorkingFolder(mTemporaryFolder);
                    sendQueue.start();
                }
                timer = new Timer();
                timerTask = createTask();
                timer.scheduleAtFixedRate(timerTask, 0, interval);
            }

            @Override
            public void onPause() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onPause");
                //TODO: record elapsed time for the next task
                timer.cancel();
                timerTask.cancel();
            }

            @Override
            public void onCancel() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onCancel");
                timer.cancel();
                timerTask.cancel();
                // Close files pointer properly
                try {
                    // As we have a security margin of 1 iteration between each iteration 'close' we need to close manually the last 2 iterations
                    if (iteration>1)
                        mRecorderWriter.frIterationSensorsFosClose(iteration-1);
                    mRecorderWriter.frIterationSensorsFosClose(iteration);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // Clear the sending queue
                sendQueue.terminate(true);
                deleteWorkingFolders();
                iteration = 0;
            }

            @Override
            public void onSave() {
                android.util.Log.d(Application.LOG_TAG, "FlightRecorder: onSave");
                timer.cancel();
                timerTask.cancel();
                // As we have a security margin of 1 iteration between each iteration 'close' we need to close manually the last 2 iterations
                save(iteration-1);
                save(iteration);
                // Clear the sending queue
                sendQueue.terminate(false);
                iteration = 0;
            }

            @Override
            public void onNewLocation(Sensor sensor, Object[] objects) {
                Thread thread = new Thread(new Runnable(){
                    @Override
                    public void run() {
                        try {
                            addNewLocationToQueue(sensor, objects);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                thread.start();
            }
        };
        mRecorder.setListener(mRecorderListener);
    }

    public String batteryTemperature() {
        Intent intent = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        float  temp   = ((float) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,0)) / 10;
        return String.valueOf(temp);
    }

    private void addNewLocationToQueue(Sensor sensor, Object[] values) {
        Long time = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();
        buffer.append(android_id).append(";");
        buffer.append(time.toString()).append(";");
        for (Object value : values) {
            buffer.append(value.toString()).append(";");
        }
        buffer.append(batteryTemperature()).append(";");
        sendQueue.addRequestToQueue(geoLocationUrl + "?data=" + buffer.toString());
    }

    private void deleteWorkingFolders() {
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
        File mTemporaryFolder = new File(mContext.getFilesDir(), mainWorkingFolder);
        if (mTemporaryFolder.delete())
            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: main working folder " + mTemporaryFolder.toString() + " deleted");
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
        iteration++;
        if (iteration > 2) {
            // IMPORTANT: we added a security margin of 2 iterations, before saving/sending the zip, in order to make sure that all async write operations are done and files pointers are closed
            save(iteration - 2);
        }
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: timer run " + iteration.toString());
        // Create new (sub)folder for records
        File mTemporaryFolder = new File(mContext.getFilesDir() + "/" + mainWorkingFolder, iteration.toString());
        if (!mTemporaryFolder.mkdir()) {
            throw new FileNotFoundException();
        }
        mWorkingFolders.put(iteration, mTemporaryFolder);
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder = " + mTemporaryFolder.toString());
        mRecorderWriter.initFrIteration(iteration, mTemporaryFolder); //TODO: create files before starting/init. the new iteration in order to avoid writing in non existing files!
        for (Sensor sensor : mLog.getSensors()) {
            if (!(sensor instanceof FieldsWritableObject))
                continue;
            mRecorderWriter.createFile((FieldsWritableObject) sensor, true);
        }
    }

    private void save(Integer iterationToSave) {
        // Simple check to avoid any exception (but should not happen!)
        if (iterationToSave == 0)
            return;
        // Reminder: here we close the pointers for iteration n-2... unless it's a cancel or finish operation
        ContextCompat.getMainExecutor(mContext).execute(()  -> {
            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: saving iteration " + iterationToSave.toString());
            // Close files pointer properly
            try {
                mRecorderWriter.frIterationSensorsFosClose(iterationToSave);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // Create Zip File
            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: create zip for iteration " + iterationToSave.toString());
            String filename = mainWorkingFolder + "-" + iterationToSave.toString();
            final Pair<File, ZipCreationTask> zipCreationPair;
            try {
                zipCreationPair = mRecorderWriter.createZipFile(filename, mLog, iterationToSave);
                final File zipFile = zipCreationPair.first;
                final ZipCreationTask zipTask = zipCreationPair.second;
                zipTask.addListener(new ZipCreationTask.ZipCreationListener() {
                    @Override
                    public void onProgress(File currentFile, float ratio) {
                    }
                    @Override
                    public void onTaskFinished(File outputFile, long fileSize) {
                        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: zip created for iteration " + iterationToSave.toString());
                        // Remove working folder when zip is created
                        File mTemporaryFolder = new File(mContext.getFilesDir() + "/" + mainWorkingFolder, iterationToSave.toString());
                        if (mTemporaryFolder.isDirectory()) {
                            String[] children = mTemporaryFolder.list();
                            for (String child : children) {
                                if (new File(mTemporaryFolder, child).delete())
                                    android.util.Log.d(Application.LOG_TAG, "FlightRecorder: file " + child + " deleted in working folder " + mTemporaryFolder.toString() + " for iteration " + iterationToSave);
                            }
                        }
                        if (mTemporaryFolder.delete())
                            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: working folder " + mTemporaryFolder.toString() + " deleted for iteration " + iterationToSave);
                        zipTask.removeListener(this);
                        // copy file to internal and/or external storage
                        if (StorageHelper.isExternalStorageReadableAndWritable()) {
                            android.util.Log.d(Application.LOG_TAG, "FlightRecorder: copying file to sdcard as " + zipFile.getName());
                            copyFileToSdCard(zipFile);
                        }
                        // Send file web server
                        sendQueue.addFileToQueue(iterationToSave, zipFile);
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void copyFileToSdCard(File file) {

        File outputDir = null;

        File[] fs = mContext.getExternalFilesDirs(null);
        // at index 0 you have the internal storage and at index 1 the real external...
        if (fs != null && fs.length >= 2) {
            outputDir = fs[1];
        }

        if (outputDir==null) {
            outputDir = new File(Environment.getExternalStorageDirectory(),
                    mContext.getString(R.string.folder_logs_sd_card));
        }

        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: External Path => " + outputDir.toString());

        if(!outputDir.exists() && !outputDir.mkdir()) {
            return ;
        }

        File outputFile = new File(outputDir, file.getName());

        CopyTask task = new CopyTask();
        task.execute(new CopyTask.Input(file, outputFile));
    }

    private void cancel() {
        android.util.Log.d(Application.LOG_TAG, "FlightRecorder: timer cancel");
    }
}

